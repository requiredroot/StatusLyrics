package com.term.statuslyrics;

import android.content.Context;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
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
 * Drives live lyrics on the status bar CLOCK view itself.
 *
 * Deliberately crash-safe:
 *  - We reuse the passed view as the lyric surface; we NEVER create new views
 *    and NEVER touch the status-bar container/view tree. This avoids any chance
 *    of re-entrancy, concurrent modification, or a recursive hook that would
 *    crash System UI (which would trap the phone on "Phone is starting").
 *  - Every entry point is wrapped so nothing ever propagates an exception into
 *    the hooked method chain.
 */
public final class StatusLyricsController {

    private static final long TICK_MS = 120L;
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
    private List<LyricLine> lines;
    private boolean hasSynced;

    private static java.lang.reflect.Method mSetText;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            try {
                updateLine();
            } catch (Throwable ignore) {
            }
            try {
                main.postDelayed(this, TICK_MS);
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
            return; // not attached yet; the attach hook will retry
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
            sessionManager = (MediaSessionManager) context
                    .getSystemService(Context.MEDIA_SESSION_SERVICE);
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: no media session service", t);
            return;
        }
        if (sessionManager == null) {
            return;
        }
        try {
            sessionManager.addOnActiveSessionsChangedListener(sessionsListener, null);
            onSessionsChanged(sessionManager.getActiveSessions(null));
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: media session setup failed", t);
        }
    }

    // ---- media session ----------------------------------------------------

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    try {
                        onSessionsChanged(controllers);
                    } catch (Throwable ignore) {
                    }
                }
            };

    private final MediaController.Callback controllerCallback = new MediaController.Callback() {
        @Override
        public void onMetadataChanged(MediaMetadata metadata) {
            updateTrack();
        }

        @Override
        public void onPlaybackStateChanged(PlaybackState state) {
            updateLine();
        }
    };

    private void onSessionsChanged(List<MediaController> controllers) {
        try {
            if (controller != null) {
                try {
                    controller.unregisterCallback(controllerCallback);
                } catch (Throwable ignore) {
                }
                controller = null;
            }
            if (controllers != null) {
                MediaController fallback = null;
                for (MediaController c : controllers) {
                    MediaMetadata md = c.getMetadata();
                    if (md == null) {
                        continue;
                    }
                    if (TextUtils.isEmpty(md.getString(MediaMetadata.METADATA_KEY_TITLE))) {
                        continue;
                    }
                    int st = stateOf(c);
                    if (st == PlaybackState.STATE_PLAYING) {
                        controller = c;
                        break;
                    }
                    if (fallback == null) {
                        fallback = c;
                    }
                }
                if (controller == null) {
                    controller = fallback;
                }
            }
            if (controller == null) {
                clearLyrics();
                return;
            }
            try {
                controller.registerCallback(controllerCallback, main);
            } catch (Throwable ignore) {
            }
            updateTrack();
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: sessions changed failed", t);
        }
    }

    private int stateOf(MediaController c) {
        PlaybackState st = null;
        try {
            st = c.getPlaybackState();
        } catch (Throwable ignore) {
        }
        return st == null ? PlaybackState.STATE_NONE : st.getState();
    }

    // ---- track / lyrics ---------------------------------------------------

    private void updateTrack() {
        try {
            if (controller == null) {
                clearLyrics();
                return;
            }
            MediaMetadata md = controller.getMetadata();
            if (md == null) {
                clearLyrics();
                return;
            }
            String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
            if (TextUtils.isEmpty(title)) {
                clearLyrics();
                return;
            }
            String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
            long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);

            clearLyrics();
            int token = fetchToken.incrementAndGet();
            setLyricsText("♫ " + title);

            final String fTitle = title;
            final String fArtist = artist;
            final long fDuration = duration;
            executor.execute(new Runnable() {
                @Override
                public void run() {
                    LrclibClient.Result r = LrclibClient.fetch(fTitle, fArtist, fDuration);
                    if (fetchToken.get() != token) {
                        return; // stale result for an old track
                    }
                    main.post(new Runnable() {
                        @Override
                        public void run() {
                            try {
                                applyResult(r);
                            } catch (Throwable ignore) {
                            }
                        }
                    });
                }
            });
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: updateTrack failed", t);
        }
    }

    private void applyResult(LrclibClient.Result r) {
        if (r == null) {
            clearLyrics();
            return;
        }
        if (r.synced != null && !r.synced.isEmpty()) {
            lines = r.synced;
            hasSynced = true;
            updateLine();
            try {
                main.removeCallbacks(ticker);
                main.post(ticker);
            } catch (Throwable ignore) {
            }
        } else if (!TextUtils.isEmpty(r.plain)) {
            hasSynced = false;
            lines = null;
            setLyricsText(r.plain.replace("\n", "   "));
        } else {
            clearLyrics();
        }
    }

    private void updateLine() {
        if (controller == null || !hasSynced || lines == null || lines.isEmpty()) {
            return;
        }
        if (stateOf(controller) != PlaybackState.STATE_PLAYING) {
            return; // keep last shown line while paused
        }
        int idx = LrcParser.indexAt(lines, positionMs());
        if (idx >= 0) {
            String text = lines.get(idx).text;
            if (!TextUtils.isEmpty(text)) {
                setLyricsText(text);
            }
        }
    }

    private long positionMs() {
        try {
            PlaybackState st = controller.getPlaybackState();
            return st == null ? 0L : st.getPosition();
        } catch (Throwable t) {
            return 0L;
        }
    }

    // ---- rendering on the reused clock view ------------------------------

    private void setLyricsText(String s) {
        if (clockView == null) {
            return;
        }
        try {
            if (mSetText == null) {
                mSetText = clockView.getClass().getMethod("setText", CharSequence.class);
                mSetText.setAccessible(true);
            }
            String t = s == null ? "" : s;
            mSetText.invoke(clockView, t);
        } catch (Throwable ignore) {
            // If the clock isn't TextView-based (or reflection fails) we simply
            // do nothing — under no circumstances do we crash System UI.
        }
    }

    private void clearLyrics() {
        try {
            main.removeCallbacks(ticker);
        } catch (Throwable ignore) {
        }
        lines = null;
        hasSynced = false;
        setLyricsText(""); // empty text -> the clock updater shows the time
    }
}
