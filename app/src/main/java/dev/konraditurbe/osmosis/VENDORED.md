# Vendored: osmosis (MIT)

Copied unchanged from https://github.com/KonradIT/osmosis at commit `abd7eaa`
(2026-10-23), see `LICENSE.txt`. Only the camera media-link subset WHRRAH needs:
DUML framing (`duml/`), BLE GATT client (`ble/`), WiFi AP join + HTTP + UDP
datalink (`net/`), and the Osmo media-list session (`camera/`, `core/`, `dcf/`).

Don't edit these files: WHRRAH's own code that drives them is
`com.lunk333.whrrah.osmo.OsmoLink`. To update, re-copy the same files from a newer
osmosis checkout and re-run the unit tests (`Oa4ManifestTest`, `Oa4StarredManifestTest`
are vendored from osmosis too).

## Local patches (marked `WHRRAH patch` in the code)

- `net/DumlSession.kt`: `onRecordState` callback — recording bit + elapsed seconds from each
  `0x02/0x80` status push.
- `camera/CameraSession.kt`: `holdPlayback` — `false` keeps the keep-alive running (AP up, status pushes) but stops the app-presence beat that holds playback (the Action 4 has no playback exit command; it falls back to capture once the beat stops), and also sends the generic leave.

Re-apply these if the vendored files are ever re-copied.
