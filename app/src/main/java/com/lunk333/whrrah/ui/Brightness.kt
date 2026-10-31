package com.lunk333.whrrah.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import android.os.Build
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.colorspace.ColorSpaces
import androidx.compose.ui.platform.LocalView

/*
 * Making the timer readable in the car:
 *  - full brightness: the window asks for the top of the normal brightness range while the timer is
 *    up, whatever the slider / auto-brightness says (the system's sunlight boost and the fingerprint
 *    reader's high-brightness spot are not available to apps);
 *  - HDR chevrons: the window switches to HDR colour mode and the lit chevrons are drawn brighter
 *    than SDR white, by up to the headroom the display currently has (Android 14+; largest indoors,
 *    smallest when the screen is already near its peak in sunlight).
 */

/** While in composition (and [on]), the window runs at full brightness. */
@Composable
fun FullBrightnessEffect(on: Boolean) {
    val view = LocalView.current
    DisposableEffect(on) {
        val window = (view.context as? Activity)?.window
        if (on && window != null) {
            window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL }
        }
        onDispose {
            if (on && window != null) {
                window.attributes = window.attributes.apply { screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE }
            }
        }
    }
}

/** HDR is possible here: Android 14+ (extended-range drawing into an HDR window) on a display that has headroom. */
val hdrSupported: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE

/**
 * While in composition (and [on]), puts the window in HDR colour mode and returns the brightness
 * multiplier to draw highlights with: [manual] when > 0, else the display's current HDR/SDR ratio
 * (followed live). 1 when off or unsupported.
 */
@Composable
fun rememberHdrBoost(on: Boolean, manual: Float = 0f): Float {
    val view = LocalView.current
    var ratio by remember { mutableFloatStateOf(1f) }
    DisposableEffect(on) {
        val activity = view.context as? Activity
        val window = activity?.window
        var cleanup: () -> Unit = {}
        if (on && hdrSupported && window != null) {
            val oldMode = window.colorMode
            window.colorMode = ActivityInfo.COLOR_MODE_HDR
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) window.desiredHdrHeadroom = 0f   // 0 = as much as the display has
            val display = view.display
            if (display != null && display.isHdrSdrRatioAvailable) {
                ratio = display.hdrSdrRatio
                val listener = java.util.function.Consumer<android.view.Display> { ratio = it.hdrSdrRatio }
                display.registerHdrSdrRatioChangedListener(activity.mainExecutor, listener)
                cleanup = { display.unregisterHdrSdrRatioChangedListener(listener) }
            }
            val c = cleanup
            cleanup = { c(); window.colorMode = oldMode; ratio = 1f }
        }
        onDispose { cleanup() }
    }
    return when {
        !on || !hdrSupported -> 1f
        manual > 0f -> manual
        else -> ratio
    }
}

/** Current HDR/SDR headroom of the view's display (1 = none), for the test panel. */
fun hdrSdrRatioNow(view: android.view.View): Float? {
    if (!hdrSupported) return null
    val d = view.display ?: return null
    return if (d.isHdrSdrRatioAvailable) d.hdrSdrRatio else null
}

/**
 * [c] made [boost]× brighter as an extended-range (linear) colour for an android.graphics.Paint —
 * > 1 only shows brighter in an HDR window; plain [c] for boost ≤ 1.
 */
fun hdrColor(c: Color, boost: Float): Long {
    if (boost <= 1.001f) return android.graphics.Color.pack(c.red, c.green, c.blue, c.alpha)
    val lin = c.convert(ColorSpaces.LinearExtendedSrgb)
    return android.graphics.Color.pack(lin.red * boost, lin.green * boost, lin.blue * boost, c.alpha,
        android.graphics.ColorSpace.get(android.graphics.ColorSpace.Named.LINEAR_EXTENDED_SRGB))
}
