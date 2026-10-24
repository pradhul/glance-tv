package com.minimaltv.home;

import android.content.Context;
import android.content.res.AssetManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.IOException;
import java.io.InputStream;
import java.util.Random;

/**
 * The wallpapers bundled in assets/wallpapers, prepared by docs/design/prepare_wallpapers.py: each photo as a
 * 1080p crop plus a blurred half-size twin, and themes.json saying which colour pair each screen region uses.
 * One is picked at random per launcher start. Nothing is computed on the TV beyond decoding two JPEGs.
 */
final class Wallpapers {

    static final class Theme {
        final String file;
        final boolean topDark;
        final boolean pillsDark;
        final boolean dockDark;
        boolean night;        // in a night set (22:00-06:00)
        String weather = "";  // v25: "" for the normal sets, "cloudy" or "rain" for the weather sets
        float luminance;      // mean of the three regions, 0-1

        Theme(String file, boolean topDark, boolean pillsDark, boolean dockDark) {
            this.file = file;
            this.topDark = topDark;
            this.pillsDark = pillsDark;
            this.dockDark = dockDark;
        }
    }

    static final class Loaded {
        final Theme theme;
        final Bitmap sharp;
        final Bitmap blurred;

        Loaded(Theme theme, Bitmap sharp, Bitmap blurred) {
            this.theme = theme;
            this.sharp = sharp;
            this.blurred = blurred;
        }
    }

    private static final String TAG = "Wallpapers";
    private static final String DIR = "wallpapers";
    /** Mean region luminance below which the top text and pills switch to white. */
    private static final double LIGHT_TEXT_BELOW = 0.70;

    private Wallpapers() {
    }

    private static String lastFile;

    /**
     * Picks a wallpaper at random from the day or night set (all photos if that set is empty), never the one
     * shown last when there is a choice, and decodes it. Null when the assets are missing. Call off the main thread.
     */
    static Loaded pickAndLoad(Context context, boolean night, String forced) {
        return pickAndLoad(context, night, "", false, forced);
    }

    /**
     * v25: weather is "" (normal sets), "cloudy" or "rain": the matching day/night weather set when it has photos,
     * else the normal set for that time of day.
     */
    static Loaded pickAndLoad(Context context, boolean night, String weather, boolean light, String forced) {
        AssetManager assets = context.getAssets();
        Theme[] all = readThemes(assets);
        if (all.length == 0) return null;
        java.util.List<Theme> set = pickSet(all, night, weather);
        if (set.size() > 1) for (int i = 0; i < set.size(); i++) if (set.get(i).file.equals(lastFile)) set.remove(i--);
        Theme theme = set.get(new Random().nextInt(set.size()));
        if (forced != null) for (Theme t : all) if (t.file.equals(forced)) theme = t;
        lastFile = theme.file;
        Bitmap sharp = background(assets, theme.file, light);
        if (sharp == null) return null;
        Bitmap blurred = decode(assets, theme.file.replace(".jpg", "-blur.jpg"));
        return new Loaded(theme, sharp, blurred);
    }

    /** The candidates for a time of day and weather; never empty when all is not. */
    static java.util.List<Theme> pickSet(Theme[] all, boolean night, String weather) {
        java.util.List<Theme> set = new java.util.ArrayList<>();
        String w = weather == null ? "" : weather;
        for (Theme t : all) if (t.night == night && t.weather.equals(w)) set.add(t);
        if (set.isEmpty()) for (Theme t : all) if (t.night == night && t.weather.isEmpty()) set.add(t);
        if (set.isEmpty()) java.util.Collections.addAll(set, all);
        return set;
    }

    /**
     * The background bitmap: the lightly blurred "-soft" copy (pre-made: no per-frame cost on the TV), or with
     * light (v26, tall posters) the even lighter "-light" copy; each falls back to the next, then the original.
     */
    static Bitmap background(AssetManager assets, String file, boolean light) {
        Bitmap b = light ? decode(assets, file.replace(".jpg", "-light.jpg")) : null;
        if (b == null) b = decode(assets, file.replace(".jpg", "-soft.jpg"));
        if (b == null) b = decode(assets, file);
        return b;
    }

    /**
     * v38: the heavily blurred half-size "-blur" copy (All apps backdrop; home keeps its light blur), falling back
     * to the soft background when no -blur copy exists.
     */
    static Bitmap heavyBlur(Context context, String file) {
        String name = file.replace(".jpg", "-blur.jpg");
        java.io.File d = new java.io.File(context.getFilesDir(), Bing.DIR);
        Bitmap b = new java.io.File(d, file).exists() ? decodeFile(new java.io.File(d, name)) : decode(context.getAssets(), name);
        return b != null ? b : background(context, file, false);
    }

    static Bitmap background(Context context, String file, boolean light) {
        java.io.File d = new java.io.File(context.getFilesDir(), Bing.DIR);
        if (new java.io.File(d, file).exists()) {   // v30: the Bing photo and its generated variants
            Bitmap b = light ? decodeFile(new java.io.File(d, file.replace(".jpg", "-light.jpg"))) : null;
            if (b == null) b = decodeFile(new java.io.File(d, file.replace(".jpg", "-soft.jpg")));
            if (b == null) b = decodeFile(new java.io.File(d, file));
            return b;
        }
        return background(context.getAssets(), file, light);
    }

    static Theme[] readThemes(AssetManager assets) {
        try (InputStream in = assets.open(DIR + "/themes.json")) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            JSONArray array = new JSONArray(new String(buf, 0, n, "UTF-8"));
            Theme[] themes = new Theme[array.length()];
            for (int i = 0; i < array.length(); i++) {
                themes[i] = theme(array.getJSONObject(i));
            }
            return themes;
        } catch (Exception e) {
            Log.w(TAG, "themes.json unreadable", e);
            return new Theme[0];
        }
    }

    /** One themes.json line (v30: also the Bing photo's generated line). */
    static Theme theme(JSONObject o) throws org.json.JSONException {
        // Light text unless the region is really bright: mid-tones (sunsets) read better in white.
        JSONObject l = o.optJSONObject("luminance");
        boolean topDark = "dark".equals(o.optString("top")) || (l != null && l.optDouble("top", 1) < LIGHT_TEXT_BELOW);
        boolean pillsDark = "dark".equals(o.optString("pills")) || (l != null && l.optDouble("pills", 1) < LIGHT_TEXT_BELOW);
        Theme t = new Theme(o.getString("file"), topDark, pillsDark,
                "dark".equals(o.optString("dock")));
        // Sets: day, night, cloudy-day, cloudy-night, rain-day, rain-night.
        String set = o.optString("set");
        t.night = set.endsWith("night");
        int dash = set.indexOf('-');
        t.weather = dash > 0 ? set.substring(0, dash) : "";
        JSONObject lum = o.optJSONObject("luminance");
        t.luminance = lum == null ? 0.5f
                : (float) (lum.optDouble("top", .5) + lum.optDouble("pills", .5) + lum.optDouble("dock", .5)) / 3f;
        return t;
    }

    /** v30: the stored Bing photo from files/bing, or null (none, disabled, or undecodable). Off the main thread. */
    static Loaded loadBing(Context context, android.content.SharedPreferences prefs, boolean light) {
        String base = Bing.current(context, prefs);
        if (base == null) return null;
        try {
            Theme theme = theme(new JSONObject(prefs.getString(Bing.KEY_THEME, "{}")));
            Bitmap sharp = background(context, theme.file, light);
            if (sharp == null) return null;
            Bitmap blurred = decodeFile(new java.io.File(Bing.dir(context), base + "-blur.jpg"));
            lastFile = theme.file;
            return new Loaded(theme, sharp, blurred);
        } catch (Exception e) {
            Log.w(TAG, "bing theme unreadable", e);
            return null;
        }
    }

    static Bitmap decodeFile(java.io.File f) {
        if (!f.exists()) return null;
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;
        return BitmapFactory.decodeFile(f.getPath(), opts);
    }

    private static Bitmap decode(AssetManager assets, String name) {
        BitmapFactory.Options opts = new BitmapFactory.Options();
        opts.inPreferredConfig = Bitmap.Config.RGB_565;   // no alpha needed; half the memory of ARGB on a 1.4 GB TV
        try (InputStream in = assets.open(DIR + "/" + name)) {
            return BitmapFactory.decodeStream(in, null, opts);
        } catch (IOException e) {
            Log.w(TAG, "cannot decode " + name, e);
            return null;
        }
    }
}
