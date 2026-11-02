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
| S2 | Create private GitHub repo `LUNK333/WHRRAH-android`, push | desk | DONE | desk |
| S3 | In the desktop WHRRAH repo: commit `wifi_gyro_sync/` and push (only exists on the desktop right now) | desk | DONE | desk |
| S4 | Laptop: install Android Studio, clone this repo + `libxrk` sibling, open project, run on phone | laptop | TODO | |
| S5 | Laptop: Rust + NDK + `cargo-ndk` (only if you'll rebuild `libxrk_ffi.so` there; prebuilt `.so` is checked in) | laptop | TODO | |
| S6 | Put test data (sample `.mp4`, `.xrk`, CSV) in a synced folder (OneDrive/Drive) — not git | any | TODO | |
| S7 | Update design-doc §8: WiFi gyro fetch from the Osmo **does** work now (see `WHRRAH/wifi_gyro_sync/HANDOFF.md`) | any | DONE | desk |

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
| B1a | Measure WiFi fetch speed with the Python CLI (ms/request, full clip vs 60 s window) — HANDOFF open Q1 | car | DONE | desk |
| B1b | Cut request count: try HTTP multi-range / parallel connections / windowed fetch — HANDOFF open Q2 | car | TODO | |
| B1c | Android BLE: pair with camera (DUML over GATT `fff5`/`fff4`), keepalive, read SSID + password. Reference: osmosis Android app | any | DONE | desk |
| B1d | Android WiFi: join camera AP with `WifiNetworkSpecifier`, bind HTTP traffic to that network (no internet on it) | car | DONE | desk |
| B1e | Android: list files on camera (UDP datalink or HEAD probe) | car | DONE | desk |
| B1f | Port `osmo_wifi_gyro.py` decoder to Kotlin (moov walk, djmd protobuf, timebase incl. u32 wrap) + unit tests against Python output as fixtures | any | DONE | desk |
| B1g | Fix desktop `read_mp4_creation_time()` treating UTC as local (4 h off) — desktop repo | any | TODO | |
| B1h | Wireless Android Auto and the camera WiFi link knock each other out (both 5 GHz WiFi). Parked — the track car has no AA. If needed later: try wired AA, or check whether the phone can hold STA + Wi-Fi Direct on different channels | car | TODO | |

### B2 — RaceBox Mini (BLE: GPS + IMU) — needs H2

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| B2a | Get RaceBox's published BLE protocol doc; note service/characteristic UUIDs, packet layout, rates | any | DONE | desk |
| B2b | Explore with nRF Connect on the phone (no code) — confirm UUIDs and see raw packets | car | TODO | |
| B2c | PC prototype: Python `bleak` script that connects, parses packets, logs CSV. Save raw packet captures as test fixtures | desk | TODO | |
| B2d | Kotlin parser (pure, unit-tested against B2c fixtures) | any | DONE | desk |
| B2e | Android BLE connection service: scan, connect, reconnect on drop, request MTU / high connection priority | any | DOING | desk |
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
| I1 | Design doc: one master clock (phone `elapsedRealtimeNanos`), every sample stamped on receipt, plus each source's own clock | any | DONE | desk |
| I2 | Per-source alignment: RaceBox → GPS UTC; ESP32 → offset estimated via BLE ping round-trip; ELM327 → receive time minus half poll latency; video → gyro cross-correlation vs RaceBox/AiM IMU | any | DONE | desk |
| I3 | Session recording format (one file per session, all channels, raw + aligned timestamps). Feed into existing `TelemetryLog` model | any | DONE | desk |
| I4 | Port `correlate_signals()` + angular-velocity math to Kotlin (or Rust in `xrk-ffi`); unit-test vs desktop output | any | DONE | desk |
| I5 | Recording test app: connect all sources, live channel list with rates + "last seen", record session | any | TODO | |
| I6 | Car test: record a full session, check alignment against AiM + video | car | TODO | |

## 3b. Logger: sessions + lap timing (replaces the AiM Solo)

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| L1 | Session storage on device: list/rename/delete sessions, export CSV (same format the CSV importer reads) | any | TODO | |
| L2 | Track definitions: start/finish line (+ optional splits) set on a map or by "drop line here" while parked on it; saved per track | any | DONE | desk |
| L3 | Lap detection from RaceBox GPS: line-crossing with interpolation between fixes for sub-sample timing | any | DONE | desk |
| L4 | Check lap times vs AiM Solo on the same session (run both in car once) | car | TODO | |
| L5 | Keep logging alive with screen off / app in background (foreground service, wake lock, BLE reconnect) | any | TODO | |
| L6 | v2: live in-car display — current/last/best lap, predictive delta — live delta + chevron bar in landscape done (`session/LapDelta.kt`, spec `docs/lap-delta-spec.md`); test in the car | any | DOING | desk |
| L7 | v2: lap comparison view | any | TODO | |

## 4. App core (existing milestones from design-doc §5)

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| M0 | CSV import + scrubber | — | DONE | |
| M1 | libxrk spike (JNI) | — | DONE | |
| M2 | Video import + ExoPlayer playback (in progress? `video/VideoPlayerScreen.kt` exists) | any | DOING? | |
| M3 | Canvas + draggable/resizable widgets (started: `canvas/`) | any | DOING? | |
| M4 | Sync offset control + per-widget offset + layout JSON save/load | any | TODO | |
| M5 | Export via Media3 Transformer — `export/ClipExporter.kt`: overlay + layout crop + padded trim → Movies/WHRRAH; tested on the Pixel with a 2.7K HEVC clip (Dev → Overlay preview → Test export). Camera-stream source untested | desk | DOING | desk |
| M6 | `.xrk` wired into real data model (replace JSON-summary spike with bulk per-channel fetch) | any | TODO | |

## 5. GUI

| ID | Task | Where | Status | Who |
|---|---|---|---|---|
| G1 | Inventory desktop WHRRAH widgets + their settings; pick v1 set for phone | any | DONE | desk |
| G2 | Wireframe phone screens: session/source connect, live dashboard, editor, export | any | TODO | |
| G3 | Port widgets: Lap Timer, Numeric, Bar Graph, Track Map (v1); Data Trace (later) — all 6 desktop types drawn in `overlay/` (view only); check on real clips | any | DOING | desk |
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
- 2026-11-01 · desk · L3/L6 · NE-SVT #16 review: finish misses = 20 ft finish line (cars crossed 3.2–3.7 m off centre); false/missed starts = staging over the start line. New timing: start = launch from the last stop in the stage + start distance (6 m default; RaceBox IMU refines the launch), finish = line crossing; calibrate offset to official times (run menu). On NE-SVT: ±0.043 s sd vs official (was 0.36). Line width editable in place; finish default 100 ft. Live timer + delta use the same launch rule.
- 2026-10-31 · desk · M5, L6, G · Run viewer: download (swipe right) / delete copy (swipe left) / export (long press, overlay + padded, to the gallery). Landscape timer: panel states, delta bar style (chevrons/boxes), full brightness, HDR chevrons (Dev). Export verified on the phone with a local clip; next: export straight from the camera's WiFi.
- 2026-10-30 · desk · L6 · Live lap delta per docs/lap-delta-spec.md, adapted to autocross (separate start/finish, open course): reference = fastest gap-free timed run, picked at run start; projection window + 15 m lateral limit + reacquire. Replay of LAPTIMING TEST vs run 4: final deltas match lap differences to <0.3 ms, ~1–5 µs/fix. Landscape panel: 10+10 chevron bar (0.2 s each), red left = behind, green right = ahead, number in the middle. Next: try it in the car.
- 2026-10-29 · desk · G1, G3, M4 · Widget overlay port: `overlay/` reads/writes desktop layout JSON (round-trips unknown fields), renders all 6 desktop widget types (port of the export drawing code), run data under AiM channel names from the GPS/RaceBox logs. Layouts folder `Android/data/com.lunk333.whrrah/files/layouts/` (bundled `layout_phone.json` seeded). Run viewer: Show overlay checkbox + layout picker, video framed like the desktop export crop. Dev → Overlay preview. Next: check on real clips; no layout editing yet (G4).
- 2026-10-29 · desk · B1 · Camera clip list after WiFi join: an empty first SD answer (card not ready after a cold wake) now re-asks after ~3 s instead of ~12 s; the re-ask stops once its answer settles. Vendored osmosis patch, see VENDORED.md.

- 2026-10-29 · desk · B1 · Camera setup checks over Bluetooth: mode (Video/Photo/Slow-mo… detected, one-tap Switch to video works) and stabilization (detected) — warning banner + notification. FOV and color mode are NOT reported over the R-SDK link (only the battery byte moved while toggling them), so a wrong FOV only shows up after the fact, as "clip has no gyro" at Match clips.
- 2026-10-29 · desk · L3, I4 · Real test session (LAPTIMING TEST): RaceBox 25 Hz with zero dropped fixes, ±0.64 m; 3 timed runs 23.893 / 23.256 / 22.433 s, matching an independent recompute to the ms, crossings 0.4–1 m off line centre. Video sync on real data r = 0.98 on all 3 clips; the video starts ~0.45–0.52 s after Start run. Camera clock still ~14.6 s fast after a WiFi time sync (matcher handles it).
- 2026-10-28 · desk · I1–I4 · Video sync: RaceBox rotation rates logged per run (imu/lapNN.csv); GyroSync correlates them with the clip's gyro (angular speed, mount-independent, ±10 s then 1 ms). Synthetic tests: true offset found within 5 ms from guesses 1.2–7.5 s off. NOT yet checked on real data — no gyro clip + matching AiM log on the desktop (the 2025-06-29 Palmer clip has no 1 kHz gyro). First real check: a drive with camera + RaceBox, then Match clips; look at the sync column's match score.
- 2026-10-28 · desk · L3 · Lap timing: forward crossings of the start/finish lines within their width, interpolated between fixes on GNSS time; last start before the first finish (staging creep ignored); 3 s dropout limit; <2 Hz flagged rough. Times on stop, re-times all runs on line set/nudge and session open. Emulator drive matched an independent calc to the ms (17.073 s). Next: I4 sync offset (clip gyro vs RaceBox rotation rates).
- 2026-10-28 · desk · B1 · Sessions use the camera over Bluetooth only (DJI R-SDK remote): record start/stop + status work on the Action 4. Match clips = one WiFi visit after the event (48 s for 3 clips): ClipMatcher found the camera clock 14.3 s fast and matched all 3 runs within the 1 s filename precision. The camera ignores media pairing after being used as a remote, so the WiFi join relies on the saved SSID/password (re-pair in the test bench if the camera's WiFi is reset).
- 2026-10-27 · desk · — · Test drive (daily car): GPS trace good. Wireless Android Auto + camera WiFi fight, neither works — parked as B1h (no AA in the track car).
- 2026-10-27 · desk · L2 · Start/finish are now lines: width from Settings (default 20 ft), centred on the fix, square to the direction of travel (GPS heading when moving >0.5 m/s, else bearing of the last ≥5 m walked in 30 s). Track sketch on the session screen. Next: L3 lap timing = crossing detection against these lines.
- 2026-10-27 · desk · B2a, B2d, B2e · RaceBox: UBX framer + 80 B Data Message parser (tested against the protocol doc's example packet), BLE client (MTU 517, high priority, DIS read, GNSS config query), Dev options → RaceBox test panel (live data, rate/gaps/checksum stats, CSV log). Sessions use the RaceBox as GPS when it's streaming, phone GPS otherwise. Not yet tried on the device. Skipped B2b/B2c (nRF Connect / PC prototype) — went straight to Kotlin with the doc's golden packet.
- 2026-10-27 · desk · G2, L1, L2 · GUI skeleton per `design notes git ignore this/GUI design.txt`: Home (new/open session, settings, dev options, camera+GPS status), session folders (`Android/data/com.lunk333.whrrah/files/sessions/<event>_<date>/`), session screen with Define track (start/finish from GPS), runs list. Each camera recording = one run: GPS logged during it, clip matched by timestamp after stop, whole gyro pulled into the session. Phone GPS as stand-in source; lap time + sync offset are placeholders until RaceBox (B2/L3/I4). Old Data tab moved to Dev options. Not yet tried with the camera on the phone.
- 2026-10-27 · desk · B1a, B1c–e · Proven on the Osmo Action 4: BLE pair → WiFi join → clip list → gyro, plus a REC indicator from WiFi status pushes and record start/stop over WiFi. Loop test: stop → new clip listed ≈15 s, 2 min clip gyro pull 6.6 s (2.2 ms/request, 4 parallel) — clip length doesn't matter. Camera can't record for ~9 s after each listing (viewer mode; Action 4 has no exit command). BLE R-SDK monitor tried and dropped (camera ignores DUML pairing on an R-SDK link). Phone needs AdGuard paused/excluded. Next: RaceBox (B2) + lap timing (L2/L3).
- 2026-10-26 · desk · B1c–B1f · Camera tab in the app: BLE scan/pair → read WiFi creds → join AP → list clips → fetch gyro (window + parallelism selectable, shows ms/request = B1a measurement), CSV to Downloads/WHRRAH. Protocol code vendored from osmosis (MIT, `dev/konraditurbe/osmosis/`). Gyro decoder ported to Kotlin (B1f done: matches Python exactly on fixture + real 4.4 GB clip). **B1c–e untested on a real camera** — first thing to check with the Osmo. Target: basic lap timer + DJI + RaceBox for Sunday autox (2026-11-01).
- 2026-10-25 · desk · S7 · Design doc: goal is now to replace the AiM Solo (phone logger + on-device overlay, SoloStorm-style); .xrk is testing-only. Rewrote §8 for WiFi camera access. Added workstream L (sessions + lap timing).
- 2026-10-25 · desk · S1, S2, S3 · Committed all Android work, pushed to github.com/LUNK333/WHRRAH-android (branch `main`). Pushed `wifi_gyro_sync` to WHRRAH. Dropped 59.94 fps gyro task (out of scope: those recording formats have no gyro).
- 2026-10-25 · desk · — · Board created from `WHRRAH android ver. development dashboard.xlsx`. Fixed `.gitignore` (was about to commit `app/build/` + 348 MB `rust/xrk-ffi/target/`).
