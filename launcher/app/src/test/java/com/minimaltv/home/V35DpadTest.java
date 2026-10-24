package com.minimaltv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.GridView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.time.Duration;

/**
 * v35: All apps D-pad selection broke in v34 (glow / clipping / focus). These tests drive the remote the way
 * ViewRootImpl does: dispatch the key, and if nobody consumes it, focusSearch + requestFocus.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class V35DpadTest {

    static final String[] PKGS = {
            "com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android", "org.xbmc.kodi", "com.example.other"
    };

    private final Context c = ApplicationProvider.getApplicationContext();

    @Before
    public void install() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        Weather.networkOff = true;
        ShadowDialog.reset();
        for (String p : PKGS) {
            PackageInfo pi = new PackageInfo();
            pi.packageName = p;
            pi.applicationInfo = new ApplicationInfo();
            pi.applicationInfo.packageName = p;
            pi.applicationInfo.nonLocalizedLabel = labelFor(p);
            shadowOf(c.getPackageManager()).installPackage(pi);
            ComponentName cn = new ComponentName(p, p + ".Main");
            shadowOf(c.getPackageManager()).addActivityIfNotPresent(cn);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            shadowOf(c.getPackageManager()).addIntentFilterForActivity(cn, f);
        }
        c.getSharedPreferences("dock", Context.MODE_PRIVATE).edit()
                .putString("apps", String.join(",", PKGS[0], PKGS[1], PKGS[2])).commit();
    }

    private static String labelFor(String p) {
        if (p.contains("netflix")) return "Netflix";
        if (p.contains("youtube")) return "YouTube";
        if (p.contains("amazon")) return "Prime Video";
        if (p.contains("spotify")) return "Spotify";
        if (p.contains("kodi")) return "Kodi";
        return "Other";
    }

    private AllAppsActivity openAllApps(boolean pick) {
        Intent i = new Intent().setClassName("com.minimaltv.home", "com.minimaltv.home.AllAppsActivity");
        if (pick) i.putExtra(AllAppsActivity.EXTRA_PICK, true);
        AllAppsActivity a = Robolectric.buildActivity(AllAppsActivity.class, i).setup().get();
        GridView g = a.findViewById(R.id.grid);
        for (int k = 0; k < 100 && g.getAdapter() == null; k++) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(50));
            try {
                Thread.sleep(20);
            } catch (InterruptedException ignored) {
            }
        }
        assertNotNull("All apps never loaded", g.getAdapter());
        g.measure(View.MeasureSpec.makeMeasureSpec(1920, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(900, View.MeasureSpec.EXACTLY));
        g.layout(0, 0, 1920, 900);
        shadowOf(Looper.getMainLooper()).idle();
        g.requestFocus();
        if (g.getSelectedItemPosition() < 0 && g.getAdapter().getCount() > 0) g.setSelection(0);
        shadowOf(Looper.getMainLooper()).idle();
        return a;
    }

    private void press(AllAppsActivity a, int code) {
        View root = a.getWindow().getDecorView();
        long t = SystemClock.uptimeMillis();
        boolean handled = a.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT
                : code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT
                : code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP
                : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : 0;
        if (!handled && dir != 0) {
            View f = root.findFocus();
            if (f != null) {
                View next = f.focusSearch(dir);
                if (next != null) next.requestFocus(dir);
            }
        }
        a.dispatchKeyEvent(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
        shadowOf(Looper.getMainLooper()).idle();
    }

    @Test
    public void allApps_dpadSelectsLaunchesAndHides() {
        AllAppsActivity a = openAllApps(false);
        GridView g = a.findViewById(R.id.grid);
        assertTrue("GridView must keep key focus so D-pad moves selection", g.hasFocus());
        assertTrue("a tile must be selected so OK and Menu work", g.getSelectedItemPosition() >= 0);
        assertTrue(g.getAdapter().getCount() >= 2);
        assertTrue("GridView must clip: v34 set clipChildren=false so the 104dp glow overflowed neighbors",
                g.getClipChildren());

        View cell = g.getChildAt(0);
        assertNotNull(cell);
        assertEquals("cell descendants must not steal D-pad from GridView selection",
                ViewGroup.FOCUS_BLOCK_DESCENDANTS, ((ViewGroup) cell).getDescendantFocusability());
        View glow = cell.findViewById(R.id.glow);
        assertNotNull(glow);
        assertFalse(glow.isFocusable());
        assertFalse(glow.isClickable());
        assertFalse(cell.findViewById(R.id.icon).isFocusable());
        assertFalse(cell.findViewById(R.id.label).isFocusable());

        int start = g.getSelectedItemPosition();
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        assertEquals("RIGHT must move GridView selection, not focus a glow/icon", start + 1, g.getSelectedItemPosition());
        assertTrue(g.hasFocus());
        assertEquals(g.getSelectedItemPosition(), a.glowPosition);

        press(a, KeyEvent.KEYCODE_DPAD_LEFT);
        assertEquals(start, g.getSelectedItemPosition());

        Apps.Entry chosen = (Apps.Entry) g.getSelectedItem();
        assertNotNull(chosen);
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);
        Intent launched = shadowOf(a).getNextStartedActivity();
        assertNotNull("OK on a selected tile must launch the app", launched);
        assertEquals(chosen.pkg, launched.getComponent().getPackageName());

        press(a, KeyEvent.KEYCODE_MENU);
        assertTrue("Menu on a selected tile must offer Hide from All apps",
                a.hideDialog != null && a.hideDialog.isShowing());
        assertEquals(a.getString(R.string.hide_app),
                String.valueOf(a.hideDialog.getListView().getAdapter().getItem(0)));
    }

    @Test
    public void allApps_pickMode_dpadCenterReturnsPackage() {
        AllAppsActivity a = openAllApps(true);
        GridView g = a.findViewById(R.id.grid);
        assertTrue(g.hasFocus());
        press(a, KeyEvent.KEYCODE_DPAD_RIGHT);
        Apps.Entry chosen = (Apps.Entry) g.getSelectedItem();
        press(a, KeyEvent.KEYCODE_DPAD_CENTER);
        assertTrue(a.isFinishing());
        assertEquals(chosen.pkg, shadowOf(a).getResultIntent().getStringExtra(AllAppsActivity.EXTRA_PACKAGE));
    }

    @Test
    public void homeRow_dpadMovesAndSelects() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        shadowOf((android.app.Application) c).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT);
        Intent i = new Intent().setClassName("com.minimaltv.home", "com.minimaltv.home.MainActivity");
        i.putExtra("style", "glass");
        ActivityController<MainActivity> controller = Robolectric.buildActivity(MainActivity.class, i).setup();
        MainActivity activity = controller.get();
        for (int k = 0; k < 6; k++) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        ViewGroup dock = activity.findViewById(R.id.dock);
        assertTrue(dock.getChildCount() >= 2);
        View first = dock.getChildAt(0);
        first.requestFocus();
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(first, activity.getWindow().getDecorView().findFocus());

        long t = SystemClock.uptimeMillis();
        boolean handled = activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT, 0));
        if (!handled) {
            View next = first.focusSearch(View.FOCUS_RIGHT);
            assertNotNull(next);
            next.requestFocus(View.FOCUS_RIGHT);
        }
        activity.dispatchKeyEvent(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT, 0));
        shadowOf(Looper.getMainLooper()).idle();
        assertSame(dock.getChildAt(1), activity.getWindow().getDecorView().findFocus());

        t = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
        activity.dispatchKeyEvent(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull("OK on a home tile must launch", shadowOf(activity).getNextStartedActivity());

        dock.getChildAt(0).requestFocus();
        t = SystemClock.uptimeMillis();
        activity.dispatchKeyEvent(new KeyEvent(t, t, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER, 0));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200));
        activity.dispatchKeyEvent(new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER, 0));
        shadowOf(Looper.getMainLooper()).idle();
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue("long-press on a home tile must open the tile menu", d != null && d.isShowing());
        controller.pause().stop().destroy();
    }
}
