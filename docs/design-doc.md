# WHRRAH Android — Design Doc

## 0. Context for whoever picks this up

WHRRAH ("We Have RaceRender At Home") is an existing **Windows desktop app**
(PyQt6, single file at `source/WHRRAH.py`, ~4000 lines — see
https://github.com/LUNK333/WHRRAH) that overlays AiM motorsports telemetry
data (lap timer, numeric readouts, bar graphs, GPS track map) onto race
footage, synced via a timeline offset, and exports a composited or
green-screen video.

This is **not a port** — Qt Widgets, the bundled Windows-only `.xrk`-parsing
DLL, and `ffmpeg` via `subprocess`+PATH all have no Android equivalent. This
doc scopes a **from-scratch native Android app** that reproduces the desktop
app's core workflow, reusing only the *concepts* (data model, layout JSON
schema, widget types) — no code.

The person commissioning this has **never built an Android app before**.
Assume nothing about their toolchain familiarity; the setup section is
written for a first-time Android dev.

**Direction change (2026-10-25):** the app is no longer just a viewer for
AiM logs. The goal is to **replace the AiM Solo entirely** — the phone
becomes the data logger, fed by its own sensors, and does the video
overlay/export on-device too. Think SoloStorm, not RaceRender. Sections
below that assume AiM is the data source (§3 libxrk, §8 Data Wizard's
RaceStudio share flow) are now secondary: `.xrk` import stays useful for
testing against known-good AiM data, but it's not the main path.

## 1. Goal / non-goals

**Goal:** a phone app that logs a track session from its own sensors and
turns it into an overlaid video, with no AiM hardware in the car.

Data sources (all over Bluetooth to the phone):
- **RaceBox Mini** (BLE) — GPS position/speed + IMU. Replaces the Solo's
  GPS; it's the basis for lap timing and the track map.
- **ESP32 CAN listener** (BLE) — listen-only on the E46 PT-CAN; engine and
  chassis channels (RPM, throttle, wheel speeds, steering, temps…).
- **ELM327** (Bluetooth classic, EDIABAS) — brake pressure, which isn't on
  the CAN bus.
- **DJI Osmo Action 4** (BLE + WiFi) — gyro track for syncing video to
  data, pulled over WiFi without downloading the video (see
  `WHRRAH/wifi_gyro_sync/HANDOFF.md`).

**v1 — logger + overlay:**
1. Connect to all sources, show live status (connected, sample rate, last
   seen) and record a session: every channel on one common timebase (phone
   clock, anchored to GPS time).
2. Lap timing from RaceBox GPS: start/finish (and optional split) lines,
   saved per track; laps split automatically in the recorded session.
3. Save sessions on-device; export them as CSV.
4. Import the session's video (from DJI Mimo / device storage) and sync it
   to the data by gyro cross-correlation, with a manual offset fallback.
5. Overlay widgets (Lap Timer, Numeric Display, Bar Graph, Track Map) on a
   canvas, positioned/scaled by touch; scrub a synced preview.
6. Export a composited MP4 using on-device hardware encoding — no bundled
   `ffmpeg` binary.
7. Save/load overlay layouts (JSON), schema-compatible in spirit with
   desktop WHRRAH's layout files (exact byte-compatibility is not a goal).

**v2 — on-track features:**
- Live in-car display: current/last/best lap, predictive delta, key
  channels in big glanceable text.
- Lap comparison (overlay two laps' traces/speed vs distance).
- Track Map widget polish, auto track detection from GPS.

**Still supported, but secondary:**
- Importing AiM `.xrk`/`.xrz` (libxrk) and CSV logs — for testing the
  overlay/export pipeline against known-good data and for old sessions.

**Explicitly out of scope for v1** (desktop-only or later milestones):
- The desktop Data Wizard's **batch folder-scanning** auto-match (point at a
  folder of AiM sessions + a folder of videos, score every plausible
  pairing). See §8 — Android gets a single-pair version of this instead,
  scoped down for platform constraints, not deferred to v2.
- Green-screen-only export, multi-lap selection UI, layout auto-load by
  filename convention.
- Any Windows-DLL-based `.xrk` path — Android uses libxrk (Rust) instead,
  see §3.

## 2. High-level architecture

- **Language:** Kotlin, single-module Android app to start (split into
  `:app` / `:core-data` / `:core-media` modules only if it grows unwieldy).
- **UI:** Jetpack Compose. (Reasoning: it's the current standard, and a
  drag/resize/select canvas is easier to reason about with Compose's
  `Modifier.pointerInput` than with legacy View XML + custom View subclasses.)
- **Min SDK:** 26 (Android 8.0) — `MediaCodec` async API and modern storage
  APIs are solid by then; bump later if a specific API needs it.
- **Target/compile SDK:** latest stable at build time (35 as of writing).
- **Video decode/encode/mux:** Jetpack **Media3** (`androidx.media3:media3-*`)
  wrapping `MediaCodec`/`MediaMuxer` — see §4. Not ffmpeg.
- **Telemetry parsing:**
  - CSV: plain Kotlin parsing, mirrors desktop's header-row + `Beacon
    Markers` metadata row convention (see desktop `README.md` "Importing
    data" section for the exact format) — no porting decision needed here,
    just implement against that spec.
  - `.xrk`/`.xrz`: via **libxrk** (https://github.com/m3rlin45/libxrk), a
    pure-Rust, MIT-licensed, clean-room reverse-engineered parser with no
    AiM-DLL dependency. See §3 for integration strategy — this is the one
    piece of real R&D risk in the whole project and should be spiked first.
- **File access:** Storage Access Framework (`ACTION_OPEN_DOCUMENT` /
  `ACTION_CREATE_DOCUMENT`) and `MediaStore` for writing the exported video
  to the gallery. No raw filesystem path assumptions — Android scoped
  storage forbids it.
- **Persistence:** layouts as JSON files via SAF (user picks where to save,
  same mental model as desktop); app preferences (last-used folder, etc.)
  via `DataStore`, not `QSettings` (no equivalent — use the AndroidX
  Preferences DataStore).

## 3. The libxrk integration (done as a spike in M1; now secondary — testing with AiM data only)

Two viable approaches, in order of recommended effort:

**Option A — Rust via JNI (recommended).**
1. Add `aarch64-linux-android` and `armv7-linux-androideabi` targets to the
   Rust toolchain (`rustup target add ...`).
2. Use `cargo-ndk` to cross-compile `crates/libxrk` (the pure-Rust core, not
   the Cython binding) into `.so` libraries for those ABIs.
3. Write a thin JNI shim crate (or use `uniffi-rs` to generate Kotlin
   bindings automatically — less hand-written glue, recommended if the
   libxrk public API is small) exposing: open file → channel list → channel
   samples (timestamps + values) → lap boundaries.
4. Drop the `.so` files into `app/src/main/jniLibs/<abi>/` and call through
   `System.loadLibrary` + generated/hand-written JNI bindings.
5. **Spike this in isolation before any UI work** — write a tiny
   command-line-style Android instrumentation test that opens a known
   `.xrk` test file (libxrk ships sample files under `tests/test_data/`)
   and prints channel names, to prove the cross-compiled library loads and
   parses correctly on an emulator/device before building anything on top
   of it.

**Option B — Chaquopy (Python-on-Android), faster to try, riskier long-term.**
Chaquopy embeds a Python interpreter in the APK and can `pip install` pure
manylinux/universal wheels, including libxrk's published wheel if it has an
Android-compatible (or pure-Python-fallback) build. Worth a quick spike
(maybe an hour) to see if `pip install libxrk` resolves and the Cython
extension loads under Chaquopy's environment — if yes, this avoids any Rust
cross-compilation work entirely. If the wheel doesn't have an Android ABI
build, this dead-ends quickly and you fall back to Option A. Don't sink more
than an hour confirming before moving to A.

**CSV always works** regardless of how this spike goes — it's pure Kotlin,
zero risk, build and ship it first so the rest of the app has data to work
against while the `.xrk` spike runs in parallel.

## 4. Video pipeline (Media3 / MediaCodec, no ffmpeg)

Per-frame pipeline for "composite overlay onto video" export:

1. **Decode**: `MediaExtractor` + `MediaCodec` decoder reads source video
   frames into a `Surface`/`SurfaceTexture` (or use Media3's
   `ExoPlayer`-adjacent `Transformer` API — see note below).
2. **Overlay draw**: render the Compose-equivalent canvas widgets (lap
   timer text, bar graph fill, numeric readout) onto an OpenGL layer (or a
   `Canvas`-backed bitmap composited via GL) on top of the decoded frame, at
   the timestamp-correct data values (same lookup-by-offset logic as
   desktop: video offset + per-widget time-offset).
3. **Encode**: feed the composited frame into a `MediaCodec` encoder
   (H.264/HEVC, hardware-accelerated) writing to a `Surface` input.
4. **Mux**: `MediaMuxer` writes the encoded video track; if audio passthrough
   is wanted, extract the original audio track (no re-encode needed,
   `MediaExtractor` + `MediaMuxer` can copy compressed audio samples
   directly) and mux it alongside.
5. Write the final `.mp4` via SAF/`MediaStore` so it lands in the user's
   gallery/Downloads, matching the "where did my export go" expectation
   from desktop.

**Strongly consider androidx.media3's `Transformer` API**
(`androidx.media3:media3-transformer`) instead of hand-rolling steps 1-4 —
it's built exactly for "decode, apply effects/overlays, re-encode, mux" and
has a built-in `OverlayEffect`/`TextureOverlay` mechanism for compositing a
bitmap onto video frames. It would likely cut the custom MediaCodec
plumbing down to "implement a custom `TextureOverlay` that draws the
widgets for the current frame's timestamp." Recommend prototyping with
`Transformer` first and only dropping to raw `MediaCodec` if `Transformer`
can't do something WHRRAH needs (e.g. the green-screen export mode, or
precise per-widget time-offset sampling).

## 5. Suggested module/feature breakdown (rough milestones)

1. **M0 — Hello Compose + CSV import.** Open a CSV, list channels, show raw
   values scrubbing through time with a slider. No video, no widgets yet.
   Validates the data model and SAF file access.
2. **M1 — libxrk spike.** Per §3 — isolated, proven before anything depends
   on it.
3. **M2 — Video import + playback.** Load a video via SAF, play it with
   ExoPlayer, scrub it, no overlay yet.
4. **M3 — Canvas + widgets.** Compose canvas with draggable/resizable Lap
   Timer, Numeric Display, Bar Graph widgets bound to live data at the
   scrubber position. This is the most UI-heavy milestone.
5. **M4 — Sync + layout save/load.** Video offset control, per-widget time
   offset, JSON layout persistence.
6. **M5 — Export.** Media3 Transformer pipeline burning the canvas into the
   source video, muxing audio, writing to MediaStore.
7. **M6 — `.xrk` import wired into the real UI** (once M1's spike is proven,
   plug it into the same data model M0 built for CSV).

Green-screen mode is v2+ — explicitly deferred per §1. The Track Map widget
is now v1 (the RaceBox provides the GPS for it). The Data Wizard is **not**
deferred — it gets a scoped-down mobile version, see §8 — fold that into M4
(sync) once there's a session (own-logged, or `.xrk` for testing) and a
video to match.

These milestones cover the overlay/export half. The logger half (sensor
connections, session recording, lap timing) is broken down in
`docs/TASKS.md` workstreams B, I and L.

## 6. Dev machine setup (assume zero prior Android experience)

You need, in this order:

1. **Android Studio** (the official IDE) — download from
   https://developer.android.com/studio. Installing it also installs:
   - The Android SDK (multiple API levels — accept the default install,
     which includes a recent stable one, e.g. API 35).
   - The Android Emulator + a default virtual device image.
   - `adb` (Android Debug Bridge — the tool used to install/run/debug apps
     on a device or emulator), bundled with the SDK platform-tools.
2. **JDK** — Android Studio bundles its own JetBrains-Runtime JDK, you
   generally don't need to install one separately. Don't fight this; let
   Android Studio manage it.
3. **A way to actually run the app**, pick one or both:
   - **Emulator** (built into Android Studio, no physical device needed) —
     easiest for early UI work. Create an x86_64 virtual device with a
     recent Android version (e.g. "Pixel 8, API 35") via Android Studio's
     Device Manager.
   - **A real Android phone**, for testing camera/video-file-realistic
     workflows and actual hardware encoder behavior (the emulator's video
     encode path is not representative of real device performance/codec
     support — **you will want a real device before trusting any export
     timing/quality numbers**). To use one: enable Developer Options (Settings
     → About Phone → tap "Build number" 7 times), then enable "USB
     debugging" in the new Developer Options menu, then plug in via USB and
     accept the RSA-key trust prompt that pops up on the phone. `adb devices`
     should then list it.
4. **If doing the Rust/libxrk route (Option A in §3):**
   - Install Rust via https://rustup.rs.
   - `rustup target add aarch64-linux-android armv7-linux-androideabi`
   - Install the Android NDK — Android Studio → Settings → Languages &
     Frameworks → Android SDK → SDK Tools tab → check "NDK (Side by side)".
   - `cargo install cargo-ndk`
   - (Optional, recommended) `cargo install uniffi-bindgen` if generating
     Kotlin bindings from Rust via UniFFI rather than hand-writing JNI.
5. **If doing the Chaquopy route (Option B in §3) instead:** no extra
   machine setup beyond Android Studio — Chaquopy is a Gradle plugin, added
   to the project itself, not the dev machine.
6. **Git**, already in use for the existing repos — no Android-specific
   setup needed there.
7. **A sample `.xrk` test file** to validate the parser spike against —
   libxrk's repo ships real sample files under `tests/test_data/` (e.g. the
   `SFJ` and `86` vehicle sessions) — use those rather than needing real
   hardware to generate one.

### First commands to run, end to end

```
# 1. Clone a fresh project from Android Studio's "New Project" wizard
#    (Empty Activity template, Compose, Kotlin, min SDK 26).

# 2. Confirm a device is visible:
adb devices

# 3. Run from Android Studio: green Run ▶ button, pick emulator or device.
```

That first run — empty Compose screen on an emulator — is the milestone to
hit before writing any WHRRAH-specific code; it confirms the whole toolchain
is wired correctly.

## 7. Open questions for the Kotlin-side Claude instance to flag back

- Confirm whether `media3-transformer`'s overlay API can sample per-frame
  "what's the data value at time T" cheaply enough for live preview, or
  whether preview and export need genuinely different rendering paths
  (live preview via Compose canvas + ExoPlayer frame callbacks, export via
  Transformer) — desktop WHRRAH already has this preview/export split, so
  precedent exists, but confirm before assuming.
- Confirm UniFFI vs hand-written JNI based on how big libxrk's public
  surface area actually is once read closely — UniFFI is less code but adds
  a build-step dependency.
- Decide early whether layout JSON should aim for round-trip compatibility
  with desktop WHRRAH's layout files (shared layouts across platforms) or
  diverge freely — affects the data model design in M0/M3.

## 8. Mobile Data Wizard: gyro sync with the camera over WiFi

Updated 2026-10-25 (replaces the 2026-06-23 version, which concluded there
was no WiFi access to the Osmo — that turned out to be wrong).

The desktop Data Wizard matches a folder of AiM sessions against a folder
of videos by gyro cross-correlation: `video_gyro_signal(path)` →
telemetry_parser → one quaternion per frame → |angular velocity| →
`correlate_signals()` against the log's yaw/roll/pitch-rate magnitude
(20 Hz grid, 0.25 s steps). On Android the data side is now our own
logged session, and the camera side can be read over WiFi.

### Camera access (proven)

The Osmo Action 4 **does** serve its files over WiFi — proven on a real
camera in `WHRRAH/wifi_gyro_sync/` (full details in its `HANDOFF.md`;
protocol reference is `osmosis/MEDIA_PROTOCOL.md`, and KonradIT/osmosis
includes an Android app that implements the whole flow):

1. **BLE** (GATT `fff5` write / `fff4` notify, DUML frames): pair, keepalive
   ~1 Hz, read the camera AP's SSID + password.
2. **Join the camera AP** (WPA2, 5 GHz, no internet, camera at
   192.168.2.1). On Android: `WifiNetworkSpecifier` + bind the HTTP client
   to that network, since it has no internet.
3. **HTTP:** `GET http://192.168.2.1/v2?storage=<0 SD|1 internal>&path=DCIM/DJI_001/<name>.MP4`
   — no auth, supports `Range`. File list comes over the UDP datalink.

So **the gyro track can be pulled without downloading the video**: read
`moov` (~6 requests, 1–2 MB), then range-read only the `djmd` sample per
frame. The Python decoder matches telemetry_parser to within 1.2 ms per
sample on 6 real clips. Open items: WiFi speed not yet measured (a full
clip is 13k–24k requests), and reducing request count (multi-range,
parallel connections, or correlating short windows instead of whole clips).

Only some camera recording formats include the 1 kHz gyro block. Clips
without it are out of scope for auto-sync — the app should detect that and
fall back to the manual offset control.

### Workflow

1. Record a session with the app (RaceBox + ESP32 + ELM327). The RaceBox
   IMU's angular rate is the data-side signal to correlate against.
2. Connect to the camera, list clips, and pick candidates by time: the
   camera's `mvhd` creation_time is real UTC (desktop WHRRAH wrongly treats
   it as local time) and the session is anchored to GPS time, so overlapping
   clips can be found without guessing.
3. Fetch each candidate's gyro over WiFi, cross-correlate, show a
   confidence score; the user confirms before the offset is locked in.
   Because the phone now has both the session and the camera's file list,
   matching several clips to one session is back on the table — a scoped
   version of desktop's multi-pair matching, not just one pair.
4. Get the video itself onto the phone for overlay/export: via DJI Mimo
   (confirmed to keep the `djmd` gyro track intact) or a direct HTTP
   download from the same endpoint (untested for speed).

The decoder (`osmo_wifi_gyro.py`, ~400 lines, standard library only) was
written to be ported; port it to Kotlin, or cross-compile telemetry_parser
(Rust) alongside `xrk-ffi`. The same decoder also works on a local file,
so already-downloaded clips use the same code path.

### AiM sessions (testing only)

Importing `.xrk` from RaceStudio3 Mobile still works via the Android share
sheet (`ACTION_SEND`, see `MainActivity.handleIncomingIntent`) — RaceStudio3
has no folder export, so sessions arrive one at a time. Kept for testing
sync and overlay against known-good AiM data, not as the main path.
