package com.minimaltv.home;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * Weather for Singasandra, Bangalore, from Open-Meteo (no key): current conditions, the next hours and the
 * air quality. v25: two small requests (forecast + air quality); the raw answers are cached by the caller so a
 * restart shows the last result at once. Calls back with null on any failure.
 */
final class Weather {

    interface Callback {
        void done(Weather w, String forecastJson, String aqiJson);
    }

    static final double LAT = 12.885, LON = 77.645;
    static final int HOURS = 6;
    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast?latitude=" + LAT + "&longitude=" + LON
            + "&current=temperature_2m,weather_code,is_day"
            + "&hourly=temperature_2m,precipitation_probability,weather_code&forecast_hours=8&timezone=auto";
    private static final String AIR = "https://air-quality-api.open-meteo.com/v1/air-quality?latitude=" + LAT
            + "&longitude=" + LON + "&current=us_aqi,european_aqi,pm2_5&timezone=auto";

    /** Tests: never touch the network. */
    static volatile boolean networkOff;

    final int temp;
    final int code;
    final boolean day;
    /** Next hours: "HH:mm" labels, temperatures, rain chance % (-1 unknown), WMO codes. */
    final String[] hourLabel;
    final int[] hourTemp, hourRain, hourCode;
    /** US AQI, European AQI (-1 unknown) and PM2.5 in µg/m³ (NaN unknown). */
    final int usAqi, euAqi;
    final double pm25;

    private Weather(int temp, int code, boolean day, String[] hl, int[] ht, int[] hr, int[] hc, int us, int eu, double pm) {
        this.temp = temp;
        this.code = code;
        this.day = day;
        hourLabel = hl;
        hourTemp = ht;
        hourRain = hr;
        hourCode = hc;
        usAqi = us;
        euAqi = eu;
        pm25 = pm;
    }

    /** Builds a Weather from the two raw answers; the air-quality one may be null. Null if the forecast is bad. */
    static Weather parse(String forecastJson, String aqiJson) {
        if (forecastJson == null) return null;
        try {
            JSONObject root = new JSONObject(forecastJson);
            JSONObject cur = root.getJSONObject("current");
            int temp = (int) Math.round(cur.getDouble("temperature_2m"));
            int code = cur.getInt("weather_code");
            boolean day = cur.optInt("is_day", 1) != 0;
            String now = cur.optString("time", "");
            String nowHour = now.length() >= 13 ? now.substring(0, 13) : "";
            String[] hl = new String[HOURS];
            int[] ht = new int[HOURS], hr = new int[HOURS], hc = new int[HOURS];
            int n = 0;
            JSONObject h = root.optJSONObject("hourly");
            if (h != null) {
                JSONArray t = h.getJSONArray("time"), tt = h.getJSONArray("temperature_2m");
                JSONArray pp = h.optJSONArray("precipitation_probability"), wc = h.optJSONArray("weather_code");
                for (int i = 0; i < t.length() && n < HOURS; i++) {
                    String ts = t.getString(i);
                    // Only hours after the current one ("2026-10-24T05" sorts before "2026-10-24T06").
                    if (ts.length() < 16 || ts.substring(0, 13).compareTo(nowHour) <= 0) continue;
                    hl[n] = ts.substring(11, 16);
                    ht[n] = (int) Math.round(tt.getDouble(i));
                    hr[n] = pp == null || pp.isNull(i) ? -1 : pp.getInt(i);
                    hc[n] = wc == null || wc.isNull(i) ? code : wc.getInt(i);
                    n++;
                }
            }
            if (n < HOURS) {
                hl = java.util.Arrays.copyOf(hl, n);
                ht = java.util.Arrays.copyOf(ht, n);
                hr = java.util.Arrays.copyOf(hr, n);
                hc = java.util.Arrays.copyOf(hc, n);
            }
            int us = -1, eu = -1;
            double pm = Double.NaN;
            if (aqiJson != null) {
                try {
                    JSONObject a = new JSONObject(aqiJson).getJSONObject("current");
                    if (a.has("us_aqi") && !a.isNull("us_aqi")) us = a.getInt("us_aqi");
                    if (a.has("european_aqi") && !a.isNull("european_aqi")) eu = a.getInt("european_aqi");
                    if (a.has("pm2_5") && !a.isNull("pm2_5")) pm = a.getDouble("pm2_5");
                } catch (Exception ignored) {
                    // No air quality; the forecast still shows.
                }
            }
            return new Weather(temp, code, day, hl, ht, hr, hc, us, eu, pm);
        } catch (Exception e) {
            return null;
        }
    }

    static void fetch(Callback cb) {
        new Thread(() -> {
            String fc = get(FORECAST);
            String aq = fc == null ? null : get(AIR);
            Weather w = parse(fc, aq);
            cb.done(w, w == null ? null : fc, aq);
        }, "weather").start();
    }

    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(8000);
            c.setReadTimeout(8000);
            if (c.getResponseCode() != 200) return null;
            InputStream in = c.getInputStream();
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[4096];
            for (int n; (n = in.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toString("UTF-8");
        } catch (Exception e) {
            return null;
        } finally {
            if (c != null) c.disconnect();
        }
    }

    /** WMO weather code to a short word. */
    String word() {
        return word(code);
    }

    static String word(int code) {
        if (code == 0) return "Clear";
        if (code == 1) return "Mostly clear";
        if (code == 2) return "Partly cloudy";
        if (code == 3) return "Overcast";
        if (code == 45 || code == 48) return "Fog";
        if (code == 51) return "Light drizzle";
        if (code == 55) return "Heavy drizzle";
        if (code >= 51 && code <= 57) return "Drizzle";
        if (code == 61) return "Light rain";
        if (code == 65) return "Heavy rain";
        if (code >= 80 && code <= 82) return "Showers";
        if (code >= 61 && code <= 67) return "Rain";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "Snow";
        if (code >= 95) return "Thunderstorm";
        return "Cloudy";
    }

    /** Rain, drizzle, showers or thunderstorm. */
    static boolean rainy(int code) {
        return (code >= 51 && code <= 67) || (code >= 80 && code <= 82) || code >= 95;
    }

    static boolean foggy(int code) {
        return code == 45 || code == 48;
    }

    /** v25: the wallpaper set the sky calls for: "rain" (rain, drizzle, showers, storms), "cloudy" (overcast), "". */
    String wallpaperSet() {
        return wallpaperSet(code);
    }

    static String wallpaperSet(int code) {
        if (rainy(code)) return "rain";
        if (code == 3) return "cloudy";
        return "";
    }

    /** Which overlay the conditions call for (RainView.FX_*). */
    int fx() {
        if (rainy(code)) return RainView.FX_RAIN;
        if (foggy(code)) return RainView.FX_MIST;
        if (!day && code <= 1) return RainView.FX_GLOW;
        return RainView.FX_NONE;
    }

    /** One plain symbol for the slim clock line. */
    String symbol() {
        return symbol(code);
    }

    static String symbol(int code) {
        if (code >= 95) return "\u26C8";
        if (rainy(code)) return "\u2602";
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return "\u2744";
        if (code == 45 || code == 48) return "\u2248";
        if (code <= 1) return "\u2600";
        return "\u2601";
    }

    /** v25: line-style icon (stroke only), the clear-night one a moon. */
    int lineIcon() {
        if (code <= 1) return day ? R.drawable.ic_wl_sun : R.drawable.ic_wl_moon;
        if (foggy(code)) return R.drawable.ic_wl_fog;
        if (code >= 95) return R.drawable.ic_wl_storm;
        if (rainy(code)) return R.drawable.ic_wl_rain;
        return R.drawable.ic_wl_cloud;
    }

    int icon() {
        if (code <= 1) return R.drawable.ic_w_sun;
        if (code == 45 || code == 48) return R.drawable.ic_w_fog;
        if (code >= 95) return R.drawable.ic_w_storm;
        if (code >= 51 && code <= 86) return R.drawable.ic_w_rain;
        return R.drawable.ic_w_cloud;
    }

    /** The AQI shown (US scale, else European), or -1. */
    int aqi() {
        return usAqi >= 0 ? usAqi : euAqi;
    }

    /** Label and colour for the shown AQI: US categories, or the European bands when only that is known. */
    String aqiLabel() {
        int a = aqi();
        if (a < 0) return "";
        if (usAqi >= 0) {
            return a <= 50 ? "Good" : a <= 100 ? "Moderate" : a <= 150 ? "Unhealthy for some" : a <= 200 ? "Unhealthy"
                    : a <= 300 ? "Very unhealthy" : "Hazardous";
        }
        return a <= 20 ? "Good" : a <= 40 ? "Fair" : a <= 60 ? "Moderate" : a <= 80 ? "Poor" : a <= 100 ? "Very poor" : "Extremely poor";
    }

    int aqiColor() {
        int a = aqi();
        if (a < 0) return 0xFFBBBBBB;
        int level = usAqi >= 0 ? (a <= 50 ? 0 : a <= 100 ? 1 : a <= 150 ? 2 : a <= 200 ? 3 : a <= 300 ? 4 : 5)
                : (a <= 20 ? 0 : a <= 40 ? 0 : a <= 60 ? 1 : a <= 80 ? 2 : a <= 100 ? 3 : 4);
        return new int[]{0xFF4CD964, 0xFFFFD84D, 0xFFFF9F40, 0xFFFF5A5A, 0xFFB57BFF, 0xFFC0506A}[level];
    }

    /** Test/debug hook: a hand-made answer. */
    static Weather fake(int temp, int code, boolean day, int usAqi, double pm25) {
        String[] hl = new String[HOURS];
        int[] ht = new int[HOURS], hr = new int[HOURS], hc = new int[HOURS];
        for (int i = 0; i < HOURS; i++) {
            hl[i] = String.format(java.util.Locale.ROOT, "%02d:00", (6 + i) % 24);
            ht[i] = temp + i % 3;
            hr[i] = 10 * i;
            hc[i] = code;
        }
        return new Weather(temp, code, day, hl, ht, hr, hc, usAqi, -1, pm25);
    }
}
