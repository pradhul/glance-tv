package com.minimaltv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.VectorDrawable;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class V32Test {
    private static final String[][] STREAMING = {
            {"com.netflix.ninja", "ic_app_arc_netflix"},
            {"com.google.android.youtube.tv", "ic_app_arc_youtube"},
            {"org.smarttube.stable", "ic_app_arc_smarttube"},
            {"com.amazon.amazonvideo.livingroom", "ic_app_arc_amazon_prime_video"},
            {"com.apple.atve.androidtv.appletv", "ic_app_arc_apple_tv"},
            {"tv.airtel.xstream.tvapp", "ic_app_arc_airtel_xstream"},
            {"com.graymatrix.did", "ic_app_arc_zee5"},
            {"in.startv.hotstar", "ic_app_arc_jiohotstar"},
            {"com.sonyliv", "ic_app_arc_sony_liv"},
            {"com.suntv.sunnxt", "ic_app_arc_sun_nxt"},
    };

    private final Context c = ApplicationProvider.getApplicationContext();

    @Test
    public void streamingAppsUseArcticonsVectors() {
        for (String[] s : STREAMING) {
            Drawable d = Glyphs.forApp(c, s[0], "X");
            assertTrue(s[0], d instanceof VectorDrawable);
            assertFalse(s[0], d instanceof Glyphs.Letter);
            assertTrue(s[1], c.getResources().getIdentifier(s[1], "drawable", c.getPackageName()) != 0);
        }
    }

    @Test
    public void otherAppsKeepLetterTile() {
        assertTrue(Glyphs.forApp(c, "com.mitv.livetv", "Live TV") instanceof Glyphs.Letter);
        assertTrue(Glyphs.forApp(c, "com.pradhul.remotehelper", "Remote") instanceof Glyphs.Letter);
    }

    @Test
    public void brandTintColoursTheStrokes() {
        int brand = Glyphs.brandColor("com.netflix.ninja");
        Drawable d = Glyphs.forApp(c, "com.netflix.ninja", "Netflix");
        d.setTint(brand);
        Bitmap b = Bitmap.createBitmap(128, 80, Bitmap.Config.ARGB_8888);
        d.setBounds(0, 0, 128, 80);
        d.draw(new Canvas(b));
        int brandPx = 0, whitePx = 0;
        for (int y = 0; y < b.getHeight(); y++) {
            for (int x = 0; x < b.getWidth(); x++) {
                int p = b.getPixel(x, y);
                if (Color.alpha(p) < 200) continue;
                if (Color.red(p) > 200 && Color.green(p) < 60) brandPx++;
                if (Color.green(p) > 200) whitePx++;
            }
        }
        assertTrue("strokes drawn in brand colour", brandPx > 20);
        assertEquals("no untinted white strokes", 0, whitePx);
    }
}
