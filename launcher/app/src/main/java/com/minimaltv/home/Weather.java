package com.minimaltv.home;

import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/** Current weather for Singasandra, Bangalore, from Open-Meteo (no key). Calls back with null on any failure. */
final class Weather {

    interface Callback {
        void done(Weather w);
    }

    private static final String URL_STR = "https://api.open-meteo.com/v1/forecast?latitude=12.88&longitude=77.65"
            + "&current=temperature_2m,weather_code";

    final int temp;
    final int code;

    private Weather(int temp, int code) {
        this.temp = temp;
        this.code = code;
    }

    static void fetch(Callback cb) {
        new Thread(() -> {
            Weather w = null;
            HttpURLConnection c = null;
            try {
                c = (HttpURLConnection) new URL(URL_STR).openConnection();
                c.setConnectTimeout(8000);
                c.setReadTimeout(8000);
                if (c.getResponseCode() == 200) {
                    InputStream in = c.getInputStream();
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
                    JSONObject cur = new JSONObject(out.toString("UTF-8")).getJSONObject("current");
                    w = new Weather((int) Math.round(cur.getDouble("temperature_2m")), cur.getInt("weather_code"));
                }
            } catch (Exception ignored) {
                // Fail silently.
            } finally {
                if (c != null) c.disconnect();
            }
            cb.done(w);
        }, "weather").start();
    }

    /** WMO weather code to a short word. */
    String word() {
        if (code == 0) return "Clear";
        if (code <= 2) return "Partly cloudy";
        if (code == 3) return "Cloudy";
        if (code == 45 || code == 48) return "Fog";
        if (code >= 51 && code <= 57) return "Drizzle";
        if ((code >= 61 && code <= 67) || (code >= 80 && code <= 82)) return "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "Cloudy";
    }

    /** Rain, drizzle, showers or thunderstorm. */
    static boolean rainy(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
    }

    /** One plain symbol for the slim clock line. */
    String symbol() {
        if (code >= 95) return "\u26C8";
        if (rainy(code)) return "\u2602";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "\u2744";
        if (code == 45 || code == 48) return "\u2248";
        if (code <= 1) return "\u2600";
        return "\u2601";
    }

    int icon() {
        if (code <= 1) return R.drawable.ic_w_sun;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if (code >= 95) return R.drawable.ic_w_storm;
        if (code >= 51 && code <= 86) return R.drawable.ic_w_rain;
        return R.drawable.ic_w_cloud;
    }
}
