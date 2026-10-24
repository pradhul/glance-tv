package com.minimaltv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.PorterDuff;
import android.graphics.PorterDuffColorFilter;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridView;
import android.widget.ImageView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;

import java.time.Duration;

/** v38: All apps backdrop is blurred harder than home and darkened by a ~50% scrim; D-pad still works. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class V38Test {
    private final Context c = ApplicationProvider.getApplicationContext();

    @Before
    public void install() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        for (String p : new String[]{"com.example.a", "com.example.b", "com.example.c"}) {
            PackageInfo pi = new PackageInfo();
            pi.packageName = p;
            pi.applicationInfo = new ApplicationInfo();
            pi.applicationInfo.packageName = p;
            pi.applicationInfo.nonLocalizedLabel = p;
            shadowOf(c.getPackageManager()).installPackage(pi);
            ComponentName cn = new ComponentName(p, p + ".Main");
            shadowOf(c.getPackageManager()).addActivityIfNotPresent(cn);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            shadowOf(c.getPackageManager()).addIntentFilterForActivity(cn, f);
        }
        c.getSharedPreferences(Looks.PREFS, Context.MODE_PRIVATE).edit()
                .putString(MainActivity.KEY_WALLPAPER_FILE, "26.jpg").commit();
    }

    @Test
    public void scrimIsDarkerThanHomeDim() {
        int alpha = Looks.ALL_APPS_SCRIM >>> 24;
        assertTrue("scrim ~45-55%: " + alpha, alpha >= 115 && alpha <= 140);
        assertEquals(0, Looks.ALL_APPS_SCRIM & 0xFFFFFF);
        assertTrue(alpha > (Looks.WALLPAPER_DIM >>> 24));
    }

    @Test
    public void heavyBlurIsTheBlurCopyNotHomesLightOne() {
        Bitmap heavy = Wallpapers.heavyBlur(c, "26.jpg");
        Bitmap home = Wallpapers.background(c, "26.jpg", false);
        assertNotNull(heavy);
        assertNotNull(home);
        assertTrue("-blur copy is the half-size heavy one", heavy.getWidth() < home.getWidth());
    }

    @Test
    public void allAppsUsesHeavyBlurScrimFadeAndKeepsDpad() {
        AllAppsActivity a = Robolectric.buildActivity(AllAppsActivity.class,
                new Intent().setClassName("com.minimaltv.home", "com.minimaltv.home.AllAppsActivity")).setup().get();
        GridView g = a.findViewById(R.id.grid);
        for (int k = 0; k < 100 && (g.getAdapter() == null || a.wallpaperShown == null); k++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            try { Thread.sleep(20); } catch (InterruptedException ignored) { }
        }
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(Looks.WALLPAPER_FADE_MS + 100));
        ImageView wp = a.findViewById(R.id.wallpaper);
        assertEquals("26.jpg", a.wallpaperShown);
        assertEquals(new PorterDuffColorFilter(Looks.ALL_APPS_SCRIM, PorterDuff.Mode.SRC_ATOP), wp.getColorFilter());
        Bitmap shown = ((android.graphics.drawable.BitmapDrawable) wp.getDrawable()).getBitmap();
        assertTrue(shown.getWidth() < Wallpapers.background(c, "26.jpg", false).getWidth());
        assertEquals(1f, wp.getAlpha(), 0.01f);

        g.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
        g.layout(0, 0, 1920, 900);
        assertTrue(g.hasFocus());
        assertTrue(g.getClipChildren());
        int before = g.getSelectedItemPosition();
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(before + 1, g.getSelectedItemPosition());
    }
}
