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
import android.text.Spanned;
import android.text.style.ImageSpan;
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
public class V37OrdersTest {

    static final LocalDate TODAY = V36OrdersTest.TODAY;
    static final Instant NOW = V36OrdersTest.NOW;
    static final String FRESH = V36OrdersTest.FRESH;

    static String order(String store, String item, String status, String eta) {
        String e = eta == null ? "null" : "\"" + eta + "\"";
        String it = item == null ? "null" : "\"" + item + "\"";
        return "{\"store\":\"" + store + "\",\"item\":" + it + ",\"status\":\"" + status + "\",\"eta\":" + e + "}";
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
    public void label_shortItem_trimmedAtWord_fallsBackToStore() {
        assertEquals("Air Mouse", Orders.label(new Orders.Order("Flipkart", " Air  Mouse ", "ordered", null)));
        assertEquals("Wireless Keyboard…", Orders.label(new Orders.Order("Amazon", "Wireless Keyboard and Mouse Combo", "ordered", null)));
        assertEquals("Logitech MX Master…", Orders.label(new Orders.Order("Amazon", "Logitech MX Master 3S, Graphite", "ordered", null)));
        assertEquals("Supercalifragilist…", Orders.label(new Orders.Order("Amazon", "Supercalifragilisticexpialidocious", "ordered", null)));
        assertEquals("Exactly eighteen c", Orders.label(new Orders.Order("Amazon", "Exactly eighteen c", "ordered", null)));
        assertEquals("Amazon", Orders.label(new Orders.Order("Amazon", "", "ordered", null)));
        assertEquals("Amazon", Orders.label(new Orders.Order("Amazon", null, "ordered", null)));
        assertEquals("Amazon", Orders.label(Orders.parse(V36OrdersTest.json(FRESH, order("Amazon", null, "shipped", null))).orders.get(0)));
    }

    @Test
    public void phrase_dateThenArrivingThenStatus() {
        assertEquals("Thu 29 Oct", Orders.phrase(new Orders.Order("F", "x", "shipped", LocalDate.of(2026, 10, 29)), TODAY));
        assertEquals("Today", Orders.phrase(new Orders.Order("F", "x", "out_for_delivery", TODAY), TODAY));
        assertEquals("Today", Orders.phrase(new Orders.Order("F", "x", "shipped", TODAY.minusDays(1)), TODAY));
        assertEquals("Tomorrow", Orders.phrase(new Orders.Order("F", "x", "ordered", TODAY.plusDays(1)), TODAY));
        assertEquals("arriving today", Orders.phrase(new Orders.Order("F", "x", "out_for_delivery", null), TODAY));
        assertEquals("shipped", Orders.phrase(new Orders.Order("F", "x", "shipped", null), TODAY));
    }

    @Test
    public void line_itemsAndDates_capPlusN() {
        Orders.Snapshot s = Orders.parse(V36OrdersTest.json(FRESH,
                order("Flipkart", "Air Mouse", "ordered", "2026-10-29") + ","
                        + order("Amazon", "Phone Case", "shipped", null) + ","
                        + order("Blinkit", "Milk", "out_for_delivery", null) + ","
                        + order("Amazon", "", "ordered", null)));
        assertEquals("Milk · arriving today  |  Air Mouse · Thu 29 Oct  |  Phone Case · shipped  |  +1",
                Orders.line(s, TODAY, NOW));
    }

    @Test
    public void bust_addsQueryParam() {
        assertEquals("https://x.test/o.json?t=5", Orders.bust("https://x.test/o.json", 5));
        assertEquals("https://x.test/o.json?a=1&t=5", Orders.bust("https://x.test/o.json?a=1", 5));
        assertEquals("", Orders.url(ApplicationProvider.getApplicationContext()
                .getSharedPreferences(Looks.PREFS, Context.MODE_PRIVATE).getString(Orders.KEY_REFRESH_URL, "")));
    }

    @Test
    public void home_boxIconAndRefreshButton_onlyWithOrders() {
        String payload = V36OrdersTest.json(FRESH, order("Flipkart", "Air Mouse", "ordered", "2026-10-29"));
        MainActivity a = launch(new Intent()
                .putExtra(Orders.EXTRA_JSON, payload)
                .putExtra(MainActivity.EXTRA_HOLIDAY_TEXT, "Kannada Rajyotsava · in 7 days"));
        CharSequence text = a.holidayLine.getText();
        assertEquals("Kannada Rajyotsava · in 7 days  |  " + Orders.ICON_SLOT + " Air Mouse · Thu 29 Oct", text.toString());
        assertTrue(text instanceof Spanned);
        ImageSpan[] icons = ((Spanned) text).getSpans(0, text.length(), ImageSpan.class);
        assertEquals(1, icons.length);
        assertEquals(text.toString().indexOf(Orders.ICON_SLOT), ((Spanned) text).getSpanStart(icons[0]));
        assertEquals(Math.round(a.holidayLine.getTextSize()), icons[0].getDrawable().getBounds().height());

        assertEquals(View.VISIBLE, a.ordersRefresh.getVisibility());
        assertTrue("orders refresh must be D-pad reachable", a.ordersRefresh.isFocusable());
        assertTrue(a.ordersRefresh.requestFocus());
        assertTrue(a.ordersRefresh.performClick());
        assertEquals(MainActivity.BING_LOADING, a.ordersState);
        a.finish();

        MainActivity noOrders = launch(new Intent()
                .putExtra(Orders.EXTRA_JSON, V36OrdersTest.json("2020-01-01T00:00:00Z", order("Amazon", "Mouse", "shipped", null)))
                .putExtra(MainActivity.EXTRA_HOLIDAY_TEXT, "Kannada Rajyotsava · in 7 days"));
        assertEquals("Kannada Rajyotsava · in 7 days", noOrders.holidayLine.getText().toString());
        assertEquals(View.GONE, noOrders.ordersRefresh.getVisibility());
        noOrders.finish();
    }

    private MainActivity launch(Intent i) {
        i.setClassName("com.minimaltv.home", "com.minimaltv.home.MainActivity");
        i.putExtra("style", "glass");
        ActivityController<MainActivity> c = Robolectric.buildActivity(MainActivity.class, i).setup();
        for (int k = 0; k < 6; k++) shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(300));
        return c.get();
    }
}
