package com.term.statuslyrics;

/** A single timed lyric line. ts is the start time in milliseconds. */
public final class LyricLine {
    public final long startMs;
    public final String text;

    public LyricLine(long startMs, String text) {
        this.startMs = startMs;
        this.text = text;
    }
}