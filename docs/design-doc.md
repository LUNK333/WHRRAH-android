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

## 1. Goal / non-goals

**Goal (v1):** An Android app that can:
1. Import a telemetry log (CSV first; `.xrk`/`.xrz` once the libxrk
   integration below is working).
2. Import a video clip from device storage.
3. Place a small set of overlay widgets (Lap Timer, Numeric Display, Bar
   Graph) on a canvas, positioned/scaled by touch.
4. Scrub a synced preview (video + data, with a manual time-offset control).
5. Export a composited MP4 (overlay burned into the source video) using
   on-device hardware encoding — no bundled `ffmpeg` binary.
6. Save/load a layout (JSON), schema-compatible in spirit with desktop
   WHRRAH's layout files (field names can differ; exact byte-compatibility
   is not a goal).

**Explicitly out of scope for v1** (desktop-only or later milestones):
- Track Map widget (needs more GPS/map work — defer to v2).
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

## 3. The libxrk integration (do this first — highest uncertainty)

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

Track map widget and green-screen mode are v2+ — explicitly deferred per §1.
The Data Wizard is **not** deferred — it gets a scoped-down mobile version,
see §8 — fold that into M4 (sync) once M1/M2 are both in place, since it
needs both a loaded XRK session and a loaded video.

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

## 8. Mobile Data Wizard: single-pair gyro sync (not the desktop batch model)

Researched 2026-06-23. The desktop Data Wizard scans a folder of AiM
sessions against a folder of videos and scores every plausible pairing
(§0/README "Data Wizard" section, `WHRRAH.py` `video_gyro_signal` +
surrounding match-scoring code). That model doesn't translate to Android:

- **No WiFi access to the camera.** Researched whether the Osmo Action 4
  exposes any WiFi HTTP API for file listing/download (the way GoPro's
  cameras famously do, documented at
  https://github.com/KonradIT/goprowifihack). It doesn't — DJI's own docs
  confirm Osmo Action WiFi is exclusively for pairing with the DJI Mimo app,
  no public/reverse-engineered protocol exists. `KonradIT/mmt`'s DJI
  support (https://github.com/KonradIT/mmt) is USB-mass-storage-only
  (`pkg/dji/dji.go` detects a mounted SD card partition, not a network
  connection) — same as plugging in a cable, not a WiFi alternative.
  **Confirmed:** downloading the full clip via the DJI Mimo app onto the
  phone retains the embedded gyro metadata (`djmd` stream) — verified by
  opening the downloaded file in Gyroflow. So gyro data survives the
  "normal" no-Android-effort path of using Mimo to get the clip onto the
  device; no need to solve WiFi-without-Mimo at all.
- **No folder access to AiM's storage either.** RaceStudio3 Mobile is a
  .NET MAUI app with no "save to folder" export — only an app-to-app
  Android share action (confirmed by inspecting its installed package:
  `mono.MonoRuntimeProvider` + `microsoft.maui.essentials.fileProvider`,
  and its private storage is inaccessible without root since it isn't
  debuggable). So there is no folder of `.xrk` files sitting on the device
  to scan, the way desktop's Data Wizard expects — sessions arrive
  one at a time, via Android's share sheet.
- **Consequence:** there is no batch of files to fuzzy-match against. The
  realistic mobile workflow is inherently a single video + single session,
  arriving one at a time:
  1. Download the clip off the camera via DJI Mimo (gyro metadata intact).
  2. Load the video into WHRRAH (`Open Video…`, already implemented — M2).
  3. Export/share the session from RaceStudio3 Mobile → WHRRAH now appears
     in the Android share sheet as a target (`ACTION_SEND` intent filter,
     already implemented — see `MainActivity.handleIncomingIntent`).
  4. WHRRAH extracts the video's gyro signal and the session's rotation
     signal and cross-correlates them (same math as desktop's
     `video_gyro_signal` / `_decode_protobuf_fields` — that logic was
     **hand-rolled by reverse-engineering DJI's `djmd` metadata stream's
     schema-less protobuf framing**, not built on a third-party library;
     confirmed by reading `WHRRAH.py` directly. `AdrianEddy/telemetry-parser`
     (https://github.com/AdrianEddy/telemetry-parser), which supports DJI
     Action 2/4/5/6 telemetry extraction, was *not* used for the desktop
     version but is worth evaluating for the Android port instead of
     reimplementing the protobuf walker from scratch in Kotlin/Rust).
  5. Show a confidence score (same cross-correlation confidence concept as
     desktop) and let the user confirm before locking in the offset — a
     one-pair "does this look right?" check, not a ranked multi-candidate
     table like desktop's results view.
- This still needs Media3/`MediaExtractor` to demux the DJI metadata track
  on Android (equivalent of desktop's `ffmpeg -map 0:2 -c copy -f data`),
  and a Kotlin/Rust port of the quaternion → angular-velocity math. Natural
  fit for the `xrk-ffi` Rust crate or a sibling one, since the
  cross-correlation math is the same shape of problem already being solved
  there for libxrk's `Channel` data.
