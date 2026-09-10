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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedBridge;

/**
 * Status bar live lyrics, modelled after Lunaris-AOSP LyricsFetcher.
 *
 * Proven API usage (see Lunaris frameworks_base):
 *  - MediaSessionManager is obtained via the typed overload
 *      context.getSystemService(MediaSessionManager.class),
 *      NOT via the MEDIA_SESSION_SERVICE string constant (which returns null
 *      to the untyped getSystemService(String) in practice).
 *  - Active-session listener + component name + main Handler are passed to
 *      addOnActiveSessionsChangedListener(.., component, handler).
 *  - Controller callbacks are registered with a Handler.
 *  - Playback position is interpolated with getPlaybackSpeed() like Lunaris.
 *
 * Rendering stays crash-safe: lyrics are written onto the existing clock view
 * via reflection; we never create or insert views, so System UI can never be
 * crashed into a boot loop by this module.
 */
public final class StatusLyricsController {

    private static final long TICK_MS = 120L;
    private static final long POLL_MS = 1000L;
    private static final Set<View> INSTALLED =
            Collections.synchronizedSet(Collections.newSetFromMap(
                    new IdentityHashMap<View, Boolean>()));

    private final View clockView;   // reused as the lyric text surface
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger fetchToken = new AtomicInteger();

    private MediaSessionManager sessionManager;
    private MediaController controller;
    private List<LyricLine> lines = Collections.emptyList();
    private boolean hasSynced;
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
            new StatusLyricsController(clockView).start();
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: install failed", t);
        }
    }

    private void start() {
        try {
            sessionManager = context.getSystemService(MediaSessionManager.class);
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: no MediaSessionManager", t);
            return;
        }
        if (sessionManager == null) {
            return;
        }
        try {
            // Match Lunaris: component name of the SystemUI notification listener
            // + a main-loop Handler, so the manager tracks us correctly.
            ComponentName component = new ComponentName(context,
                    "com.android.systemui.statusbar.phone.NotificationListener");
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, component, main);
            attachBestController(sessionManager.getActiveSessions(component));
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: media session setup failed", t);
        }
    }

    // ---- media session (Lunaris pattern) --------------------------------

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    try {
                        attachBestController(controllers);
                    } catch (Throwable ignore) {
                    }
                }
            };

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            dispatchPlaybackUpdate();
        }

        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            maybeFetchForCurrentMetadata();
        }

        @Override
        public void onSessionDestroyed() {
            detachController();
        }
    };

    private void attachBestController(List<MediaController> controllers) {
        MediaController best = null;
        if (controllers != null) {
            for (MediaController c : controllers) {
                int st = stateOf(c);
                if (st == PlaybackState.STATE_PLAYING) {
                    best = c;
                    break;
                }
                if (best == null && st != PlaybackState.STATE_NONE) {
                    best = c;
                }
            }
        }
        if (best == null) {
            detachController();
            return;
        }
        detachController();
        controller = best;
        try {
            controller.registerCallback(controllerCallback, main);
        } catch (Throwable ignore) {
        }
        maybeFetchForCurrentMetadata();
        dispatchPlaybackUpdate();
    }

    private void detachController() {
        if (controller != null) {
            try {
                controller.unregisterCallback(controllerCallback);
            } catch (Throwable ignore) {
            }
            controller = null;
        }
        stopPolling();
        clearLyrics();
    }

    private int stateOf(MediaController c) {
        try {
            PlaybackState st = c.getPlaybackState();
            return st == null ? PlaybackState.STATE_NONE : st.getState();
        } catch (Throwable t) {
            return PlaybackState.STATE_NONE;
        }
    }

    // ---- track / lyrics ---------------------------------------------------

    private void maybeFetchForCurrentMetadata() {
        if (controller == null) {
            clearLyrics();
            return;
        }
        MediaMetadata md;
        try {
            md = controller.getMetadata();
        } catch (Throwable t) {
            clearLyrics();
            return;
        }
        if (md == null) {
            clearLyrics();
            return;
        }
        String song = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        if (TextUtils.isEmpty(song) || TextUtils.isEmpty(artist)) {
            clearLyrics();
            return;
        }
        if (TextUtils.equals(song, lastSong) && TextUtils.equals(artist, lastArtist)) {
            return; // already fetching this track
        }
        lastSong = song;
        lastArtist = artist;
        lines = Collections.emptyList();
        plain = null;
        lastActive = -1;
        stopPolling();
        setLyricsText("♫ " + song);

        final int token = fetchToken.incrementAndGet();
        final String fSong = cleanSong(song);
        final String fArtist = cleanArtist(artist);
        executor.execute(new Runnable() {
            @Override
            public void run() {
                LrclibClient.Result r = LrclibClient.fetch(fArtist, fSong);
                if (fetchToken.get() != token) {
                    return; // stale result for an old track
                }
                main.post(new Runnable() {
                    @Override
                    public void run() {
                        try {
                            applyResult(r, token);
                        } catch (Throwable ignore) {
                        }
                    }
                });
            }
        });
    }

    private void applyResult(LrclibClient.Result r, int token) {
        if (token != fetchToken.get()) {
            return;
        }
        if (r == null) {
            clearLyrics();
            return;
        }
        if (r.synced != null && !r.synced.isEmpty()) {
            lines = r.synced;
            hasSynced = true;
            startPolling();
            dispatchPlaybackUpdate();
        } else if (!TextUtils.isEmpty(r.plain)) {
            hasSynced = false;
            lines = Collections.emptyList();
            stopPolling();
            setLyricsText(r.plain.replace("\n", "   "));
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
        if (controller == null || lines == null || lines.isEmpty()) {
            return;
        }
        PlaybackState state;
        try {
            state = controller.getPlaybackState();
        } catch (Throwable t) {
            return;
        }
        if (state == null) {
            return;
        }
        int ps = state.getState();
        if (ps != PlaybackState.STATE_PLAYING) {
            if (lastActive != -1) {
                lastActive = -1;
                // leave last shown line visible while paused
            }
            if (ps == PlaybackState.STATE_STOPPED || ps == PlaybackState.STATE_NONE
                    || ps == PlaybackState.STATE_ERROR) {
                stopPolling();
            }
            return;
        }
        if (!polling) {
            startPolling();
        }
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
        setLyricsText(""); // empty -> the clock updater shows the time
    }

    // ---- title / artist cleaning (Lunaris pattern) -----------------------

    private static String cleanSong(String title) {
        if (TextUtils.isEmpty(title)) return title;
        String out = "";
        try {
            out = title.split("\\s+[\u2013\u2014\u2022|]\\s+")[0];
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
        if (clockView == null) {
            return;
        }
        try {
            if (mSetText == null) {
                mSetText = clockView.getClass().getMethod("setText", CharSequence.class);
                mSetText.setAccessible(true);
            }
            mSetText.invoke(clockView, s == null ? "" : s);
        } catch (Throwable ignore) {
            // if the clock has no text setter, degrade to a no-op, never crash
        }
    }
}
