package com.minimaltv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffXfermode;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.widget.ImageView;

/** A faint, squashed, mirrored copy of an ImageView's picture that fades out downwards (floating icons). */
final class ReflectionView extends android.view.View {

    private final ImageView source;
    private final Paint fade = new Paint();
    private final Rect saved = new Rect();
    private final float iconW, iconH;

    /** iconW/iconH: the size, in px, at which the focused icon is drawn. */
    ReflectionView(Context context, ImageView source, float iconW, float iconH) {
        super(context);
        this.source = source;
        this.iconW = iconW;
        this.iconH = iconH;
        fade.setXfermode(new PorterDuffXfermode(PorterDuff.Mode.DST_IN));
        setFocusable(false);
        setWillNotDraw(false);
    }

    /** Height, in px, of the picture as drawn in the icon box. */
    float pictureHeight() {
        Drawable d = source.getDrawable();
        if (d == null || d.getIntrinsicWidth() <= 0 || d.getIntrinsicHeight() <= 0) return iconH;
        return d.getIntrinsicHeight() * Math.min(iconW / d.getIntrinsicWidth(), iconH / d.getIntrinsicHeight());
    }

    @Override
    protected void onSizeChanged(int w, int h, int ow, int oh) {
        fade.setShader(new LinearGradient(0, 0, 0, h, 0x5CFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        Drawable d = source.getDrawable();
        int w = getWidth(), h = getHeight();
        if (d == null || w == 0 || h == 0) return;
        // Fit the picture into the icon box the way FIT_CENTER does.
        int iw = d.getIntrinsicWidth(), ih = d.getIntrinsicHeight();
        float s = (iw > 0 && ih > 0) ? Math.min(iconW / iw, iconH / ih) : 1f;
        float dw = iw > 0 ? iw * s : iconW, dh = ih > 0 ? ih * s : iconH;
        int layer = canvas.saveLayer(0, 0, w, h, null);
        canvas.save();
        // Flip about the top edge and squash to ~45%, so the picture's bottom sits at our top.
        canvas.translate((w - dw) / 2f, 0);
        canvas.scale(1f, -0.45f);
        canvas.translate(0, -dh);
        saved.set(d.getBounds());
        d.setBounds(0, 0, Math.round(dw), Math.round(dh));
        d.draw(canvas);
        d.setBounds(saved);
        canvas.restore();
        canvas.drawRect(0, 0, w, h, fade);
        canvas.restoreToCount(layer);
    }
}
