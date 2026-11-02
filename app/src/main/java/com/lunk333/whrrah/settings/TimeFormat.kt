package com.lunk333.whrrah.settings

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/**
 * How run times read everywhere in the app: "1:05.123", or seconds only ("65.123" — how autocross
 * times are usually said). Compose state, so a change in Settings redraws every time on screen.
 * Set from [AppSettings] (Settings → Times in seconds).
 */
object TimeFormat {
    var secondsOnly by mutableStateOf(false)

    /** A run time, or [blank] for none. */
    fun run(ms: Long?, blank: String = "—"): String = when {
        ms == null -> blank
        secondsOnly -> "%.3f".format(ms / 1000.0)
        else -> "%d:%02d.%03d".format(ms / 60000, (ms / 1000) % 60, ms % 1000)
    }

    /** Placeholder shaped like a time, for the big timer before there is one. */
    val placeholder: String get() = if (secondsOnly) "——.———" else "—:——.———"
}
