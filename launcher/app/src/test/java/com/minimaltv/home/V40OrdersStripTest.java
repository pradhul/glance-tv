package com.minimaltv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.view.View;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34, qualifiers = "w960dp-h540dp-land-television-xhdpi")
public class V40OrdersStripTest {

    private static final String JSON = "{\"updated\":\"2026-10-25T08:00:00+05:30\",\"orders\":["
            + "{\"store\":\"Amazon\",\"item\":\"USB Hub\",\"status\":\"ordered\",\"eta\":\"2026-10-26\",\"image\":\"https://example.com/a.jpg\"},"
            + "{\"store\":\"Flipkart\",\"item\":\"Air Mouse\",\"status\":\"shipped\",\"eta\":\"2026-10-29\",\"image\":null},"
            + "{\"store\":\"Amazon\",\"item\":\"Cable\",\"status\":\"out_for_delivery\",\"eta\":null}]}";

    private Orders.Snapshot snap() {
        return Orders.parse(JSON);
    }

    @Test
    public void parsesImageTolerantly() {
        List<Orders.Order> o = snap().orders;
        assertEquals(3, o.size());
        assertEquals("https://example.com/a.jpg", o.get(0).image);
        assertNull(o.get(1).image);
        assertNull(o.get(2).image);
        assertNull(Orders.parse("{\"updated\":\"2026-10-25T08:00:00Z\",\"orders\":[{\"store\":\"A\",\"status\":\"shipped\",\"image\":\"  \"}]}").orders.get(0).image);
    }

    @Test
    public void stepMapping() {
        assertEquals(0, Orders.step("ordered"));
        assertEquals(1, Orders.step("shipped"));
        assertEquals(2, Orders.step("out_for_delivery"));
        assertEquals(3, Orders.step("delivered"));
        assertEquals(0, Orders.step(null));
        assertEquals(0, Orders.step("weird"));
        assertEquals(0xFF9E9E9E, Orders.stepColor(0));
        assertEquals(0xFF4A8CFF, Orders.stepColor(1));
        assertEquals(0xFFFFB300, Orders.stepColor(2));
        assertEquals(0xFF43C463, Orders.stepColor(3));
    }

    @Test
    public void storeIconMapping() {
        assertEquals(0, Orders.storeIcon("Amazon"));
        assertEquals(0, Orders.storeIcon("Flipkart"));
        assertEquals(0, Orders.storeIcon("Myntra"));
        assertEquals(0, Orders.storeIcon(null));
    }

    @Test
    public void cardDates() {
        List<Orders.Order> o = snap().orders;
        assertEquals("due Mon 26 Oct", Orders.cardDate(o.get(0)));
        assertEquals("by Thu 29 Oct", Orders.cardDate(o.get(1)));
        assertNull(Orders.cardDate(o.get(2)));
    }

    @Test
    public void stripBindsCardsAndHidesWhenEmpty() {
        Context c = ApplicationProvider.getApplicationContext();
        LinearLayout strip = new LinearLayout(c);
        List<Orders.Order> cards = Orders.cards(snap(), LocalDate.parse("2026-10-25"), Instant.parse("2026-10-25T03:00:00Z"));
        OrdersStrip.bind(strip, cards);
        assertEquals(View.VISIBLE, strip.getVisibility());
        assertFalse(strip.getChildAt(0).isFocusable());
        View badge = strip.getChildAt(0).findViewWithTag("store_badge");
        boolean sawText = false;
        for (int i = 0; i < 3; i++) {
            View b = strip.getChildAt(i).findViewWithTag("store_badge");
            if (b instanceof TextView && "Flipkart".contentEquals(((TextView) b).getText())) sawText = true;
        }
        assertTrue(badge instanceof TextView);
        assertEquals("Amazon", ((TextView) badge).getText().toString());
        assertTrue(sawText);
        OrdersStrip.bind(strip, Orders.cards(null, LocalDate.parse("2026-10-25"), Instant.now()));
        assertEquals(View.GONE, strip.getVisibility());
    }
}
