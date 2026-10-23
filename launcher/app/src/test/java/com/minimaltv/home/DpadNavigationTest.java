package com.minimaltv.home;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.AlertDialog;
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
    public void rainOverlay_floatingOnly() {
        launch("floating", new Intent().putExtra("forceRain", true));
        RainView rain = (RainView) field(activity, "rain");
        assertEquals(View.VISIBLE, rain.getVisibility());
        shot("floating-rain");
        controller.newIntent(new Intent().putExtra("style", "glass"));
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
}
