package com.minimaltv.home;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.widget.LinearLayout;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TestWatcher;
import org.junit.runner.Description;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;

import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.*;

/**
 * v24: D-pad-only navigation tests. Robolectric (no KVM on the build box, so no emulator). Arrow keys are
 * routed the way ViewRootImpl does it: dispatch the key, and if nobody consumes it, focusSearch + requestFocus.
 * 1920x1080 at 320 dpi = 960x540 dp, like the TV.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class DpadNavigationTest {

    static final String[] PKGS = {"com.netflix.ninja", "com.google.android.youtube.tv", "com.amazon.amazonvideo.livingroom",
            "com.spotify.tv.android", "org.xbmc.kodi"};
    static final String[] STYLES = System.getProperty("onlyStyle", "").isEmpty() ? new String[]{"glass", "floating", "posters"} : new String[]{System.getProperty("onlyStyle")};
    static final File SHOTS = new File(System.getProperty("shotsDir", "/workspace/v24-shots/robolectric"));

    ActivityController<MainActivity> controller;
    MainActivity activity;
    View focusedStart;

    @Rule
    public TestWatcher onFail = new TestWatcher() {
        @Override
        protected void failed(Throwable e, Description d) {
            if (activity != null) shot("FAIL-" + d.getMethodName());
        }
    };

    @Before
    public void installApps() {
        // A TV remote: never in touch mode, so focus is always drawn and requestFocus works.
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        Weather.networkOff = true;   // v25: no live weather in tests; tests inject their own
        Bt.resetForTest();
        // Granted over adb on the TV; without it Robolectric records the runtime-permission request as a started activity.
        shadowOf((android.app.Application) ApplicationProvider.getApplicationContext()).grantPermissions(android.Manifest.permission.BLUETOOTH_CONNECT);
        ShadowDialog.reset();
        Context c = ApplicationProvider.getApplicationContext();
        for (String p : PKGS) {
            PackageInfo pi = new PackageInfo();
            pi.packageName = p;
            pi.applicationInfo = new ApplicationInfo();
            pi.applicationInfo.packageName = p;
            pi.applicationInfo.nonLocalizedLabel = p.contains("netflix") ? "Netflix" : p.contains("youtube") ? "YouTube"
                    : p.contains("amazon") ? "Prime Video" : p.contains("spotify") ? "Spotify" : "Kodi";
            shadowOf(c.getPackageManager()).installPackage(pi);
            ComponentName cn = new ComponentName(p, p + ".Main");
            shadowOf(c.getPackageManager()).addActivityIfNotPresent(cn);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            shadowOf(c.getPackageManager()).addIntentFilterForActivity(cn, f);
        }
        prefs().edit().putString("apps", String.join(",", PKGS))
                .putString(MediaListener.KEY_PKG, PKGS[0]).putString(MediaListener.KEY_TITLE, "Stranger Things").commit();
    }

    @After
    public void tearDown() {
        if (controller != null) {
            try {
                controller.pause().stop().destroy();
            } catch (RuntimeException ignored) {
            }
        }
        controller = null;
    }

    SharedPreferences prefs() {
        return ApplicationProvider.getApplicationContext().getSharedPreferences("dock", Context.MODE_PRIVATE);
    }

    void launch(String style, Intent extraIntent) {
        Intent i = extraIntent != null ? extraIntent : new Intent();
        i.setClassName("com.minimaltv.home", "com.minimaltv.home.MainActivity");
        if (style != null) i.putExtra("style", style);
        controller = Robolectric.buildActivity(MainActivity.class, i).setup();
        activity = controller.get();
        settle();
        focusedStart = focused();
    }

    void settle() {
        for (int k = 0; k < 6; k++) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
    }

    PopupWindow morePopup() {
        return (PopupWindow) field(activity, "morePopup");
    }

    View moreView() {
        return (View) field(activity, "morePill");
    }

    static Object field(Object o, String name) {
        try {
            Field f = MainActivity.class.getDeclaredField(name);
            f.setAccessible(true);
            return f.get(o);
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    /** The window that has key focus: a showing dialog, the ⋯ popup, or the activity. */
    View focusRoot() {
        android.app.Dialog d = ShadowDialog.getLatestDialog();
        if (d != null && d.isShowing()) return d.getWindow().getDecorView();
        PopupWindow p = morePopup();
        if (p != null && p.isShowing()) return p.getContentView().getRootView();
        return activity.getWindow().getDecorView();
    }

    View focused() {
        return focusRoot().findFocus();
    }

    boolean dispatch(View root, KeyEvent e) {
        if (root == activity.getWindow().getDecorView()) return activity.dispatchKeyEvent(e);
        return root.dispatchKeyEvent(e);
    }

    void press(int code) {
        View root = focusRoot();
        long t = SystemClock.uptimeMillis();
        boolean handled = dispatch(root, new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        int dir = code == KeyEvent.KEYCODE_DPAD_LEFT ? View.FOCUS_LEFT : code == KeyEvent.KEYCODE_DPAD_RIGHT ? View.FOCUS_RIGHT
                : code == KeyEvent.KEYCODE_DPAD_UP ? View.FOCUS_UP : code == KeyEvent.KEYCODE_DPAD_DOWN ? View.FOCUS_DOWN : 0;
        if (!handled && dir != 0) {
            View f = root.findFocus();
            if (f != null) {
                View next = f.focusSearch(dir);
                if (next != null) next.requestFocus(dir);
            }
        }
        dispatch(root, new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
        settle();
    }

    void longPress(int code) {
        View root = focusRoot();
        long t = SystemClock.uptimeMillis();
        dispatch(root, new KeyEvent(t, t, KeyEvent.ACTION_DOWN, code, 0));
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1200));
        dispatch(root, new KeyEvent(t, SystemClock.uptimeMillis(), KeyEvent.ACTION_UP, code, 0));
        settle();
    }

    static final int[] DIRS = {KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT, KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN};

    static boolean shown(View v) {
        return v.isShown() && v.getWidth() > 0 && v.getHeight() > 0 && v.getGlobalVisibleRect(new Rect());
    }

    static void collect(View v, List<View> out) {
        boolean container = v instanceof ViewGroup && ((ViewGroup) v).getDescendantFocusability() != ViewGroup.FOCUS_BLOCK_DESCENDANTS
                && hasFocusableChild((ViewGroup) v);
        if (v.isFocusable() && shown(v) && !container) out.add(v);
        if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), out);
        }
    }

    static boolean hasFocusableChild(ViewGroup g) {
        ArrayList<View> l = new ArrayList<>();
        for (int i = 0; i < g.getChildCount(); i++) g.getChildAt(i).addFocusables(l, View.FOCUS_FORWARD);
        return !l.isEmpty();
    }

    /** Styles with the ⋯ pill (glass = layout C, posters = layout A); floating keeps the classic pills. */
    static final String[] MORE_STYLES = {"glass", "posters"};

    String describe(View v) {
        if (v == null) return "null";
        Object tag = v.getTag(R.id.label);
        CharSequence cd = v.getContentDescription();
        String s = tag != null ? "tile:" + tag : cd != null ? "cd:" + cd : v instanceof TextView ? "text:" + ((TextView) v).getText() : v.getClass().getSimpleName();
        try {
            return v.getId() != View.NO_ID ? s + "#" + activity.getResources().getResourceEntryName(v.getId()) : s;
        } catch (RuntimeException e) {
            return s;
        }
    }

    void assertOnScreen(View v) {
        if (Boolean.getBoolean("skipOnScreen")) return;
        int[] xy = new int[2];
        v.getLocationOnScreen(xy);
        int w = activity.getResources().getDisplayMetrics().widthPixels, h = activity.getResources().getDisplayMetrics().heightPixels;
        assertTrue("off screen: " + describe(v) + " at " + xy[0] + "," + xy[1] + " " + v.getWidth() + "x" + v.getHeight(),
                xy[0] >= 0 && xy[1] >= 0 && xy[0] + v.getWidth() <= w && xy[1] + v.getHeight() <= h);
        Rect r = new Rect();
        assertTrue("not fully visible (clipped): " + describe(v) + " visible " + r,
                v.getGlobalVisibleRect(r) && r.width() >= v.getWidth() - 2 && r.height() >= v.getHeight() - 2);
    }

    /** D-pad press sequence from focusedStart to target, found by BFS over real presses. */
    List<Integer> pathTo(View target) {
        Map<View, List<Integer>> seen = new LinkedHashMap<>();
        ArrayDeque<View> q = new ArrayDeque<>();
        View start = focusedStart;
        assertNotNull("no initial focus", start);
        seen.put(start, new ArrayList<>());
        q.add(start);
        while (!q.isEmpty()) {
            View v = q.poll();
            if (v == target) return seen.get(v);
            for (int code : DIRS) {
                replay(seen.get(v));
                press(code);
                View n = focused();
                assertNotNull("focus lost after " + KeyEvent.keyCodeToString(code) + " from " + describe(v), n);
                if (!seen.containsKey(n)) {
                    List<Integer> p = new ArrayList<>(seen.get(v));
                    p.add(code);
                    seen.put(n, p);
                    q.add(n);
                }
            }
        }
        return null;
    }

    void replay(List<Integer> path) {
        focusedStart.requestFocus();
        settle();
        for (int c : path) press(c);
    }

    void goTo(View target) {
        List<Integer> p = pathTo(target);
        assertNotNull(describe(target) + " not reachable by D-pad in " + style(), p);
        replay(p);
        assertSame(target, focused());
    }

    String style() {
        return STYLES[prefs().getInt("card_style", 0)];
    }

    void shot(String name) {
        try {
            SHOTS.mkdirs();
            View root = activity.getWindow().getDecorView();
            Bitmap b = Bitmap.createBitmap(Math.max(1, root.getWidth()), Math.max(1, root.getHeight()), Bitmap.Config.ARGB_8888);
            Canvas c = new Canvas(b);
            root.draw(c);
            PopupWindow p = morePopup();
            if (p != null && p.isShowing()) {
                int[] xy = new int[2];
                p.getContentView().getLocationOnScreen(xy);
                c.save();
                c.translate(xy[0], xy[1]);
                p.getContentView().draw(c);
                c.restore();
            }
            android.app.Dialog d = ShadowDialog.getLatestDialog();
            if (d != null && d.isShowing()) {
                View dv = d.getWindow().getDecorView();
                c.save();
                c.translate((b.getWidth() - dv.getWidth()) / 2f, (b.getHeight() - dv.getHeight()) / 2f);
                dv.draw(c);
                c.restore();
            }
            try (FileOutputStream out = new FileOutputStream(new File(SHOTS, name + ".png"))) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            }
        } catch (Throwable t) {
            System.err.println("screenshot failed: " + t);
        }
    }

    // ---- tests --------------------------------------------------------------------------------

    @Test
    public void everyFocusableReachable_allStyles() {
        for (String s : STYLES) {
            launch(s, null);
            assertEquals(s, style());
            View start = focused();
            assertNotNull(s + ": no initial focus", start);
            assertNotNull(s + ": initial focus should be first tile, was " + describe(start), start.getTag(R.id.label));
            shot(s + "-home");
            List<View> all = new ArrayList<>();
            collect(activity.getWindow().getDecorView(), all);
            View more = moreView();
            if (!s.equals("floating")) assertTrue(s + ": ⋯ not visible", more != null && shown(more));
            else for (int id : new int[]{R.id.btnInputs, R.id.btnNetwork, R.id.btnSettings, R.id.btnAllApps, R.id.btnAdd})
                assertTrue(s + ": pill missing " + id, all.contains(activity.findViewById(id)));
            assertTrue(s + ": Last used not visible", activity.findViewById(R.id.btnLastWatched).isShown());
            for (View v : all) {
                List<Integer> p = pathTo(v);
                assertNotNull(s + ": " + describe(v) + " not reachable by D-pad", p);
                replay(p);
                assertSame(v, focused());
                assertOnScreen(v);
                if (v == more) shot(s + "-more-focused");
                if (v.getId() == R.id.btnLastWatched) shot(s + "-lastused-focused");
            }
            start.requestFocus();
            settle();
            for (int k = 0; k < PKGS.length + 2; k++) {
                press(KeyEvent.KEYCODE_DPAD_RIGHT);
                assertNotNull(focused());
                assertOnScreen(focused());
            }
            shot(s + "-last-tile");
            tearDown();
        }
    }

    void openMoreByDpad(String s) {
        launch(s, null);
        goTo(moreView());
        press(KeyEvent.KEYCODE_DPAD_CENTER);
        PopupWindow p = morePopup();
        assertTrue(s + ": DPAD_CENTER on ⋯ did not open the menu", p != null && p.isShowing());
    }

    @Test
    public void moreMenu_opensOnScreen_itemsReachable_backCloses_allStyles() {
        for (String s : MORE_STYLES) {
            openMoreByDpad(s);
            PopupWindow p = morePopup();
            ViewGroup rows = (ViewGroup) p.getContentView();
            int[] xy = new int[2];
            rows.getLocationOnScreen(xy);
            int w = activity.getResources().getDisplayMetrics().widthPixels, h = activity.getResources().getDisplayMetrics().heightPixels;
            assertTrue(s + ": menu off screen at " + xy[0] + "," + xy[1] + " " + rows.getWidth() + "x" + rows.getHeight(),
                    xy[0] >= 0 && xy[1] >= 0 && xy[0] + rows.getWidth() <= w && xy[1] + rows.getHeight() <= h);
            assertSame(s + ": first menu row not focused", rows.getChildAt(0), focused());
            shot(s + "-menu-open");
            for (int i = 1; i < rows.getChildCount(); i++) {
                press(KeyEvent.KEYCODE_DPAD_DOWN);
                assertSame(s + ": menu row " + i + " not reached", rows.getChildAt(i), focused());
            }
            press(KeyEvent.KEYCODE_DPAD_DOWN);
            assertTrue(s + ": focus escaped the menu", p.isShowing() && focused() != null);
            press(KeyEvent.KEYCODE_BACK);
            assertFalse(s + ": BACK did not close the menu", p.isShowing());
            View f = activity.getWindow().getDecorView().findFocus();
            assertSame(s + ": focus not back on ⋯ after BACK, was " + describe(f), moreView(), f);
            tearDown();
        }
    }

    @Test
    public void moreMenu_allAppsItemStartsAllApps() {
        for (String s : MORE_STYLES) {
            openMoreByDpad(s);
            press(KeyEvent.KEYCODE_DPAD_CENTER);
            Intent next = shadowOf(activity).getNextStartedActivity();
            assertNotNull(s + ": All apps did not start", next);
            assertEquals(AllAppsActivity.class.getName(), next.getComponent().getClassName());
            assertFalse(morePopup().isShowing());
            tearDown();
        }
    }

    @Test
    public void longPressMore_launcherSettings_autoSwitchPersists_styleSwitch() {
        launch("posters", null);
        goTo(moreView());
        longPress(KeyEvent.KEYCODE_DPAD_CENTER);
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        assertTrue("long-press ⋯ did not open launcher settings", d != null && d.isShowing());
        assertNull("long-press ⋯ also opened the ⋯ menu", morePopup() != null && morePopup().isShowing() ? morePopup() : null);
        shot("posters-launcher-settings");
        assertNotNull("no focus in launcher settings", focused());
        ListView list = d.getListView();
        boolean before = prefs().getBoolean("auto_style", false);
        list.performItemClick(list.getChildAt(3), 3, list.getItemIdAtPosition(3));
        settle();
        assertEquals(!before, prefs().getBoolean("auto_style", false));
        d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        settle();
        AlertDialog cs = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame(d, cs);
        shot("card-style-dialog");
        cs.getListView().performItemClick(cs.getListView().getChildAt(1), 1, 1);
        settle();
        assertEquals("floating", style());
        assertFalse(cs.isShowing());
        View f = activity.getWindow().getDecorView().findFocus();
        assertNotNull("focus lost after style switch", f);
        assertNotNull("focus not on a tile after style switch: " + describe(f), f.getTag(R.id.label));
        shot("after-switch-floating");
        tearDown();
        launch(null, null);
        assertEquals("auto-switch pinned today's style", "floating", style());
        assertEquals(!before, prefs().getBoolean("auto_style", false));
    }

    /** A crowded dock (more tiles than fit) and nothing played yet: Last used hidden, ⋯ alone. */
    @Test
    public void manyApps_noLastUsed_moreReachableFromEveryTile_allStyles() {
        Context c = ApplicationProvider.getApplicationContext();
        List<String> pk = new ArrayList<>(Arrays.asList(PKGS));
        for (int i = 0; i < 7; i++) {
            String p = "com.example.app" + i;
            PackageInfo pi = new PackageInfo();
            pi.packageName = p;
            pi.applicationInfo = new ApplicationInfo();
            pi.applicationInfo.packageName = p;
            pi.applicationInfo.nonLocalizedLabel = "Example App " + i;
            shadowOf(c.getPackageManager()).installPackage(pi);
            ComponentName cn = new ComponentName(p, p + ".Main");
            shadowOf(c.getPackageManager()).addActivityIfNotPresent(cn);
            IntentFilter f = new IntentFilter(Intent.ACTION_MAIN);
            f.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
            shadowOf(c.getPackageManager()).addIntentFilterForActivity(cn, f);
            pk.add(p);
        }
        prefs().edit().putString("apps", String.join(",", pk)).remove(MediaListener.KEY_PKG).remove(MediaListener.KEY_TITLE).commit();
        for (String s : STYLES) {
            launch(s, null);
            assertFalse(activity.findViewById(R.id.btnLastWatched).isShown());
            ViewGroup dock = activity.findViewById(R.id.dock);
            assertEquals(pk.size(), dock.getChildCount());
            View target = s.equals("floating") ? activity.findViewById(R.id.btnAllApps) : moreView();
            for (int i = 0; i < dock.getChildCount(); i++) {
                // Walk right to tile i, then DOWN must leave the row for the pill row.
                focusedStart.requestFocus();
                settle();
                for (int k = 0; k < i; k++) press(KeyEvent.KEYCODE_DPAD_RIGHT);
                assertSame(s + ": RIGHT x" + i, dock.getChildAt(i), focused());
                assertOnScreen(focused());
                if (i == dock.getChildCount() - 1) shot(s + "-many-last-tile");
                press(KeyEvent.KEYCODE_DPAD_DOWN);
                View f = focused();
                assertNotNull(s + ": focus lost on DOWN from tile " + i, f);
                if (s.equals("floating")) {
                    assertTrue(s + ": DOWN from tile " + i + " went to " + describe(f), f != dock.getChildAt(i));
                } else {
                    assertSame(s + ": DOWN from tile " + i + " went to " + describe(f), target, f);
                    assertOnScreen(f);
                    press(KeyEvent.KEYCODE_DPAD_CENTER);
                    assertTrue(s + ": ⋯ menu did not open from tile " + i, morePopup() != null && morePopup().isShowing());
                    press(KeyEvent.KEYCODE_BACK);
                    press(KeyEvent.KEYCODE_DPAD_UP);
                    assertNotNull(s + ": UP from ⋯ lost focus", focused());
                    assertNotNull(s + ": UP from ⋯ went to " + describe(focused()), focused().getTag(R.id.label));
                }
            }
            shot(s + "-many");
            tearDown();
        }
    }

    /** TVs that report a larger density (e.g. 1080p at 360 dpi = 853x480 dp) or a 720p panel at 240 dpi (640x360 dp). */
    @Test
    @Config(qualifiers = "w853dp-h480dp-land-television-xhdpi")
    public void smallerDpScreen_853x480_moreReachableAndOnScreen() {
        smallScreenSweep("853x480");
    }

    @Test
    @Config(qualifiers = "w640dp-h360dp-land-television-hdpi")
    public void smallerDpScreen_640x360_moreReachableAndOnScreen() {
        smallScreenSweep("640x360");
    }

    void smallScreenSweep(String tag) {
        for (String s : STYLES) {
            launch(s, null);
            shot(s + "-" + tag + "-home");
            List<View> all = new ArrayList<>();
            collect(activity.getWindow().getDecorView(), all);
            for (View v : all) {
                List<Integer> p = pathTo(v);
                assertNotNull(s + "@" + tag + ": " + describe(v) + " not reachable", p);
                replay(p);
                assertOnScreen(v);
            }
            if (!s.equals("floating")) {
                goTo(moreView());
                assertOnScreen(moreView());
                press(KeyEvent.KEYCODE_DPAD_CENTER);
                PopupWindow pw = morePopup();
                assertTrue(s + "@" + tag + ": menu did not open", pw != null && pw.isShowing());
                ViewGroup rows = (ViewGroup) pw.getContentView();
                shot(s + "-" + tag + "-menu");
                for (int i = 0; i < rows.getChildCount(); i++) assertOnScreen(rows.getChildAt(i));
                press(KeyEvent.KEYCODE_BACK);
            }
            tearDown();
        }
    }

    @Test
    public void floating_longPressSettingsPill_opensLauncherSettings() {
        launch("floating", null);
        goTo(activity.findViewById(R.id.btnSettings));
        longPress(KeyEvent.KEYCODE_DPAD_CENTER);
        android.app.Dialog d = ShadowDialog.getLatestDialog();
        assertTrue("long-press Settings did not open launcher settings", d != null && d.isShowing());
        assertNotNull(focused());
        press(KeyEvent.KEYCODE_BACK);
        assertFalse(d.isShowing());
        assertSame(activity.findViewById(R.id.btnSettings), activity.getWindow().getDecorView().findFocus());
    }

    @Test
    public void longPressTile_offersRemove() {
        for (String s : STYLES) {
            launch(s, null);
            longPress(KeyEvent.KEYCODE_DPAD_CENTER);
            android.app.Dialog d = ShadowDialog.getLatestDialog();
            assertTrue(s + ": long-press on tile did nothing", d != null && d.isShowing());
            assertNull(s + ": long-press also launched the app", shadowOf(activity).getNextStartedActivity());
            shot(s + "-longpress-tile");
            press(KeyEvent.KEYCODE_BACK);
            assertFalse(d.isShowing());
            assertNotNull(activity.getWindow().getDecorView().findFocus());
            tearDown();
            ShadowDialog.reset();
        }
    }

    @Test
    public void clickTile_launchesApp() {
        for (String s : STYLES) {
            launch(s, null);
            press(KeyEvent.KEYCODE_DPAD_CENTER);
            Intent next = shadowOf(activity).getNextStartedActivity();
            assertNotNull(s + ": OK on tile launched nothing", next);
            tearDown();
        }
    }

    @Test
    public void rainOverlay_allStyles() {
        // v25: the rain effect is no longer floating-only.
        launch("floating", new Intent().putExtra("forceRain", true));
        RainView rain = (RainView) field(activity, "rain");
        assertEquals(View.VISIBLE, rain.getVisibility());
        shot("floating-rain");
        for (String s : new String[]{"glass", "posters"}) {
            controller.newIntent(new Intent().putExtra("style", s));
            settle();
            assertEquals(s, View.VISIBLE, rain.getVisibility());
            assertEquals(RainView.FX_RAIN, rain.mode());
        }
        controller.newIntent(new Intent().putExtra("forceRain", false));
        settle();
        assertNotEquals(View.VISIBLE, rain.getVisibility());
    }

    @Test
    public void openMoreExtra_opensMenu_allStyles() {
        for (String s : MORE_STYLES) {
            launch(s, new Intent().putExtra("openMore", true));
            PopupWindow p = morePopup();
            assertTrue(s + ": openMore extra did not open menu", p != null && p.isShowing());
            press(KeyEvent.KEYCODE_BACK);
            assertFalse(p.isShowing());
            tearDown();
        }
    }

    @Test
    public void backOnHome_doesNothing_focusKept() {
        for (String s : STYLES) {
            launch(s, null);
            View f = focused();
            press(KeyEvent.KEYCODE_BACK);
            assertFalse(activity.isFinishing());
            assertSame(f, focused());
            tearDown();
        }
    }

    // ---- v25: Bluetooth icons, weather effects, forecast + AQI panel --------------------------

    static final String FORECAST = "{\"current\":{\"time\":\"2026-10-24T05:15\",\"temperature_2m\":20.3,\"weather_code\":51,\"is_day\":0},"
            + "\"hourly\":{\"time\":[\"2026-10-24T04:00\",\"2026-10-24T05:00\",\"2026-10-24T06:00\",\"2026-10-24T07:00\",\"2026-10-24T08:00\","
            + "\"2026-10-24T09:00\",\"2026-10-24T10:00\",\"2026-10-24T11:00\"],"
            + "\"temperature_2m\":[20.6,20.3,20.5,21.1,22.5,23.1,24.5,25.0],\"precipitation_probability\":[16,18,16,12,10,9,10,null],"
            + "\"weather_code\":[3,51,51,51,3,51,51,2]}}";
    static final String AQI = "{\"current\":{\"time\":\"2026-10-24T04:30\",\"us_aqi\":52,\"european_aqi\":25,\"pm2_5\":7.5}}";

    Weather fake() {
        return Weather.parse(FORECAST, AQI);
    }

    void withWeather(Weather w) {
        activity.setWeatherForTest(w);
        settle();
        for (int k = 0; k < 50 && activity.wallpaperBusy; k++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }
        settle();
        // A weather-driven wallpaper change rebuilds the dock: start from the (new) first tile.
        focusedStart = ((ViewGroup) field(activity, "dock")).getChildAt(0);
        focusedStart.requestFocus();
        settle();
    }

    LinearLayout panel() {
        return (LinearLayout) field(activity, "forecastPanel");
    }

    // ---- parsing / pure logic -----------------------------------------------------------------

    @Test
    public void parse_nextSixHours_aqi_fx() {
        Weather w = fake();
        assertEquals(20, w.temp);
        assertEquals("Light drizzle", w.word());
        assertEquals("Light rain", Weather.word(61));
        assertEquals("Overcast", Weather.word(3));
        assertFalse(w.day);
        assertEquals(6, w.hourLabel.length);
        assertEquals("06:00", w.hourLabel[0]);   // the current (05:xx) and earlier hours are skipped
        assertEquals("11:00", w.hourLabel[5]);
        assertEquals(-1, w.hourRain[5]);         // null rain chance -> unknown
        assertEquals(52, w.aqi());
        assertEquals("Moderate", w.aqiLabel());
        assertEquals(7.5, w.pm25, 0.001);
        assertEquals(RainView.FX_RAIN, w.fx());
        assertEquals(RainView.FX_MIST, Weather.fake(18, 45, true, 40, 5).fx());
        assertEquals(RainView.FX_GLOW, Weather.fake(18, 0, false, 40, 5).fx());
        assertEquals(RainView.FX_NONE, Weather.fake(18, 0, true, 40, 5).fx());
        assertEquals(RainView.FX_NONE, Weather.fake(18, 3, false, 40, 5).fx());
        assertEquals("Good", Weather.fake(18, 0, true, 30, 5).aqiLabel());
        assertNull(Weather.parse("not json", null));
        assertNull(Weather.parse(null, AQI));
        Weather noAir = Weather.parse(FORECAST, "{bad");
        assertNotNull(noAir);
        assertEquals(-1, noAir.aqi());
    }

    @Test
    public void bt_classify_events_lowBattery() {
        assertEquals(Bt.KIND_REMOTE, Bt.kindOf(BluetoothClass.Device.Major.PERIPHERAL, 0x0500, "Amazon Fire TV Remote"));
        assertEquals(Bt.KIND_REMOTE, Bt.kindOf(-1, -1, "Mi Remote"));
        assertEquals(Bt.KIND_AUDIO, Bt.kindOf(BluetoothClass.Device.Major.AUDIO_VIDEO, BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, "Speaker"));
        assertEquals(-1, Bt.kindOf(BluetoothClass.Device.Major.PHONE, 0x0200, "Pixel"));
        assertTrue(Bt.headphones(BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES, "x"));
        assertFalse(Bt.headphones(BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER, "JBL Go"));
        assertTrue(new Bt.State("a", "r", Bt.KIND_REMOTE, false, true, 19).low());
        assertFalse(new Bt.State("a", "r", Bt.KIND_REMOTE, false, true, 20).low());
        assertFalse(new Bt.State("a", "r", Bt.KIND_REMOTE, false, true, -1).low());
        // Reflection on a method that does not exist fails gracefully.
        assertNull(Bt.callInt(new Object(), "getBatteryLevel"));
        Bt.onEvent(BluetoothDevice.ACTION_ACL_CONNECTED, "F3:4F:12:BF:58:8A", -1);
        Bt.onEvent(Bt.ACTION_BATTERY_LEVEL_CHANGED, "F3:4F:12:BF:58:8A", 150);   // out of range: ignored
        Bt.onEvent(BluetoothDevice.ACTION_ACL_DISCONNECTED, "F3:4F:12:BF:58:8A", -1);
    }

    @Test
    public void btIcons_allStyles_stateDrawn_notFocusable() {
        List<Bt.State> st = Arrays.asList(
                new Bt.State("F3:4F:12:BF:58:8A", "Fire TV Remote", Bt.KIND_REMOTE, false, true, 15),
                new Bt.State("00:00:00:00:A3:F3", "Speaker", Bt.KIND_AUDIO, false, false, -1));
        for (String s : STYLES) {
            launch(s, null);
            View before = focused();
            activity.showBtStates(st);
            settle();
            LinearLayout row = (LinearLayout) field(activity, "btRow");
            assertTrue(s + ": BT row not shown", shown(row));
            assertEquals(2, row.getChildCount());
            BtIconView remote = (BtIconView) row.getChildAt(0), speaker = (BtIconView) row.getChildAt(1);
            assertTrue(remote.showsBar());
            assertTrue(s + ": low dot missing", remote.showsLowDot());
            assertFalse(speaker.showsBar());
            assertFalse(speaker.showsLowDot());
            assertFalse(remote.isFocusable());
            assertOnScreen(remote);
            assertOnScreen(speaker);
            assertSame(before, focused());
            shot(s + "-bt-icons");
            activity.showBtStates(new ArrayList<>());
            settle();
            assertEquals(View.GONE, row.getVisibility());
            tearDown();
        }
    }

    @Test
    public void weatherFx_allStyles() {
        for (String s : STYLES) {
            launch(s, null);
            RainView fx = (RainView) field(activity, "rain");
            withWeather(Weather.fake(21, 61, true, 40, 5));
            assertEquals(s, RainView.FX_RAIN, fx.mode());
            assertEquals(View.VISIBLE, fx.getVisibility());
            assertTrue(fx.isRunning());
            withWeather(Weather.fake(18, 45, true, 40, 5));
            assertEquals(RainView.FX_MIST, fx.mode());
            assertTrue(fx.isRunning());
            withWeather(Weather.fake(18, 0, false, 40, 5));
            assertEquals(RainView.FX_GLOW, fx.mode());
            assertFalse("glow is static", fx.isRunning());
            shot(s + "-glow");
            withWeather(Weather.fake(28, 0, true, 40, 5));
            assertEquals(View.GONE, fx.getVisibility());
            controller.pause();
            withWeather(Weather.fake(21, 61, true, 40, 5));
            assertFalse("no animation while paused", fx.isRunning());
            controller.resume();
            tearDown();
        }
    }

    @Test
    public void cachedWeather_shownWithoutNetwork() {
        prefs().edit().putString("wx_forecast", FORECAST).putString("wx_aqi", AQI).putLong("wx_at", System.currentTimeMillis()).commit();
        launch("glass", null);
        TextView line = (TextView) activity.weatherLine();
        assertTrue(shown(line));
        assertTrue(line.getText().toString().contains("20\u00B0"));
    }

    @Test
    public void forecast_dpadOpens_backCloses_navigationIntact_allStyles() {
        for (String s : STYLES) {
            launch(s, null);
            withWeather(fake());
            activity.showBtStates(Arrays.asList(new Bt.State("F3:4F:12:BF:58:8A", "Remote", Bt.KIND_REMOTE, false, true, 80)));
            settle();
            View line = activity.weatherLine();
            assertTrue(s + ": weather line not shown", shown(line));
            assertTrue(((TextView) line).getText().toString().contains("20\u00B0 Light drizzle"));
            assertNotNull("line icon", ((TextView) line).getCompoundDrawablesRelative()[0]);
            assertFalse(activity.forecastShowing());
            assertNotNull(s + ": initial focus should stay on the first tile", focusedStart.getTag(R.id.label));

            // Every focusable (tiles, pills, ⋯, the weather line) is still reachable by D-pad.
            List<View> all = new ArrayList<>();
            collect(activity.getWindow().getDecorView(), all);
            assertTrue(s + ": weather line not focusable", all.contains(line));
            for (View v : all) {
                List<Integer> p = pathTo(v);
                assertNotNull(s + ": " + describe(v) + " not reachable by D-pad", p);
            }

            // Focus opens it, on screen, with six hours and the AQI.
            goTo(line);
            assertTrue(s + ": focus did not open the forecast", activity.forecastShowing());
            settle();
            LinearLayout hours = (LinearLayout) field(activity, "forecastHours");
            assertEquals(6, hours.getChildCount());
            TextView aqi = (TextView) field(activity, "forecastAqi");
            assertTrue(aqi.getText().toString().contains("AQI 52"));
            assertTrue(aqi.getText().toString().contains("PM2.5 7.5"));
            assertOnScreen(panel());
            assertFalse(panel().isFocusable());
            shot(s + "-forecast");

            // Back closes it and keeps focus; Back again does nothing (home screen).
            press(KeyEvent.KEYCODE_BACK);
            assertFalse(s + ": Back did not close the forecast", activity.forecastShowing());
            assertSame(line, focused());
            assertFalse(activity.isFinishing());
            // OK toggles it.
            press(KeyEvent.KEYCODE_DPAD_CENTER);
            assertTrue(s + ": OK did not open the forecast", activity.forecastShowing());
            press(KeyEvent.KEYCODE_DPAD_CENTER);
            assertFalse(activity.forecastShowing());
            press(KeyEvent.KEYCODE_DPAD_CENTER);
            assertTrue(activity.forecastShowing());

            // Moving away closes it, and the first tile is reachable from the line.
            View first = focusedStart;
            focusedStart = line;
            List<Integer> back = pathTo(first);
            assertNotNull(s + ": first tile not reachable from the weather line", back);
            replay(back);
            assertSame(first, focused());
            assertFalse(s + ": panel stayed open after focus left", activity.forecastShowing());
            tearDown();
        }
    }

    @Test
    public void openForecastExtra() {
        launch("posters", null);
        withWeather(fake());
        controller.newIntent(new Intent().putExtra("openForecast", true));
        settle();
        assertTrue(activity.forecastShowing());
        Rect r = new Rect();
        assertTrue(panel().getGlobalVisibleRect(r));
    }

    @Test
    public void wallpaper_followsWeather() {
        Wallpapers.Theme[] all = Wallpapers.readThemes(ApplicationProvider.getApplicationContext().getAssets());
        for (boolean night : new boolean[]{false, true}) {
            for (String sky : new String[]{"", "cloudy", "rain"}) {
                List<Wallpapers.Theme> set = Wallpapers.pickSet(all, night, sky);
                assertFalse(sky + night + ": empty set", set.isEmpty());
                for (Wallpapers.Theme t : set) {
                    assertEquals(t.file, night, t.night);
                    assertEquals(t.file + " in the wrong set", sky, t.weather);
                }
            }
        }
        assertEquals("rain", Weather.wallpaperSet(51));   // drizzle
        assertEquals("rain", Weather.wallpaperSet(63));
        assertEquals("rain", Weather.wallpaperSet(95));
        assertEquals("cloudy", Weather.wallpaperSet(3));  // overcast
        assertEquals("", Weather.wallpaperSet(2));
        assertEquals("", Weather.wallpaperSet(0));
        assertEquals("", Weather.wallpaperSet(45));       // fog: normal set + mist

        launch("glass", null);
        withWeather(Weather.fake(21, 61, true, 40, 5));
        assertEquals("rain", field(activity, "wallpaperWeather"));
        withWeather(Weather.fake(21, 3, true, 40, 5));
        assertEquals("cloudy", field(activity, "wallpaperWeather"));
        withWeather(Weather.fake(28, 0, true, 40, 5));
        assertEquals("", field(activity, "wallpaperWeather"));
    }

    void waitWallpaper() {
        settle();
        for (int k = 0; k < 60 && activity.wallpaperBusy; k++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
        }
        settle();
    }

    @Test
    public void posters_lighterBlur_swapsWithStyle_noRelaunch() {
        android.content.res.AssetManager am = ApplicationProvider.getApplicationContext().getAssets();
        for (Wallpapers.Theme t : Wallpapers.readThemes(am)) {
            try {
                am.open("wallpapers/" + t.file.replace(".jpg", "-light.jpg")).close();
            } catch (java.io.IOException e) {
                fail("missing -light copy for " + t.file);
            }
        }
        launch("glass", null);
        waitWallpaper();
        assertNotNull(activity.wallpaperFile);
        assertFalse(activity.wallpaperLight);
        android.widget.ImageView wp = activity.findViewById(R.id.wallpaper);
        android.graphics.drawable.Drawable before = wp.getDrawable();
        MainActivity same = activity;
        controller.newIntent(new Intent().putExtra("style", "posters"));
        waitWallpaper();
        assertSame("relaunched", same, activity);
        assertTrue(activity.wallpaperLight);
        assertNotSame("bitmap not swapped", before, wp.getDrawable());
        boolean night = (boolean) field(activity, "nightApplied");
        if (!night) assertNotNull("posters day dim", wp.getColorFilter());
        shot("posters-light-blur");
        controller.newIntent(new Intent().putExtra("style", "glass"));
        waitWallpaper();
        assertFalse(activity.wallpaperLight);
        if (!night) assertNull("glass unchanged: no dim", wp.getColorFilter());
    }
}

