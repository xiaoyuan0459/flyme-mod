package dev.rikumi.flymemod;

import android.content.Context;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.ViewParent;
import java.lang.ref.WeakReference;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BiConsumer;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Turns a collapsed same-application notification group into an iOS-style horizontal pager.
 *
 * <p>Flyme keeps the visible card of a collapsed group in its {@code NotificationGroupCollapseContainer}.
 * The container paints the notification rows in child order, so rotating the row children brings the
 * next notification to the front. The native swipe helper still owns the gesture; this hook only
 * suppresses its dismissal decision for grouped rows and applies the page turn on release.
 */
final class NotificationGroupPagingHooks {
    private static final String ROW = "com.android.systemui.statusbar.notification.row.ExpandableNotificationRow";
    private static final String HELPER = "com.android.systemui.statusbar.notification.stack.NotificationSwipeHelper";
    private static final String BASE = "com.android.systemui.SwipeHelper";
    private static final String GROUP_CONTAINER = "com.flyme.notification.view.NotificationGroupCollapseContainer";
    private static final float PAGE_SWIPE_FRACTION = .32f;

    private final Class<?> rowClass, helperClass;
    private final Field touched;
    private final Method summary, groupCollapse, childrenContainer, expanded;
    private final Consumer<Context> settings;
    private final BooleanSupplier enabled;
    private final BiConsumer<String, Throwable> log;
    private final Map<Object, Gesture> gestures = new WeakHashMap<>();

    NotificationGroupPagingHooks(ClassLoader loader, Consumer<Context> settings, BooleanSupplier enabled,
                                 BiConsumer<String, Throwable> log) throws ReflectiveOperationException {
        this.settings = settings;
        this.enabled = enabled;
        this.log = log;
        rowClass = loader.loadClass(ROW);
        helperClass = loader.loadClass(HELPER);
        touched = loader.loadClass(BASE).getField("mTouchedView");
        summary = rowClass.getMethod("isSummaryWithChildren");
        groupCollapse = rowClass.getMethod("getGroupCollapseContainer");
        childrenContainer = optionalMethod(rowClass, "getChildrenContainer");
        expanded = optionalMethod(rowClass, "isGroupExpanded", "isExpanded", "areChildrenExpanded");
    }

    void install(SignalHooks.Installer installer) {
        installer.hook(HELPER, "onDownUpdate", chain -> {
            Object result = chain.proceed();
            begin(chain.getThisObject(), (View) chain.getArg(0), (MotionEvent) chain.getArg(1));
            return result;
        }, View.class, MotionEvent.class);
        installer.hook(BASE, "onTouchEvent", chain -> {
            if (!helperClass.isInstance(chain.getThisObject())) return chain.proceed();
            track(chain.getThisObject(), (MotionEvent) chain.getArg(0));
            return chain.proceed();
        }, MotionEvent.class);
        installer.hook(HELPER, "onMoveUpdate", chain -> {
            track(chain.getThisObject(), (MotionEvent) chain.getArg(1));
            return chain.proceed();
        }, View.class, MotionEvent.class, float.class, float.class);
        // A page turn replaces the dismiss gesture only inside a collapsed same-app group.
        installer.hook(BASE, "isSwipeDismissible", chain ->
                blocksDismiss(chain.getThisObject()) ? false : chain.proceed());
        for (String method : new String[]{"resetSwipeStates"}) {
            installer.hook(BASE, method, chain -> {
                clear(chain.getThisObject());
                return chain.proceed();
            }, boolean.class);
        }
        installer.hook(HELPER, "snapChild", chain -> {
            Gesture gesture = gestures.get(chain.getThisObject());
            if (gesture == null || gesture.row.get() != chain.getArg(0))
                clear(chain.getThisObject());
            return chain.proceed();
        }, View.class, float.class, float.class);
    }

    void refresh() {
        if (!enabled.getAsBoolean()) for (Object helper : new ArrayList<>(gestures.keySet())) clear(helper);
    }

    private void begin(Object helper, View row, MotionEvent event) {
        clear(helper);
        if (row == null || event == null || event.getActionMasked() != MotionEvent.ACTION_DOWN) return;
        if (!rowClass.isInstance(row)) return;
        settings.accept(row.getContext());
        if (!enabled.getAsBoolean()) return;
        try {
            boolean summaryRow = (Boolean) summary.invoke(row);
            if (expanded != null && (Boolean) expanded.invoke(row)) return;
            Object container = groupCollapse.invoke(row);
            View pages = container instanceof View ? (View) container : null;
            if (pages == null && childrenContainer != null) {
                Object children = childrenContainer.invoke(row);
                pages = children instanceof View ? (View) children : null;
            }
            if (!summaryRow && pages == null) return;
            ViewGroup parent = null;
            if (pages instanceof ViewGroup group) parent = findPagingParent(group);
            if (parent == null) parent = findCollapseParent(row);
            if (parent == null) return;
            float density = row.getResources().getDisplayMetrics().density;
            gestures.put(helper, new Gesture(row, parent,
                    event.getRawX(), event.getRawY(),
                    ViewConfiguration.get(row.getContext()).getScaledTouchSlop(), 48f * density));
        } catch (ReflectiveOperationException | RuntimeException error) {
            log.accept("Cannot start notification group paging", error);
        }
    }

    private void track(Object helper, MotionEvent event) {
        Gesture gesture = gestures.get(helper);
        if (gesture == null) return;
        int action = event.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) return;
        if (gesture.row.get() != touched.get(helper)) { clear(helper); return; }
        if (action == MotionEvent.ACTION_MOVE) {
            float dx = event.getRawX() - gesture.x, dy = event.getRawY() - gesture.y;
            if (!gesture.active) {
                if (Math.abs(dx) <= gesture.slop || Math.abs(dx) < Math.abs(dy) * 1.3f) return;
                gesture.active = true;
                gesture.original = gesture.pages.getTranslationX();
                gesture.pages.animate().cancel();
            }
            gesture.dx = dx;
            gesture.pages.setTranslationX(gesture.original + dx * PAGE_SWIPE_FRACTION);
            return;
        }
        if (action == MotionEvent.ACTION_UP) {
            boolean turn = gesture.active && Math.abs(gesture.dx) >= gesture.threshold;
            clear(helper, gesture.original);
            if (turn) rotate(gesture.pages, gesture.dx < 0f);
        } else if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_POINTER_DOWN) {
            clear(helper, gesture.original);
        }
    }

    private boolean blocksDismiss(Object helper) {
        Gesture gesture = gestures.get(helper);
        return gesture != null && gesture.row.get() == touched.get(helper);
    }

    private void clear(Object helper) {
        Gesture gesture = gestures.remove(helper);
        if (gesture != null) restore(gesture, gesture.original);
    }

    private void clear(Object helper, float translation) {
        Gesture gesture = gestures.remove(helper);
        if (gesture != null) restore(gesture, translation);
    }

    private void restore(Gesture gesture, float translation) {
        View pages = gesture.pages;
        if (pages.getTranslationX() == translation) return;
        pages.animate().cancel();
        pages.animate().translationX(translation).setDuration(180L).start();
    }

    private ViewGroup findPagingParent(ViewGroup group) {
        List<ViewGroup> pending = new ArrayList<>();
        pending.add(group);
        while (!pending.isEmpty()) {
            ViewGroup current = pending.remove(pending.size() - 1);
            if (rowCount(current) >= 2) return current;
            for (int i = 0; i < current.getChildCount(); i++) {
                View child = current.getChildAt(i);
                if (child instanceof ViewGroup nested) pending.add(nested);
            }
        }
        return null;
    }

    private ViewGroup findCollapseParent(View row) {
        ViewParent parent = row.getParent();
        while (parent instanceof ViewGroup group) {
            if (GROUP_CONTAINER.equals(group.getClass().getName())) {
                ViewGroup paging = findPagingParent(group);
                if (paging != null) return paging;
            }
            parent = group.getParent();
        }
        if (!(row instanceof ViewGroup root)) return null;
        List<ViewGroup> pending = new ArrayList<>();
        pending.add(root);
        while (!pending.isEmpty()) {
            ViewGroup current = pending.remove(pending.size() - 1);
            if (GROUP_CONTAINER.equals(current.getClass().getName())) {
                ViewGroup parent = findPagingParent(current);
                if (parent != null) return parent;
            }
            for (int i = 0; i < current.getChildCount(); i++) {
                View child = current.getChildAt(i);
                if (child instanceof ViewGroup nested) pending.add(nested);
            }
        }
        return findPagingParent(root);
    }

    private int rowCount(ViewGroup group) {
        int count = 0;
        for (int i = 0; i < group.getChildCount(); i++)
            if (rowClass.isInstance(group.getChildAt(i))) count++;
        return count;
    }

    private void rotate(ViewGroup parent, boolean next) {
        List<View> rows = new ArrayList<>();
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            if (rowClass.isInstance(child)) rows.add(child);
        }
        if (rows.size() < 2) return;
        View target = next ? rows.get(0) : rows.get(rows.size() - 1);
        View anchor = next ? rows.get(rows.size() - 1) : rows.get(0);
        int anchorIndex = indexOf(parent, anchor);
        ViewGroup.LayoutParams params = target.getLayoutParams();
        parent.removeView(target);
        if (params != null)
            parent.addView(target, Math.max(0, Math.min(anchorIndex, parent.getChildCount())), params);
        else
            parent.addView(target, Math.max(0, Math.min(anchorIndex, parent.getChildCount())));
        parent.requestLayout();
        parent.invalidate();
    }

    private static int indexOf(ViewGroup parent, View child) {
        for (int i = 0; i < parent.getChildCount(); i++) if (parent.getChildAt(i) == child) return i;
        return 0;
    }

    private static Method optionalMethod(Class<?> type, String... names) {
        for (String name : names) {
            try { return type.getMethod(name); }
            catch (NoSuchMethodException ignored) { }
        }
        return null;
    }

    private static final class Gesture {
        final WeakReference<View> row;
        final ViewGroup pages;
        final float x, y, slop, threshold;
        float dx, original;
        boolean active;
        Gesture(View row, ViewGroup pages, float x, float y, float slop, float threshold) {
            this.row = new WeakReference<>(row);
            this.pages = pages;
            this.x = x; this.y = y; this.slop = slop; this.threshold = threshold;
            original = pages.getTranslationX();
        }
    }
}
