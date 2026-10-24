package com.minimaltv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * v30: the daily Bing photo as the wallpaper. Only Bing's small JSON list is fetched, then a 64x36 thumbnail per
 * candidate (about 1 KB each) to judge brightness, then ONE 1920x1080 photo. On disk there is only ever the current
 * photo and its -soft / -light / -blur variants (made here, same radii as docs/design/add_wallpapers.py); the
 * previous set is deleted once the new one is written. Everything here runs off the main thread.
 */
final class Bing {

    static final class Entry {
        final String id;          // hsh, or urlbase
        final String urlbase;
        final String copyright;
        float lum = -1;           // thumbnail mean luminance 0-1, -1 unknown

        Entry(String id, String urlbase, String copyright) {
            this.id = id;
            this.urlbase = urlbase;
            this.copyright = copyright;
        }
    }

    private static final String TAG = "Bing";
    static final String LIST_URL = "https://www.bing.com/HPImageArchive.aspx?format=js&idx=0&n=8&mkt=en-IN";
    static final String DIR = "bing";
    static final String KEY_ID = "bing_id", KEY_FILE = "bing_file", KEY_COPY = "bing_copy", KEY_AT = "bing_at",
            KEY_NIGHT = "bing_night", KEY_SEEN = "bing_seen", KEY_THEME = "bing_theme";
    static final float SOFT_SIGMA = 3f, LIGHT_SIGMA = 1.5f, BLUR_SIGMA = 40f;

    private Bing() {
    }

    static List<Entry> parseList(String json) {
        List<Entry> out = new ArrayList<>();
        if (json == null) return out;
        try {
            JSONArray images = new JSONObject(json).getJSONArray("images");
            for (int i = 0; i < images.length(); i++) {
                JSONObject o = images.getJSONObject(i);
                String base = o.optString("urlbase", "");
                if (base.isEmpty()) continue;
                String id = o.optString("hsh", base);
                out.add(new Entry(id, base, o.optString("copyright", "")));
            }
        } catch (Exception e) {
            Log.w(TAG, "bad list", e);
        }
        return out;
    }

    /**
     * The pick: never the current photo, prefer ones not shown recently; by day the brightest, by night the darkest.
     * Candidates with unknown brightness count as mid-grey. -1 when there is nothing else to show.
     */
    static int pick(List<Entry> list, boolean night, String currentId, List<String> seen) {
        int best = -1;
        for (int pass = 0; pass < 2 && best < 0; pass++) {
            for (int i = 0; i < list.size(); i++) {
                Entry e = list.get(i);
                if (e.id.equals(currentId)) continue;
                if (pass == 0 && seen.contains(e.id)) continue;
                if (best < 0 || better(e, list.get(best), night)) best = i;
            }
        }
        return best;
    }

    private static boolean better(Entry a, Entry b, boolean night) {
        float la = a.lum < 0 ? 0.5f : a.lum, lb = b.lum < 0 ? 0.5f : b.lum;
        return night ? la < lb : la > lb;
    }

    static File dir(Context c) {
        File d = new File(c.getFilesDir(), DIR);
        //noinspection ResultOfMethodCallIgnored
        d.mkdirs();
        return d;
    }

    /** Deletes every file in dir that does not belong to the photo named keepBase (keepBase.jpg, keepBase-*.jpg). */
    static void cleanup(File dir, String keepBase) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            String n = f.getName();
            boolean keep = keepBase != null && (n.equals(keepBase + ".jpg") || n.startsWith(keepBase + "-"));
            if (!keep) //noinspection ResultOfMethodCallIgnored
                f.delete();
        }
    }

    /** The stored photo's file base, or null when there is none on disk. */
    static String current(Context c, SharedPreferences prefs) {
        String base = prefs.getString(KEY_FILE, null);
        if (base == null || !new File(dir(c), base + ".jpg").exists()) return null;
        return base;
    }

    /** Refresh due: nothing stored, stored for the other half of the day, or older than 14 hours. */
    static boolean stale(Context c, SharedPreferences prefs, boolean night) {
        if (current(c, prefs) == null) return true;
        if (prefs.getBoolean(KEY_NIGHT, false) != night) return true;
        return System.currentTimeMillis() - prefs.getLong(KEY_AT, 0) > 14L * 3600 * 1000;
    }

    /** One fetch: the list, the thumbnails, one full photo and its variants. True when a new photo is stored. */
    static boolean refresh(Context c, SharedPreferences prefs, boolean night) {
        String json = Holidays.get(LIST_URL);
        List<Entry> list = parseList(json);
        if (list.isEmpty()) return false;
        for (Entry e : list) e.lum = thumbLum(e.urlbase);
        List<String> seen = new ArrayList<>();
        for (String s : prefs.getString(KEY_SEEN, "").split(",")) if (!s.isEmpty()) seen.add(s);
        int i = pick(list, night, prefs.getString(KEY_ID, null), seen);
        if (i < 0) return false;
        Entry e = list.get(i);
        byte[] jpg = download("https://www.bing.com" + e.urlbase + "_1920x1080.jpg", 4 << 20);
        if (jpg == null) return false;
        String base = "b" + Integer.toHexString(e.id.hashCode()) + Long.toString(System.currentTimeMillis() % 100000, 36);
        File d = dir(c);
        try {
            String theme = writeVariants(d, base, jpg);
            prefs.edit().putString(KEY_ID, e.id).putString(KEY_FILE, base).putString(KEY_COPY, e.copyright)
                    .putString(KEY_THEME, theme).putLong(KEY_AT, System.currentTimeMillis()).putBoolean(KEY_NIGHT, night)
                    .putString(KEY_SEEN, seenAfter(seen, e.id)).commit();
            cleanup(d, base);   // only now: the old photo stays until the new one is complete
            return true;
        } catch (Throwable t) {   // OutOfMemoryError included: fall back to what was there
            Log.w(TAG, "variants failed", t);
            cleanup(d, current(c, prefs));
            return false;
        }
    }

    static String seenAfter(List<String> seen, String id) {
        List<String> s = new ArrayList<>(seen);
        s.remove(id);
        s.add(id);
        while (s.size() > 7) s.remove(0);
        return android.text.TextUtils.join(",", s);
    }

    private static float thumbLum(String urlbase) {
        byte[] b = download("https://www.bing.com/th?id=" + urlbase.replaceFirst("^/th\\?id=", "") + "_1920x1080.jpg&w=64&h=36&c=1", 64 << 10);
        if (b == null) return -1;
        Bitmap t = BitmapFactory.decodeByteArray(b, 0, b.length);
        if (t == null) return -1;
        float l = meanLum(t, 0, 0, t.getWidth(), t.getHeight());
        t.recycle();
        return l;
    }

    static float meanLum(Bitmap b, int l, int t, int r, int bot) {
        double sum = 0;
        int n = 0;
        int step = Math.max(1, (r - l) / 64);
        for (int y = t; y < bot; y += step) {
            for (int x = l; x < r; x += step) {
                int c = b.getPixel(x, y);
                sum += (0.2126 * ((c >> 16) & 255) + 0.7152 * ((c >> 8) & 255) + 0.0722 * (c & 255)) / 255.0;
                n++;
            }
        }
        return n == 0 ? 0.5f : (float) (sum / n);
    }

    /**
     * Writes base.jpg (the bytes as downloaded), base-soft.jpg, base-light.jpg and the half-size base-blur.jpg;
     * returns the theme line (themes.json format) sampled from the blur, regions as in prepare_wallpapers.py.
     */
    static String writeVariants(File d, String base, byte[] jpg) throws IOException, org.json.JSONException {
        try (FileOutputStream o = new FileOutputStream(new File(d, base + ".jpg"))) {
            o.write(jpg);
        }
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(jpg, 0, jpg.length, bounds);
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inSampleSize = 1;
        while (bounds.outWidth / (opts.inSampleSize * 2) >= 1920) opts.inSampleSize *= 2;   // a UHD answer: halve
        Bitmap src = BitmapFactory.decodeByteArray(jpg, 0, jpg.length, opts);
        if (src == null) throw new IOException("undecodable");
        float scale = src.getWidth() / 1920f;
        save(Blur.gaussian(src, SOFT_SIGMA * scale), new File(d, base + "-soft.jpg"));
        save(Blur.gaussian(src, LIGHT_SIGMA * scale), new File(d, base + "-light.jpg"));
        Bitmap half = Bitmap.createScaledBitmap(src, Math.max(1, src.getWidth() / 2), Math.max(1, src.getHeight() / 2), true);
        src.recycle();
        Bitmap blur = Blur.gaussian(half, BLUR_SIGMA * scale / 2f);
        half.recycle();
        float k = blur.getWidth() / 1920f;
        float top = meanLum(blur, (int) (560 * k), (int) (120 * k), (int) (1360 * k), (int) (440 * k));
        float pills = meanLum(blur, (int) (1250 * k), (int) (56 * k), (int) (1860 * k), (int) (150 * k));
        float dock = meanLum(blur, (int) (300 * k), (int) (600 * k), (int) (1620 * k), (int) (1030 * k));
        save(blur, new File(d, base + "-blur.jpg"));
        JSONObject t = new JSONObject();
        t.put("file", base + ".jpg");
        t.put("top", top < 0.42 ? "dark" : "light");
        t.put("pills", pills < 0.42 ? "dark" : "light");
        t.put("dock", dock < 0.42 ? "dark" : "light");
        JSONObject l = new JSONObject();
        l.put("top", Math.round(top * 100) / 100.0);
        l.put("pills", Math.round(pills * 100) / 100.0);
        l.put("dock", Math.round(dock * 100) / 100.0);
        t.put("luminance", l);
        return t.toString();
    }

    private static void save(Bitmap b, File f) throws IOException {
        try (FileOutputStream o = new FileOutputStream(f)) {
            b.compress(Bitmap.CompressFormat.JPEG, 80, o);
        } finally {
            b.recycle();
        }
    }

    static byte[] download(String url, int max) {
        java.net.HttpURLConnection c = null;
        try {
            c = (java.net.HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(15000);
            if (c.getResponseCode() != 200) return null;
            try (java.io.InputStream in = c.getInputStream()) {
                return Holidays.readAll(in, max);
            }
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }
}
