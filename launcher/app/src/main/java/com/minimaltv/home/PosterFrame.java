package com.minimaltv.home;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.animation.AccelerateDecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * A poster card that can sweep a diagonal glare band across itself. The band is painted in dispatchDraw, after
 * the card's children (so on top of the content) and clipped to the card's own rounded rectangle.
 */
final class PosterFrame extends FrameLayout {

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path clip = new Path();
    private final RectF rect = new RectF();
    private final float radius, band;
    private float progress = -1f;   // -1: no glare; 0..1: band position across the card
    private ValueAnimator sweep;

    PosterFrame(Context context, float radius) {
        super(context);
        this.radius = radius;
        this.band = 90 * context.getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
    }

    /** One sweep, left to right, over durationMs (after delayMs). */
    void sweep(long delayMs, long durationMs) {
        stopGlare();
        sweep = ValueAnimator.ofFloat(0f, 1f);
        sweep.setStartDelay(delayMs);
        sweep.setDuration(durationMs);
        sweep.setInterpolator(new AccelerateDecelerateInterpolator());
        sweep.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            invalidate();
        });
        sweep.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator animation) {
                progress = -1f;
                invalidate();
            }
        });
        sweep.start();
    }

    void stopGlare() {
        if (sweep != null) sweep.cancel();
        sweep = null;
        progress = -1f;
        invalidate();
    }

    @Override
    protected void dispatchDraw(Canvas canvas) {
        super.dispatchDraw(canvas);
        if (progress < 0f) return;
        int w = getWidth(), h = getHeight();
        if (w == 0 || h == 0) return;
        canvas.save();
        rect.set(0, 0, w, h);
        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);
        canvas.clipPath(clip);
        // The band leans ~20 degrees; its centre travels from left of the card to past its right edge.
        float slant = h * 0.36f;
        float cx = -band - slant + progress * (w + 2 * band + 2 * slant);
        canvas.translate(cx, 0);
        canvas.skew(-slant / h, 0);
        paint.setShader(new LinearGradient(-band / 2f, 0, band / 2f, 0,
                new int[]{0x00FFFFFF, 0x59FFFFFF, 0x00FFFFFF}, null, Shader.TileMode.CLAMP));
        canvas.drawRect(-band / 2f, 0, band / 2f, h, paint);
        canvas.restore();
    }
}
