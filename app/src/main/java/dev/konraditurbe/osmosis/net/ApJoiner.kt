package dev.konraditurbe.osmosis.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.wifi.WifiNetworkSpecifier

/**
 * Joins the camera's WiFi AP (WPA2-PSK, internet-less) via WifiNetworkSpecifier (API 29+) and
 * binds the process to it so our HTTP/UDP sockets egress over the camera network. This is the
 * Android-native replacement for osmo-download's macOS `networksetup` juggling.
 */
class ApJoiner(context: Context, private val listener: Listener) {
    interface Listener {
        fun onLog(s: String)
        fun onNetwork(network: Network, link: LinkProperties?)
        fun onFailed(reason: String)
        /**
         * The AP went away after a successful join. Retry policy lives with the caller, which is the
         * only thing that knows whether a session is still worth saving — call [rejoin] to try again.
         */
        fun onLost()
    }

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private var cb: ConnectivityManager.NetworkCallback? = null

    /** WHRRAH patch: the joined AP's BSSID, once the platform reports it (needs location permission). */
    @Volatile var connectedBssid: String? = null
        private set

    // Remembered so [rejoin] can re-issue the identical request without the caller having to hold
    // the credentials for the whole session.
    private var lastSsid: String? = null
    private var lastPassphrase: String = ""
    private var lastWpa3: Boolean = false

    fun join(ssid: String, passphrase: String, wpa3: Boolean = false, bssid: String? = null) {
        lastSsid = ssid; lastPassphrase = passphrase; lastWpa3 = wpa3
        val specBuilder = WifiNetworkSpecifier.Builder().setSsid(ssid)
        // WHRRAH patch: with the exact BSSID of a network the user already approved for this app,
        // Android can connect without showing the approval dialog again.
        bssid?.let { runCatching { specBuilder.setBssid(android.net.MacAddress.fromString(it)) } }
        if (passphrase.isNotEmpty()) {
            // The Osmo 360 AP is WPA3-SAE; the rest are WPA2-PSK.
            if (wpa3) specBuilder.setWpa3Passphrase(passphrase) else specBuilder.setWpa2Passphrase(passphrase)
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .removeCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .setNetworkSpecifier(specBuilder.build())
            .build()

        // WHRRAH patch: FLAG_INCLUDE_LOCATION_INFO so the network's WifiInfo isn't redacted (API 31+)
        // and the AP's BSSID can be read in onCapabilitiesChanged — see [connectedBssid].
        val callback = object : ConnectivityManager.NetworkCallback(
            if (android.os.Build.VERSION.SDK_INT >= 31) FLAG_INCLUDE_LOCATION_INFO else 0
        ) {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                val info = if (android.os.Build.VERSION.SDK_INT >= 29) caps.transportInfo as? android.net.wifi.WifiInfo else null
                info?.bssid?.takeIf { it.isNotBlank() && it != "02:00:00:00:00:00" }?.let { connectedBssid = it }
            }

            override fun onAvailable(network: Network) {
                val bound = cm.bindProcessToNetwork(network)
                val link = runCatching { cm.getLinkProperties(network) }.getOrNull()
                listener.onLog("WiFi: onAvailable, bindProcessToNetwork=$bound")
                listener.onNetwork(network, link)
            }

            override fun onUnavailable() {
                listener.onFailed("WiFi: onUnavailable (wrong password, AP down, or user cancelled)")
            }

            override fun onLost(network: Network) {
                listener.onLog("WiFi: onLost")
                listener.onLost()
            }
        }
        cb = callback
        listener.onLog("WiFi: requesting \"$ssid\" (${if (wpa3) "WPA3" else "WPA2"}, no-internet)...")
        cm.requestNetwork(request, callback)
    }

    /**
     * Re-issue the last [join] after the AP dropped.
     *
     * A `WifiNetworkSpecifier` request does not reconnect on its own — once the camera's AP goes away
     * the process stays bound to a dead network and every socket fails `ENONET`, which is exactly how
     * an interrupted transfer surfaced (a Pocket 4 Pro lost its AP 49 s into a download and nothing
     * downstream could recover). Unregister and request again; `onAvailable` re-binds the process.
     *
     * @return false if there is nothing to retry because [join] was never called.
     */
    fun rejoin(): Boolean {
        val ssid = lastSsid ?: return false
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        cb = null
        join(ssid, lastPassphrase, lastWpa3)
        return true
    }

    fun release() {
        cb?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        runCatching { cm.bindProcessToNetwork(null) }
        cb = null
    }
}
