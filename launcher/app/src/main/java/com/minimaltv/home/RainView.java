package com.minimaltv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.view.View;

import java.util.Random;

/**
 * Floating icons, rainy weather only: a faint cool-blue tint and thin diagonal streaks drifting down.
 * Cheap on purpose: one drawLines call per frame, at most ~30 fps, and nothing scheduled while stopped.
 */
final class RainView extends View {

    private static final int DROPS = 70;
    private static final long FRAME_MS = 33;   // <= 30 fps
    private static final float SLANT = 0.22f;  // dx per dy

    private final float[] x = new float[DROPS], y = new float[DROPS], len = new float[DROPS], speed = new float[DROPS];
    private final float[] pts = new float[DROPS * 4];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float density;
    private boolean running;
    private long last;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, FRAME_MS);
        }
    };

    RainView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        paint.setStrokeWidth(Math.max(1f, density * 0.75f));
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setColor(0x2EE6F0FF);   // ~18% pale blue-white
        setFocusable(false);
        setClickable(false);
        setWillNotDraw(false);
        setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);
    }

    private void seed(int i, int w, int h, boolean anywhere) {
        x[i] = random.nextFloat() * (w + h * SLANT) - h * SLANT;
        y[i] = anywhere ? random.nextFloat() * h : -random.nextFloat() * h * 0.2f;
        len[i] = (14 + random.nextFloat() * 22) * density;
        speed[i] = (160 + random.nextFloat() * 120) * density;   // px per second: slow
    }

    /** Starts or stops the animation; stopped also hides the view. */
    void setRaining(boolean on) {
        setVisibility(on ? VISIBLE : GONE);
        setRunning(on);
    }

    /** Pause/resume with the activity without changing visibility. */
    void setRunning(boolean on) {
        boolean go = on && getVisibility() == VISIBLE;
        if (go == running) return;
        running = go;
        removeCallbacks(frame);
        if (go) {
            last = 0;
            post(frame);
        }
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        running = false;
        removeCallbacks(frame);
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        for (int i = 0; i < DROPS; i++) seed(i, w, h, true);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        canvas.drawColor(0x1A2F5A8C);   // cool-blue tint, ~10%
        long now = android.os.SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(0.1f, (now - last) / 1000f);
        last = now;
        for (int i = 0; i < DROPS; i++) {
            y[i] += speed[i] * dt;
            x[i] += speed[i] * dt * SLANT;
            if (y[i] - len[i] > h) seed(i, w, h, false);
            int k = i * 4;
            pts[k] = x[i];
            pts[k + 1] = y[i];
            pts[k + 2] = x[i] - len[i] * SLANT;
            pts[k + 3] = y[i] - len[i];
        }
        canvas.drawLines(pts, paint);
    }
}
