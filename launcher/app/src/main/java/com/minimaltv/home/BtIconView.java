package com.minimaltv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.text.TextPaint;
import android.view.View;

/**
 * One Bluetooth device by the clock: a line icon coloured by state (v28: green / amber / red by battery, ink when
 * the level is unknown, grey with a slash when disconnected), a thin battery bar in the same colour, a red dot on a
 * remote under 20%, and a short name ("Mi 61%") in small text underneath. Not focusable; drawn, not laid out.
 */
final class BtIconView extends View {

    static final int AMBER = Bt.AMBER;
    private static final int ICON_DP = 20, BAR_W_DP = 16, BAR_H_DP = 2, GAP_DP = 3, LABEL_GAP_DP = 3;
    private static final float LABEL_SP = 9.5f;

    private final float d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final TextPaint text = new TextPaint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private Drawable icon;
    private int ink = 0xFFFFFFFF;
    Bt.State state;
    String label = "";
    int color;

    BtIconView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        text.setTextSize(LABEL_SP * c.getResources().getDisplayMetrics().scaledDensity);
        text.setTypeface(Typeface.create("sans-serif", Typeface.NORMAL));
        text.setTextAlign(Paint.Align.CENTER);
        text.setShadowLayer(3 * d, 0, d * 0.5f, 0x80000000);
        setFocusable(false);
        setWillNotDraw(false);
    }

    void bind(Bt.State s, int ink) {
        state = s;
        this.ink = ink;
        color = Bt.color(s, ink);
        label = Bt.label(s);
        int res = s.kind == Bt.KIND_REMOTE ? R.drawable.ic_bt_remote : s.headphones ? R.drawable.ic_bt_headphones : R.drawable.ic_bt_speaker;
        icon = getContext().getDrawable(res).mutate();
        setContentDescription((s.name != null ? s.name : s.usb ? "USB" : "Bluetooth") + (s.connected ? ", connected" : ", disconnected")
                + (s.battery >= 0 ? ", battery " + s.battery + "%" : ""));
        requestLayout();
        invalidate();
    }

    boolean showsLowDot() {
        return state != null && state.kind == Bt.KIND_REMOTE && state.connected && state.low();
    }

    boolean showsBar() {
        return state != null && state.connected && state.battery >= 0;
    }

    boolean showsSlash() {
        return state != null && !state.connected;
    }

    @Override
    protected void onMeasure(int w, int h) {
        float tw = label.isEmpty() ? 0 : text.measureText(label);
        int width = (int) Math.ceil(Math.max((ICON_DP + 4) * d, tw + 2 * d));
        Paint.FontMetrics fm = text.getFontMetrics();
        float labelH = label.isEmpty() ? 0 : LABEL_GAP_DP * d + (fm.descent - fm.ascent);
        setMeasuredDimension(width, Math.round((ICON_DP + GAP_DP + BAR_H_DP + 1) * d + labelH));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (icon == null || state == null) return;
        int cx = getWidth() / 2, s = Math.round(ICON_DP * d);
        icon.setBounds(cx - s / 2, 0, cx - s / 2 + s, s);
        icon.setTint(color);
        icon.setAlpha(state.connected ? 255 : 150);
        icon.draw(c);
        if (showsSlash()) {
            // Disconnected: a short slash across the icon, with a dark edge so it reads on light photos.
            paint.setStrokeCap(Paint.Cap.ROUND);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(3.2f * d);
            paint.setColor(0x66000000);
            c.drawLine(cx - s * 0.36f, s * 0.14f, cx + s * 0.36f, s * 0.86f, paint);
            paint.setStrokeWidth(1.6f * d);
            paint.setColor(color);
            c.drawLine(cx - s * 0.36f, s * 0.14f, cx + s * 0.36f, s * 0.86f, paint);
            paint.setStyle(Paint.Style.FILL);
        }
        float bh = BAR_H_DP * d, top = s + GAP_DP * d;
        if (showsBar()) {
            float bw = BAR_W_DP * d;
            r.set(cx - bw / 2, top, cx + bw / 2, top + bh);
            paint.setColor((color & 0x00FFFFFF) | 0x4D000000);
            c.drawRoundRect(r, bh / 2, bh / 2, paint);
            r.right = r.left + bw * Math.max(0.06f, state.battery / 100f);
            paint.setColor(color);
            c.drawRoundRect(r, bh / 2, bh / 2, paint);
        }
        if (showsLowDot()) {
            float rad = 3.2f * d;
            paint.setColor(0x99000000);
            c.drawCircle(cx + s / 2f - rad * 0.6f, rad + d * 0.5f, rad + d, paint);
            paint.setColor(Bt.RED);
            c.drawCircle(cx + s / 2f - rad * 0.6f, rad + d * 0.5f, rad, paint);
        }
        if (!label.isEmpty()) {
            Paint.FontMetrics fm = text.getFontMetrics();
            float base = top + bh + d + LABEL_GAP_DP * d - fm.ascent;
            // Name in ink (dimmed when off), the % in the state colour.
            String name = Bt.displayName(state);
            String pct = label.substring(Math.min(label.length(), name.length()));
            float wName = text.measureText(name), wAll = text.measureText(label);
            float x0 = cx - wAll / 2f;
            text.setTextAlign(Paint.Align.LEFT);
            text.setColor(state.connected ? ink : (ink & 0x00FFFFFF) | 0x99000000);
            text.setAlpha(state.connected ? 235 : 150);
            c.drawText(name, x0, base, text);
            if (!pct.isEmpty()) {
                text.setColor(color);
                c.drawText(pct, x0 + wName, base, text);
            }
        }
    }
}
