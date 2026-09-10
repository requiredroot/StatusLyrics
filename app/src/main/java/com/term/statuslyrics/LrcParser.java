package com.term.statuslyrics;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Very small zero-dependency LRC (synced lyrics) parser. */
public final class LrcParser {

    // Matches [mm:ss] or [mm:ss.xx] / [mm:ss:xx] style stamps.
    private static final Pattern TIMESTAMP =
            Pattern.compile("\\[(\\d{1,3}):(\\d{2})(?:(?:[.:])(\\d{1,3}))?\\]");

    private LrcParser() {
    }

    /**
     * Parse an LRC document (newline separated lines). Lines with multiple
     * timestamps produce one {@link LyricLine} entry per timestamp. Empty
     * timestamps / metadata lines (e.g. [ar:...]) are skipped.
     */
    public static List<LyricLine> parse(String lrc) {
        List<LyricLine> out = new ArrayList<>();
        if (lrc == null || lrc.isEmpty()) {
            return out;
        }
        for (String rawLine : lrc.split("\\r?\\n")) {
            if (rawLine.trim().isEmpty()) {
                continue;
            }
            int lastBracket = rawLine.lastIndexOf(']');
            String text = lastBracket >= 0 ? rawLine.substring(lastBracket + 1).trim()
                    : rawLine.trim();
            Matcher m = TIMESTAMP.matcher(rawLine);
            boolean any = false;
            while (m.find()) {
                long ms = toMs(m.group(1), m.group(2), m.group(3));
                out.add(new LyricLine(ms, text));
                any = true;
            }
            if (!any && !text.isEmpty()) {
                // Unsynchronised stray line; keep it as an untimed entry at the start.
                out.add(new LyricLine(0L, text));
            }
        }
        return out;
    }

    private static long toMs(String minutes, String seconds, String fraction) {
        long ms = Long.parseLong(minutes) * 60_000L + Long.parseLong(seconds) * 1_000L;
        if (fraction != null) {
            // Treat fraction as hundredths (.xx) or milliseconds depending on length.
            if (fraction.length() == 3) {
                ms += Integer.parseInt(fraction);
            } else if (fraction.length() == 2) {
                ms += Integer.parseInt(fraction) * 10L; // centiseconds -> ms
            } else {
                ms += Integer.parseInt(fraction) * 100L;
            }
        }
        return ms;
    }

    /** Find the last line whose start time is at or before the given position. */
    public static int indexAt(List<LyricLine> lines, long positionMs) {
        if (lines == null || lines.isEmpty()) {
            return -1;
        }
        int lo = 0, hi = lines.size() - 1, ans = -1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (lines.get(mid).startMs <= positionMs) {
                ans = mid;
                lo = mid + 1;
            } else {
                hi = mid - 1;
            }
        }
        return ans;
    }
}