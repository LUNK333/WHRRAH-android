# Vendored: osmosis (MIT)

Copied unchanged from https://github.com/KonradIT/osmosis at commit `abd7eaa`
(2026-10-23), see `LICENSE.txt`. Only the camera media-link subset WHRRAH needs:
DUML framing (`duml/`), BLE GATT client (`ble/`), WiFi AP join + HTTP + UDP
datalink (`net/`), and the Osmo media-list session (`camera/`, `core/`, `dcf/`).

Don't edit these files: WHRRAH's own code that drives them is
`com.lunk333.whrrah.osmo.OsmoLink`. To update, re-copy the same files from a newer
osmosis checkout and re-run the unit tests (`Oa4ManifestTest`, `Oa4StarredManifestTest`
are vendored from osmosis too).

Also `rsdk/RsdkController.kt` + `rsdk/RsdkProtocol.kt` (DJI R-SDK remote protocol: BLE camera
status / recording state), with their tests, driven by `com.lunk333.whrrah.osmo.RecordingMonitor`.
