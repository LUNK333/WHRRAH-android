package com.lunk333.whrrah.settings

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** User settings (SharedPreferences), exposed as Compose state. */
class AppSettings private constructor(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var drivers by mutableStateOf(readList("drivers")); private set
    var cars by mutableStateOf(readList("cars")); private set
    var lastDriver by mutableStateOf(prefs.getString("last_driver", "") ?: ""); private set
    var lastCar by mutableStateOf(prefs.getString("last_car", "") ?: ""); private set
    /** Connect to the camera automatically (and keep retrying) while a session is open — like DJI Mimo. */
    var autoConnectCamera by mutableStateOf(prefs.getBoolean("auto_connect_camera", true)); private set
    /** Fetch each new clip's whole gyro track as soon as it's listed. */
    var autoFetchGyro by mutableStateOf(prefs.getBoolean("auto_fetch_gyro", true)); private set
    /** Use the phone's GPS when no RaceBox is connected. */
    var phoneGpsFallback by mutableStateOf(prefs.getBoolean("phone_gps_fallback", true)); private set
    /** Landscape timer: run the screen at full brightness while it's up. */
    var forceFullBrightness by mutableStateOf(prefs.getBoolean("force_full_brightness", true)); private set
    /** Landscape timer: the delta indicators are boxes instead of chevrons. */
    var deltaBoxes by mutableStateOf(prefs.getBoolean("delta_boxes", false)); private set
    /** Landscape timer: draw the lit delta chevrons in HDR (brighter than SDR white). Dev options. */
    var hdrChevrons by mutableStateOf(prefs.getBoolean("hdr_chevrons", false)); private set
    /** HDR brightness multiplier for the chevrons; 0 = the display's current headroom. Dev options. */
    var hdrManualBoost by mutableStateOf(prefs.getFloat("hdr_manual_boost", 0f)); private set

    fun addDriver(name: String) { if (name.isNotBlank() && name.trim() !in drivers) { drivers = drivers + name.trim(); writeList("drivers", drivers) } }
    fun removeDriver(name: String) { drivers = drivers - name; writeList("drivers", drivers) }
    fun addCar(name: String) { if (name.isNotBlank() && name.trim() !in cars) { cars = cars + name.trim(); writeList("cars", cars) } }
    fun removeCar(name: String) { cars = cars - name; writeList("cars", cars) }

    fun rememberChoice(driver: String, car: String) {
        addDriver(driver); addCar(car)
        lastDriver = driver.trim(); lastCar = car.trim()
        prefs.edit().putString("last_driver", lastDriver).putString("last_car", lastCar).apply()
    }

    /** Width of start/finish lines set from now on, in feet. */
    var lineWidthFt by mutableStateOf(prefs.getFloat("line_width_ft", DEFAULT_LINE_WIDTH_FT)); private set

    fun changeLineWidthFt(ft: Float) {
        lineWidthFt = ft.coerceIn(MIN_LINE_WIDTH_FT, MAX_LINE_WIDTH_FT)
        prefs.edit().putFloat("line_width_ft", lineWidthFt).apply()
    }

    fun changeAutoConnectCamera(on: Boolean) { autoConnectCamera = on; prefs.edit().putBoolean("auto_connect_camera", on).apply() }
    fun changeAutoFetchGyro(on: Boolean) { autoFetchGyro = on; prefs.edit().putBoolean("auto_fetch_gyro", on).apply() }
    fun changePhoneGpsFallback(on: Boolean) { phoneGpsFallback = on; prefs.edit().putBoolean("phone_gps_fallback", on).apply() }
    fun changeForceFullBrightness(on: Boolean) { forceFullBrightness = on; prefs.edit().putBoolean("force_full_brightness", on).apply() }
    fun changeDeltaBoxes(on: Boolean) { deltaBoxes = on; prefs.edit().putBoolean("delta_boxes", on).apply() }
    fun changeHdrChevrons(on: Boolean) { hdrChevrons = on; prefs.edit().putBoolean("hdr_chevrons", on).apply() }
    fun changeHdrManualBoost(v: Float) { hdrManualBoost = v; prefs.edit().putFloat("hdr_manual_boost", v).apply() }

    // Newline-joined so order is kept (a StringSet isn't ordered).
    private fun readList(k: String) = prefs.getString(k, "")!!.split('\n').filter { it.isNotBlank() }
    private fun writeList(k: String, v: List<String>) = prefs.edit().putString(k, v.joinToString("\n")).apply()

    companion object {
        const val DEFAULT_LINE_WIDTH_FT = 20f
        const val MIN_LINE_WIDTH_FT = 5f
        const val MAX_LINE_WIDTH_FT = 200f

        @Volatile private var instance: AppSettings? = null
        fun get(context: Context): AppSettings =
            instance ?: synchronized(this) { instance ?: AppSettings(context.applicationContext).also { instance = it } }
    }
}
