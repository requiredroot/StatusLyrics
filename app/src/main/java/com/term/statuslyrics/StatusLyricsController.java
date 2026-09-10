package com.term.statuslyrics;

import android.content.ComponentName;
import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.TextUtils;
import android.view.View;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedBridge;

/**
 * Status bar live lyrics, modelled after Lunaris-AOSP LyricsFetcher.
 *
 * Proven API usage (see Lunaris frameworks_base):
 *  - MediaSessionManager via the typed overload:
 *      context.getSystemService(MediaSessionManager.class).
 *  - Listener: addOnActiveSessionsChangedListener(listener, component, handler)
 *    with ComponentName(com.android.systemui,
 *    com.android.systemui.statusbar.phone.NotificationListener).
 *  - Controller callbacks registered with a Handler.
 *  - Position interpolated with getPlaybackSpeed() like Lunaris.
 *
 * Rendering stays crash-safe: lyrics are written onto the existing clock view
 * via reflection; we never create or insert views, so System UI can never be
 * crashed into a boot loop by this module.
 */
public final class StatusLyricsController {

    private static final long POLL_MS = 1000L;
    private static final Set<View> INSTALLED =
            Collections.synchronizedSet(Collections.newSetFromMap(
                    new IdentityHashMap<View, Boolean>()));

    private final View clockView;   // reused as the lyric text surface
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final AtomicInteger fetchToken = new AtomicInteger();

    private MediaSessionManager sessionManager;
    private ComponentName notifyComp;
    private MediaController controller;
    private List<LyricLine> lines = Collections.emptyList();
    private String plain;
    private String lastSong;
    private String lastArtist;
    private int lastActive = -1;
    private boolean polling;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                dispatchPlaybackUpdate();
            } catch (Throwable ignore) {
            }
            try {
                if (polling) main.postDelayed(this, POLL_MS);
            } catch (Throwable ignore) {
            }
        }
    };

    private final MediaSessionManager.OnActiveSessionsChangedListener
            sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
        @Override
        public void onActiveSessionsChanged(
                List<MediaController> controllers) {
            try {
                attachBestController(controllers);
            } catch (Throwable t) {
                XposedBridge.log("StatusLyrics: sessions cb: " + t);
            }
        }
    };

    private final MediaController.Callback controllerCallback =
            new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            maybeFetchForCurrentMetadata();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            dispatchPlaybackUpdate();
        }

        @Override
        public void onSessionDestroyed() {
            detachController();
            clearLyrics();
        }
    };

    private StatusLyricsController(View clockView) {
        this.clockView = clockView;
        this.context = clockView.getContext();
    }

    public static synchronized void install(View clockView) {
        if (clockView == null || INSTALLED.contains(clockView)) {
            return;
        }
        Context c = clockView.getContext();
        if (c == null) {
            return; // not attached yet; the attach hook retries
        }
        INSTALLED.add(clockView);
        try {
            StatusLyricsController s = new StatusLyricsController(clockView);
            s.start(c);
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: start failed: " + t);
        }
    }

    private void start(Context c) {
        try {
            sessionManager = c.getSystemService(MediaSessionManager.class);
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: no MediaSessionManager: " + t);
            return;
        }
        if (sessionManager == null) {
            XposedBridge.log("StatusLyrics: MediaSessionManager null");
            return;
        }
        notifyComp = new ComponentName("com.android.systemui",
                "com.android.systemui.statusbar.phone.NotificationListener");
        try {
            sessionManager.addOnActiveSessionsChangedListener(
                    sessionsListener, notifyComp, main);
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: add listener failed: " + t);
        }
        try {
            attachBestController(sessionManager.getActiveSessions(notifyComp));
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: initial sessions: " + t);
        }
    }

    private void attachBestController(List<MediaController> controllers) {
        MediaController best = null;
        try {
            if (controllers != null) {
                for (MediaController c : controllers) {
                    if (c == null) continue;
                    if (stateOf(c) == PlaybackState.STATE_PLAYING) {
                        best = c;
                        break;
                    }
                }
                if (best == null) {
                    for (MediaController c : controllers) {
                        if (c == null) continue;
                        try {
                            if (c.getMetadata() != null) {
                                best = c;
                                break;
                            }
                        } catch (Throwable ignore) {
                        }
                    }
                }
            }
        } catch (Throwable ignore) {
        }
        if (best == controller) {
            if (best != null) maybeFetchForCurrentMetadata();
            return;
        }
        detachController();
        controller = best;
        if (controller != null) {
            try {
                controller.registerCallback(controllerCallback, main);
            } catch (Throwable t) {
                XposedBridge.log("StatusLyrics: registerCallback: " + t);
            }
            maybeFetchForCurrentMetadata();
        } else {
            clearLyrics();
        }
    }

    private static int stateOf(MediaController c) {
        try {
            PlaybackState s = c.getPlaybackState();
            return s == null ? PlaybackState.STATE_NONE : s.getState();
        } catch (Throwable ignore) {
            return PlaybackState.STATE_NONE;
        }
    }

    private void detachController() {
        if (controller != null) {
            try {
                controller.unregisterCallback(controllerCallback);
            } catch (Throwable ignore) {
            }
            controller = null;
        }
        fetchToken.incrementAndGet();
    }

    private void maybeFetchForCurrentMetadata() {
        if (controller == null) return;
        try {
            MediaMetadata meta = controller.getMetadata();
            if (meta == null) return;
            String artist = meta.getString(MediaMetadata.METADATA_KEY_ARTIST);
            String song = meta.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (artist == null || song == null) return;
            artist = cleanArtist(artist);
            song = cleanSong(song);
            if (artist.isEmpty() || song.isEmpty()) return;
            if (song.equals(lastSong) && artist.equals(lastArtist)) {
                dispatchPlaybackUpdate();
                return;
            }
            lastSong = song;
            lastArtist = artist;
            final int token = fetchToken.incrementAndGet();
            final String fArtist = artist;
            final String fSong = song;
            new Thread(new Runnable() {
                @Override
                public void run() {
                    final LrclibClient.Result r =
                            LrclibClient.fetch(fArtist, fSong);
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            applyResult(r, token);
                        }
                    });
                }
            }).start();
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: maybeFetch: " + t);
        }
    }

    private void applyResult(LrclibClient.Result r, int token) {
        if (token != fetchToken.get()) return;
        if (r == null) {
            clearLyrics();
            return;
        }
        if (r.synced != null && !r.synced.isEmpty()) {
            lines = r.synced;
            startPolling();
            dispatchPlaybackUpdate();
        } else if (!TextUtils.isEmpty(r.plain)) {
            lines = Collections.emptyList();
            stopPolling();
            setLyricsText(r.plain.replace((char) 10, (char) 32));
        } else {
            clearLyrics();
        }
    }

    private void startPolling() {
        if (polling) return;
        polling = true;
        try {
            main.post(ticker);
        } catch (Throwable ignore) {
        }
    }

    private void stopPolling() {
        polling = false;
        try {
            main.removeCallbacks(ticker);
        } catch (Throwable ignore) {
        }
    }

    private void dispatchPlaybackUpdate() {
        if (controller == null || lines == null || lines.isEmpty()) return;
        PlaybackState state;
        try {
            state = controller.getPlaybackState();
        } catch (Throwable t) {
            return;
        }
        if (state == null) return;
        int ps = state.getState();
        if (ps != PlaybackState.STATE_PLAYING) {
            if (lastActive != -1) lastActive = -1;
            if (ps == PlaybackState.STATE_STOPPED || ps == PlaybackState.STATE_NONE
                    || ps == PlaybackState.STATE_ERROR) {
                stopPolling();
            }
            return;
        }
        if (!polling) startPolling();
        long pos = state.getPosition();
        long elapsed = SystemClock.elapsedRealtime() - state.getLastPositionUpdateTime();
        float speed = state.getPlaybackSpeed();
        pos += (long) (elapsed * speed);
        pos = Math.max(0L, pos);
        int idx = LrcParser.indexAt(lines, pos);
        if (idx != lastActive) {
            lastActive = idx;
            if (idx >= 0 && !TextUtils.isEmpty(lines.get(idx).text)) {
                setLyricsText(lines.get(idx).text);
            }
        }
    }

    private void clearLyrics() {
        stopPolling();
        lines = Collections.emptyList();
        plain = null;
        lastActive = -1;
        setLyricsText("");
    }

    // ---- title / artist cleaning (Lunaris pattern) -----------------------

    private static String cleanSong(String title) {
        if (TextUtils.isEmpty(title)) return title;
        String out = title;
        try {
            out = out.split("\\s+[\u2013\u2014\u2022|]\\s+")[0];
            out = out.replaceAll("(?i)\\s*[\\[(][^\\])]]*(?:feat|remaster|live|video|version|edit|acoustic|single|studio|mono|stereo|re-recorded)[^\\])]]*[\\])])", "");
            out = out.replaceAll("(?i)\\s+\\b(feat\\.?|featuring|ft\\.?|with)\\b.*", "");
        } catch (Throwable t) {
            return title;
        }
        return out.trim();
    }

    private static String cleanArtist(String artist) {
        if (TextUtils.isEmpty(artist)) return artist;
        try {
            return artist.split("(?i)\\s*[,/;]\\s*|\\s+\\b(feat\\.?|featuring|ft\\.?|and|&)\\b\\s+")[0].trim();
        } catch (Throwable t) {
            return artist;
        }
    }

    // ---- rendering on the reused clock view ------------------------------

    private static java.lang.reflect.Method mSetText;

    private void setLyricsText(String s) {
        if (clockView == null) return;
        try {
            if (mSetText == null) {
                mSetText = clockView.getClass().getMethod("setText", CharSequence.class);
                mSetText.setAccessible(true);
            }
            mSetText.invoke(clockView, s == null ? "" : s);
        } catch (Throwable ignore) {
        }
    }
}
