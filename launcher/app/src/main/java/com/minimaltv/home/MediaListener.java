package com.minimaltv.home;

import android.content.ComponentName;
import android.content.Context;
import android.content.SharedPreferences;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.service.notification.NotificationListenerService;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * Exists so the app can hold a notification listener grant, which is what MediaSessionManager.getActiveSessions()
 * needs. While connected it follows the media sessions and remembers the last title + package in prefs.
 */
public class MediaListener extends NotificationListenerService {

    static final String TAG = "MTL";
    static final String PREFS = "dock";
    static final String KEY_TITLE = "last_title";
    static final String KEY_PKG = "last_pkg";

    private MediaSessionManager msm;
    private final List<MediaController> watched = new ArrayList<>();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final MediaController.Callback callback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            scan();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            scan();
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = controllers -> {
        Log.d(TAG, "active sessions changed: " + (controllers == null ? 0 : controllers.size()));
        follow(controllers);
    };

    @Override
    public void onListenerConnected() {
        Log.d(TAG, "listener connected");
        try {
            msm = getSystemService(MediaSessionManager.class);
            ComponentName me = new ComponentName(this, MediaListener.class);
            msm.addOnActiveSessionsChangedListener(sessionsListener, me, main);
            follow(msm.getActiveSessions(me));
        } catch (RuntimeException ignored) {
            // No grant (yet): nothing to follow.
        }
    }

    @Override
    public void onListenerDisconnected() {
        try {
            if (msm != null) msm.removeOnActiveSessionsChangedListener(sessionsListener);
        } catch (RuntimeException ignored) {
        }
        unfollow();
    }

    private void follow(List<MediaController> controllers) {
        unfollow();
        if (controllers != null) {
            for (MediaController c : controllers) {
                try {
                    c.registerCallback(callback, main);
                    watched.add(c);
                } catch (RuntimeException ignored) {
                }
            }
        }
        scan();
    }

    private void unfollow() {
        for (MediaController c : watched) {
            try {
                c.unregisterCallback(callback);
            } catch (RuntimeException ignored) {
            }
        }
        watched.clear();
    }

    private void scan() {
        record(this, watched);
    }

    /** Sessions worth remembering: not ours, not Bluetooth or another system package. */
    static boolean eligible(Context context, String pkg) {
        if (pkg == null || pkg.equals(context.getPackageName()) || pkg.equals("android")
                || pkg.startsWith("com.android.") || pkg.startsWith("com.google.android.tv.remote")) return false;
        try {
            android.content.pm.ApplicationInfo ai = context.getPackageManager().getApplicationInfo(pkg, 0);
            boolean system = (ai.flags & android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
                    && (ai.flags & android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) == 0;
            // Preinstalled-but-updated apps (Netflix, YouTube on many TVs) count; only launchable apps count.
            boolean launchable = Apps.launchIntent(context.getPackageManager(), pkg) != null;
            return launchable && (!system || ai.packageName.contains("netflix") || ai.packageName.contains("youtube"));
        } catch (Exception e) {
            return false;
        }
    }

    private static boolean active(PlaybackState st) {
        if (st == null) return false;
        int s = st.getState();
        return s == PlaybackState.STATE_PLAYING || s == PlaybackState.STATE_PAUSED || s == PlaybackState.STATE_BUFFERING
                || s == PlaybackState.STATE_CONNECTING || s == PlaybackState.STATE_FAST_FORWARDING
                || s == PlaybackState.STATE_REWINDING;
    }

    /**
     * Saves one session: a playing/paused/buffering one first, else the eligible session with the newest
     * PlaybackState update (getActiveSessions also lists inactive ones, e.g. Netflix's STOPPED session with
     * null metadata). The title is optional; with none, the pill shows "Resume · App".
     */
    static void record(Context context, List<MediaController> controllers) {
        if (controllers == null) return;
        MediaController best = null;
        boolean bestActive = false;
        long bestTime = Long.MIN_VALUE;
        for (MediaController c : controllers) {
            try {
                String pkg = c.getPackageName();
                PlaybackState st = c.getPlaybackState();
                Log.d(TAG, "session " + pkg + " state=" + (st == null ? "null" : st.getState())
                        + " title=" + title(c.getMetadata()));
                if (!eligible(context, pkg)) continue;
                boolean act = active(st);
                long t = st == null ? 0 : st.getLastPositionUpdateTime();
                if (best == null || (act && !bestActive) || (act == bestActive && t > bestTime)) {
                    best = c;
                    bestActive = act;
                    bestTime = t;
                }
            } catch (RuntimeException e) {
                Log.d(TAG, "session read failed", e);
            }
        }
        if (best == null) return;
        String pkg = best.getPackageName();
        String title = title(best.getMetadata());
        SharedPreferences p = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        String oldPkg = p.getString(KEY_PKG, null), oldTitle = p.getString(KEY_TITLE, null);
        // Same app, no title now: keep the title it published earlier.
        if (title == null && pkg.equals(oldPkg)) title = oldTitle;
        if (pkg.equals(oldPkg) && (title == null ? oldTitle == null : title.equals(oldTitle))) return;
        SharedPreferences.Editor e = p.edit().putString(KEY_PKG, pkg);
        if (title == null) e.remove(KEY_TITLE); else e.putString(KEY_TITLE, title);
        boolean ok = e.commit();
        Log.d(TAG, "recorded " + pkg + " title=" + title + " active=" + bestActive + " ok=" + ok);
    }

    private static String title(MediaMetadata m) {
        if (m == null) return null;
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (t == null || t.toString().trim().isEmpty()) t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null || t.toString().trim().isEmpty()) return null;
        return t.toString().trim();
    }
}
