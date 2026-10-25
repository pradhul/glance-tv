package com.minimaltv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.os.Looper;
import android.view.KeyEvent;
import android.view.View;
import android.widget.GridView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@LooperMode(LooperMode.Mode.PAUSED)
public class V42OrdersColumnTest {

    private static String json(int n) {
        StringBuilder b = new StringBuilder("{\"updated\":\"2026-10-25T08:00:00+05:30\",\"orders\":[");
        for (int i = 0; i < n; i++) {
            if (i > 0) b.append(',');
            b.append("{\"store\":\"S").append(i).append("\",\"item\":\"Item ").append(i)
                    .append("\",\"status\":\"shipped\",\"eta\":\"2026-11-").append(String.format("%02d", 20 - i)).append("\"}");
        }
        b.append(",{\"store\":\"Amazon\",\"item\":\"Fast\",\"status\":\"out_for_delivery\",\"eta\":null}");
        b.append(",{\"store\":\"Amazon\",\"item\":\"NoDate\",\"status\":\"ordered\",\"eta\":null}]}");
        return b.toString();
    }

    @Before
    public void pin() {
        Orders.todayForTest = LocalDate.parse("2026-10-25");
        Orders.nowForTest = Instant.parse("2026-10-25T03:00:00Z");
        Context c = ApplicationProvider.getApplicationContext();
        for (int i = 0; i < 6; i++) {
            String p = "com.example.app" + i;
            android.content.pm.PackageInfo pi = new android.content.pm.PackageInfo();
            pi.packageName = p;
            pi.applicationInfo = new android.content.pm.ApplicationInfo();
            pi.applicationInfo.packageName = p;
            pi.applicationInfo.nonLocalizedLabel = "App " + i;
            shadowOf(c.getPackageManager()).installPackage(pi);
            android.content.ComponentName cn = new android.content.ComponentName(p, p + ".Main");
            shadowOf(c.getPackageManager()).addActivityIfNotPresent(cn);
            android.content.IntentFilter f = new android.content.IntentFilter(android.content.Intent.ACTION_MAIN);
            f.addCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER);
            shadowOf(c.getPackageManager()).addIntentFilterForActivity(cn, f);
        }
    }

    @After
    public void unpin() {
        Orders.todayForTest = null;
        Orders.nowForTest = null;
    }

    @Test
    public void noLimitAndSortOrder() {
        List<Orders.Order> c = Orders.cards(Orders.parse(json(6)), Orders.today(), Orders.now());
        assertEquals(8, c.size());
        assertEquals("Fast", c.get(0).item);
        assertEquals("Item 5", c.get(1).item);   // earliest eta
        assertEquals("Item 0", c.get(6).item);
        assertEquals("NoDate", c.get(7).item);
    }

    @Test
    public void todayDate() {
        Orders.Order o = new Orders.Order("X", "Y", "out_for_delivery", LocalDate.parse("2026-10-25"));
        assertEquals("today", Orders.cardDate(o));
    }

    private AllAppsActivity open(String ordersJson) {
        Context c = ApplicationProvider.getApplicationContext();
        android.content.SharedPreferences p = c.getSharedPreferences(Looks.PREFS, Context.MODE_PRIVATE);
        p.edit().putString(Orders.KEY_URL, ordersJson == null ? "" : "https://example.com/o.json")
                .putString(Orders.KEY_CACHE, ordersJson).commit();
        AllAppsActivity a = Robolectric.buildActivity(AllAppsActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        return a;
    }

    @Test
    public void hiddenWhenNoOrders() {
        AllAppsActivity a = open(null);
        assertEquals(View.GONE, a.findViewById(R.id.orders_column).getVisibility());
        assertEquals(6, ((GridView) a.findViewById(R.id.grid)).getNumColumns());
    }

    @Test
    public void shownWithAllCardsAndLeftReturns() {
        AllAppsActivity a = open(json(6));
        assertEquals(View.VISIBLE, a.findViewById(R.id.orders_column).getVisibility());
        assertEquals(8, ((android.view.ViewGroup) a.findViewById(R.id.orders_strip)).getChildCount());
        GridView g = a.findViewById(R.id.grid);
        assertEquals(5, g.getNumColumns());
        for (int i = 0; i < 100 && g.getAdapter() == null; i++) {
            try { Thread.sleep(20); } catch (InterruptedException ignored) { }
            shadowOf(Looper.getMainLooper()).idle();
        }
        assertTrue("apps loaded", g.getAdapter() != null && g.getAdapter().getCount() >= 5);
        View scroll = a.findViewById(R.id.orders_scroll);
        g.requestFocus();
        g.setSelection(4);   // last column of row 1
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
        View refresh = a.findViewById(R.id.orders_refresh);
        assertTrue("Right from grid lands on refresh", refresh.hasFocus());
        org.junit.Assert.assertFalse(a.findViewById(R.id.orders_header).isFocusable());
        refresh.performClick();
        assertEquals(1, a.ordersRefreshes);
        assertEquals(MainActivity.BING_LOADING, a.ordersState);
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN));
        assertTrue("Down enters the list", scroll.hasFocus());
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP));
        assertTrue("Up at top returns to refresh", refresh.hasFocus());
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        assertTrue(g.hasFocus());
        scroll.requestFocus();
        a.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
        assertTrue(g.hasFocus());
    }
}
