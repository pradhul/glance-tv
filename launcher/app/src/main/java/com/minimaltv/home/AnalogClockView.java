package com.minimaltv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.Build;
import android.os.SystemClock;
import android.view.View;

import java.util.Calendar;

/**
 * Minimal analog clock for the Glass style: 12 small ticks (longer at 12/3/6/9), slim hour and minute hands
 * with round caps, a small centre dot; optional thin seconds hand (off). One colour plus a soft shadow.
 * Redraws once a minute, aligned to the minute boundary (or once a second when seconds are shown).
 */
final class AnalogClockView extends View {

    private final Paint tick = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint hand = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private boolean showSeconds;
    private boolean attached;

    private final Runnable tickRunnable = new Runnable() {
        @Override
        public void run() {
            invalidate();
            long now = System.currentTimeMillis();
            long period = showSeconds ? 1000 : 60_000;
            postDelayed(this, period - (now % period) + 20);
        }
    };

    AnalogClockView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        tick.setStrokeCap(Paint.Cap.ROUND);
        hand.setStrokeCap(Paint.Cap.ROUND);
        tick.setStyle(Paint.Style.STROKE);
        hand.setStyle(Paint.Style.STROKE);
        // Shadows on strokes need a software layer before API 28.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) setLayerType(LAYER_TYPE_SOFTWARE, null);
        setColor(0xFFFFFFFF, true);
    }

    void setColor(int color, boolean shadow) {
        float r = shadow ? 6 * density : 0;
        for (Paint p : new Paint[]{tick, hand, dot}) {
            p.setColor(color);
            p.setShadowLayer(r, 0, shadow ? density : 0, 0x66000000);
        }
        invalidate();
    }

    void setShowSeconds(boolean on) {
        showSeconds = on;
        restart();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        restart();
    }

    @Override
    protected void onDetachedFromWindow() {
        attached = false;
        removeCallbacks(tickRunnable);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onVisibilityChanged(View changedView, int visibility) {
        super.onVisibilityChanged(changedView, visibility);
        restart();
    }

    private void restart() {
        removeCallbacks(tickRunnable);
        if (attached && isShown()) tickRunnable.run();
    }

    @Override
    protected void onDraw(Canvas c) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f;
        float r = Math.min(cx, cy) - 8 * density;   // room for the shadow
        Calendar now = Calendar.getInstance();
        float min = now.get(Calendar.MINUTE) + now.get(Calendar.SECOND) / 60f;
        float hour = (now.get(Calendar.HOUR) + min / 60f);

        for (int i = 0; i < 12; i++) {
            boolean major = i % 3 == 0;
            tick.setStrokeWidth((major ? 2.2f : 1.4f) * density);
            double a = Math.toRadians(i * 30);
            float outer = r, inner = r - (major ? 11 : 6) * density;
            float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
            c.drawLine(cx + sx * inner, cy + sy * inner, cx + sx * outer, cy + sy * outer, tick);
        }
        hand(c, cx, cy, hour * 30, r * 0.50f, 3.6f);
        hand(c, cx, cy, min * 6, r * 0.78f, 2.4f);
        if (showSeconds) {
            int sec = now.get(Calendar.SECOND);
            hand(c, cx, cy, sec * 6, r * 0.84f, 1f);
        }
        c.drawCircle(cx, cy, 4 * density, dot);
    }

    private void hand(Canvas c, float cx, float cy, float deg, float len, float width) {
        hand.setStrokeWidth(width * density);
        double a = Math.toRadians(deg);
        float tail = 0.12f * len;
        float sx = (float) Math.sin(a), sy = (float) -Math.cos(a);
        c.drawLine(cx - sx * tail, cy - sy * tail, cx + sx * len, cy + sy * len, hand);
    }
}
