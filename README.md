# StatusLyrics

An **Xposed / LSPosed** module that shows the live lyrics of the currently
playing song in the **status bar**, replacing the clock.

When a song starts playing, StatusLyrics picks up the current track through the
Android **MediaSession** (works across Spotify, YouTube Music, Poweramp, etc.),
looks the track up on [lrclib.net](https://lrclib.net), hides the status bar
clock and displays the current lyric line (or scrolling lyrics for unsynced
tracks) in its place.

This is inspired by the always-on lyrics view seen on LunarOS / AOSP mods.

## Features

- `scope = com.android.systemui` — only hooks the System UI process.
- Reads the active/playing media session (`MediaSessionManager`).
- Queries `lrclib.net/api/search` with `track_name`, `artist_name`, `duration`.
- Parses **LRC** synced lyrics and follows playback position (ticker driven by
  the media session's `PlaybackState`).
- Falls back to scrolling the plain lyrics when no synced version exists.
- Hides the clock and injects a single-line marquee `TextView` in its place.
- Robust hooking: primary hook targets
  `com.android.systemui.statusbar.policy.Clock`; a fallback hooks any
  `TextView` whose resource id matches `R.id.clock`.

## Build

Requires an Android SDK (this repo uses `compileSdk 34`).

```bash
export ANDROID_HOME=$HOME/android-sdk   # wherever your SDK lives
./gradlew assembleDebug
```

APK: `app/build/outputs/apk/debug/app-debug.apk`

The Xposed API (`de.robv.android.xposed.*`) is provided as a **compile-only
stub jar** (`app/libs/xposed-api.jar`, source in `xposed-stubs/`) because the
JitPack-hosted artifact is no longer anonymously resolvable. At runtime the
real implementation comes from the Xposed framework. Xposed is intentionally
**not** bundled into the APK.

## Installation

1. Build (or grab a release) and install the APK.
2. In **LSPosed**: enable the **StatusLyrics** module.
3. Set the scope to **System UI** (**com.android.systemui**).
4. Reboot.

After rebooting, play a song — the status bar clock area now shows live lyrics.

## Troubleshooting

- Lyrics won't appear if the app doesn't publish a MediaSession, or when no
  metadata (title/artist) is available.
- Some ROMs rename `Clock`; the fallback hook (resource id `R.id.clock`)
  keeps things working in most cases. Logs are written via `XposedBridge.log`
  (check logcat in the Xposed manager).

## License

MIT
