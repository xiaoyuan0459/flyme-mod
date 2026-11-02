package dev.rikumi.flymemod;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Typeface;
import android.view.View;

/**
 * Compact, optically centred network-type mark for the status bar. The base text stays in a
 * medium-weight sans face; "+" is drawn as a vector superscript and the 5G-Advanced "A" as a
 * subscript so the small glyphs do not depend on font metrics or TextView span clipping.
 */
final class NetworkTypeBadgeView extends View {
    private static final float MAIN_SIZE_RATIO = .60f;
    private static final float SUFFIX_SIZE_RATIO = .46f;
    private static final float PLUS_SIZE_RATIO = .48f;
    private static final float PLUS_STROKE_RATIO = .075f;
    private static final float GAP_RATIO = .055f;
    private static final float SIDE_PADDING_RATIO = .06f;
    private static final float PLUS_RAISE_RATIO = .40f;
    private static final float SUBSCRIPT_DROP_RATIO = .17f;

    private final Paint mainPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint suffixPaint = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint plusPaint = new Paint(Paint.ANTI_ALIAS_FLAG);

    private ColorStateList tint;
    private String label = "";
    private String mainText = "";
    private boolean plus, advanced;
    private float mainSize, suffixSize, plusSize, plusStroke;
    private float gap, sidePadding, mainWidth, suffixWidth, contentWidth, baseline;

    NetworkTypeBadgeView(Context context) {
        super(context);
        setWillNotDraw(false);
        plusPaint.setStyle(Paint.Style.FILL);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
    }

    boolean setLabel(String value) {
        String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT)
                .replace(" ", "");
        boolean hasPlus = false, hasAdvanced = false;
        for (;;) {
            if (normalized.endsWith("+")) {
                hasPlus = true;
                normalized = normalized.substring(0, normalized.length() - 1);
            } else if (normalized.endsWith("A")) {
                hasAdvanced = true;
                normalized = normalized.substring(0, normalized.length() - 1);
            } else if (normalized.endsWith("-")) {
                normalized = normalized.substring(0, normalized.length() - 1);
            } else {
                break;
            }
        }
        String nextLabel = normalized + (hasPlus ? "+" : "") + (hasAdvanced ? "A" : "");
        if (nextLabel.equals(label)) return false;
        label = nextLabel;
        mainText = normalized;
        plus = hasPlus;
        advanced = hasAdvanced;
        setContentDescription(label);
        requestLayout();
        invalidate();
        return true;
    }

    String getLabel() {
        return label;
    }

    void setTintList(ColorStateList value) {
        tint = value;
        invalidate();
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        int height = MeasureSpec.getSize(heightMeasureSpec);
        if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED || height <= 0)
            height = Math.round(20f * getResources().getDisplayMetrics().density);
        updateMetrics(height);
        int desired = Math.max(1, Math.round(contentWidth));
        int width = MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY
                ? MeasureSpec.getSize(widthMeasureSpec) : desired;
        setMeasuredDimension(width, height);
    }

    private void updateMetrics(int height) {
        mainSize = Math.max(1f, height * MAIN_SIZE_RATIO);
        suffixSize = mainSize * SUFFIX_SIZE_RATIO;
        plusSize = mainSize * PLUS_SIZE_RATIO;
        plusStroke = Math.max(1f, mainSize * PLUS_STROKE_RATIO);
        gap = mainSize * GAP_RATIO;
        sidePadding = mainSize * SIDE_PADDING_RATIO;
        Typeface face = Typeface.create("sans-serif-medium", Typeface.NORMAL);
        if (face == null) face = Typeface.DEFAULT_BOLD;
        mainPaint.setTypeface(face);
        mainPaint.setTextSize(mainSize);
        mainPaint.setLetterSpacing(-.01f);
        mainWidth = mainPaint.measureText(mainText);
        suffixPaint.setTypeface(face);
        suffixPaint.setTextSize(suffixSize);
        suffixWidth = advanced ? suffixPaint.measureText("A") : 0f;
        contentWidth = sidePadding * 2 + mainWidth
                + (plus ? gap + plusSize : 0f)
                + (advanced ? gap + suffixWidth : 0f);
        Paint.FontMetrics metrics = mainPaint.getFontMetrics();
        baseline = height * .5f - (metrics.ascent + metrics.descent) * .5f;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        if (mainText.isEmpty()) return;
        int color = tint == null ? 0xFFFFFFFF
                : tint.getColorForState(getDrawableState(), tint.getDefaultColor());
        mainPaint.setColor(color);
        suffixPaint.setColor(color);
        plusPaint.setColor(color);

        float left = (getWidth() - contentWidth) * .5f + sidePadding;
        canvas.drawText(mainText, left, baseline, mainPaint);
        left += mainWidth;
        if (plus) {
            float centerX = left + gap + plusSize * .5f;
            float centerY = baseline - mainSize * PLUS_RAISE_RATIO;
            float half = plusSize * .5f;
            float stroke = plusStroke;
            canvas.drawRoundRect(centerX - half, centerY - stroke * .5f,
                    centerX + half, centerY + stroke * .5f, stroke * .5f, stroke * .5f, plusPaint);
            canvas.drawRoundRect(centerX - stroke * .5f, centerY - half,
                    centerX + stroke * .5f, centerY + half, stroke * .5f, stroke * .5f, plusPaint);
            left += gap + plusSize;
        }
        if (advanced) {
            canvas.drawText("A", left + gap, baseline + mainSize * SUBSCRIPT_DROP_RATIO, suffixPaint);
        }
    }
}
