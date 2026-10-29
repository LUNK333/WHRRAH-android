package com.lunk333.whrrah

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.lunk333.whrrah.osmo.OsmoLink
import com.lunk333.whrrah.racebox.RaceBox
import com.lunk333.whrrah.session.SessionController

/**
 * Keeps the app alive in the background while a session is open or a device is connected, so the
 * camera (BLE/WiFi), RaceBox (BLE) and GPS keep running when the app is minimized or the screen is
 * off. A foreground service with a status notification + a partial wake lock (CPU only).
 *
 * Call [update] whenever something is connected/disconnected or a session opens/closes; it starts or
 * stops the service as needed. All the actual link logic stays in OsmoLink / RaceBox / SessionController.
 */
class LinkService : Service() {
    private val main = Handler(Looper.getMainLooper())
    private var wakeLock: PowerManager.WakeLock? = null

    private val tick = object : Runnable {
        override fun run() {
            if (!needed(this@LinkService)) { stopSelf(); return }
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
            main.postDelayed(this, 2000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            SessionController.get(this).close()
            OsmoLink.get(this).disconnect()
            RaceBox.get(this).disconnect()
            stopSelf()
            return START_NOT_STICKY
        }
        startInForeground()
        if (wakeLock == null) wakeLock = getSystemService(PowerManager::class.java)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "WHRRAH:links").apply { setReferenceCounted(false); acquire() }
        main.removeCallbacks(tick)
        main.postDelayed(tick, 2000)
        return START_NOT_STICKY   // after a process kill the links are gone anyway; don't come back empty
    }

    private fun startInForeground() {
        createChannel()
        var types = 0
        if (Build.VERSION.SDK_INT >= 29) {
            if (Build.VERSION.SDK_INT < 31 || granted(Manifest.permission.BLUETOOTH_CONNECT))
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE
            if (granted(Manifest.permission.ACCESS_FINE_LOCATION))
                types = types or ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
        }
        // Without Bluetooth or location permission there is nothing this service may legally run as
        // (Android 14 rejects a typeless one) — and nothing connected to keep alive anyway.
        runCatching {
            if (Build.VERSION.SDK_INT >= 29 && types != 0) startForeground(NOTIFICATION_ID, notification(), types)
            else startForeground(NOTIFICATION_ID, notification())
        }.onFailure { android.util.Log.w("LinkService", "can't run in the foreground: ${it.message}"); stopSelf() }
    }

    private fun granted(p: String) = ContextCompat.checkSelfPermission(this, p) == PackageManager.PERMISSION_GRANTED

    private fun notification(): Notification {
        val open = PendingIntent.getActivity(this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE)
        val disconnect = PendingIntent.getService(this, 1,
            Intent(this, LinkService::class.java).setAction(ACTION_DISCONNECT), PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle(SessionController.get(this).session?.event ?: "WHRRAH")
            .setContentText(statusLine(this))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setContentIntent(open)
            .addAction(0, "Disconnect all", disconnect)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < 26) return
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL, "Connections", NotificationManager.IMPORTANCE_LOW).apply {
                description = "Keeps the camera, RaceBox and GPS connected in the background"
            }
        )
    }

    override fun onDestroy() {
        main.removeCallbacks(tick)
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    companion object {
        private const val CHANNEL = "links"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_DISCONNECT = "com.lunk333.whrrah.DISCONNECT_ALL"

        /** Something worth keeping alive: an open session, or a camera / RaceBox link in use. */
        fun needed(context: Context): Boolean =
            SessionController.get(context).session != null ||
                com.lunk333.whrrah.osmo.CameraMonitor.get(context).state != com.lunk333.whrrah.osmo.CameraMonitor.State.OFF ||
                OsmoLink.get(context).phase !in setOf(OsmoLink.Phase.IDLE, OsmoLink.Phase.FAILED, OsmoLink.Phase.SCANNING) ||
                RaceBox.get(context).state in setOf(RaceBox.State.CONNECTING, RaceBox.State.CONNECTED)

        fun statusLine(context: Context): String {
            val link = OsmoLink.get(context)
            val rb = RaceBox.get(context)
            val ble = com.lunk333.whrrah.osmo.CameraMonitor.get(context)
            val cam = when {
                ble.recording == true || link.recording == true -> "REC"
                ble.connected -> "standby"
                link.phase == OsmoLink.Phase.READY -> "connected"
                link.phase == OsmoLink.Phase.IDLE || link.phase == OsmoLink.Phase.FAILED -> "off"
                else -> "connecting"
            }
            val box = when (rb.state) {
                RaceBox.State.CONNECTED -> if (rb.latest != null) "%.0f Hz".format(rb.rateHz) else "connected"
                RaceBox.State.CONNECTING -> "connecting"
                else -> "off"
            }
            val problem = ble.problems.firstOrNull()
            return (if (problem != null) "⚠ $problem · " else "") + "Camera: $cam · RaceBox: $box"
        }

        /** Start or stop the service to match [needed]. Call on the main thread from foreground UI actions. */
        fun update(context: Context) {
            val app = context.applicationContext
            if (needed(app)) runCatching { ContextCompat.startForegroundService(app, Intent(app, LinkService::class.java)) }
            // Stopping is the service's own job (its tick sees needed() go false), so a stray call
            // here can never tear it down in the middle of a reconnect.
        }
    }
}
