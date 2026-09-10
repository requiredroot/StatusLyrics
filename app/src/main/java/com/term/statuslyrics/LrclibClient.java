package com.term.statuslyrics;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONException;

/**
 * Minimal lrclib.net client. Returns the best synced (or plain) lyrics found
 * for a title / artist / duration combination.
 *
 * See https://lrclib.net/docs — GET /api/search query parameters:
 *   track_name, artist_name, album_name, duration.
 */
public final class LrclibClient {

    private static final String ENDPOINT_PARAMS_MIN = "https://lrclib.net/api/search";

    private LrclibClient() {
    }

    /** Result holds both parsed synced lines and (optionally) plain lyrics. */
    public static final class Result {
        public final List<LyricLine> synced;
        public final String plain; // may be null

        Result(List<LyricLine> synced, String plain) {
            this.synced = synced;
            this.plain = plain;
        }
    }

    public static Result fetch(String title, String artist, long durationMs) {
        try {
            StringBuilder url = new StringBuilder(ENDPOINT_PARAMS_MIN).append("?track_name=")
                    .append(enc(title));
            if (artist != null && !artist.isEmpty()) {
                url.append("&artist_name=").append(enc(artist));
            }
            if (durationMs > 0) {
                url.append("&duration=").append(durationMs / 1000L);
            }

            HttpURLConnection conn = (HttpURLConnection) new URL(url.toString()).openConnection();
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(9000);
            conn.setRequestProperty("Accept", "application/json");
            conn.setRequestProperty("User-Agent",
                    "StatusLyrics/1.0 (https://github.com/requiredroot/StatusLyrics)");

            int code = conn.getResponseCode();
            InputStream in = (code >= 200 && code < 300) ? conn.getInputStream()
                    : conn.getErrorStream();
            if (in == null) {
                return new Result(new ArrayList<LyricLine>(), null);
            }
            String body = readAll(in);
            in.close();
            conn.disconnect();
            if (body.isEmpty()) {
                return new Result(new ArrayList<LyricLine>(), null);
            }

            JSONArray arr = new JSONArray(body);
            String bestSynced = null;
            String bestPlain = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null) continue;
                if (obj.optBoolean("instrumental", false)) continue;
                String synced = obj.optString("syncedLyrics");
                if (bestSynced == null && nonEmpty(synced)) {
                    bestSynced = synced;
                }
                if (bestPlain == null) {
                    bestPlain = obj.optString("plainLyrics");
                }
            }
            List<LyricLine> lines = linesFrom(bestSynced, bestPlain);
            return new Result(lines, bestPlain);
        } catch (Throwable t) {
            return new Result(new ArrayList<LyricLine>(), null);
        }
    }

    private static List<LyricLine> linesFrom(String synced, String plain) {
        List<LyricLine> parsed = LrcParser.parse(synced);
        if (!parsed.isEmpty()) {
            return parsed;
        }
        // Unsynced: use plain lyrics, falling back to unscrolled shows.
        if (nonEmpty(plain)) {
            return LrcParser.parse(plain);
        }
        return new ArrayList<LyricLine>();
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String enc(String s) {
        try {
            return URLEncoder.encode(s == null ? "" : s, "UTF-8");
        } catch (Exception e) {
            return "";
        }
    }

    private static String readAll(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(
                new InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
}