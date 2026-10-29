# WHRRAH Android

A phone-based data logger and lap timer for autocross, built to replace an AiM Solo:
a **RaceBox Mini** for 25 Hz GPS + IMU, a **DJI Osmo Action 4** for video (started
from DJI Mic Mini remotes), and the phone tying them together — lap times on the
dash, then clips matched to runs and synced to the data afterwards.

Native Android counterpart to the desktop app (https://github.com/LUNK333/WHRRAH).
Scope and direction: [docs/design-doc.md](docs/design-doc.md). Work in progress and
the session log: [docs/TASKS.md](docs/TASKS.md).

## What works

| | |
|---|---|
| **Sessions** | New / open session (`<event>_<date>` folder), driver + car lists, everything saved to `session.json` |
| **Track** | Record the course walk; set start/finish lines (20 ft by default, square to your direction of travel) while walking; nudge ±1 m along the walk trace to calibrate against official timing |
| **Runs** | One run per camera recording (Mic remote / camera button), or the phone's **Start run** button (also starts the camera) |
| **Lap timing** | Forward crossings of the lines, interpolated between 25 Hz fixes on GNSS time; big live timer on the session screen; all runs re-time when a line moves |
| **RaceBox** | BLE, 25 Hz position + g + rotation rates, logged per run |
| **Camera** | **Bluetooth-only during the session** (DJI R-SDK remote: recording start/stop + status, no WiFi) |
| **Match clips** | One WiFi visit after the event: lists clips, matches them to runs by start time + length (works out the camera's clock offset itself), pulls each clip's gyro, back to Bluetooth (~25–30 s) |
| **Video sync** | Clip gyro cross-correlated with the RaceBox rotation rates: video start pinned to the data clock, with a match score |
| **Background** | Foreground service keeps camera / RaceBox / GPS connected when minimized; screen stays on while the app is open |

Validated on a real session (2026-10-29): RaceBox 25 Hz with no dropped fixes at
±0.64 m; lap times matched an independent recompute to the millisecond; video sync
r = 0.98 on every clip.

Not done yet: rendering the overlay video (the canvas/widgets/export pieces from the
original plan exist only as early prototypes), ESP32 CAN and ELM327 data.

## Using it

**One-time setup (Dev options):**
1. *RaceBox test panel* → Scan → connect. Check the rate reads ~25 Hz.
2. *Camera test bench* → Scan → connect, then let it join the camera's WiFi once
   (approve on the camera; accept Android's prompt). This saves the camera's WiFi
   name + password, which Match clips relies on later.

**At the event:**
1. **New session** → event / driver / car. The camera (Bluetooth) and RaceBox connect
   on their own; the first time, approve the remote on the camera's screen.
2. **Record track walk** at the start of the course walk; walk toward each line in
   the driving direction, stop on it, tap **Set start** / **Set finish**. Stop recording.
3. Each run: start recording from the Mic remote ~30 s before the start line, stop
   ~30 s after the finish. The timer shows the time as soon as you finish.
4. After the event (or whenever): **Match clips (camera WiFi)**.
5. If times are consistently off official timing, nudge a line (≈ 0.056 s per metre at 40 mph).

**Known gotchas**
- **AdGuard / any non-bypassable VPN** blocks the camera WiFi: exclude WHRRAH or pause it.
- **Wireless Android Auto** and the camera WiFi knock each other out (B1h, parked).
- After being used as a remote, the camera ignores WiFi pairing, so Match clips joins with
  the **saved** WiFi password. If the camera's WiFi is reset, re-pair it once in the
  Camera test bench (with no session open).
- For ~9 s after any clip listing the camera sits in viewer mode and won't start recording.
- Only some Action 4 recording formats carry the 1 kHz gyro; clips without it can't be synced.

## Session files

On the phone at `Android/data/com.lunk333.whrrah/files/sessions/<event>_<date>/`
(reachable over USB):

```
session.json          the project file: lines, runs, clip matches, times, sync
gps/trackwalk.csv     course walk trace
gps/lapNN.csv         per-run position log   (elapsed_ms, utc_ms, lat, lon, speed, heading, accuracy, source)
imu/lapNN.csv         per-run RaceBox motion (elapsed_ms, wall_ms, utc_ms, g_x/y/z, roll/pitch/yaw °/s)
gyro/lapNN_<clip>.csv the matched clip's camera gyro (t_ms from clip start, w, x, y, z)
video/                reserved for downloaded clips
```

Per run `session.json` holds the recording start/stop (phone clock), lap time + line
crossing times (GNSS clock), the matched clip with its start/length/offset, and
`videoStartMs` (video t = 0 on the data clock) + `syncR`. The session holds the lines
and the camera clock offset the matcher found.

## Building

Open the folder in Android Studio and Run. There's no `gradlew` script in the repo;
from a terminal, use Android Studio's JDK and its cached Gradle 8.13:

```
JAVA_HOME="C:/Program Files/Android/Android Studio/jbr" ~/.gradle/wrapper/dists/gradle-8.13-bin/*/gradle-8.13/bin/gradle :app:testDebugUnitTest :app:assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Unit tests (JVM, ~90) cover the parts that can be pinned down off-device: the gyro
decoder against the Python reference (fixture built by `tools/make_gyro_fixture.py`
from a real clip), UBX/RaceBox parsing against the protocol doc's example packet,
line geometry and heading, path-following nudges, lap timing, clip matching, gyro
sync, session storage, and osmosis's own Action 4 manifest and R-SDK tests.

## Code layout

`app/src/main/java/com/lunk333/whrrah/`

| | |
|---|---|
| `ui/AppScreens.kt` | Home, new/open session, session screen (timer, track, runs), settings, dev options |
| `session/` | `SessionController` (runs, logging, Match clips), `Session` model + store, `LapTimer`, `ClipMatcher`, `GyroSync` |
| `gps/` | `GpsSource` (+ phone GPS fallback), `Geo`, `TrackPath`, heading estimate |
| `racebox/` | UBX framing + Data Message parser, BLE client, test panel |
| `osmo/` | `CameraMonitor` (Bluetooth R-SDK remote), `OsmoLink` (pair → WiFi → list → gyro), camera test bench |
| `osmo/gyro/` | djmd gyro decoder (Kotlin port of `WHRRAH/wifi_gyro_sync/osmo_wifi_gyro.py`) + range readers |
| `LinkService.kt` | Foreground service keeping the links alive in the background |
| `settings/` | Drivers, cars, line width, toggles |
| `data/`, `xrk/`, `canvas/`, `video/` | Earlier prototypes: CSV/XRK viewer (Dev options), overlay canvas, video player |

`app/src/main/java/dev/konraditurbe/osmosis/` is **vendored** from
[osmosis](https://github.com/KonradIT/osmosis) (MIT): DUML framing, GATT client, WiFi
join, the UDP datalink media list, and the R-SDK protocol. A few small local patches,
all marked `WHRRAH patch` — see [VENDORED.md](app/src/main/java/dev/konraditurbe/osmosis/VENDORED.md)
before re-copying any of it.

## Legacy: libxrk / `.xrk` import

The CSV/XRK viewer under Dev options uses libxrk (https://github.com/m3rlin45/libxrk)
through a hand-written JNI shim in `rust/xrk-ffi/`; the `.so` files in
`app/src/main/jniLibs/` are prebuilt and checked in. Only needed again for testing
against AiM data. To rebuild: clone libxrk next to this repo, install the Android
targets + NDK + `cargo-ndk` (and MSVC build tools on Windows), then from `rust/xrk-ffi/`:

```
cargo ndk -t arm64-v8a -t armeabi-v7a -o ../../app/src/main/jniLibs build --release
```
