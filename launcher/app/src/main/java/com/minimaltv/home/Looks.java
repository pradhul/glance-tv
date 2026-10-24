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

    /** v38: All apps uses the heavy "-blur" copy of the photo under a ~50% black scrim so line icons read. */
    static final int ALL_APPS_SCRIM = 0x80000000;

    /** v38: All apps' wallpaper fades in with the screen. */
    static final long WALLPAPER_FADE_MS = 300;

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
