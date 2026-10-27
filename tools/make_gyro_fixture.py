"""Build the small DJI gyro test fixture for DjiGyroTest from a real Osmo clip.

Real clips are 4-11 GB, so this writes a synthetic MP4 that keeps only what the
decoder reads: ftyp, a few frames' worth of real djmd samples in an mdat, and a
moov at the END (as on the camera) whose djmd track is cut down to those frames.
A second, untouched track rides along so the decoder has to pick djmd out.

Expected values come from the reference Python decoder
(WHRRAH/wifi_gyro_sync/osmo_wifi_gyro.py), which was validated against
telemetry_parser on 6 real clips.

    py tools/make_gyro_fixture.py "E:/Videos/.../DJI_..._D.MP4"

Writes app/src/test/resources/gyro/{oa4_75f.mp4,oa4_75f_1khz.csv,oa4_75f_window_1khz.csv,oa4_75f_frames.csv}.
"""

from __future__ import annotations

import csv
import os
import struct
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
sys.path.insert(0, os.path.join(HERE, "..", "..", "WHRRAH", "wifi_gyro_sync"))
import osmo_wifi_gyro as g  # noqa: E402

FRAMES = 75                 # 3 s at 25 fps
WINDOW = range(30, 60)      # a window that doesn't start at frame 0
OUT = os.path.join(HERE, "..", "app", "src", "test", "resources", "gyro")
CONTAINERS = {b"trak", b"mdia", b"minf", b"stbl"}


def box(btype: bytes, payload: bytes) -> bytes:
    return struct.pack(">I4s", 8 + len(payload), btype) + payload


def rebuild(moov: bytes, s: int, e: int, new_tables: dict[bytes, bytes]) -> bytes:
    """Re-emit the boxes in moov[s:e], swapping in new sample tables inside stbl."""
    out = b""
    for bt, a, b in g._boxes(moov, s, e):
        if bt in new_tables:
            out += new_tables[bt]
        elif bt in (b"stsc", b"stsz", b"stco", b"co64"):
            continue  # dropped, replaced by new_tables
        elif bt in CONTAINERS:
            out += box(bt, rebuild(moov, a, b, new_tables))
        else:
            out += moov[a - 8:b]
    return out


def main(src: str) -> int:
    reader = g.FileRangeReader(src)
    with open(src, "rb") as f:
        size, btype = struct.unpack(">I4s", f.read(8))
        assert btype == b"ftyp"
        f.seek(0)
        ftyp = f.read(size)
    moov = g.read_moov(reader)
    tracks = g.parse_tracks(moov)
    traks = [(a, b) for bt, a, b in g._boxes(moov, 0, len(moov)) if bt == b"trak"]
    di = next(i for i, t in enumerate(tracks) if t.codec == "djmd")
    ranges = tracks[di].sample_ranges()[:FRAMES]
    samples = [reader.read(o, n) for o, n in ranges]

    # Layout: ftyp | free | mdat(samples) | moov
    free = box(b"free", b"\0" * 32)
    mdat_payload_start = len(ftyp) + len(free) + 8
    offsets, pos = [], mdat_payload_start
    for s in samples:
        offsets.append(pos)
        pos += len(s)
    mdat = box(b"mdat", b"".join(samples))

    tables = {
        b"stsc": box(b"stsc", struct.pack(">II", 0, 1) + struct.pack(">III", 1, 1, 1)),
        b"stsz": box(b"stsz", struct.pack(">III", 0, 0, FRAMES) + struct.pack(f">{FRAMES}I", *map(len, samples))),
        b"co64": box(b"co64", struct.pack(">II", 0, FRAMES) + struct.pack(f">{FRAMES}Q", *offsets)),
    }
    mvhd = next(moov[a - 8:b] for bt, a, b in g._boxes(moov, 0, len(moov)) if bt == b"mvhd")
    other = traks[(di + 1) % len(traks)]  # an untouched non-djmd track
    da, db = traks[di]
    new_moov = box(b"moov", mvhd + moov[other[0] - 8:other[1]] + box(b"trak", rebuild(moov, da, db, tables)))

    os.makedirs(OUT, exist_ok=True)
    mp4 = os.path.join(OUT, "oa4_75f.mp4")
    with open(mp4, "wb") as f:
        f.write(ftyp + free + mdat + new_moov)

    # The synthetic file must decode exactly like the same frames of the real clip.
    real = g.samples_1khz(g.fetch_gyro(reader, range(FRAMES)))
    syn_track = g.fetch_gyro(g.FileRangeReader(mp4))
    syn = g.samples_1khz(syn_track)
    assert real == syn, "synthetic fixture decodes differently from the real clip"
    window = g.samples_1khz(g.fetch_gyro(g.FileRangeReader(mp4), WINDOW))

    def write(name: str, header: list[str], rows) -> None:
        with open(os.path.join(OUT, name), "w", newline="") as f:
            w = csv.writer(f)
            w.writerow(header)
            w.writerows([repr(v) if isinstance(v, float) else v for v in r] for r in rows)

    write("oa4_75f_1khz.csv", ["t_ms", "w", "x", "y", "z"], syn)
    write("oa4_75f_window_1khz.csv", ["t_ms", "w", "x", "y", "z"], window)
    h = syn_track.header
    write("oa4_75f_frames.csv",
          ["index", "frame_ts_us", "imu_ts_us", "imu_counter", "n_quats", "model", "firmware", "imu_hz", "fps"],
          [[fr.index, fr.frame_ts_us, fr.imu_ts_us, fr.imu_counter, len(fr.quats),
            h.model, h.firmware, h.imu_hz, h.fps] for fr in syn_track.frames])
    print(f"{mp4}: {os.path.getsize(mp4)} B, {len(syn)} samples ({h.model} fw {h.firmware}, {h.fps} fps)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1]))
