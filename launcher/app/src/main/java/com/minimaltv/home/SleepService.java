package com.minimaltv.home;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;

/**
 * The sleep timer's arm. A non-system app cannot call PowerManager.goToSleep() or inject KEYCODE_SLEEP, but an
 * enabled accessibility service may perform GLOBAL_ACTION_LOCK_SCREEN (API 28+), which the system implements as
 * go-to-sleep + lock. The service lives as long as it is enabled, so the timer fires even while another app plays.
 * Enable: adb shell settings put secure enabled_accessibility_services com.minimaltv.home/com.minimaltv.home.SleepService
 */
public class SleepService extends AccessibilityService {

    static final String KEY_SLEEP_AT = "sleep_at";   // System.currentTimeMillis() deadline, 0 = off
    private static SleepService instance;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable check = new Runnable() {
        @Override
        public void run() {
            checkDeadline();
            handler.postDelayed(this, 20_000);
        }
    };

    static boolean running() {
        return instance != null;
    }

    /** Called when the timer changes, so a fresh deadline is checked promptly. */
    static void poke() {
        if (instance != null) {
            instance.handler.removeCallbacks(instance.check);
            instance.handler.post(instance.check);
        }
    }

    /** Sleeps now. True if the system took the action. */
    static boolean sleepNow() {
        if (instance == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        try {
            return instance.performGlobalAction(GLOBAL_ACTION_LOCK_SCREEN);
        } catch (RuntimeException e) {
            return false;
        }
    }

    @Override
    protected void onServiceConnected() {
        instance = this;
        handler.post(check);
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        handler.removeCallbacks(check);
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        instance = null;
        handler.removeCallbacks(check);
        super.onDestroy();
    }

    private void checkDeadline() {
        long at = getSharedPreferences(MediaListener.PREFS, Context.MODE_PRIVATE).getLong(KEY_SLEEP_AT, 0);
        if (at == 0 || System.currentTimeMillis() < at) return;
        getSharedPreferences(MediaListener.PREFS, Context.MODE_PRIVATE).edit().putLong(KEY_SLEEP_AT, 0).apply();
        sleepNow();
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
    }

    @Override
    public void onInterrupt() {
    }
}
