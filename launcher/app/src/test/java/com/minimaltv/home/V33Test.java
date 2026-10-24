package com.minimaltv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.drawable.VectorDrawable;
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

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class V33Test {
    static final String[] ARC = {"com.netflix.ninja", "com.google.android.youtube.tv", "org.smarttube.stable",
            "com.amazon.amazonvideo.livingroom", "com.apple.atve.androidtv.appletv", "tv.airtel.xstream.tvapp",
            "com.graymatrix.did", "in.startv.hotstar", "com.sonyliv", "com.suntv.sunnxt",
            "com.android.vending", "com.mitv.gallery", "com.phlox.tvwebbrowser", "com.yablio.sendfilestotv"};
    private final Context c = ApplicationProvider.getApplicationContext();

    @Before
    public void install() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        for (String p : new String[]{"com.netflix.ninja", "com.example.other"}) {
            PackageInfo pi = new PackageInfo();
            pi.packageName = p;
            pi.applicationInfo = new ApplicationInfo();
            pi.applicationInfo.packageName = p;
            pi.applicationInfo.nonLocalizedLabel = p.contains("netflix") ? "Netflix" : "Other";
            shadowOf(c.getPackageManager()).installPackage(pi);
            ComponentName cn = new ComponentName(p, p + ".Main");
            shadowOf(c.getPackageManager()).addActivityIfNotPresent(cn);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            shadowOf(c.getPackageManager()).addIntentFilterForActivity(cn, f);
        }
    }

    @Test
    public void allFourteenArcticonsMapped() {
        for (String p : ARC) {
            assertTrue(p, Glyphs.has(p));
            String name = c.getResources().getResourceEntryName(
                    c.getResources().getIdentifier(resFor(p), "drawable", c.getPackageName()));
            assertTrue(p + " -> " + name, name.startsWith("ic_app_arc_"));
        }
        assertFalse(Glyphs.has("com.example.other"));
    }

    private String resFor(String p) {
        switch (p) {
            case "com.android.vending": return "ic_app_arc_google_play_store";
            case "com.mitv.gallery": return "ic_app_arc_gallery";
            case "com.phlox.tvwebbrowser": return "ic_app_arc_browser";
            case "com.yablio.sendfilestotv": return "ic_app_arc_send_files_to_tv";
            default: return "ic_app_arc_netflix";
        }
    }

    @Test
    public void glowUsesBrandColourWithWhiteFallback() {
        assertEquals(0xFFE50914, Glyphs.glowColor("com.netflix.ninja"));
        assertEquals(0xFFFFFFFF, Glyphs.glowColor("com.apple.atve.androidtv.appletv"));   // black brand -> white
        assertEquals(0xFFFFFFFF, Glyphs.glowColor("com.example.other"));
    }

    private AllAppsActivity open() {
        AllAppsActivity a = Robolectric.buildActivity(AllAppsActivity.class,
                new Intent().setClassName("com.minimaltv.home", "com.minimaltv.home.AllAppsActivity")).setup().get();
        GridView g = a.findViewById(R.id.grid);
        for (int k = 0; k < 100 && (g.getAdapter() == null || (a.wallpaperShown == null && hasWp())); k++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            try { Thread.sleep(20); } catch (InterruptedException ignored) { }
        }
        assertNotNull(g.getAdapter());
        g.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
        g.layout(0, 0, 1920, 900);
        return a;
    }

    private boolean hasWp() {
        return c.getSharedPreferences("dock", Context.MODE_PRIVATE).contains(MainActivity.KEY_WALLPAPER_FILE);
    }

    @Test
    public void allAppsShowsHomeWallpaperDimmed() {
        c.getSharedPreferences("dock", Context.MODE_PRIVATE).edit().putString(MainActivity.KEY_WALLPAPER_FILE, "26.jpg").commit();
        AllAppsActivity a = open();
        assertEquals("26.jpg", a.wallpaperShown);
        ImageView wp = a.findViewById(R.id.wallpaper);
        assertFalse("glow must not be clipped by the grid", ((android.view.ViewGroup) a.findViewById(R.id.grid)).getClipChildren());
        assertNotNull(wp.getDrawable());
        assertNotNull(wp.getColorFilter());
    }

    @Test
    public void glyphIconsForMappedAppsAndGlowFollowsSelection() {
        AllAppsActivity a = open();
        GridView g = a.findViewById(R.id.grid);
        int netflix = -1, other = -1;
        for (int i = 0; i < g.getAdapter().getCount(); i++) {
            String p = ((Apps.Entry) g.getAdapter().getItem(i)).pkg;
            if (p.equals("com.netflix.ninja")) netflix = i;
            if (p.equals("com.example.other")) other = i;
        }
        assertTrue(netflix >= 0 && other >= 0);
        View nCell = g.getChildAt(netflix - g.getFirstVisiblePosition());
        assertTrue(((ImageView) nCell.findViewById(R.id.icon)).getDrawable() instanceof VectorDrawable);
        View oCell = g.getChildAt(other - g.getFirstVisiblePosition());
        assertFalse(((ImageView) oCell.findViewById(R.id.icon)).getDrawable() instanceof VectorDrawable);

        g.requestFocus();
        g.setSelection(other);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AllAppsActivity.GLOW_IN_MS + 100));
        g.setSelection(netflix);
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(netflix, a.glowPosition);
        View glow = g.getChildAt(netflix - g.getFirstVisiblePosition()).findViewById(R.id.glow);
        assertTrue("slow fade-in", AllAppsActivity.GLOW_IN_MS >= 400 && AllAppsActivity.GLOW_OUT_MS >= 200);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AllAppsActivity.GLOW_IN_MS + 100));
        assertEquals(1f, glow.getAlpha(), 0.01f);
        View cellView = g.getChildAt(netflix - g.getFirstVisiblePosition());
        assertTrue("glow inside its cell vertically (never clipped)", glow.getTop() >= 0 && glow.getBottom() <= cellView.getHeight());
        assertEquals(View.VISIBLE, g.getChildAt(netflix - g.getFirstVisiblePosition()).findViewById(R.id.glow).getVisibility());
        int next = other;
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, next > netflix ? KeyEvent.KEYCODE_DPAD_RIGHT : KeyEvent.KEYCODE_DPAD_LEFT));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(next, a.glowPosition);
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(AllAppsActivity.GLOW_OUT_MS + 100));
        assertEquals(View.INVISIBLE, g.getChildAt(netflix - g.getFirstVisiblePosition()).findViewById(R.id.glow).getVisibility());
    }
}
