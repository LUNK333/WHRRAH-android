package com.lunk333.whrrah.canvas

enum class WidgetType {
    LAP_TIMER,
    NUMERIC_DISPLAY,
    BAR_GRAPH
}

/**
 * A placed overlay widget. Position/size are fractions of the canvas
 * (0..1), not pixels - keeps placement independent of screen size/density
 * and matches how desktop WHRRAH's layout JSON stores widget geometry
 * (see design doc §1 goal 6 and open question in §7 about layout
 * round-trip compatibility with desktop).
 */
data class OverlayWidget(
    val id: Int,
    val type: WidgetType,
    /** Bound channel name. Null/ignored for LAP_TIMER. */
    val channel: String? = null,
    val xFrac: Float = 0.1f,
    val yFrac: Float = 0.1f,
    val widthFrac: Float = 0.25f,
    val heightFrac: Float = 0.12f
)
