package dev.rikumi.flymemod;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.telephony.SubscriptionManager;
import android.telephony.TelephonyCallback;
import android.telephony.TelephonyManager;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.BiConsumer;
import io.github.libxposed.api.XposedInterface;

final class SignalHooks {
    interface Installer {
        void hook(String name, String method, XposedInterface.Hooker hooker, Class<?>... parameters);
    }

    private static final String MOBILE = "com.flyme.systemui.statusbar.net.mobile.ui.view.FlymeModernStatusBarMobileView";
    private static final String CONTAINER = "com.android.systemui.statusbar.phone.StatusIconContainer";
    // TelephonyDisplayInfo override values. Referenced numerically so hidden/SystemApi
    // constants are not required at build time.
    private static final int OVERRIDE_NR_NSA = 3;
    private static final int OVERRIDE_NR_NSA_MMWAVE = 4;
    private static final int OVERRIDE_NR_ADVANCED = 5;
    private static volatile int radioNetworkType = Integer.MIN_VALUE;
    private static volatile int radioOverrideNetworkType = Integer.MIN_VALUE;
    private static volatile int radioNrState = Integer.MIN_VALUE;
    private static volatile String radioNrCaState = "";
    private final ClassLoader loader;
    private final Installer installer;
    private final Consumer<Context> settings;
    private final BooleanSupplier merge, network;
    private final BiConsumer<String, Throwable> log;
    private final Map<View, Mobile> mobiles = new WeakHashMap<>();
    private boolean receiverRegistered;
    private TelephonyCallback dataCallback;
    private ConnectivityManager.NetworkCallback networkCallback;
    private volatile boolean ratRefreshPending;
    private boolean wifiDefault;

    SignalHooks(ClassLoader loader, Installer installer, Consumer<Context> settings,
                BooleanSupplier merge, BooleanSupplier network, BiConsumer<String, Throwable> log) {
        this.loader = loader;
        this.installer = installer;
        this.settings = settings;
        this.merge = merge;
        this.network = network;
        this.log = log;
    }

    void install() throws ReflectiveOperationException {
        Class<?> model = type("com.android.systemui.statusbar.pipeline.mobile.ui.viewmodel.LocationBasedMobileViewModel");
        Class<?> logger = type("com.android.systemui.statusbar.pipeline.mobile.ui.MobileViewLogger");
        installer.hook("com.flyme.systemui.statusbar.net.mobile.ui.binder.FlymeMobileIconBinder", "bind", chain -> {
            ViewGroup root = (ViewGroup) chain.getArg(0);
            settings.accept(root.getContext());
            if (merge.getAsBoolean() || network.getAsBoolean()) initialize(root);
            Object result = chain.proceed();
            root.requestLayout();
            return result;
        }, ViewGroup.class, model, int.class, logger);
        installer.hook(CONTAINER, "onMeasure", chain -> {
            prepare((ViewGroup) chain.getThisObject());
            return chain.proceed();
        }, int.class, int.class);
        for (String method : new String[]{"getViewTotalMeasuredWidth", "getViewTotalWidth"}) {
            installer.hook(CONTAINER, method, chain -> {
                View view = (View) chain.getArg(0);
                Mobile mobile = mobiles.get(view);
                if (mobile != null && mobile.secondary && view.getParent() instanceof ViewGroup parent) {
                    // Also remove the extra inter-icon gap allocated to the second SIM.
                    return -parent.getClass().getField("mIconSpacing").getInt(parent);
                }
                return chain.proceed();
            }, View.class);
        }
        installer.hook("android.widget.ImageView", "setImageResource", chain -> {
            Object result = chain.proceed();
            ImageView image = (ImageView) chain.getThisObject();
            for (Mobile mobile : new ArrayList<>(mobiles.values())) {
                if (mobile.type == image) {
                    mobile.rat = ratName(image.getContext(), (Integer) chain.getArg(0));
                    requestUpdate(mobile);
                    break;
                }
            }
            return result;
        }, int.class);
        installer.hook("android.widget.ImageView", "invalidateDrawable", chain -> {
            Object result = chain.proceed();
            View image = (View) chain.getThisObject();
            for (Mobile mobile : new ArrayList<>(mobiles.values())) {
                if (mobile.signal == image || mobile.type == image) {
                    if (mobile.primary != null) mobile.primary.signal.invalidate();
                    updateTint(mobile);
                    break;
                }
            }
            return result;
        }, Drawable.class);
        installer.hook("android.widget.ImageView", "onDraw", chain -> {
            ImageView image = (ImageView) chain.getThisObject();
            Mobile combined = null;
            for (Mobile mobile : mobiles.values()) {
                if (mobile.signal == image && mobile.mergedSecond != null) {
                    combined = mobile;
                    break;
                }
            }
            if (combined == null) return chain.proceed();
            Canvas canvas = (Canvas) chain.getArg(0);
            int save = canvas.save();
            Object result;
            try {
                canvas.translate(0, image.getHeight() * 0.12f);
                canvas.scale(1f, 0.6f);
                result = chain.proceed();
            } finally {
                canvas.restoreToCount(save);
            }
            drawLowerSignal(canvas, image, combined.mergedSecond.signal);
            return result;
        }, Canvas.class);

        installer.hook("android.telephony.TelephonyDisplayInfo", "getNetworkType", chain -> {
            Object result = chain.proceed();
            if (result instanceof Integer && radioNetworkType != (Integer) result) {
                radioNetworkType = (Integer) result;
                scheduleRatRefresh();
            }
            return result;
        });
        installer.hook("android.telephony.TelephonyDisplayInfo", "getOverrideNetworkType", chain -> {
            Object result = chain.proceed();
            if (result instanceof Integer && radioOverrideNetworkType != (Integer) result) {
                radioOverrideNetworkType = (Integer) result;
                scheduleRatRefresh();
            }
            return result;
        });
        installer.hook("android.telephony.ServiceState", "getNrState", chain -> {
            Object result = chain.proceed();
            if (result instanceof Integer && radioNrState != (Integer) result) {
                radioNrState = (Integer) result;
                scheduleRatRefresh();
            }
            return result;
        });
        installer.hook("android.telephony.ServiceState", "getNrCaState", chain -> {
            Object result = chain.proceed();
            String value = result == null ? "" : String.valueOf(result);
            if (!value.equalsIgnoreCase(radioNrCaState)) {
                radioNrCaState = value;
                scheduleRatRefresh();
            }
            return result;
        });
    }

    private void scheduleRatRefresh() {
        if (ratRefreshPending) return;
        ratRefreshPending = true;
        new Handler(Looper.getMainLooper()).post(() -> {
            ratRefreshPending = false;
            for (Mobile mobile : new ArrayList<>(mobiles.values())) requestUpdate(mobile);
        });
    }

    private void initialize(ViewGroup root) throws ReflectiveOperationException {
        if (mobiles.containsKey(root)) return;
        ImageView signal = root.findViewById(id(root, "mobile_signal"));
        ImageView type = root.findViewById(id(root, "mobile_type"));
        LinearLayout group = root.findViewById(id(root, "mobile_group"));
        if (signal == null || type == null || group == null) return;
        ImageView inout = root.findViewById(id(root, "mobile_inout"));
        Mobile mobile = new Mobile(root, signal, type, inout, group);
        mobiles.put(root, mobile);
        if (network.getAsBoolean()) {
            int iconSizeId = root.getResources().getIdentifier("status_bar_icon_size", "dimen", "com.android.systemui");
            int iconSize = iconSizeId == 0 ? 0 : root.getResources().getDimensionPixelSize(iconSizeId);
            mobile.label = new NetworkTypeBadgeView(root.getContext(), iconSize);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.MATCH_PARENT);
            if (type.getLayoutParams() instanceof LinearLayout.LayoutParams nativeParams) {
                params.setMarginStart(nativeParams.getMarginStart());
                params.setMarginEnd(nativeParams.getMarginEnd());
                params.topMargin = nativeParams.topMargin;
                params.bottomMargin = nativeParams.bottomMargin;
                params.gravity = nativeParams.gravity;
            } else {
                params.setMarginEnd(Math.round(2 * root.getResources().getDisplayMetrics().density));
            }
            int typeIndex = group.indexOfChild(type);
            group.addView(mobile.label, Math.max(0, typeIndex), params);
            type.setVisibility(View.GONE);
        }
        if (!receiverRegistered) {
            IntentFilter filter = new IntentFilter("android.intent.action.ACTION_DEFAULT_DATA_SUBSCRIPTION_CHANGED");
            filter.addAction("android.telephony.action.SUBSCRIPTION_CARRIER_IDENTITY_CHANGED");
            root.getContext().registerReceiver(new BroadcastReceiver() {
                @Override public void onReceive(Context context, Intent intent) {
                    for (Mobile item : new ArrayList<>(mobiles.values())) requestUpdate(item);
                }
            }, filter, Context.RECEIVER_NOT_EXPORTED);
            receiverRegistered = true;
            if (network.getAsBoolean()) observeDefaultNetwork(root.getContext());
            TelephonyManager telephony = root.getContext().getSystemService(TelephonyManager.class);
            if (network.getAsBoolean() && telephony != null) {
                dataCallback = new DataSubscriptionCallback(() -> {
                    for (Mobile item : new ArrayList<>(mobiles.values())) requestUpdate(item);
                });
                try {
                    telephony.registerTelephonyCallback(root.getContext().getMainExecutor(), dataCallback);
                } catch (RuntimeException e) {
                    log.accept("Cannot observe active data subscription", e);
                }
            }
        }
    }

    private void prepare(ViewGroup container) throws ReflectiveOperationException {
        List<Mobile> visible = new ArrayList<>();
        List<Mobile> members = new ArrayList<>();
        for (int i = 0; i < container.getChildCount(); i++) {
            View child = container.getChildAt(i);
            if (!child.getClass().getName().equals(MOBILE)) continue;
            settings.accept(child.getContext());
            if (!merge.getAsBoolean() && !network.getAsBoolean()) continue;
            initialize((ViewGroup) child);
            Mobile mobile = mobiles.get(child);
            if (mobile == null) continue;
            members.add(mobile);
            mobile.secondary = false;
            mobile.primary = null;
            mobile.mergedSecond = null;
            if (child.getVisibility() == View.VISIBLE
                    && (Boolean) child.getClass().getMethod("isIconVisible").invoke(child)
                    && !(Boolean) child.getClass().getMethod("isIconBlocked").invoke(child)) visible.add(mobile);
        }
        Mobile primary = null;
        if (merge.getAsBoolean() && visible.size() == 2) {
            primary = visible.get(0);
            Mobile secondary = visible.get(1);
            secondary.secondary = true;
            secondary.primary = primary;
            primary.mergedSecond = secondary;
        }
        int active = activeDataSubscription();
        Mobile data = null;
        for (Mobile mobile : visible) {
            if ((Integer) mobile.root.getClass().getMethod("getSubId").invoke(mobile.root) == active) data = mobile;
        }
        for (Mobile mobile : members) {
            mobile.group.setAlpha(mobile.secondary ? 0f : 1f);
            if (mobile.inout != null && mobile.inout.getLayoutParams() instanceof FrameLayout.LayoutParams params) {
                int margin = mobile == primary
                        ? Math.round(3f * mobile.root.getResources().getDisplayMetrics().density)
                        : mobile.inoutMarginStart;
                int topMargin = mobile.inoutMarginTop + (mobile == primary
                        ? Math.round(0.5f * mobile.root.getResources().getDisplayMetrics().density) : 0);
                int gravity = mobile == primary ? Gravity.LEFT | Gravity.TOP : mobile.inoutGravity;
                if (params.getMarginStart() != margin || params.topMargin != topMargin || params.gravity != gravity) {
                    params.setMarginStart(margin);
                    params.topMargin = topMargin;
                    params.gravity = gravity;
                    mobile.inout.setLayoutParams(params);
                }
            }
            if (mobile.label == null) continue;
            Mobile source = primary == mobile ? data : (!mobile.secondary && mobile == data ? mobile : null);
            String label = source == null || wifiDefault ? "" : preferRat(source.rat);
            if (!android.text.TextUtils.equals(mobile.label.getLabel(), label)) {
                mobile.label.setLabel(label);
            }
            mobile.label.setVisibility(label.isEmpty() ? View.GONE : View.VISIBLE);
            mobile.type.setVisibility(View.GONE);
            updateTint(mobile);
        }
    }

    private void observeDefaultNetwork(Context context) {
        ConnectivityManager connectivity = context.getSystemService(ConnectivityManager.class);
        if (connectivity == null) return;
        try {
            NetworkCapabilities capabilities = connectivity.getNetworkCapabilities(connectivity.getActiveNetwork());
            wifiDefault = capabilities != null && capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI);
            networkCallback = new ConnectivityManager.NetworkCallback() {
                @Override public void onCapabilitiesChanged(Network current, NetworkCapabilities capabilities) {
                    setWifiDefault(capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI));
                }

                @Override public void onLost(Network current) { setWifiDefault(false); }
            };
            connectivity.registerDefaultNetworkCallback(networkCallback, new Handler(Looper.getMainLooper()));
        } catch (RuntimeException e) {
            log.accept("Cannot observe default network for RAT visibility", e);
        }
    }

    private void setWifiDefault(boolean wifi) {
        if (wifiDefault == wifi) return;
        wifiDefault = wifi;
        for (Mobile mobile : new ArrayList<>(mobiles.values())) requestUpdate(mobile);
    }

    private void requestUpdate(Mobile mobile) {
        if (mobile.root.getParent() instanceof View parent) parent.requestLayout();
        else mobile.root.requestLayout();
        if (mobile.primary != null) mobile.primary.signal.invalidate();
    }

    private static void updateTint(Mobile mobile) {
        if (mobile.label == null) return;
        ColorStateList tint = mobile.signal.getImageTintList();
        if (tint != null) mobile.label.setTintList(tint);
    }

    private static int activeDataSubscription() {
        try {
            int active = (Integer) SubscriptionManager.class.getMethod("getActiveDataSubscriptionId").invoke(null);
            if (SubscriptionManager.isValidSubscriptionId(active)) return active;
        } catch (ReflectiveOperationException ignored) { }
        return SubscriptionManager.getDefaultDataSubscriptionId();
    }

    private static String ratName(Context context, int resource) {
        if (resource == 0) return "";
        String name = context.getResources().getResourceEntryName(resource).toLowerCase(java.util.Locale.ROOT)
                .replace('-', '_').replace(' ', '_');
        String compact = name.replace("_", "");
        if (compact.contains("5g") || compact.contains("nr5g")
                || name.contains("nr_advanced") || name.contains("nr_5g")) {
            boolean advanced = compact.contains("advanced") || compact.contains("5ga")
                    || compact.contains("plusa") || compact.contains("5gplusa")
                    || compact.contains("nradv");
            boolean plus = compact.contains("plus") || name.contains("+");
            if (advanced) return "5G+A";
            if (plus) return "5G+";
            return "5G";
        }
        if (compact.contains("4g") || compact.contains("lte")) return compact.contains("plus") ? "4G+" : "4G";
        if (compact.contains("3g")) return "3G";
        if (name.contains("h_plus")) return "H+";
        if (name.contains("_h")) return "H";
        if (name.contains("_1x")) return "1X";
        if (name.contains("_e")) return "E";
        if (name.contains("_g")) return "G";
        return "";
    }

    private static String preferRat(String nativeRat) {
        String telephony = telephonyFivegLabel();
        if (telephony == null) return nativeRat;
        return fivegRank(telephony) >= fivegRank(nativeRat) ? telephony : nativeRat;
    }

    private static int fivegRank(String label) {
        if (label == null || label.isEmpty()) return 0;
        if (label.startsWith("5G+A") || label.startsWith("5GA")) return 4;
        if (label.startsWith("5G+")) return 3;
        if (label.startsWith("5G")) return 2;
        return 1;
    }

    private static String telephonyFivegLabel() {
        if (radioOverrideNetworkType == OVERRIDE_NR_ADVANCED) return "5G+A";
        if (radioOverrideNetworkType == OVERRIDE_NR_NSA_MMWAVE) return "5G+";
        if (radioOverrideNetworkType == OVERRIDE_NR_NSA) return "5G";
        if (radioNetworkType == TelephonyManager.NETWORK_TYPE_NR)
            return nrCaEnabled() ? "5G+" : "5G";
        if (radioNrState == 2 || radioNrState == 3)
            return nrCaEnabled() ? "5G+" : "5G";
        return null;
    }

    private static boolean nrCaEnabled() {
        return "true".equalsIgnoreCase(radioNrCaState) || "1".equals(radioNrCaState)
                || "2".equals(radioNrCaState);
    }

    private static int id(View view, String name) {
        return view.getResources().getIdentifier(name, "id", "com.android.systemui");
    }

    private Class<?> type(String name) throws ClassNotFoundException { return Class.forName(name, false, loader); }

    private static final class Mobile {
        final ViewGroup root;
        final ImageView signal, type, inout;
        final LinearLayout group;
        final int inoutMarginStart, inoutMarginTop, inoutGravity;
        NetworkTypeBadgeView label;
        Mobile primary, mergedSecond;
        boolean secondary;
        String rat = "";

        Mobile(ViewGroup root, ImageView signal, ImageView type, ImageView inout, LinearLayout group) {
            this.root = root;
            this.signal = signal;
            this.type = type;
            this.inout = inout;
            this.group = group;
            FrameLayout.LayoutParams params = inout != null && inout.getLayoutParams() instanceof FrameLayout.LayoutParams
                    ? (FrameLayout.LayoutParams) inout.getLayoutParams() : null;
            inoutMarginStart = params == null ? 0 : params.getMarginStart();
            inoutMarginTop = params == null ? 0 : params.topMargin;
            inoutGravity = params == null ? -1 : params.gravity;
        }
    }

    private static final class DataSubscriptionCallback extends TelephonyCallback
            implements TelephonyCallback.ActiveDataSubscriptionIdListener {
        private final Runnable update;

        DataSubscriptionCallback(Runnable update) { this.update = update; }

        @Override public void onActiveDataSubscriptionIdChanged(int subId) { update.run(); }
    }

    private static void drawLowerSignal(Canvas canvas, ImageView upper, ImageView lower) {
        Drawable drawable = lower.getDrawable();
        int width = upper.getWidth(), height = upper.getHeight();
        if (drawable == null || drawable.getBounds().isEmpty() || width <= 0 || height <= 0
                || lower.getWidth() <= 0 || lower.getHeight() <= 0) return;
        int save = canvas.save();
        try {

            canvas.clipRect(0, height * 0.65f, width, height);
            canvas.scale((float) width / lower.getWidth(), (float) height / lower.getHeight());
            canvas.translate(lower.getPaddingLeft(), lower.getPaddingTop());
            canvas.concat(lower.getImageMatrix());
            drawable.draw(canvas);
        } finally {
            canvas.restoreToCount(save);
        }
    }
}
