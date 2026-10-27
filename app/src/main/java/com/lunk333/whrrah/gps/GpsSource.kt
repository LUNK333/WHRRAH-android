package com.lunk333.whrrah.gps

import android.annotation.SuppressLint
import android.content.Context
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** One position fix. [elapsedMs] is the phone's elapsedRealtime when it arrived (the session's master clock). */
data class GpsFix(
    val elapsedMs: Long,
    val utcMs: Long,
    val lat: Double,
    val lon: Double,
    val speedMps: Float?,
    val headingDeg: Float?,
    val accuracyM: Float?,
)

/**
 * Anything that produces position fixes. The RaceBox Mini (B2, ~25 Hz) plugs in here; [PhoneGps]
 * (~1 Hz) is the stand-in until then — good enough to pin start/finish on a course walk, not for
 * lap timing.
 */
interface GpsSource {
    val name: String
    val connected: Boolean
    val lastFix: GpsFix?
    /** Called on the main thread for every fix. */
    var onFix: ((GpsFix) -> Unit)?
    fun start()
    fun stop()
}

/** The phone's own GPS through LocationManager. Needs ACCESS_FINE_LOCATION (asked for by the UI). */
@SuppressLint("MissingPermission")
class PhoneGps(context: Context) : GpsSource {
    private val lm = context.applicationContext.getSystemService(Context.LOCATION_SERVICE) as LocationManager

    override val name = "Phone GPS"
    override var connected by mutableStateOf(false); private set
    override var lastFix by mutableStateOf<GpsFix?>(null); private set
    override var onFix: ((GpsFix) -> Unit)? = null

    private val listener = LocationListener { loc: Location ->
        val fix = GpsFix(
            elapsedMs = SystemClock.elapsedRealtime(),
            utcMs = loc.time,
            lat = loc.latitude,
            lon = loc.longitude,
            speedMps = if (loc.hasSpeed()) loc.speed else null,
            headingDeg = if (loc.hasBearing()) loc.bearing else null,
            accuracyM = if (loc.hasAccuracy()) loc.accuracy else null,
        )
        lastFix = fix
        connected = true
        onFix?.invoke(fix)
    }

    override fun start() {
        if (!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)) return
        runCatching { lm.requestLocationUpdates(LocationManager.GPS_PROVIDER, 0L, 0f, listener, Looper.getMainLooper()) }
    }

    override fun stop() {
        runCatching { lm.removeUpdates(listener) }
        connected = false
    }
}
