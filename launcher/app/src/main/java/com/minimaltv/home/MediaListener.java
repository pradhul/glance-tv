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

import java.util.ArrayList;
import java.util.List;

/**
 * Exists so the app can hold a notification listener grant, which is what MediaSessionManager.getActiveSessions()
 * needs. While connected it follows the media sessions and remembers the last title + package in prefs.
 */
public class MediaListener extends NotificationListenerService {

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

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener = controllers -> follow(controllers);

    @Override
    public void onListenerConnected() {
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

    /** Saves the best session's title: a playing one first, else the first with a title. */
    static void record(Context context, List<MediaController> controllers) {
        if (controllers == null) return;
        String title = null, pkg = null;
        for (MediaController c : controllers) {
            try {
                String t = title(c.getMetadata());
                if (t == null || context.getPackageName().equals(c.getPackageName())) continue;
                PlaybackState st = c.getPlaybackState();
                boolean playing = st != null && st.getState() == PlaybackState.STATE_PLAYING;
                if (title == null || playing) {
                    title = t;
                    pkg = c.getPackageName();
                    if (playing) break;
                }
            } catch (RuntimeException ignored) {
            }
        }
        if (title == null) return;
        SharedPreferences p = context.getSharedPreferences(PREFS, MODE_PRIVATE);
        if (title.equals(p.getString(KEY_TITLE, null)) && pkg.equals(p.getString(KEY_PKG, null))) return;
        p.edit().putString(KEY_TITLE, title).putString(KEY_PKG, pkg).apply();
    }

    private static String title(MediaMetadata m) {
        if (m == null) return null;
        CharSequence t = m.getText(MediaMetadata.METADATA_KEY_DISPLAY_TITLE);
        if (t == null || t.toString().trim().isEmpty()) t = m.getText(MediaMetadata.METADATA_KEY_TITLE);
        if (t == null || t.toString().trim().isEmpty()) return null;
        return t.toString().trim();
    }
}
