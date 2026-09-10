package com.term.statuslyrics;

import android.content.Context;
import android.graphics.Color;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;

import de.robv.android.xposed.XposedBridge;

/**
 * Injected into the System UI process. Hides the status bar clock and shows a
 * marquee TextView that follows the current lyric line of the active media
 * session, fetched from lrclib.net.
 */
public final class StatusLyricsController {

    private static final long TICK_MS = 160L;

    private static final java.util.Set<View> INSTALLED =
            java.util.Collections.synchronizedSet(
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<View, Boolean>()));

    private final TextView clockView;
    private final TextView lyricsView;
    private final ViewGroup parent;
    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private final AtomicInteger fetchToken = new AtomicInteger();

    private MediaSessionManager sessionManager;
    private MediaController controller;
    private List<LyricLine> lines;
    private boolean hasSynced;
    private String plainText;

    private final Runnable ticker = new Runnable() {
        @Override
        public void run() {
            updateLine();
            main.postDelayed(this, TICK_MS);
        }
    };

    private StatusLyricsController(TextView clockView) {
        this.clockView = clockView;
        this.parent = (ViewGroup) clockView.getParent();
        this.context = clockView.getContext();
        this.lyricsView = buildLyricsView();
    }

    public static synchronized void install(TextView clockView) {
        if (clockView == null || clockView.getParent() == null) {
            return;
        }
        if (!(clockView.getParent() instanceof ViewGroup)) {
            return;
        }
        if (INSTALLED.contains(clockView)) {
            return; // already handled for this clock view
        }
        INSTALLED.add(clockView);
        StatusLyricsController c = new StatusLyricsController(clockView);
        try {
            c.start();
        } catch (Throwable t) {
            XposedBridge.log("StatusLyrics: install failed", t);
        }
    }

    private void start() {
        int idx = parent.indexOfChild(clockView);
        if (idx >= 0) {
            parent.addView(lyricsView, idx);
        } else {
            parent.addView(lyricsView);
        }
        clockView.setVisibility(View.GONE);

        sessionManager = (MediaSessionManager) context
                .getSystemService(Context.MEDIA_SESSION_SERVICE);
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

    private TextView buildLyricsView() {
        TextView tv = new TextView(context);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTextColor(Color.WHITE);
        tv.setShadowLayer(5f, 0f, 1f, Color.BLACK);
        tv.setGravity(Gravity.CENTER_VERTICAL);
        tv.setSingleLine(true);
        tv.setEllipsize(TextUtils.TruncateAt.MARQUEE);
        tv.setMarqueeRepeatLimit(-1);
        tv.setFocusable(true);
        tv.setFocusableInTouchMode(true);
        tv.setSelected(true);
        tv.setMaxWidth(dp(240));
        tv.setVisibility(View.GONE);
        return tv;
    }

    private int dp(int v) {
        return (int) (v * context.getResources().getDisplayMetrics().density + 0.5f);
    }
// ---- Media sessions -----------------------------------------------------

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsListener =
            new MediaSessionManager.OnActiveSessionsChangedListener() {
                @Override
                public void onActiveSessionsChanged(List<MediaController> controllers) {
                    onSessionsChanged(controllers);
                }
            };

    private void onSessionsChanged(List<MediaController> controllers) {
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
                if (md == null) continue;
                String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
                if (TextUtils.isEmpty(title)) continue;
                int state = stateOf(c);
                if (state == PlaybackState.STATE_PLAYING) {
                    controller = c;
                    break;
                }
                if (fallback == null) fallback = c;
            }
            if (controller == null) controller = fallback;
        }
        if (controller == null) {
            hideLyrics();
            return;
        }
        try {
            controller.registerCallback(controllerCallback, main);
        } catch (Throwable ignore) {
        }
        updateTrack();
    }

    private int stateOf(MediaController c) {
        PlaybackState st = c.getPlaybackState();
        return st == null ? PlaybackState.STATE_NONE : st.getState();
    }

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

    // ---- Track / lyrics --------------------------------------------------

    private void updateTrack() {
        if (controller == null) {
            hideLyrics();
            return;
        }
        MediaMetadata md = controller.getMetadata();
        if (md == null) {
            hideLyrics();
            return;
        }
        String title = md.getString(MediaMetadata.METADATA_KEY_TITLE);
        if (TextUtils.isEmpty(title)) {
            hideLyrics();
            return;
        }
        String artist = md.getString(MediaMetadata.METADATA_KEY_ARTIST);
        long duration = md.getLong(MediaMetadata.METADATA_KEY_DURATION);

        // Reset state for the new track.
        lines = null;
        hasSynced = false;
        plainText = null;
        main.removeCallbacks(ticker);

        int token = fetchToken.incrementAndGet();
        showText("♫ " + title);

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
                        applyResult(r);
                    }
                });
            }
        });
    }

    private void applyResult(LrclibClient.Result r) {
        if (r == null) {
            hideLyrics();
            return;
        }
        plainText = r.plain;
        if (r.synced != null && !r.synced.isEmpty()) {
            lines = r.synced;
            hasSynced = true;
            lyricsView.setVisibility(View.VISIBLE);
            updateLine();
            main.removeCallbacks(ticker);
            main.post(ticker);
        } else if (!TextUtils.isEmpty(plainText)) {
            // Unsynced lyrics: scroll them as one long marquee line.
            hasSynced = false;
            lines = null;
            String joined = plainText.replace("\n", "   ");
            lyricsView.setVisibility(View.VISIBLE);
            lyricsView.setText(joined);
            lyricsView.setSelected(true);
        } else {
            hideLyrics();
        }
    }

    private void updateLine() {
        if (controller == null || !hasSynced || lines == null || lines.isEmpty()) {
            return;
        }
        if (stateOf(controller) != PlaybackState.STATE_PLAYING) {
            return; // keep last shown line while paused
        }
        long pos = positionMs();
        int idx = LrcParser.indexAt(lines, pos);
        if (idx >= 0) {
            String text = lines.get(idx).text;
            if (!TextUtils.isEmpty(text)) {
                lyricsView.setText(text);
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

    private void showText(String s) {
        lyricsView.setText(s);
        lyricsView.setVisibility(View.VISIBLE);
    }

    private void hideLyrics() {
        main.removeCallbacks(ticker);
        lyricsView.setVisibility(View.GONE);
    }
}