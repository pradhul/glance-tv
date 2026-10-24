package com.minimaltv.home;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.graphics.Bitmap;
import android.graphics.Color;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.File;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** v30: Bing pick by brightness, cleanup keeps one photo, holiday countdown text, blur radii. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V30Test {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private static List<Bing.Entry> entries(float... lums) {
        List<Bing.Entry> l = new ArrayList<>();
        for (int i = 0; i < lums.length; i++) {
            Bing.Entry e = new Bing.Entry("id" + i, "/th?id=OHR.X" + i, "© " + i);
            e.lum = lums[i];
            l.add(e);
        }
        return l;
    }

    @Test
    public void bingPick_dayBrightest_nightDarkest_skipsCurrentAndSeen() {
        List<Bing.Entry> l = entries(0.3f, 0.8f, 0.1f, 0.6f);
        assertEquals(1, Bing.pick(l, false, null, Collections.emptyList()));
        assertEquals(2, Bing.pick(l, true, null, Collections.emptyList()));
        // The current photo is never picked again; the next-brightest is.
        assertEquals(3, Bing.pick(l, false, "id1", Collections.emptyList()));
        // Recently shown ones are skipped while others remain...
        assertEquals(0, Bing.pick(l, true, "id3", Arrays.asList("id2")));
        // ...but when everything has been seen, the best other than the current one still comes back.
        assertEquals(2, Bing.pick(l, true, "id0", Arrays.asList("id1", "id2", "id3")));
        assertEquals(-1, Bing.pick(entries(0.5f), false, "id0", Collections.emptyList()));
    }

    @Test
    public void bingParse_list() {
        String json = "{\"images\":[{\"urlbase\":\"/th?id=OHR.A_EN-IN1\",\"copyright\":\"Lake (© X)\",\"hsh\":\"h1\"},"
                + "{\"urlbase\":\"\"},{\"urlbase\":\"/th?id=OHR.B\"}]}";
        List<Bing.Entry> l = Bing.parseList(json);
        assertEquals(2, l.size());
        assertEquals("h1", l.get(0).id);
        assertEquals("Lake (© X)", l.get(0).copyright);
        assertEquals("/th?id=OHR.B", l.get(1).id);
        assertTrue(Bing.parseList("not json").isEmpty());
    }

    @Test
    public void cleanup_keepsOnlyTheCurrentPhotoAndItsVariants() throws Exception {
        File d = tmp.newFolder("bing");
        for (String n : new String[]{"bold.jpg", "bold-soft.jpg", "bold-light.jpg", "bold-blur.jpg",
                "bnew.jpg", "bnew-soft.jpg", "bnew-light.jpg", "bnew-blur.jpg", "stray.tmp"}) {
            assertTrue(new File(d, n).createNewFile());
        }
        Bing.cleanup(d, "bnew");
        List<String> left = new ArrayList<>(Arrays.asList(d.list()));
        Collections.sort(left);
        assertEquals(Arrays.asList("bnew-blur.jpg", "bnew-light.jpg", "bnew-soft.jpg", "bnew.jpg"), left);
    }

    @Test
    public void writeVariants_makesFourFiles_andPaleDarkTheme() throws Exception {
        File d = tmp.newFolder("bing2");
        Bitmap b = Bitmap.createBitmap(192, 108, Bitmap.Config.ARGB_8888);
        b.eraseColor(Color.rgb(20, 20, 30));
        java.io.ByteArrayOutputStream o = new java.io.ByteArrayOutputStream();
        b.compress(Bitmap.CompressFormat.JPEG, 90, o);
        String theme = Bing.writeVariants(d, "bx", o.toByteArray());
        Bing.cleanup(d, "bx");
        assertEquals(4, d.list().length);
        Wallpapers.Theme t = Wallpapers.theme(new org.json.JSONObject(theme));
        assertTrue(t.topDark && t.pillsDark && t.dockDark);
        assertTrue(t.luminance < 0.3f);
    }

    @Test
    public void blurRadii_lowered() {
        assertEquals(3f, Bing.SOFT_SIGMA, 0);
        assertEquals(1.5f, Bing.LIGHT_SIGMA, 0);
        int[] boxes = Blur.boxes(3f, 3);
        assertEquals(3, boxes.length);
    }

    @Test
    public void holidayLine_countdownText() {
        List<Holidays.Holiday> l = Holidays.merge(
                Holidays.parse("[{\"date\":\"2026-11-08\",\"name\":\"Deepavali\"},{\"date\":\"2026-11-01\",\"name\":\"Kannada Rajyotsava\"}]"),
                Holidays.parse("[{\"date\":\"2026-11-08\",\"localName\":\"Diwali\"},{\"date\":\"2026-12-25\",\"localName\":\"Christmas Day\"}]"));
        assertEquals(3, l.size());   // same-date Nager entry dropped, bundled name kept
        assertEquals("Kannada Rajyotsava · in 8 days", Holidays.line(l, LocalDate.of(2026, 10, 24)));
        assertEquals("Deepavali · in 6 days", Holidays.line(l, LocalDate.of(2026, 11, 2)));
        assertEquals("Deepavali · tomorrow", Holidays.line(l, LocalDate.of(2026, 11, 7)));
        assertEquals("Happy Deepavali", Holidays.line(l, LocalDate.of(2026, 11, 8)));
        assertEquals("Christmas Day · in 46 days", Holidays.line(l, LocalDate.of(2026, 11, 9)));
        assertEquals(null, Holidays.line(l, LocalDate.of(2027, 1, 1)));
    }

    @Test
    public void bundledHolidays_cover2026And2027() {
        android.content.Context c = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        List<Holidays.Holiday> l = Holidays.load(c, c.getSharedPreferences("t", 0), LocalDate.of(2026, 10, 24));
        assertTrue(l.size() >= 40);
        assertEquals("Kannada Rajyotsava · in 8 days", Holidays.line(l, LocalDate.of(2026, 10, 24)));
        assertEquals("Happy Ugadi", Holidays.line(l, LocalDate.of(2027, 4, 7)));
    }
}
