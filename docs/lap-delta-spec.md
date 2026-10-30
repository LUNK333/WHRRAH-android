# WHRRAH Android — Live Lap Delta Spec

Oct 30, 2026 · @Luke

## Overview

Live delta = time elapsed in the current lap minus the time the session-best lap took to reach the same distance along the track. Positive means behind, negative means ahead.

Each GPS fix is projected onto the best lap's trace to get a continuous distance-along-track `s`. The best lap's time at `s` is interpolated, and the difference is the delta. This replaces fixed-width boxes: no width tuning, no stair-stepping, and it degrades gracefully when the line differs.

Cost per fix is about 60 point-to-segment projections inside a search window, which is microseconds on any phone. At 25 Hz the CPU load is negligible; accuracy depends on lap-line timing and GPS noise, not compute.

Assumptions: fixes arrive at roughly 25 Hz with GPS timestamps, and the track is a closed loop with one start/finish line.

## Coordinate setup

Convert every lat/lon to local x/y meters once, at ingest, with an equirectangular projection around a fixed origin (the start/finish point is a good choice). At track scale the distortion is negligible, and all later math becomes plain 2D vectors.

```latex
x = (\lambda - \lambda_0) \cdot \cos(\varphi_0) \cdot R \cdot \pi / 180
```

```latex
y = (\varphi - \varphi_0) \cdot R \cdot \pi / 180
```

Here φ is latitude, λ is longitude in degrees, and R = 6,371,008.8 m. Precompute `cos(φ0) · R · π/180` as a constant. Keep the conversion in `Double`; the stored trace can be `Double` too since it is only a few thousand points.

The origin must be the same for the reference lap and the live fixes, so store it with the reference.

## Building the reference lap

The reference is the best valid lap, resampled to one point per meter of track distance, each carrying the time the best lap reached it.

1. Take the lap's fixes from start crossing to end crossing. Prepend a synthetic point at the start line (t = 0) and append one at the end line (t = lap time), both from the interpolated crossings.
2. Lightly smooth x/y with a 3 to 5 point centered moving average. Raw jitter inflates cumulative distance and makes `s` drift.
3. Compute cumulative distance along the smoothed points.
4. Resample at a fixed 1.0 m step: for each target distance, linearly interpolate x, y and t between the bracketing source points.
5. Store as parallel arrays `xs`, `ys`, `ss`, `ts` (index i is at s = i meters), plus total length `L` and the lap time `T`.

A 2 km track gives about 2,000 points, roughly 64 KB as four `DoubleArray`s. Building it is a single linear pass, well under 10 ms.

Because the last point is at the finish line with t = T, a live lap that ends exactly on the reference gets a final delta equal to the true lap-time difference.

## Live loop (per fix)

Each fix is projected onto a small window of reference segments around the last known position, never the whole trace. The window keeps it O(1) and stops the projection from snapping to a parallel section of track.

1. Convert the fix to `p = (x, y)`.
2. Candidate segments: indices from `lastIdx − 10` to `lastIdx + 40` (meters). Wrap indices modulo N near the start/finish.
3. For each segment a→b, project and clamp, then keep the segment with the smallest squared distance.
4. From the winning segment i and fraction u, compute `s = ss[i] + u · step` and `tRef = ts[i] + u · (ts[i+1] − ts[i])`.
5. `delta = (fixTime − lapStartTime) − tRef`.
6. Set `lastIdx = i`.

The projection for one segment:

```latex
u = \mathrm{clamp}\left(\frac{(p - a) \cdot (b - a)}{\lVert b - a \rVert^2},\ 0,\ 1\right)
```

```latex
d^2 = \lVert p - (a + u(b - a)) \rVert^2
```

Since every segment is 1 m, `‖b − a‖²` can be precomputed per segment or approximated as 1.

Guards:

- **Lateral limit.** If `sqrt(d²)` exceeds about 15 m, mark the fix off-track: don't update `lastIdx`, and grey out the delta.
- **No going backwards.** At low speed, jitter can pull `s` back. Clamp to `s ≥ sPrev − 2 m` within a lap.
- **Window size.** At 60 m/s and 25 Hz the car moves 2.4 m per fix, so +40 m covers about 16 dropped fixes. If several fixes are missed, widen the forward window by `speed × gap × 1.5`.
- **Reacquire.** After roughly 1 s off-track or a dropout, run one global search over all N segments (about 2,000 checks, still trivial) and accept it only if lateral distance is under the limit.

## Start/finish crossing

Lap boundaries are interpolated between fixes, not snapped to the nearest one. At 25 Hz, snapping adds up to 40 ms of error per lap, which is larger than many of the deltas worth showing.

The line is a segment about 30 m wide, centered on the start/finish point and perpendicular to the reference trace's heading there.

For each consecutive pair of fixes p0 (t0) and p1 (t1):

1. Test segment p0→p1 against the line segment for intersection, giving fraction f along p0→p1.
2. Check direction: the dot product of (p1 − p0) with the track heading must be positive.
3. `tCross = t0 + f · (t1 − t0)`.
4. Debounce: ignore a crossing within 10 s of the previous one.

On a crossing: close the lap (`lapTime = tCross − lapStartTime`), set `lapStartTime = tCross`, reset `lastIdx = 0` and `sPrev = 0`. The first crossing of a session only arms timing; the out-lap is never timed.

Use the GPS timestamps from the receiver, never the phone's arrival time. Bluetooth or USB delivery jitter would otherwise leak straight into lap times.

## Delta output and display

Compute delta on every fix, but render at about 10 Hz with light smoothing so the number is readable at speed.

- **Smoothing:** exponential moving average with α ≈ 0.3 per fix (about 0.1 s time constant at 25 Hz). Reset it at each lap start.
- **Delta rate:** slope of delta over the last \~0.5 s (12 fixes), via least squares or a difference of two EMAs. Negative = gaining. Drive the display color from this: green while gaining, red while losing.
- **Format:** signed, two decimals, e.g. `+0.42` / `−0.17`.
- **Predicted lap:** `T + delta` is free to show alongside.
- **At the line:** show the final lap-time difference (exact, from the crossing interpolation) for 3 s, then switch to the new lap's live delta.

Publish a `DeltaState` through a `StateFlow` and let the UI sample it at frame rate; the tracker itself never touches the UI thread.

## Edge cases

| Case | Behavior |
| --- | --- |
| No reference yet | Show the running lap timer only; the first valid timed lap becomes the reference. |
| New best lap | At the crossing, if the lap is valid and faster than `T`, build a new reference from its fixes and swap it in before the next fix is processed. Never swap mid-lap. |
| Invalid lap | Any fix gap over 0.5 s, more than 1 s off-track, or total `s` more than 5% off `L`. Still timed, never used as a reference. |
| GPS dropout | Hold the last delta, greyed out; reacquire with a global search when fixes return. |
| Pit lane | Lateral limit trips, delta blanks. A pit exit that misses the line simply doesn't start a lap. |
| Different line | Path length differs slightly from the reference, so mid-lap delta carries a small error. Every commercial delta timer has this; the final delta at the line is still exact. |
| Sections close together | Handled by the search window; only a lost-lock reacquire uses a global search, and that is gated by the lateral limit. |
| Session restart / new track | Clear reference, origin and line; keep the old reference only if the user reloads it for the same track. |

## Suggested Kotlin layout

Four small classes with no Android dependencies, so all of it runs in plain JVM unit tests. Avoid allocating per fix: reuse scratch fields instead of returning `Pair`s.

```kotlin
data class Fix(val tSec: Double, val lat: Double, val lon: Double, val speedMps: Float?)

class LocalProjector(lat0: Double, lon0: Double) {
    fun toX(lon: Double): Double
    fun toY(lat: Double): Double
}

class ReferenceLap(
    val xs: DoubleArray, val ys: DoubleArray,
    val ss: DoubleArray, val ts: DoubleArray,
    val length: Double, val lapTime: Double,
) {
    companion object {
        fun build(fixes: List<Fix>, tStart: Double, tEnd: Double,
                  proj: LocalProjector, stepM: Double = 1.0): ReferenceLap
    }
}

class StartFinishLine(ax: Double, ay: Double, bx: Double, by: Double,
                      headingX: Double, headingY: Double) {
    /** Returns fraction f in [0,1] along p0->p1, or NaN if no forward crossing. */
    fun crossing(x0: Double, y0: Double, x1: Double, y1: Double): Double
}

class DeltaTracker(private val proj: LocalProjector,
                   private val line: StartFinishLine) {
    var reference: ReferenceLap? = null
    val state: StateFlow<DeltaState>
    fun onFix(fix: Fix)
}

data class DeltaState(
    val lapElapsed: Double?,
    val delta: Double?,        // null = no reference or off-track
    val deltaRate: Double?,    // s per s; negative = gaining
    val s: Double?,
    val onTrack: Boolean,
    val lastLap: LapResult?,
)

data class LapResult(val lapTime: Double, val valid: Boolean, val deltaToBest: Double?)
```

Run `onFix` on a single-thread executor fed by the GPS callback. Rebuilding the reference after a best lap fits inside one fix interval, so it can run inline.

## Testing

Build a replay harness first: feed a recorded session's fixes into `DeltaTracker` at real time and at 100× speed. Everything below runs against it.

- [ ] **Synthetic circle:** constant speed, then 1% faster. Delta should fall linearly to exactly −1% of `T` at the line.
- [ ] **Line interpolation:** fixes straddling the line at known positions; `tCross` must match to within 1 ms.
- [ ] **Wrap-around:** `s` near `L` on one fix and near 0 on the next does not produce a spike.
- [ ] **Hairpin / parallel sections:** a fixture with two track sections 10 m apart; the window must never jump between them.
- [ ] **Dropouts:** delete 0.5 s and 3 s of fixes from a real lap; check hold, grey-out and clean reacquire.
- [ ] **Real session:** final delta at each line equals the true lap-time difference; mid-lap delta compared against the logger's own delta or predictive channel, if recorded.
- [ ] **Performance:** micro-benchmark `onFix`. Expect single-digit microseconds; anything near a millisecond means the window isn't being used.
