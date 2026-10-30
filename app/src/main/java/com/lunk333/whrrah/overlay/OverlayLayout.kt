package com.lunk333.whrrah.overlay

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.roundToInt

/**
 * A widget layout in desktop WHRRAH's layout JSON format (`File → Save Layout` there), so the same
 * file works in both apps:
 *
 * ```
 * { "resolution": [w, h], "aspect_ratio": "16:9", "crop_offset": [x, y], "widgets": [ {...}, ... ] }
 * ```
 *
 * Widget geometry is in overlay pixels of `resolution` (the video is letterboxed into that frame),
 * colours are BGR (desktop draws with OpenCV). Every field the desktop writes is read here; unknown
 * ones are kept in [raw] and written back untouched, so a file round-trips without losing anything
 * a newer desktop version added.
 */
data class OverlayLayout(
    val width: Int = 1920,
    val height: Int = 1080,
    /** "Original", "4:3", "16:9" or "21:9" — the export crop. */
    val aspectRatio: String = "Original",
    val cropOffsetX: Int = 0,
    val cropOffsetY: Int = 0,
    val widgets: List<OverlayWidget> = emptyList(),
    private val raw: JSONObject = JSONObject(),
) {
    /**
     * (x, y, w, h) in overlay px: the region the desktop export crops to — the whole frame for
     * "Original". Same rule as desktop `OverlayCanvas.crop_rect`: full width, crop the height;
     * only if that's too tall, full height and crop the width. Centred, moved by the crop offset.
     */
    fun cropRect(): IntArray {
        val ratio = ASPECT_RATIOS[aspectRatio] ?: return intArrayOf(0, 0, width, height)
        var cw = width
        var ch = (width / ratio).roundToInt()
        if (ch > height) { ch = height; cw = (ch * ratio).roundToInt() }
        cw = cw.coerceIn(1, width); ch = ch.coerceIn(1, height)
        val bx = (width - cw) / 2
        val by = (height - ch) / 2
        return intArrayOf(bx + cropOffsetX.coerceIn(-bx, bx), by + cropOffsetY.coerceIn(-by, by), cw, ch)
    }

    fun toJson(): JSONObject = JSONObject(raw.toString())
        .put("resolution", JSONArray(listOf(width, height)))
        .put("aspect_ratio", aspectRatio)
        .put("crop_offset", JSONArray(listOf(cropOffsetX, cropOffsetY)))
        .put("widgets", JSONArray(widgets.map { it.toJson() }))

    companion object {
        val ASPECT_RATIOS = mapOf("4:3" to 4.0 / 3, "16:9" to 16.0 / 9, "21:9" to 21.0 / 9)

        /** Also accepts the desktop's old format: a bare array of widgets. */
        fun parse(text: String): OverlayLayout {
            val t = text.trim()
            if (t.startsWith("[")) return OverlayLayout(widgets = JSONArray(t).widgets())
            val o = JSONObject(t)
            val res = o.optJSONArray("resolution")
            val crop = o.optJSONArray("crop_offset")
            return OverlayLayout(
                width = res?.optInt(0, 1920) ?: 1920,
                height = res?.optInt(1, 1080) ?: 1080,
                aspectRatio = o.optString("aspect_ratio", "Original").ifBlank { "Original" },
                cropOffsetX = crop?.optInt(0) ?: 0,
                cropOffsetY = crop?.optInt(1) ?: 0,
                widgets = o.optJSONArray("widgets")?.widgets() ?: emptyList(),
                raw = o,
            )
        }

        private fun JSONArray.widgets() = (0 until length()).mapNotNull { optJSONObject(it)?.let(OverlayWidget::fromJson) }
    }
}

/** One widget, field for field as desktop `OverlayWidget.to_dict()` writes it. */
data class OverlayWidget(
    val type: String,
    val x: Int = 100,
    val y: Int = 100,
    val scaleX: Double = 1.0,
    val scaleY: Double = 1.0,
    val channel: String = "",
    val label: String = type,
    val minVal: Double = 0.0,
    val maxVal: Double = 100.0,
    /** BGR. */
    val color: IntArray = defaultColor(type),
    val showValue: Boolean = true,
    /** BGR. */
    val textColor: IntArray = intArrayOf(255, 255, 255),
    val showTime: Boolean = true,
    val showLast: Boolean = true,
    val showBest: Boolean = true,
    val showLap: Boolean = true,
    /** Seconds; + = sample further ahead in the data (for channels that lag). */
    val timeOffset: Double = 0.0,
    val decimals: Int = if (type == G_METER) 1 else 2,
    val labelFontSize: Int = 16,
    val intDigits: Int = 3,
    val labelGap: Int = 0,
    val traceWindow: Double = 3.0,
    /** Clockwise degrees, a multiple of 90. */
    val mapRotation: Int = 0,
    val longChannel: String = "",
    val latChannel: String = "",
    val gRangeLong: Double = 2.0,
    val gRangeLat: Double = 2.0,
    val invertLong: Boolean = false,
    val invertLat: Boolean = false,
    val markerScale: Double = 1.0,
    private val raw: JSONObject = JSONObject(),
) {
    /** Desktop `font_size`: the base 24 px times the vertical scale. */
    val fontSize: Int get() = maxOf(6, (BASE_FONT_SIZE * scaleY).toInt())

    fun toJson(): JSONObject = JSONObject(raw.toString())
        .put("widget_type", type).put("x", x).put("y", y).put("scale_x", scaleX).put("scale_y", scaleY)
        .put("channel", channel).put("label", label).put("min_val", minVal).put("max_val", maxVal)
        .put("color", JSONArray(color.toList())).put("show_value", showValue).put("text_color", JSONArray(textColor.toList()))
        .put("show_time", showTime).put("show_last", showLast).put("show_best", showBest).put("show_lap", showLap)
        .put("time_offset", timeOffset).put("decimals", decimals).put("label_font_size", labelFontSize)
        .put("int_digits", intDigits).put("label_gap", labelGap).put("trace_window", traceWindow)
        .put("map_rotation", mapRotation).put("long_channel", longChannel).put("lat_channel", latChannel)
        .put("g_range_long", gRangeLong).put("g_range_lat", gRangeLat)
        .put("invert_long", invertLong).put("invert_lat", invertLat).put("marker_scale", markerScale)

    override fun equals(other: Any?) = other is OverlayWidget && toJson().toString() == other.toJson().toString()
    override fun hashCode() = toJson().toString().hashCode()

    companion object {
        const val LAP_TIMER = "Lap Timer"
        const val NUMERIC = "Numeric Display"
        const val BAR_GRAPH = "Bar Graph"
        const val TRACK_MAP = "Track Map"
        const val DATA_TRACE = "Data Trace"
        const val G_METER = "G Meter"
        const val BASE_FONT_SIZE = 24

        /** Unscaled box size per type (desktop `BASE_SIZES`); Lap Timer / Numeric size to their text instead. */
        val BASE_SIZES = mapOf(LAP_TIMER to (220 to 70), NUMERIC to (200 to 60), BAR_GRAPH to (200 to 60),
            TRACK_MAP to (220 to 220), DATA_TRACE to (300 to 100), G_METER to (220 to 220))

        private fun defaultColor(type: String) =
            if (type == G_METER || type == DATA_TRACE) intArrayOf(0, 200, 255) else intArrayOf(0, 255, 0)

        fun fromJson(o: JSONObject): OverlayWidget? {
            val type = o.optString("widget_type").ifBlank { return null }
            val d = OverlayWidget(type)
            fun color(k: String, def: IntArray) = o.optJSONArray(k)?.takeIf { it.length() >= 3 }
                ?.let { a -> IntArray(3) { a.optInt(it) } } ?: def
            return OverlayWidget(
                type = type,
                x = o.optDouble("x", d.x.toDouble()).toInt(), y = o.optDouble("y", d.y.toDouble()).toInt(),
                scaleX = o.optDouble("scale_x", o.optDouble("scale", 1.0)),
                scaleY = o.optDouble("scale_y", o.optDouble("scale", 1.0)),
                channel = o.optString("channel", ""), label = o.optString("label", type),
                minVal = o.optDouble("min_val", d.minVal), maxVal = o.optDouble("max_val", d.maxVal),
                color = color("color", d.color), showValue = o.optBoolean("show_value", true),
                textColor = color("text_color", d.textColor),
                showTime = o.optBoolean("show_time", true), showLast = o.optBoolean("show_last", true),
                showBest = o.optBoolean("show_best", true), showLap = o.optBoolean("show_lap", true),
                timeOffset = o.optDouble("time_offset", 0.0), decimals = o.optInt("decimals", d.decimals),
                labelFontSize = o.optInt("label_font_size", 16), intDigits = o.optInt("int_digits", 3),
                labelGap = o.optInt("label_gap", 0), traceWindow = o.optDouble("trace_window", 3.0),
                mapRotation = o.optInt("map_rotation", 0),
                longChannel = o.optString("long_channel", ""), latChannel = o.optString("lat_channel", ""),
                gRangeLong = o.optDouble("g_range_long", 2.0), gRangeLat = o.optDouble("g_range_lat", 2.0),
                invertLong = o.optBoolean("invert_long", false), invertLat = o.optBoolean("invert_lat", false),
                markerScale = o.optDouble("marker_scale", 1.0),
                raw = o,
            )
        }
    }
}

/**
 * The app's layout folder: `Android/data/com.lunk333.whrrah/files/layouts/` — any desktop layout
 * JSON copied in there (USB, Files app) shows up in the run viewer. The layouts bundled with the app
 * (assets/layouts) are copied in on first use and never overwritten after that.
 */
class LayoutStore(context: Context) {
    private val app = context.applicationContext
    val dir = File(app.getExternalFilesDir(null), "layouts")
    private val prefs = app.getSharedPreferences("overlay", Context.MODE_PRIVATE)

    fun list(): List<File> {
        seed()
        return dir.listFiles { f -> f.isFile && f.name.endsWith(".json", ignoreCase = true) }?.sortedBy { it.name.lowercase() } ?: emptyList()
    }

    fun load(f: File): Result<OverlayLayout> = runCatching { OverlayLayout.parse(f.readText()) }

    var selected: String?
        get() = prefs.getString("layout", null)
        set(v) { prefs.edit().putString("layout", v).apply() }

    var showOverlay: Boolean
        get() = prefs.getBoolean("show", true)
        set(v) { prefs.edit().putBoolean("show", v).apply() }

    private fun seed() {
        if (prefs.getBoolean("seeded", false) && dir.isDirectory) return
        dir.mkdirs()
        runCatching {
            app.assets.list("layouts")?.forEach { name ->
                val out = File(dir, name)
                if (!out.exists()) app.assets.open("layouts/$name").use { i -> out.outputStream().use { i.copyTo(it) } }
            }
            prefs.edit().putBoolean("seeded", true).apply()
        }
    }
}
