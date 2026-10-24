package com.minimaltv.home;

import java.util.Calendar;

/**
 * Shared look-and-feel numbers used by home and All apps. Keep visual values here so a tweak in one
 * screen does not silently diverge from the other.
 */
final class Looks {

    static final String PREFS = "dock";

    /** Home and All apps dim the current photo with the same scrim. */
    static final int WALLPAPER_DIM = 0x59000000;

    static final long GLOW_IN_MS = 450;
    static final long GLOW_OUT_MS = 250;

    private Looks() {
    }

    /** Day is 06:00–22:00; night is the rest. Used for wallpaper sets and Bing's half-day pick. */
    static boolean nightHours() {
        int hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY);
        return hour >= 22 || hour < 6;
    }
}
