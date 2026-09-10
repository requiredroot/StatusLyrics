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
- Falls back to showing the plain lyrics when no synced version exists.

### Crash-safe by design (no boot loops)

Earlier builds could crash System UI and get the phone stuck on
"Phone is starting". Version 1.1.0 is deliberately crash-safe:

- **No process-wide hooks.** We only hook the `Clock` class (its constructor
  and `onAttachedToWindow`) — never `TextView` globally.
- **No view-tree mutation.** We never create or insert new views into the
  status bar; we render lyrics onto the existing clock view itself (via
  reflection, so it degrades to a no-op instead of crashing if the clock has
  no text setter).
- **Everything is guarded.** Install and every tick/callback are wrapped so a
  failure can never propagate an exception into System UI's method chain.
- If the `Clock` class isn't found, the module disables itself cleanly.

## Build

Targets **Android 16 (API 36)** (`compileSdk 36`, `targetSdk 36`, `minSdk 26`).
Requires an Android SDK.

```bash
export ANDROID_HOME=$HOME/android-sdk   # wherever your SDK lives
./gradlew assembleDebug
```

APKs:
- Debug: `app/build/outputs/apk/debug/app-debug.apk`
- Release (unsigned): `app/build/outputs/apk/release/app-release-unsigned.apk`

### Building with GitHub Actions

A workflow at `.github/workflows/build.yml` builds the **debug** and **release**
(unsigned) APKs on every push to `main`, every pull request, and on manual
`workflow_dispatch` runs. It:

1. Sets up JDK 17 + the Android SDK with `platforms;android-36`.
2. Runs `assembleDebug` and `assembleRelease`.
3. Uploads both APKs as build artifacts.

Pushing a tag named `v*` (e.g. `v1.0.0`) additionally creates a **GitHub
Release** attached with both APKs.

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
