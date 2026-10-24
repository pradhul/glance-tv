package com.minimaltv.home;

import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.view.animation.AccelerateInterpolator;
import android.view.animation.DecelerateInterpolator;

/**
 * Brand-colour radial glow used behind a selected All-apps icon (and the same shape as the floating
 * home halo). The view must stay decorative: never focusable or clickable, or D-pad leaves GridView
 * selection.
 */
final class FocusGlow {

    private FocusGlow() {
    }

    static GradientDrawable drawable(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(color & 0x00FFFFFF) | 0x99000000, (color & 0x00FFFFFF) | 0x44000000, color & 0x00FFFFFF});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(radiusPx);
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    /** Soft halo behind a floating home icon: same radial oval, slightly lighter alphas. */
    static GradientDrawable halo(int color, float radiusPx) {
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
                new int[]{(color & 0x00FFFFFF) | 0x59000000, (color & 0x00FFFFFF) | 0x1F000000, color & 0x00FFFFFF});
        g.setGradientType(GradientDrawable.RADIAL_GRADIENT);
        g.setGradientRadius(radiusPx);
        g.setShape(GradientDrawable.OVAL);
        return g;
    }

    static void fadeIn(View g, int color, float radiusPx) {
        g.setBackground(drawable(color, radiusPx));
        g.animate().cancel();
        g.setAlpha(0f);
        g.setScaleX(0.5f);
        g.setScaleY(0.5f);
        g.setVisibility(View.VISIBLE);
        g.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(Looks.GLOW_IN_MS)
                .setInterpolator(new DecelerateInterpolator(1.5f)).withEndAction(null).start();
    }

    static void fadeOut(View g) {
        g.animate().cancel();
        g.animate().alpha(0f).scaleX(0.85f).scaleY(0.85f).setDuration(Looks.GLOW_OUT_MS)
                .setInterpolator(new AccelerateInterpolator())
                .withEndAction(() -> {
                    if (g.getTag(R.id.tag_glow_pos) == null) g.setVisibility(View.INVISIBLE);
                }).start();
    }

    static void hideNow(View g) {
        g.animate().cancel();
        g.setTag(R.id.tag_glow_pos, null);
        g.setAlpha(0f);
        g.setVisibility(View.INVISIBLE);
    }
}
