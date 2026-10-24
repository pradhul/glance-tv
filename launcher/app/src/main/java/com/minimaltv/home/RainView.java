package com.minimaltv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.view.View;

import java.util.Random;

/**
 * Weather overlay over the wallpaper, under all chrome (v21 rain, v25 for every style plus mist and a night glow).
 * Rain: a faint cool-blue tint and thin diagonal streaks, one drawLines call per frame at most ~30 fps.
 * Mist: two wide soft bands drifting sideways at ~8 fps. Glow: one static radial gradient, never animated.
 * Nothing is scheduled while stopped or hidden, and no bitmaps are allocated (it matters on a 1.8 GB TV).
 */
final class RainView extends View {

    static final int FX_NONE = 0, FX_RAIN = 1, FX_MIST = 2, FX_GLOW = 3;

    private static final int DROPS = 70;
    private static final long FRAME_MS = 33;        // rain: <= 30 fps
    private static final long MIST_FRAME_MS = 125;  // mist: 8 fps is plenty for a slow drift
    private static final float SLANT = 0.22f;  // dx per dy

    private final float[] x = new float[DROPS], y = new float[DROPS], len = new float[DROPS], speed = new float[DROPS];
    private final float[] pts = new float[DROPS * 4];
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Random random = new Random();
    private final float density;
    private int mode = FX_NONE;
    private boolean running;
    private long last;
    private float mistPhase;

    private final Runnable frame = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            invalidate();
            postDelayed(this, mode == FX_MIST ? MIST_FRAME_MS : FRAME_MS);
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

    int mode() {
        return mode;
    }

    /** Starts or stops the rain; stopped also hides the view. */
    void setRaining(boolean on) {
        setMode(on ? FX_RAIN : FX_NONE);
    }

    /** Picks the effect (FX_*); FX_NONE hides the view. Call setRunning to animate. */
    void setMode(int m) {
        if (m != mode) {
            mode = m;
            fill.setShader(null);
            boolean wasRunning = running;
            setRunning(false);
            setVisibility(m == FX_NONE ? GONE : VISIBLE);
            if (getWidth() > 0) shaders(getWidth(), getHeight());
            if (wasRunning) setRunning(true);
            invalidate();
        } else {
            setVisibility(m == FX_NONE ? GONE : VISIBLE);
        }
    }

    /** Pause/resume with the activity without changing visibility. The glow is static, so it never runs. */
    void setRunning(boolean on) {
        boolean go = on && getVisibility() == VISIBLE && (mode == FX_RAIN || mode == FX_MIST);
        if (go == running) return;
        running = go;
        removeCallbacks(frame);
        if (go) {
            last = 0;
            post(frame);
        }
    }

    boolean isRunning() {
        return running;
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
        shaders(w, h);
    }

    private void shaders(int w, int h) {
        if (w == 0 || h == 0) return;
        if (mode == FX_GLOW) {
            // A soft moonlit glow from the top right, warm-white at ~14% fading to nothing.
            fill.setShader(new RadialGradient(w * 0.82f, h * 0.08f, h * 0.9f,
                    new int[]{0x24FFF4DC, 0x10DCE6FF, 0x00000000}, new float[]{0f, 0.45f, 1f}, Shader.TileMode.CLAMP));
        } else if (mode == FX_MIST) {
            fill.setShader(new LinearGradient(0, 0, 0, h, new int[]{0x00FFFFFF, 0x1FE8EEF2, 0x08E8EEF2, 0x24E8EEF2, 0x00FFFFFF},
                    new float[]{0f, 0.35f, 0.55f, 0.78f, 1f}, Shader.TileMode.CLAMP));
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        if (mode == FX_GLOW) {
            canvas.drawRect(0, 0, w, h, fill);
            return;
        }
        long now = android.os.SystemClock.uptimeMillis();
        float dt = last == 0 ? 0 : Math.min(0.2f, (now - last) / 1000f);
        last = now;
        if (mode == FX_MIST) {
            // Two passes of the same banded gradient, offset and breathing slightly: reads as drifting mist.
            mistPhase += dt * 0.12f;
            float s = (float) Math.sin(mistPhase);
            canvas.save();
            canvas.translate(s * w * 0.04f, s * h * 0.02f);
            fill.setAlpha(255);
            canvas.drawRect(-w * 0.1f, -h * 0.1f, w * 1.1f, h * 1.1f, fill);
            canvas.restore();
            canvas.save();
            canvas.translate(-s * w * 0.05f, h * 0.12f + (float) Math.cos(mistPhase * 0.7f) * h * 0.03f);
            fill.setAlpha(150);
            canvas.drawRect(-w * 0.1f, -h * 0.2f, w * 1.1f, h, fill);
            canvas.restore();
            return;
        }
        if (mode != FX_RAIN) return;
        canvas.drawColor(0x1A2F5A8C);   // cool-blue tint, ~10%
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
