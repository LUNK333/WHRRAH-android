# WHRRAH Android — Task Board

Single source of truth for what's being worked on. Lives in git so every PC
and every Claude session sees the same board.

**Rules**
- `git pull` before you start. Update this file + commit + push when you stop.
- Claim a task by setting **Who** to the machine (`desk` / `laptop`) and
  status to `DOING`. Don't take a task another machine has `DOING`.
- Status: `TODO` · `DOING` · `BLOCKED` · `DONE`
- **Where**: `any` = any PC · `desk` = needs desktop (big files, GPU, emulator)
  · `car` = needs the car / camera / hardware on hand (laptop session)
- Add notes under a task's ID in **Session log** at the bottom, not in the
  tables.

Hardware needed to start a workstream is listed as a dependency (`needs H2`).

---

## 0. Setup for multi-PC work (do first)

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| S1 | Commit the M0/M1/M2/canvas work in this repo (all of it is still uncommitted) | desk | DONE | desk |
| S2 | Create private GitHub repo `LUNK333/WHRRAH-android`, push | desk | TODO | |
| S3 | In the desktop WHRRAH repo: commit `wifi_gyro_sync/` and push (only exists on the desktop right now) | desk | DONE | desk |
| S4 | Laptop: install Android Studio, clone this repo + `libxrk` sibling, open project, run on phone | laptop | TODO | |
| S5 | Laptop: Rust + NDK + `cargo-ndk` (only if you'll rebuild `libxrk_ffi.so` there; prebuilt `.so` is checked in) | laptop | TODO | |
| S6 | Put test data (sample `.mp4`, `.xrk`, CSV) in a synced folder (OneDrive/Drive) — not git | any | TODO | |
| S7 | Update design-doc §8: WiFi gyro fetch from the Osmo **does** work now (see `WHRRAH/wifi_gyro_sync/HANDOFF.md`) | any | TODO | |

## 1. Hardware

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| H1 | Choose ESP32 board: built-in CAN transceiver, 12 V tolerant input, screen if wanted. Shortlist + pick | any | TODO | |
| H1a | ↳ Check it has BLE (plain ESP32 / S3 do; S2 doesn't) | any | TODO | |
| H1b | ↳ Order | any | TODO | |
| H2 | Buy RaceBox Mini | any | TODO | |
| H3 | Identify AiM cable connector part (which AiM port: CAN/expansion?), source mating connector | car | TODO | |
| H4 | Wiring ESP32 ↔ AiM | any | TODO | |
| H4a | ↳ Get AiM port pinout + max current that port can supply | any | TODO | |
| H4b | ↳ Measure ESP32 board draw (idle, BLE advertising, screen on) — needs H1 | desk | TODO | |
| H4c | ↳ Decide power: from AiM port if under its limit, else switched 12 V + buck | any | TODO | |
| H4d | ↳ Wiring diagram (CAN H/L, GND, power, termination — **don't** add a 120 Ω terminator to an already-terminated bus) | any | TODO | |
| H4e | ↳ Build harness, bench test, then install | car | TODO | |
| H5 | ELM327 Bluetooth adapter on hand + confirm it's a genuine/compatible chip (see B4a) | car | TODO | |

## 2. Data sources (backend)

### B1 — DJI camera over WiFi (gyro for sync)
Data path proven in Python (`WHRRAH/wifi_gyro_sync/`). Now port to Android.

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| B1a | Measure WiFi fetch speed with the Python CLI (ms/request, full clip vs 60 s window) — HANDOFF open Q1 | car | TODO | |
| B1b | Cut request count: try HTTP multi-range / parallel connections / windowed fetch — HANDOFF open Q2 | car | TODO | |
| B1c | Android BLE: pair with camera (DUML over GATT `fff5`/`fff4`), keepalive, read SSID + password. Reference: osmosis Android app | any | TODO | |
| B1d | Android WiFi: join camera AP with `WifiNetworkSpecifier`, bind HTTP traffic to that network (no internet on it) | car | TODO | |
| B1e | Android: list files on camera (UDP datalink or HEAD probe) | car | TODO | |
| B1f | Port `osmo_wifi_gyro.py` decoder to Kotlin (moov walk, djmd protobuf, timebase incl. u32 wrap) + unit tests against Python output as fixtures | any | TODO | |
| B1g | Fix desktop `read_mp4_creation_time()` treating UTC as local (4 h off) — desktop repo | any | TODO | |

### B2 — RaceBox Mini (BLE: GPS + IMU) — needs H2

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| B2a | Get RaceBox's published BLE protocol doc; note service/characteristic UUIDs, packet layout, rates | any | TODO | |
| B2b | Explore with nRF Connect on the phone (no code) — confirm UUIDs and see raw packets | car | TODO | |
| B2c | PC prototype: Python `bleak` script that connects, parses packets, logs CSV. Save raw packet captures as test fixtures | desk | TODO | |
| B2d | Kotlin parser (pure, unit-tested against B2c fixtures) | any | TODO | |
| B2e | Android BLE connection service: scan, connect, reconnect on drop, request MTU / high connection priority | any | TODO | |
| B2f | Field test: log a drive, compare track/speed to AiM data | car | TODO | |

> "Develop test app on PC first?" — **Yes, but small.** nRF Connect for poking,
> a Python `bleak` script for parsing + capturing fixtures, then the real code
> in Kotlin. The parser is the part worth testing on PC; BLE connection
> handling has to be done on Android anyway (Windows BLE behaves differently).

### B3 — ESP32 (CAN listener + BLE to phone) — needs H1

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| B3a | Pick firmware stack (Arduino-ESP32 or ESP-IDF; NimBLE for BLE) and set up build/flash on both PCs (PlatformIO keeps it identical) | any | TODO | |
| B3b | CAN: TWAI driver at 500 kbps (E46 PT-CAN) in **listen-only mode** so it never ACKs/transmits on the car bus | any | TODO | |
| B3c | Sniff the bus in the car, dump all IDs + rates to SD/serial | car | TODO | |
| B3d | Decide channel list (RPM, throttle, wheel speeds, steering angle, temps…) and decode table from E46 CAN references | any | TODO | |
| B3e | Define the BLE protocol ESP32 → phone (one GATT service, packed binary frames with ESP32 timestamp + channel ID + value). Write it down in `docs/` | any | TODO | |
| B3f | Firmware: decode + send over BLE notify | any | TODO | |
| B3g | Test BLE side with nRF Connect before writing any app code | desk | TODO | |
| B3h | Android: ESP32 BLE client (reuse B2e connection service) | any | TODO | |
| B3i | Bench test with a second CAN device / replay of B3c capture, then car test | car | TODO | |

### B4 — ELM327 + EDIABAS (brake pressure)

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| B4a | **Feasibility first:** E46 diagnostics are K-line, not D-CAN. Check ediabaslib docs for which adapters work with K-line cars — a stock ELM327 may not. Decide adapter before writing code | any | TODO | |
| B4b | Confirm which ECU + job gives brake pressure (DSC module), and its max poll rate | car | TODO | |
| B4c | Prove it with ediabaslib's existing tools/app before writing our own | car | TODO | |
| B4d | Integrate: ediabaslib is C#/.NET — decide: embed (hard on Android/Kotlin), reimplement just the one job, or move this onto the ESP32 (K-line transceiver) | any | TODO | |
| B4e | Fallback if poll rate too low / infeasible: dedicated brake pressure sensor into ESP32 ADC | any | TODO | |

## 3. Sync + integration

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| I1 | Design doc: one master clock (phone `elapsedRealtimeNanos`), every sample stamped on receipt, plus each source's own clock | any | TODO | |
| I2 | Per-source alignment: RaceBox → GPS UTC; ESP32 → offset estimated via BLE ping round-trip; ELM327 → receive time minus half poll latency; video → gyro cross-correlation vs RaceBox/AiM IMU | any | TODO | |
| I3 | Session recording format (one file per session, all channels, raw + aligned timestamps). Feed into existing `TelemetryLog` model | any | TODO | |
| I4 | Port `correlate_signals()` + angular-velocity math to Kotlin (or Rust in `xrk-ffi`); unit-test vs desktop output | any | TODO | |
| I5 | Recording test app: connect all sources, live channel list with rates + "last seen", record session | any | TODO | |
| I6 | Car test: record a full session, check alignment against AiM + video | car | TODO | |

## 4. App core (existing milestones from design-doc §5)

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| M0 | CSV import + scrubber | — | DONE | |
| M1 | libxrk spike (JNI) | — | DONE | |
| M2 | Video import + ExoPlayer playback (in progress? `video/VideoPlayerScreen.kt` exists) | any | DOING? | |
| M3 | Canvas + draggable/resizable widgets (started: `canvas/`) | any | DOING? | |
| M4 | Sync offset control + per-widget offset + layout JSON save/load | any | TODO | |
| M5 | Export via Media3 Transformer | desk | TODO | |
| M6 | `.xrk` wired into real data model (replace JSON-summary spike with bulk per-channel fetch) | any | TODO | |

## 5. GUI

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| G1 | Inventory desktop WHRRAH widgets + their settings; pick v1 set for phone | any | TODO | |
| G2 | Wireframe phone screens: session/source connect, live dashboard, editor, export | any | TODO | |
| G3 | Port widgets: Lap Timer, Numeric, Bar Graph (v1), Data Trace, Track Map (v2) | any | TODO | |
| G4 | Touch editing UX: select, drag, pinch-resize, snap, property sheet | any | TODO | |
| G5 | Test on real phone; decide if tablet layout needed (Compose window size classes make this cheap later) | car | TODO | |
| G6 | Live in-car display mode (big text, glanceable) — only if wanted | any | TODO | |

---

## Suggested order

1. **S1–S4** (unblocks laptop work)
2. In parallel: order hardware (**H1, H2**) · **B1a/B1f** (camera, no new hardware) · **M2/M3**
3. **B4a** early — it's the biggest unknown and might change the hardware plan
4. RaceBox (**B2**) when it arrives → ESP32 (**B3**) when it arrives
5. **I1–I5** once two sources work → **G** polish

---

## Session log

Newest at top. One line per session: date · machine · task IDs · what changed / what's next.

- 2026-10-25 · desk · S1, S3 · Committed all Android work locally (no remote yet — S2). Pushed `wifi_gyro_sync` to WHRRAH. Dropped 59.94 fps gyro task (out of scope: those recording formats have no gyro).
- 2026-10-25 · desk · — · Board created from `WHRRAH android ver. development dashboard.xlsx`. Fixed `.gitignore` (was about to commit `app/build/` + 348 MB `rust/xrk-ffi/target/`).
