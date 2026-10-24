package com.minimaltv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.view.View;

/**
 * v25: one Bluetooth device by the clock: a line icon, full ink when connected and dimmed when not, a thin battery
 * bar under it when the level is known, and an amber dot on a remote below 20%. Not focusable; drawn, not laid out.
 */
final class BtIconView extends View {

    static final int AMBER = 0xFFFFB020;
    private static final int ICON_DP = 20, BAR_W_DP = 16, BAR_H_DP = 2, GAP_DP = 3;

    private final float d;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private Drawable icon;
    private int ink = 0xFFFFFFFF;
    Bt.State state;

    BtIconView(Context c) {
        super(c);
        d = c.getResources().getDisplayMetrics().density;
        setFocusable(false);
        setWillNotDraw(false);
    }

    void bind(Bt.State s, int ink) {
        state = s;
        this.ink = ink;
        int res = s.kind == Bt.KIND_REMOTE ? R.drawable.ic_bt_remote : s.headphones ? R.drawable.ic_bt_headphones : R.drawable.ic_bt_speaker;
        icon = getContext().getDrawable(res).mutate();
        setContentDescription((s.name != null ? s.name : "Bluetooth") + (s.connected ? ", connected" : ", disconnected")
                + (s.battery >= 0 ? ", battery " + s.battery + "%" : ""));
        invalidate();
    }

    boolean showsLowDot() {
        return state != null && state.kind == Bt.KIND_REMOTE && state.connected && state.low();
    }

    boolean showsBar() {
        return state != null && state.connected && state.battery >= 0;
    }

    @Override
    protected void onMeasure(int w, int h) {
        setMeasuredDimension(Math.round((ICON_DP + 4) * d), Math.round((ICON_DP + GAP_DP + BAR_H_DP + 1) * d));
    }

    @Override
    protected void onDraw(Canvas c) {
        if (icon == null || state == null) return;
        int alpha = state.connected ? 255 : 90;
        int cx = getWidth() / 2, s = Math.round(ICON_DP * d);
        icon.setBounds(cx - s / 2, 0, cx - s / 2 + s, s);
        icon.setTint(ink);
        icon.setAlpha(alpha);
        icon.draw(c);
        if (showsBar()) {
            float bw = BAR_W_DP * d, bh = BAR_H_DP * d, top = s + GAP_DP * d;
            r.set(cx - bw / 2, top, cx + bw / 2, top + bh);
            paint.setColor((ink & 0x00FFFFFF) | 0x4D000000);
            c.drawRoundRect(r, bh / 2, bh / 2, paint);
            r.right = r.left + bw * Math.max(0.06f, state.battery / 100f);
            paint.setColor(state.low() ? AMBER : ink);
            c.drawRoundRect(r, bh / 2, bh / 2, paint);
        }
        if (showsLowDot()) {
            float rad = 3.2f * d;
            paint.setColor(0x99000000);
            c.drawCircle(cx + s / 2f - rad * 0.6f, rad + d * 0.5f, rad + d, paint);
            paint.setColor(AMBER);
            c.drawCircle(cx + s / 2f - rad * 0.6f, rad + d * 0.5f, rad, paint);
        }
    }
}
