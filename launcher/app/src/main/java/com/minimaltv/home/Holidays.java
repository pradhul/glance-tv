package com.minimaltv.home;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/**
 * v30: the tiny holiday line at the top-left ("Deepavali · in 6 days"). Bundled assets/holidays.json (national
 * holidays and Karnataka festivals 2026-2027) plus Nager.Date's list for India, cached and refreshed weekly.
 */
final class Holidays {

    static final class Holiday {
        final LocalDate date;
        final String name;

        Holiday(LocalDate date, String name) {
            this.date = date;
            this.name = name;
        }
    }

    private static final String TAG = "Holidays";
    static final String KEY_NAGER = "hol_nager_", KEY_NAGER_AT = "hol_nager_at";
    private static final long WEEK = 7L * 24 * 3600 * 1000;

    private Holidays() {
    }

    /** "Happy X" on the day, "X · tomorrow", "X · in N days"; null when nothing is ahead. */
    static String line(List<Holiday> all, LocalDate today) {
        Holiday next = next(all, today);
        if (next == null) return null;
        long days = ChronoUnit.DAYS.between(today, next.date);
        if (days == 0) return "Happy " + next.name;
        if (days == 1) return next.name + " · tomorrow";
        return next.name + " · in " + days + " days";
    }

    static Holiday next(List<Holiday> all, LocalDate today) {
        Holiday best = null;
        for (Holiday h : all) {
            if (h.date.isBefore(today)) continue;
            if (best == null || h.date.isBefore(best.date)) best = h;
        }
        return best;
    }

    /** Parses [{"date":"2026-11-08","name"|"localName":...}], the bundled and Nager formats alike. */
    static List<Holiday> parse(String json) {
        List<Holiday> out = new ArrayList<>();
        if (json == null || json.isEmpty()) return out;
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                String name = o.optString("name", o.optString("localName", ""));
                if (name.isEmpty()) continue;
                out.add(new Holiday(LocalDate.parse(o.getString("date")), name));
            }
        } catch (Exception e) {
            Log.w(TAG, "bad holiday json", e);
        }
        return out;
    }

    /** Bundled first; a Nager entry is only added on a date the bundled list has nothing for. */
    static List<Holiday> merge(List<Holiday> bundled, List<Holiday> extra) {
        TreeMap<LocalDate, Holiday> byDate = new TreeMap<>();
        for (Holiday h : bundled) byDate.putIfAbsent(h.date, h);
        for (Holiday h : extra) byDate.putIfAbsent(h.date, h);
        return new ArrayList<>(byDate.values());
    }

    static List<Holiday> load(Context c, SharedPreferences prefs, LocalDate today) {
        List<Holiday> bundled = parse(asset(c, "holidays.json"));
        List<Holiday> extra = new ArrayList<>();
        for (int y = today.getYear(); y <= today.getYear() + 1; y++) extra.addAll(parse(prefs.getString(KEY_NAGER + y, null)));
        return merge(bundled, extra);
    }

    static boolean stale(SharedPreferences prefs) {
        return System.currentTimeMillis() - prefs.getLong(KEY_NAGER_AT, 0) > WEEK;
    }

    /** Off the main thread: this year's and next year's Nager.Date lists into prefs. True if anything changed. */
    static boolean refresh(SharedPreferences prefs, int year) {
        boolean changed = false;
        for (int y = year; y <= year + 1; y++) {
            String body = get("https://date.nager.at/api/v3/PublicHolidays/" + y + "/IN");
            if (body != null && body.trim().startsWith("[")) {
                prefs.edit().putString(KEY_NAGER + y, body).apply();
                changed = true;
            }
        }
        prefs.edit().putLong(KEY_NAGER_AT, System.currentTimeMillis()).apply();
        return changed;
    }

    static String get(String url) {
        return HttpIo.getString(url, 8000, 8000, 1 << 20);
    }

    static byte[] readAll(InputStream in, int max) throws java.io.IOException {
        return HttpIo.read(in, max);
    }

    private static String asset(Context c, String name) {
        try (InputStream in = c.getAssets().open(name)) {
            return new String(readAll(in, 1 << 20), "UTF-8");
        } catch (Exception e) {
            return null;
        }
    }
}
