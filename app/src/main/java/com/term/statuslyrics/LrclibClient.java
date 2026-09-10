package com.term.statuslyrics;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Minimal lrclib.net client. Mirrors Lunaris-AOSP LyricsFetcher exactly.
 * The free-form "q" query is used against /api/search, and title/artist are
 * expected to be already cleaned by the caller.
 * Returns the best synced (or plain) lyrics found.
 */
public final class LrclibClient {

    private static final String LRCLIB_URL = "https://lrclib.net/api/search?q=";

    private LrclibClient() {
    }

    /** Result holds parsed synced lines and (optionally) plain lyrics. */
    public static final class Result {
        public final List<LyricLine> synced;
        public final String plain; // may be null

        Result(List<LyricLine> synced, String plain) {
            this.synced = synced;
            this.plain = plain;
        }
    }

    /** @param artist and @param song are expected pre-cleaned. */
    public static Result fetch(String artist, String song) {
        try {
            String query = URLEncoder.encode(
                    (artist == null ? "" : artist) + " " + (song == null ? "" : song),
                    "UTF-8");
            URL url = new URL(LRCLIB_URL + query);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(5000);
            conn.setReadTimeout(5000);
            conn.setRequestProperty("User-Agent",
                    "StatusLyrics/1.0 (https://github.com/requiredroot/StatusLyrics)");

            int code = conn.getResponseCode();
            if (code != 200) {
                conn.disconnect();
                return new Result(new ArrayList<>(), null);
            }

            String body = readAll(conn.getInputStream());
            conn.disconnect();
            if (body.isEmpty()) {
                return new Result(new ArrayList<>(), null);
            }

            JSONArray arr = new JSONArray(body);
            String bestSynced = null;
            String bestPlain = null;
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.optJSONObject(i);
                if (obj == null || obj.optBoolean("instrumental", false)) continue;
                if (bestSynced == null && nonEmpty(obj.optString("syncedLyrics"))) {
                    bestSynced = obj.optString("syncedLyrics");
                }
                if (bestPlain == null) {
                    bestPlain = obj.optString("plainLyrics");
                }
            }
            List<LyricLine> lines = LrcParser.parse(bestSynced);
            return new Result(lines, bestPlain);
        } catch (Throwable t) {
            return new Result(new ArrayList<>(), null);
        }
    }

    private static boolean nonEmpty(String s) {
        return s != null && !s.trim().isEmpty();
    }

    private static String readAll(java.io.InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        BufferedReader r = new BufferedReader(
                new java.io.InputStreamReader(in, StandardCharsets.UTF_8));
        String line;
        while ((line = r.readLine()) != null) {
            sb.append(line);
        }
        return sb.toString();
    }
}
