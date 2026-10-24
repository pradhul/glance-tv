package com.minimaltv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w960dp-h540dp-land-television-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class V36OrdersTest {

    static final LocalDate TODAY = LocalDate.of(2026, 10, 24);
    static final Instant NOW = Instant.parse("2026-10-24T16:00:00Z");
    static final String FRESH = "2026-10-24T12:00:00+05:30";

    static String json(String updated, String orders) {
        return "{\"updated\":\"" + updated + "\",\"orders\":[" + orders + "]}";
    }

    static String order(String store, String status, String eta) {
        String e = eta == null ? "null" : "\"" + eta + "\"";
        return "{\"store\":\"" + store + "\",\"item\":\"\",\"status\":\"" + status + "\",\"eta\":" + e + "}";
    }

    @Before
    public void pinClock() {
        androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().setInTouchMode(false);
        Weather.networkOff = true;
        Orders.todayForTest = TODAY;
        Orders.nowForTest = NOW;
        ApplicationProvider.getApplicationContext().getSharedPreferences(Looks.PREFS, Context.MODE_PRIVATE)
                .edit().clear().commit();
    }

    @After
    public void unpinClock() {
        Orders.todayForTest = null;
        Orders.nowForTest = null;
    }

    @Test
    public void parse_skipsDeliveredAndCancelled() {
        Orders.Snapshot s = Orders.parse(json(FRESH,
                order("Flipkart", "ordered", "2026-10-29") + ","
                        + order("Amazon", "delivered", "2026-10-20") + ","
                        + order("Myntra", "cancelled", null)));
        assertNotNull(s);
        assertEquals(3, s.orders.size());
        assertEquals(1, Orders.visible(s, TODAY).size());
        assertEquals("Flipkart", Orders.visible(s, TODAY).get(0).store);
    }

    @Test
    public void format_etaAndStatusWords() {
        assertEquals("Thu 29 Oct", Orders.phrase(new Orders.Order("F", "x", "ordered", LocalDate.of(2026, 10, 29)), TODAY));
        assertEquals("Today", Orders.phrase(new Orders.Order("F", "x", "ordered", TODAY), TODAY));
        assertEquals("Tomorrow", Orders.phrase(new Orders.Order("F", "x", "shipped", TODAY.plusDays(1)), TODAY));
        assertEquals("Fri 30 Oct", Orders.phrase(new Orders.Order("F", "x", "out_for_delivery", LocalDate.of(2026, 10, 30)), TODAY));
        assertEquals("shipped", Orders.phrase(new Orders.Order("A", "x", "shipped", null), TODAY));
        assertEquals("ordered", Orders.phrase(new Orders.Order("A", "x", "ordered", null), TODAY));
    }

    @Test
    public void sort_outForDeliveryFirstThenEta_capPlusN() {
        Orders.Snapshot s = Orders.parse(json(FRESH,
                order("Amazon", "ordered", null) + ","
                        + order("Flipkart", "ordered", "2026-10-29") + ","
                        + order("Amazon", "shipped", null) + ","
                        + order("Blinkit", "out_for_delivery", null) + ","
                        + order("Zepto", "ordered", "2026-11-01")));
        assertEquals("Blinkit · arriving today  |  Flipkart · Thu 29 Oct  |  Zepto · Sun 1 Nov  |  +2",
                Orders.line(s, TODAY, NOW));
    }

    @Test
    public void exampleLine_threeStores() {
        Orders.Snapshot s = Orders.parse(json(FRESH,
                order("Amazon", "shipped", null) + ","
                        + order("Amazon", "ordered", null) + ","
                        + order("Flipkart", "ordered", "2026-10-29")));
        assertEquals("Flipkart · Thu 29 Oct  |  Amazon · shipped  |  Amazon · ordered",
                Orders.line(s, TODAY, NOW));
    }

    @Test
    public void join_holidayThenOrders_orEitherAlone() {
        assertEquals("Diwali · in 6 days  |  Air Mouse · Thu 29 Oct  |  Amazon · shipped",
                Orders.join("Diwali · in 6 days", "Air Mouse · Thu 29 Oct  |  Amazon · shipped"));
        assertEquals("Air Mouse · Thu 29 Oct", Orders.join(null, "Air Mouse · Thu 29 Oct"));
        assertEquals("Diwali · in 6 days", Orders.join("Diwali · in 6 days", null));
        assertNull(Orders.join("", ""));
    }

    @Test
    public void stale_orMissing_hidesLine() {
        Orders.Snapshot fresh = Orders.parse(json(FRESH, order("Amazon", "shipped", null)));
        assertFalse(Orders.stale(fresh, NOW));
        assertEquals("Amazon · shipped", Orders.line(fresh, TODAY, NOW));

        Orders.Snapshot old = Orders.parse(json("2026-10-23T15:59:59Z", order("Amazon", "shipped", null)));
        assertTrue(Orders.stale(old, NOW));
        assertNull(Orders.line(old, TODAY, NOW));

        assertNull(Orders.parse("not json"));
        assertNull(Orders.parse(null));
        assertNull(Orders.line(null, TODAY, NOW));
        assertNull(Orders.line(Orders.parse("{\"updated\":\"" + FRESH + "\",\"orders\":[]}"), TODAY, NOW));
    }

    @Test
    public void emptyUrl_isFeatureOff() {
        assertTrue(Orders.url("").isEmpty());
        assertTrue(Orders.url(null).isEmpty());
        assertEquals("https://example.com/orders.json", Orders.url(" https://example.com/orders.json "));
    }

    private MainActivity launch(String style, Intent extra) {
        Intent i = extra != null ? extra : new Intent();
        i.setClassName("com.minimaltv.home", "com.minimaltv.home.MainActivity");
        if (style != null) i.putExtra("style", style);
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class, i).setup();
        for (int k = 0; k < 6; k++) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        return c.get();
    }

    @Test
    public void home_sameLineAsHoliday_allStyles_hiddenWhenEmptyOrStale() {
        String payload = json(FRESH,
                order("Flipkart", "ordered", "2026-10-29") + ","
                        + order("Amazon", "shipped", null) + ","
                        + order("Amazon", "ordered", null));
        String orders = "Flipkart · Thu 29 Oct  |  Amazon · shipped  |  Amazon · ordered";
        String holiday = "Deepavali · in 6 days";
        String combined = holiday + "  |  " + Orders.ICON_SLOT + " " + orders;
        for (String style : new String[]{"glass", "floating", "posters"}) {
            Intent extra = new Intent()
                    .putExtra(Orders.EXTRA_JSON, payload)
                    .putExtra(MainActivity.EXTRA_HOLIDAY_TEXT, holiday);
            MainActivity a = launch(style, extra);
            TextView line = a.holidayLine;
            assertNotNull(style, line);
            assertEquals(View.VISIBLE, line.getVisibility());
            assertTrue(line.isShown());
            assertEquals(combined, line.getText().toString());
            assertFalse(style + ": status line must not take D-pad", line.isFocusable());
            assertEquals(11f * a.getResources().getDisplayMetrics().scaledDensity, line.getTextSize(), 0.6f);
            assertTrue(style + ": must ellipsize rather than collide", line.getEllipsize() == android.text.TextUtils.TruncateAt.END);
            assertTrue(style + ": maxWidth leaves room for the refresh button",
                    line.getMaxWidth() > 0 && line.getMaxWidth() <= a.getResources().getDisplayMetrics().widthPixels - a.bingRefresh.getWidth());

            if (a.bingRefresh.getVisibility() == View.VISIBLE) {
                Rect text = new Rect(), refresh = new Rect();
                assertTrue(line.getGlobalVisibleRect(text));
                assertTrue(a.bingRefresh.getGlobalVisibleRect(refresh));
                assertFalse(style + ": status line overlaps refresh " + text + " / " + refresh, Rect.intersects(text, refresh));
            }
            a.finish();
        }

        MainActivity onlyOrders = launch("glass", new Intent()
                .putExtra(Orders.EXTRA_JSON, payload)
                .putExtra(MainActivity.EXTRA_HOLIDAY_TEXT, ""));
        assertEquals(Orders.ICON_SLOT + " " + orders, onlyOrders.holidayLine.getText().toString());
        assertEquals(View.VISIBLE, onlyOrders.holidayLine.getVisibility());
        onlyOrders.finish();

        MainActivity stale = launch("glass", new Intent()
                .putExtra(Orders.EXTRA_JSON, json("2020-01-01T00:00:00Z", order("Amazon", "shipped", null)))
                .putExtra(MainActivity.EXTRA_HOLIDAY_TEXT, ""));
        assertFalse(stale.holidayLine.getText().toString().contains("Amazon"));
        stale.finish();
    }

    @Test
    public void ordersUrlExtra_persistsPref() {
        Intent extra = new Intent().putExtra(Orders.EXTRA_URL, "https://example.test/o.json");
        MainActivity a = launch("glass", extra);
        assertEquals("https://example.test/o.json",
                a.getSharedPreferences(Looks.PREFS, Context.MODE_PRIVATE).getString(Orders.KEY_URL, ""));
        a.finish();
    }
}
