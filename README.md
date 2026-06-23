# WHRRAH Android

Native Android counterpart to the desktop WHRRAH app
(https://github.com/LUNK333/WHRRAH). Not a port — see
[docs/design-doc.md](docs/design-doc.md) for the full scope, architecture,
and milestone breakdown.

## Status

Project skeleton only — scaffolded by hand (no Android Studio/SDK installed
on this machine yet). Nothing has been built or run.

## Next steps

1. Install Android Studio (https://developer.android.com/studio) — see
   design doc §6 for the full first-time setup walkthrough.
2. Open this folder in Android Studio. It will detect the missing Gradle
   wrapper jar and offer to regenerate it (or run `gradle wrapper` once
   Gradle is on PATH) — that's expected, this skeleton was written without
   network access to fetch the wrapper jar itself.
3. Run on an emulator or device to confirm the toolchain (`MainActivity`
   currently just shows "Hello WHRRAH!" via Compose) — this is the M0
   milestone checkpoint from the design doc.
4. Proceed to CSV import (M0) and the libxrk spike (M1) per the design doc's
   milestone list.

## Module layout

- `app/` — single module for now (per design doc §2, split into
  `:core-data` / `:core-media` only if it grows unwieldy).
- `app/src/main/java/com/lunk333/whrrah/` — Kotlin source.
- `app/src/main/java/com/lunk333/whrrah/ui/theme/` — Compose theme.
- `app/src/androidTest/` — instrumentation tests (this is where the libxrk
  load-and-parse spike test from design doc §3 step 5 belongs).

Package name: `com.lunk333.whrrah`. App id matches.
