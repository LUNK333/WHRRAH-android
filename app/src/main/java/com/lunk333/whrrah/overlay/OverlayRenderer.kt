package com.lunk333.whrrah.overlay

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Typeface
import com.lunk333.whrrah.overlay.OverlayWidget.Companion.BAR_GRAPH
import com.lunk333.whrrah.overlay.OverlayWidget.Companion.BASE_FONT_SIZE
import com.lunk333.whrrah.overlay.OverlayWidget.Companion.BASE_SIZES
import com.lunk333.whrrah.overlay.OverlayWidget.Companion.DATA_TRACE
import com.lunk333.whrrah.overlay.OverlayWidget.Companion.G_METER
import com.lunk333.whrrah.overlay.OverlayWidget.Companion.LAP_TIMER
import com.lunk333.whrrah.overlay.OverlayWidget.Companion.NUMERIC
import com.lunk333.whrrah.overlay.OverlayWidget.Companion.TRACK_MAP
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * Draws a layout's widgets onto an [android.graphics.Canvas] in overlay pixels — a port of desktop
 * WHRRAH's export renderer (`OverlayWidget.render_to_frame` and the `_draw_*` helpers), so a layout
 * looks the same in both apps. The caller maps overlay px to the screen (scale/translate the canvas).
 *
 * Text: desktop measures and draws with Consolas; here it's the system monospace, close but not
 * identical, so text-sized boxes (Lap Timer, Numeric Display) can come out a few % different.
 * Measurements follow PIL's: "text size" is the tight ink box, "line height" ascent + descent.
 */
class OverlayRenderer {
    private val regular = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
    private val bold = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    private val text = Paint(Paint.ANTI_ALIAS_FLAG)
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val bounds = Rect()
    private val sizeCache = HashMap<OverlayWidget, Pair<Int, Int>>()

    fun draw(c: Canvas, layout: OverlayLayout, data: RunData?, tSec: Double) {
        for (w in layout.widgets) {
            val t = tSec + w.timeOffset
            val (bw, bh) = size(w)
            when (w.type) {
                LAP_TIMER -> drawLapTimer(c, w, bw, bh, data, t)
                NUMERIC -> drawNumeric(c, w, bw, bh, data?.valueAt(w.channel, t) ?: 0.0)
                BAR_GRAPH -> drawBar(c, w, bw, bh, data?.takeIf { w.channel.isNotEmpty() }?.valueAt(w.channel, t) ?: 0.0)
                TRACK_MAP -> drawTrackMap(c, w, bw, bh, data, t)
                DATA_TRACE -> drawTrace(c, w, bw, bh, data, t)
                G_METER -> drawGMeter(c, w, bw, bh, data, t)
            }
        }
    }

    /** Box size in overlay px (desktop `OverlayWidget.w` / `.h`). */
    fun size(w: OverlayWidget): Pair<Int, Int> = sizeCache.getOrPut(w) {
        val fs = w.fontSize
        when (w.type) {
            LAP_TIMER -> {
                val lines = lapLines(w)
                val ref = if (lines.any { it != "Lap" }) "Time: 00:00:000" else "Lap: 0"
                val tw = ink(ref, lapTextPx(fs), true).first
                (tw + 16 + (fs * 0.3).toInt()) to (lineGap(fs) * max(1, lines.size) + 8 + (fs * 0.35).toInt())
            }
            NUMERIC -> {
                val border = numericBorder(fs)
                val (vw, vh) = ink(numericRef(w), fs, true)
                val (lw, lh) = if (w.label.isNotEmpty()) ink(w.label, w.labelFontSize, false) else 0 to 0
                val labelPart = if (w.label.isNotEmpty()) labelGapPx(w) + lh else 0
                (max(vw, lw) + 2 * border) to (border + vh + labelPart + border)
            }
            else -> {
                val (bw, bh) = BASE_SIZES[w.type] ?: (200 to 60)
                max(1, (bw * w.scaleX).toInt()) to max(1, (bh * w.scaleY).toInt())
            }
        }
    }

    // ---- text, measured the way PIL does it ------------------------------------------------------

    private fun use(px: Number, isBold: Boolean, color: Int = Color.WHITE): Paint =
        text.apply { textSize = max(1f, px.toFloat()); typeface = if (isBold) bold else regular; this.color = color }

    /** Tight ink (width, height) — PIL `getbbox`. */
    private fun ink(s: String, px: Int, isBold: Boolean): Pair<Int, Int> {
        if (s.isEmpty()) return 0 to 0
        use(px, isBold).getTextBounds(s, 0, s.length, bounds)
        return bounds.width() to bounds.height()
    }

    /** ascent + descent — PIL `getmetrics`. */
    private fun lineHeight(px: Int, isBold: Boolean): Int = use(px, isBold).fontMetrics.let { (it.descent - it.ascent).roundToInt() }

    /** Draws with the ink's top-left at (x, y) — desktop `_pil_draw_text`. */
    private fun drawInk(c: Canvas, s: String, x: Float, y: Float, px: Int, isBold: Boolean, color: Int) {
        if (s.isEmpty()) return
        val p = use(px, isBold, color)
        p.getTextBounds(s, 0, s.length, bounds)
        c.drawText(s, x - bounds.left, y - bounds.top, p)
    }

    /** Draws [s] with its ink aligned at [ax] ("left" edge / "center" / "right" edge), ink top at [top]. */
    private fun drawAligned(c: Canvas, s: String, ax: Float, top: Float, align: String, px: Int, isBold: Boolean, color: Int) {
        val tw = ink(s, px, isBold).first
        val x = when (align) { "right" -> ax - tw; "center" -> ax - tw / 2f; else -> ax }
        drawInk(c, s, x, top, px, isBold, color)
    }

    private fun bgr(c: IntArray) = Color.rgb(c[2], c[1], c[0])
    private fun box(c: Canvas, w: OverlayWidget, bw: Int, bh: Int) {
        fill.color = BG
        c.drawRect(w.x.toFloat(), w.y.toFloat(), (w.x + bw).toFloat(), (w.y + bh).toFloat(), fill)
    }

    // ---- Lap Timer -------------------------------------------------------------------------------

    private fun lapLines(w: OverlayWidget) = buildList {
        if (w.showTime) add("Time"); if (w.showLast) add("Last"); if (w.showBest) add("Best"); if (w.showLap) add("Lap")
    }

    /** Line spacing grows with sqrt(font size) so lines don't spread apart at big scales. */
    private fun lineGap(fs: Int) = (BASE_FONT_SIZE * 1.3 * sqrt(fs / BASE_FONT_SIZE.toDouble())).toInt()
    private fun lapTextPx(fs: Int) = max(6, (100.0 * lineGap(fs) / max(1, lineHeight(100, true))).toInt())

    private fun drawLapTimer(c: Canvas, w: OverlayWidget, bw: Int, bh: Int, d: RunData?, t: Double) {
        box(c, w, bw, bh)
        val lines = lapLines(w).map { k ->
            when (k) {
                "Time" -> "Time: " to lapClock(d?.lapTimeAt(t) ?: if (d == null) 0.0 else null)
                "Last" -> "Last: " to lapClock(d?.lastLapAt(t))
                "Best" -> "Best: " to lapClock(d?.bestLapAt(t))
                else -> "Lap: " to (d?.runNumber ?: 0).toString()
            }
        }
        val lineH = lineGap(w.fontSize)
        val px = lapTextPx(w.fontSize)
        val col = bgr(w.textColor)
        lines.forEachIndexed { i, (label, value) ->
            val th = ink(label, px, false).second
            val top = w.y + lineH * i + max(0, (lineH - th) / 2).toFloat()
            drawInk(c, label, w.x + 8f, top, px, false, col)
            val labelW = use(px, false).measureText(label)
            drawInk(c, value, w.x + 8f + labelW, top, px, true, col)
        }
    }

    // ---- Numeric Display -------------------------------------------------------------------------

    private fun numericBorder(fs: Int) = max(4, (fs * 0.12).toInt())
    private fun numericRef(w: OverlayWidget) = "0".repeat(max(1, w.intDigits)) + if (w.decimals > 0) "." + "0".repeat(w.decimals) else ""
    private fun labelGapPx(w: OverlayWidget) =
        max(-ink(numericRef(w), w.fontSize, true).second, max(2, (w.labelFontSize * 0.3).toInt()) + w.labelGap)

    private fun drawNumeric(c: Canvas, w: OverlayWidget, bw: Int, bh: Int, value: Double) {
        box(c, w, bw, bh)
        val col = bgr(w.textColor)
        val border = numericBorder(w.fontSize)
        val vh = ink(numericRef(w), w.fontSize, true).second
        val items = buildList {
            add(Triple(fmt(value, w.decimals), border, w.fontSize to true))
            if (w.label.isNotEmpty()) add(Triple(w.label, border + vh + labelGapPx(w), w.labelFontSize to false))
        }
        for ((s, top, f) in items) {
            val tw = ink(s, f.first, f.second).first
            drawInk(c, s, w.x + (bw - tw) / 2f, (w.y + top).toFloat(), f.first, f.second, col)
        }
    }

    // ---- Bar Graph -------------------------------------------------------------------------------

    private fun drawBar(c: Canvas, w: OverlayWidget, bw: Int, bh: Int, value: Double) {
        box(c, w, bw, bh)
        val col = bgr(w.color)
        stroke.color = col; stroke.strokeWidth = 2f
        c.drawRect(w.x.toFloat(), w.y.toFloat(), (w.x + bw).toFloat(), (w.y + bh).toFloat(), stroke)
        val pct = if (w.maxVal != w.minVal) ((value - w.minVal) / (w.maxVal - w.minVal)).coerceIn(0.0, 1.0) else 0.0
        val barW = ((bw - 4) * pct).toInt()
        if (barW > 0) { fill.color = col; c.drawRect(w.x + 2f, w.y + 2f, (w.x + 2 + barW).toFloat(), (w.y + bh - 2).toFloat(), fill) }
        val s = if (w.showValue) "${w.label}: ${fmt(value, w.decimals)}" else w.label
        if (s.isEmpty()) return
        val pad = 6
        val probeW = ink(s, 100, false).first
        val probeH = lineHeight(100, false)
        val px = max(6, (100 * min(max(1, bh - 2 * pad).toDouble() / max(1, probeH), max(1, bw - 2 * pad).toDouble() / max(1, probeW))).toInt())
        val th = ink(s, px, false).second
        drawInk(c, s, w.x + pad.toFloat(), w.y + max(0, (bh - th) / 2).toFloat(), px, false, bgr(w.textColor))
    }

    // ---- Track Map -------------------------------------------------------------------------------

    /** GPS → 0..1 box coords, north up, then rotated clockwise by [rotation] (desktop `_track_map_uv`). */
    private fun uv(lat: Double, lon: Double, b: DoubleArray, rotation: Int): Pair<Double, Double> {
        var u = (lon - b[2]) / (b[3] - b[2] + 1e-9)
        var v = 1 - (lat - b[0]) / (b[1] - b[0] + 1e-9)
        repeat(((rotation / 90) % 4 + 4) % 4) { val nu = 1 - v; v = u; u = nu }
        return u to v
    }

    private fun drawTrackMap(c: Canvas, w: OverlayWidget, bw: Int, bh: Int, d: RunData?, t: Double) {
        val b = d?.trackBounds ?: return
        if (d.track.isEmpty()) return
        val pad = 10
        val top = if (w.label.isNotEmpty()) 8 + lineHeight(w.fontSize, false) else 0
        fun pt(lat: Double, lon: Double): Pair<Float, Float> {
            val (u, v) = uv(lat, lon, b, w.mapRotation)
            return (u * (bw - pad * 2) + w.x + pad).toFloat() to (v * (bh - top - pad * 2) + w.y + top + pad).toFloat()
        }
        box(c, w, bw, bh)
        if (w.label.isNotEmpty()) drawInk(c, w.label, w.x + 8f, w.y + 8f, w.fontSize, false, bgr(w.textColor))
        val path = Path()
        d.track.forEachIndexed { i, (la, lo) -> val (x, y) = pt(la, lo); if (i == 0) path.moveTo(x, y) else path.lineTo(x, y) }
        stroke.color = Color.rgb(150, 150, 150); stroke.strokeWidth = 2f
        c.drawPath(path, stroke)
        d.positionAt(t)?.let { (la, lo) ->
            val (x, y) = pt(la, lo)
            fill.color = Color.RED
            c.drawCircle(x, y, 6f, fill)
        }
    }

    // ---- Data Trace ------------------------------------------------------------------------------

    private fun drawTrace(c: Canvas, w: OverlayWidget, bw: Int, bh: Int, d: RunData?, t: Double) {
        box(c, w, bw, bh)
        val labelPx = if (w.label.isNotEmpty()) max(8, (bh * 0.18).toInt()) else 0
        if (w.label.isNotEmpty()) drawInk(c, w.label, w.x + 6f, w.y + 4f, labelPx, false, bgr(w.textColor))
        if (d == null || w.channel.isEmpty()) return
        val pad = max(3.0, min(bw, bh) * 0.06)
        val px0 = w.x + pad; val px1 = w.x + bw - pad
        val py0 = w.y + pad + labelPx; val py1 = w.y + bh - pad
        if (px1 - px0 < 2 || py1 - py0 < 2) return
        val n = ((px1 - px0) / 2).toInt().coerceIn(20, 300)
        val half = w.traceWindow / 2
        val (frac, vals) = d.traceValues(w.channel, t - half, t + half, n) ?: return
        val span = (w.maxVal - w.minVal).takeIf { it != 0.0 } ?: 1.0
        fun toY(v: Double) = py1 - ((v - w.minVal) / span).coerceIn(0.0, 1.0) * (py1 - py0)
        stroke.strokeWidth = 1f; stroke.color = Color.rgb(60, 60, 60)
        c.drawLine(px0.toFloat(), py0.toFloat(), px1.toFloat(), py0.toFloat(), stroke)
        c.drawLine(px0.toFloat(), py1.toFloat(), px1.toFloat(), py1.toFloat(), stroke)
        val cx = (px0 + px1) / 2
        stroke.color = Color.rgb(170, 170, 170)
        c.drawLine(cx.toFloat(), py0.toFloat(), cx.toFloat(), py1.toFloat(), stroke)
        val col = bgr(w.color)
        val future = Color.rgb((Color.red(col) * 0.45).toInt(), (Color.green(col) * 0.45).toInt(), (Color.blue(col) * 0.45).toInt())
        val thick = max(1, (2 * w.scaleY).roundToInt()).toFloat()
        val xs = frac.map { px0 + it * (px1 - px0) }
        val split = xs.indexOfFirst { it >= cx }.let { if (it < 0) xs.size else it }
        stroke.strokeWidth = thick
        for ((range, color) in listOf(0..min(split, xs.size - 1) to col, split until xs.size to future)) {
            if (range.count() < 2) continue
            val p = Path()
            range.forEachIndexed { k, i -> val y = toY(vals[i]).toFloat(); if (k == 0) p.moveTo(xs[i].toFloat(), y) else p.lineTo(xs[i].toFloat(), y) }
            stroke.color = color
            c.drawPath(p, stroke)
        }
        fill.color = col
        c.drawCircle(cx.toFloat(), toY(d.valueAt(w.channel, t)).toFloat(), thick + 2, fill)
    }

    // ---- G Meter ---------------------------------------------------------------------------------

    private fun drawGMeter(c: Canvas, w: OverlayWidget, bw: Int, bh: Int, d: RunData?, t: Double) {
        box(c, w, bw, bh)
        val x = w.x.toDouble(); val y = w.y.toDouble(); val ww = bw.toDouble(); val hh = bh.toDouble()
        var longG = if (d != null && w.longChannel.isNotEmpty()) d.valueAt(w.longChannel, t) else 0.0
        var latG = if (d != null && w.latChannel.isNotEmpty()) d.valueAt(w.latChannel, t) else 0.0
        // Both axes drawn sign-flipped by default (braking moves the marker up), as desktop.
        if (!w.invertLong) longG = -longG
        if (!w.invertLat) latG = -latG
        val gsum = hypot(longG, latG)
        val pad = max(3.0, min(ww, hh) * 0.05)
        val cx = x + ww / 2; val cy = y + hh / 2
        val rl = max(0.1, w.gRangeLong); val rt = max(0.1, w.gRangeLat)
        val fontPx = max(8, (min(ww, hh) * 0.085).toInt())
        val smallPx = max(7, (fontPx * 0.6).toInt())
        val vTop = y + pad + smallPx * 1.4; val vBot = y + hh - pad - smallPx * 1.4
        val hLeft = x + pad + smallPx * 1.2; val hRight = x + ww - pad - smallPx * 1.2
        val halfH = (vBot - vTop) / 2; val halfW = (hRight - hLeft) / 2
        stroke.strokeWidth = 1f
        stroke.color = Color.rgb(150, 150, 150)
        line(c, cx, vTop, cx, vBot); line(c, hLeft, cy, hRight, cy)
        stroke.color = Color.rgb(110, 110, 110)
        val tick = max(3.0, min(ww, hh) * 0.04)
        for ((range, half, vertical) in listOf(Triple(rl, halfH, true), Triple(rt, halfW, false))) {
            val n = (range / 0.5 + 1e-6).toInt()
            for (i in -n..n) {
                if (i == 0) continue
                val off = i * 0.5 / range * half
                if (vertical) line(c, cx - tick, cy - off, cx + tick, cy - off) else line(c, cx + off, cy - tick, cx + off, cy + tick)
            }
        }
        val mx = cx + (latG / rt).coerceIn(-1.0, 1.0) * halfW
        val my = cy - (longG / rl).coerceIn(-1.0, 1.0) * halfH
        val marker = max(2.0, min(ww, hh) * 0.045 * w.markerScale)

        val tcol = bgr(w.textColor)
        val lineH = fontPx * 1.2
        val right = x + ww - pad
        val dec = w.decimals
        val texts = listOf(
            listOf("Σ", x + pad, y + pad, "left", fontPx, true),
            listOf("${fmt(gsum, dec)}G", x + pad, y + pad + lineH, "left", fontPx, true),
            listOf("${fmt(abs(longG), dec)}G", right, y + pad + fontPx + 2, "right", fontPx, true),
            listOf("${fmt(abs(latG), dec)}G", right, y + hh - pad - lineH, "right", fontPx, true),
            listOf("BRAKE", cx, y + pad, "center", smallPx, false),
            listOf("ACCEL", cx, y + hh - pad - smallPx, "center", smallPx, false),
            listOf("R", x + pad, cy - smallPx / 2.0, "left", smallPx, false),
            listOf("L", x + ww - pad, cy - smallPx / 2.0, "right", smallPx, false),
        )
        for (tx in texts) drawAligned(c, tx[0] as String, (tx[1] as Double).toFloat(), (tx[2] as Double).toFloat(),
            tx[3] as String, tx[4] as Int, tx[5] as Boolean, tcol)

        // Chevron icons: double up/down beside the longitudinal value, « » above the lateral one.
        val s = fontPx * 0.32
        fun chevron(px: Double, py: Double, dx: Int, dy: Int) {
            val tipX = px + dx * s * 0.5; val tipY = py + dy * s * 0.5
            if (dx == 0) { line(c, px - s * 0.7, tipY - dy * s, tipX, tipY); line(c, px + s * 0.7, tipY - dy * s, tipX, tipY) }
            else { line(c, tipX - dx * s, py - s * 0.7, tipX, tipY); line(c, tipX - dx * s, py + s * 0.7, tipX, tipY) }
        }
        fun double(px: Double, py: Double, dx: Int, dy: Int) {
            chevron(px - dx * s * 0.6, py - dy * s * 0.6, dx, dy); chevron(px + dx * s * 0.6, py + dy * s * 0.6, dx, dy)
        }
        stroke.color = tcol; stroke.strokeWidth = max(1.5, fontPx * 0.09).toFloat()
        val ix = right - s * 2.5; val iy = y + pad + fontPx / 2.0
        double(ix - s * 1.3, iy, 0, -1); double(ix + s * 1.3, iy, 0, 1)
        val lx = right - s * 3.0; val ly = y + hh - pad - lineH - fontPx / 2.0
        double(lx - s * 1.8, ly, -1, 0); double(lx + s * 1.8, ly, 1, 0)

        // Component bars from the centre to the marker's projection, then the marker.
        stroke.color = bgr(w.color); stroke.strokeWidth = max(2.0, min(ww, hh) * 0.022).toFloat()
        line(c, cx, cy, cx, my); line(c, cx, cy, mx, cy)
        fill.color = bgr(w.color)
        val half = (marker * 0.75).toFloat()
        c.drawRect(RectF(mx.toFloat() - half, my.toFloat() - half, mx.toFloat() + half, my.toFloat() + half), fill)
    }

    private fun line(c: Canvas, x0: Double, y0: Double, x1: Double, y1: Double) =
        c.drawLine(x0.toFloat(), y0.toFloat(), x1.toFloat(), y1.toFloat(), stroke)

    companion object {
        /** Widget background: the desktop preview's (20, 20, 20) at alpha 200. */
        private val BG = Color.argb(200, 20, 20, 20)

        private fun fmt(v: Double, decimals: Int) = "%.${max(0, decimals)}f".format(java.util.Locale.US, v)

        /** mm:ss:mmm, as desktop `_format_lap_clock`; "--:--:---" for none. */
        fun lapClock(s: Double?): String {
            if (s == null) return "--:--:---"
            val total = (s * 1000).roundToInt()
            return "%02d:%02d:%03d".format(total / 60_000, (total / 1000) % 60, total % 1000)
        }
    }
}
