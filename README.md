# WHRRAH Android

Native Android counterpart to the desktop WHRRAH app
(https://github.com/LUNK333/WHRRAH). Not a port — see
[docs/design-doc.md](docs/design-doc.md) for the full scope, architecture,
and milestone breakdown.

## Status

- M0 toolchain: builds and runs on an emulator (Compose + Gradle wired up).
- M0 CSV import: working — SAF file picker, channel/lap summary, scrubber
  slider over a parsed AiM CSV log (`MainActivity.kt`, `data/`).
- M1 libxrk spike: working — see below.

## Next steps

1. Open this folder in Android Studio (Gradle wrapper/`.idea` files are
   already generated).
2. M2: video import + ExoPlayer playback (no overlay yet).
3. M3: Compose canvas + draggable/resizable widgets.
4. M6: wire the libxrk JNI bridge into the real data model the CSV importer
   already established, instead of the JSON-summary spike shape.

## Module layout

- `app/` — single module for now (per design doc §2, split into
  `:core-data` / `:core-media` only if it grows unwieldy).
- `app/src/main/java/com/lunk333/whrrah/` — Kotlin source.
- `app/src/main/java/com/lunk333/whrrah/data/` — CSV telemetry parser/model.
- `app/src/main/java/com/lunk333/whrrah/xrk/` — `XrkNative`, the JNI bridge
  to the Rust `xrk_ffi` crate.
- `app/src/main/java/com/lunk333/whrrah/ui/theme/` — Compose theme.
- `app/src/main/jniLibs/<abi>/libxrk_ffi.so` — prebuilt native libs (checked
  in; rebuild steps below).
- `app/src/androidTest/` — instrumentation tests, including the libxrk
  load-and-parse spike (`xrk/XrkNativeSpikeTest.kt`).
- `rust/xrk-ffi/` — the Rust JNI shim crate around libxrk.

Package name: `com.lunk333.whrrah`. App id matches.

## libxrk integration (M1)

Per design doc §3 Option A: a hand-written JNI shim (not UniFFI — libxrk's
`ChannelValues`/`Metadata` types aren't UniFFI-annotated and patching the
upstream crate for that wasn't worth it for a spike). `rust/xrk-ffi` wraps
`libxrk::read_xrk_file` and returns a JSON summary string (channels, laps,
metadata) across the JNI boundary; `XrkNative.parseSummaryJson()` is the
Kotlin entry point. `XrkNativeSpikeTest` bundles a real `.xrk` sample
(`androidTest/assets/sample_session.xrk`, from libxrk's SFJ fixture) and
asserts it parses with channels and laps present — this is the proof that
the cross-compiled `.so` actually loads and works on-device, not just that
the build compiles.

**This is a spike, not the final shape.** It returns sample *summaries*
(count + first/last value), not full per-sample arrays — fine for proving
the toolchain, not for feeding the canvas/scrubber. M6 should redesign the
bridge once it needs real channel data flowing into the UI (likely a
bulk binary fetch per channel rather than JSON).

### Rebuilding the native library

Requires a sibling checkout of libxrk (`xrk_ffi`'s `Cargo.toml` has a path
dependency on `../../../libxrk/crates/libxrk`):

```
cd ..   # next to WHRRAH-android
git clone https://github.com/m3rlin45/libxrk.git
```

One-time toolchain setup (already done on this machine, listed for a fresh
machine):
- `rustup target add aarch64-linux-android armv7-linux-androideabi`
- Android NDK installed via `sdkmanager "ndk;27.0.12077973"` (or Android
  Studio's SDK Manager → SDK Tools → NDK side-by-side)
- `cargo install cargo-ndk`
- MSVC Build Tools (Rust on Windows needs `link.exe`) — if rebuilding from a
  plain terminal, run from a "Developer PowerShell for VS" so `link.exe`
  resolves to MSVC's, not Git Bash's coreutils `link`.

Then, from `rust/xrk-ffi/`:

```
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../../app/src/main/jniLibs build --release
```

This overwrites the `.so` files in `app/src/main/jniLibs/<abi>/` directly.
