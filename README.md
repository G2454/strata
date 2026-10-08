# Strata — music player for Android

A fast, local music player that pairs foobar2000's power features with Samsung Music's touch-friendly layout.
Native Kotlin + Jetpack Compose, playback on Media3/ExoPlayer, with Strata's own 32-bit float DSP chain.

## Get the APK without installing anything (GitHub builds it for you)

1. Create a free account at github.com if you don't have one.
2. Create a new **empty** repository (any name, e.g. `strata`).
3. On the repository page choose **uploading an existing file**, drag in **everything inside this folder**
   (including the hidden `.github` folder — on Windows enable "Show hidden items" first), then press **Commit changes**.
4. Open the **Actions** tab. The *Build APK* workflow starts by itself and takes about 5–8 minutes.
5. When it shows a green check, open the run and download **Strata-apk** under *Artifacts*.
   Unzip it and copy `Strata.apk` to your phone.
6. On the phone, open the APK and allow "Install unknown apps" for your file manager when Android asks.

`Strata.apk` is the optimized release build. `Strata-debug.apk` is the same app without optimization, handy if you hit a bug.

## Build it yourself (optional)

Install Android Studio, open this folder, and press Run. Or from a terminal with the Android SDK installed:

```
./gradlew assembleRelease
```

The APK lands in `app/build/outputs/apk/release/`.

## What's inside

**Library** — Tracks, Albums, Artists, Genres, Folders, Composers. Sort by title, artist, album, date added, plays or length.
Comfortable rows or a dense foobar-style column view (#, title, format, time). Album, artist, genre, folder and composer pages.
Format badges read from the files themselves (e.g. `FLAC 24/96`, `MP3 320`).

**Now playing** — artwork, synced lyrics (embedded LYRICS/USLT tags or a sidecar `.lrc`; tap a line to seek),
a live visualizer (32-band spectrum, oscilloscope, L/R peak meter fed from the real audio), and a technical info panel.
The seek bar is a real waveform decoded from the file. Album art colors tint the whole app.

**Playback** — all seven foobar2000 playback orders (Default, Repeat playlist, Repeat track, Random, Shuffle tracks / albums / folders),
a separate playback queue that plays first, gapless playback, sleep timer (minutes or end of track), speed 0.5–2× with keep-pitch,
skip silence, fade between tracks, pause on headphone disconnect, preferred output device,
background playback with notification, lock-screen and Bluetooth controls.

**Sound (DSP)** — 10-band graphic EQ with presets and preamp, ReplayGain (track / album / smart, preamps, clipping prevention)
read from your tags, stereo widening, mono downmix, balance, advanced limiter, and a reorderable DSP chain.

**Playlists** — Favorites, Most played, Recently played, Recently added, your own playlists (reorder, remove, rename, delete),
and auto playlists that fill themselves from a query.

**Search** — instant search plus a query language: `artist:`, `genre:jazz|ambient`, `format:flac`, `bits>=24`, `rate:96`,
`rating>=4`, `plays>10`, `year:2024`, `length>360`, `fav:1`, `-genre:podcast`, `"quoted phrases"`.

**Properties** — metadata, technical details (sample rate, bit depth, samples, ReplayGain values, tag type), and play statistics with star ratings.

## Honest limits of this first version

- **Tag edits** are saved in Strata's own library and show everywhere in the app; the audio files themselves are not rewritten.
- **ReplayGain** values are read from existing tags. Strata doesn't scan loudness yet (foobar2000 or MusicBrainz Picard can write the tags).
- **Fade** fades out and in at track changes; it is not an overlapping crossfade.
- **Converter** and **bit-perfect / exclusive USB output** are not included. Android doesn't let apps bypass the system mixer on most phones.
- Sidecar `.lrc` files can only be read where Android allows it; embedded lyrics always work.

## Project layout

```
app/src/main/java/com/strata/player/
  AppModel.kt          app state, navigation, playlists, settings
  audio/Engine.kt      ExoPlayer, playback orders, queue, sleep timer, fades
  audio/Dsp.kt         EQ, ReplayGain, widening, limiter, FFT analyzer (pure Kotlin, unit-tested)
  audio/PlaybackService.kt   background playback + media notification
  data/                MediaStore scan, FLAC/ID3 tag parser, LRC parser, query language, JSON storage
  ui/                  Compose screens
```

Fonts: Bricolage Grotesque and Geist, under the SIL Open Font License (see `licenses/`).
# strata
