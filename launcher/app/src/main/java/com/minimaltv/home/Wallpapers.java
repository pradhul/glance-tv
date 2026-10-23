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
        boolean night;        // in the night set (22:00-06:00)
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

    private Wallpapers() {
    }

    private static String lastFile;

    /**
     * Picks a wallpaper at random from the day or night set (all photos if that set is empty), never the one
     * shown last when there is a choice, and decodes it. Null when the assets are missing. Call off the main thread.
     */
    static Loaded pickAndLoad(Context context, boolean night, String forced) {
        AssetManager assets = context.getAssets();
        Theme[] all = readThemes(assets);
        if (all.length == 0) return null;
        java.util.List<Theme> set = new java.util.ArrayList<>();
        for (Theme t : all) if (t.night == night) set.add(t);
        if (set.isEmpty()) java.util.Collections.addAll(set, all);
        if (set.size() > 1) for (int i = 0; i < set.size(); i++) if (set.get(i).file.equals(lastFile)) set.remove(i--);
        Theme theme = set.get(new Random().nextInt(set.size()));
        if (forced != null) for (Theme t : all) if (t.file.equals(forced)) theme = t;
        lastFile = theme.file;
        Bitmap sharp = decode(assets, theme.file);
        if (sharp == null) return null;
        Bitmap blurred = decode(assets, theme.file.replace(".jpg", "-blur.jpg"));
        return new Loaded(theme, sharp, blurred);
    }

    private static Theme[] readThemes(AssetManager assets) {
        try (InputStream in = assets.open(DIR + "/themes.json")) {
            byte[] buf = new byte[in.available()];
            int n = in.read(buf);
            JSONArray array = new JSONArray(new String(buf, 0, n, "UTF-8"));
            Theme[] themes = new Theme[array.length()];
            for (int i = 0; i < array.length(); i++) {
                JSONObject o = array.getJSONObject(i);
                themes[i] = new Theme(o.getString("file"),
                        "dark".equals(o.optString("top")),
                        "dark".equals(o.optString("pills")),
                        "dark".equals(o.optString("dock")));
                themes[i].night = "night".equals(o.optString("set"));
                JSONObject lum = o.optJSONObject("luminance");
                themes[i].luminance = lum == null ? 0.5f
                        : (float) (lum.optDouble("top", .5) + lum.optDouble("pills", .5) + lum.optDouble("dock", .5)) / 3f;
            }
            return themes;
        } catch (Exception e) {
            Log.w(TAG, "themes.json unreadable", e);
            return new Theme[0];
        }
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
