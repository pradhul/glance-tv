package com.minimaltv.home;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * Simple single-colour app glyphs (Simple Icons, CC0; Material Icons, Apache-2.0; Arcticons line icons for
 * streaming apps, CC BY-SA 4.0) by package, tinted by the
 * caller. Unknown apps get a rounded square with the label's first letter. Real icons are never used here.
 */
final class Glyphs {

    private static final Map<String, Integer> BY_PACKAGE = new HashMap<>();

    static {
        BY_PACKAGE.put("com.netflix.ninja", R.drawable.ic_app_arc_netflix);
        BY_PACKAGE.put("com.netflix.mediaclient", R.drawable.ic_app_netflix);
        BY_PACKAGE.put("com.google.android.youtube.tv", R.drawable.ic_app_arc_youtube);
        BY_PACKAGE.put("com.google.android.youtube", R.drawable.ic_app_youtube);
        BY_PACKAGE.put("org.smarttube.stable", R.drawable.ic_app_arc_smarttube);
        BY_PACKAGE.put("org.smarttube.beta", R.drawable.ic_app_youtube);
        BY_PACKAGE.put("com.amazon.amazonvideo.livingroom", R.drawable.ic_app_arc_amazon_prime_video);
        BY_PACKAGE.put("com.apple.atve.androidtv.appletv", R.drawable.ic_app_arc_apple_tv);
        BY_PACKAGE.put("com.graymatrix.did", R.drawable.ic_app_arc_zee5);
        BY_PACKAGE.put("in.startv.hotstar", R.drawable.ic_app_arc_jiohotstar);
        BY_PACKAGE.put("com.sonyliv", R.drawable.ic_app_arc_sony_liv);
        BY_PACKAGE.put("com.suntv.sunnxt", R.drawable.ic_app_arc_sun_nxt);
        BY_PACKAGE.put("com.crunchyroll.crunchyroid", R.drawable.ic_app_crunchyroll);
        BY_PACKAGE.put("tv.airtel.xstream.tvapp", R.drawable.ic_app_arc_airtel_xstream);
        BY_PACKAGE.put("com.spotify.tv.android", R.drawable.ic_app_spotify);
        BY_PACKAGE.put("com.spotify.music", R.drawable.ic_app_spotify);
        BY_PACKAGE.put("com.plexapp.android", R.drawable.ic_app_plex);
        BY_PACKAGE.put("org.videolan.vlc", R.drawable.ic_app_vlc);
        BY_PACKAGE.put("org.xbmc.kodi", R.drawable.ic_app_kodi);
        BY_PACKAGE.put("com.android.vending", R.drawable.ic_app_playstore);
        BY_PACKAGE.put("com.android.chrome", R.drawable.ic_app_chrome);
        BY_PACKAGE.put("com.phlox.tvwebbrowser", R.drawable.ic_app_browser);
        BY_PACKAGE.put("com.tcl.browser", R.drawable.ic_app_browser);
        BY_PACKAGE.put("com.yablio.sendfilestotv", R.drawable.ic_app_send);
        BY_PACKAGE.put("com.android.tv.settings", R.drawable.ic_app_settings);
        BY_PACKAGE.put("com.android.settings", R.drawable.ic_app_settings);
    }

    private static final Map<String, Integer> BRAND = new HashMap<>();

    static {
        brand(0xFFE50914, "com.netflix.ninja", "com.netflix.mediaclient");
        brand(0xFFFF0000, "com.google.android.youtube.tv", "com.google.android.youtube", "org.smarttube.stable", "org.smarttube.beta");
        brand(0xFF00A8E1, "com.amazon.amazonvideo.livingroom");
        brand(0xFF000000, "com.apple.atve.androidtv.appletv");   // black: adapted to the wallpaper at use
        brand(0xFFF47521, "com.crunchyroll.crunchyroid");
        brand(0xFF1DB954, "com.spotify.tv.android", "com.spotify.music");
        brand(0xFFEBAF00, "com.plexapp.android");
        brand(0xFFFF8800, "org.videolan.vlc");
        brand(0xFF17B2E7, "org.xbmc.kodi");
        brand(0xFF01A66F, "com.android.vending");   // a touch lighter than #01875F to read on glass
        brand(0xFF4285F4, "com.android.chrome");
        brand(0xFFE40000, "tv.airtel.xstream.tvapp");
        brand(0xFF1F80E0, "in.startv.hotstar");
        brand(0xFF8230C6, "com.graymatrix.did");
        brand(0xFF4A78E0, "com.sonyliv");           // Sony LIV navy #0C2D8C, lightened to be readable
        brand(0xFFF7941D, "com.suntv.sunnxt");
    }

    private static void brand(int color, String... packages) {
        for (String p : packages) BRAND.put(p, color);
    }

    /** The app's brand colour for focused glyphs; white for generic and unknown apps. */
    static int brandColor(String pkg) {
        Integer c = BRAND.get(pkg);
        return c != null ? c : 0xFFFFFFFF;
    }

    private Glyphs() {
    }

    /** The glyph for a package (a fresh, mutable drawable), or a letter tile for unknown ones. */
    static Drawable forApp(Context c, String pkg, String label) {
        Integer res = BY_PACKAGE.get(pkg);
        if (res != null) {
            Drawable d = c.getDrawable(res);
            if (d != null) return d.mutate();
        }
        return new Letter(label, c.getResources().getDisplayMetrics().density);
    }

    /** Rounded-square outline with one letter, drawn in whatever tint is set. */
    static final class Letter extends Drawable {
        private final String letter;
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        private final int w, h;

        Letter(String label, float density) {
            String l = label == null ? "" : label.trim();
            letter = l.isEmpty() ? "?" : l.substring(0, l.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
            w = (int) (64 * density);   // same 1.6:1 box as the vector glyphs
            h = (int) (40 * density);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setColor(0xFFFFFFFF);
            text.setColor(0xFFFFFFFF);
            text.setTextAlign(Paint.Align.CENTER);
            text.setTypeface(Typeface.create("sans-serif-medium", Typeface.NORMAL));
        }

        @Override
        public void draw(Canvas canvas) {
            Rect b = getBounds();
            float s = Math.min(b.width(), b.height()) * 0.86f;
            float w = s * 0.07f;
            stroke.setStrokeWidth(w);
            box.set(b.centerX() - s / 2 + w / 2, b.centerY() - s / 2 + w / 2, b.centerX() + s / 2 - w / 2, b.centerY() + s / 2 - w / 2);
            canvas.drawRoundRect(box, s * 0.24f, s * 0.24f, stroke);
            text.setTextSize(s * 0.55f);
            Paint.FontMetrics fm = text.getFontMetrics();
            canvas.drawText(letter, b.centerX(), b.centerY() - (fm.ascent + fm.descent) / 2, text);
        }

        @Override
        public void setTint(int tint) {
            stroke.setColor(tint);
            text.setColor(tint);
            invalidateSelf();
        }

        @Override
        public void setTintList(android.content.res.ColorStateList tint) {
            if (tint != null) setTint(tint.getDefaultColor());
        }

        @Override
        public int getIntrinsicWidth() {
            return w;
        }

        @Override
        public int getIntrinsicHeight() {
            return h;
        }

        @Override
        public void setAlpha(int alpha) {
            stroke.setAlpha(alpha);
            text.setAlpha(alpha);
        }

        @Override
        public void setColorFilter(ColorFilter cf) {
            stroke.setColorFilter(cf);
            text.setColorFilter(cf);
        }

        @Override
        public int getOpacity() {
            return PixelFormat.TRANSLUCENT;
        }
    }
}
