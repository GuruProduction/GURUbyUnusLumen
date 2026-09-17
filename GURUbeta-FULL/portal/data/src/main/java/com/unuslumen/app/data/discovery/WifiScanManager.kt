package com.unuslumen.app.data.discovery

import android.Manifest
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.net.wifi.ScanResult
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import kotlinx.coroutines.delay
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import org.koin.core.annotation.Single

@Single
class WifiScanManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "WifiScanManager"
        private const val MAX_SCAN_WAIT_MS = 10000L
    }

    @Serializable
    data class WifiNetwork(
        val ssid: String,
        val bssid: String,
        val signalStrength: Int,
        val frequency: Int,
        val channel: Int,
        val securityType: String,
        val isHidden: Boolean,
        val isCurrentlyConnected: Boolean,
        val capabilities: String,
        val timestamp: Long
    )

    private var scanReceiver: BroadcastReceiver? = null
    private var scanResults: List<ScanResult> = emptyList()
    private var lastScanTimestamp: Long = 0L

    // Deprecated WiFi APIs (connectionInfo, startScan, SSID) have no clean synchronous replacements.
    // Migrating to ConnectivityManager.NetworkCallback would require an async architecture overhaul.
    // Suppressed intentionally until a proper migration is done.
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    suspend fun scanNetworks(): List<WifiNetwork> {
        val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: throw IllegalStateException("WiFiManager not available")

        if (!hasRequiredPermissions()) {
            throw SecurityException("Missing required permissions for WiFi scanning. Need ACCESS_FINE_LOCATION (or ACCESS_COARSE_LOCATION on API 28+) and NEARBY_WIFI_DEVICES on API 33+.")
        }

        val connectedBssid = try {
            wifiManager.connectionInfo?.bssid ?: ""
        } catch (e: SecurityException) {
            ""
        }

        var scanCompleted = false
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                when (intent.action) {
                    WifiManager.SCAN_RESULTS_AVAILABLE_ACTION -> {
                        scanCompleted = true
                        Log.d(TAG, "Scan results available")
                    }
                }
            }
        }

        val filter = IntentFilter(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)
        ContextCompat.registerReceiver(context, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        scanReceiver = receiver

        try {
            val scanStarted = wifiManager.startScan()
            if (!scanStarted) {
                Log.w(TAG, "startScan() returned false, may be throttled. Returning cached results.")
                scanCompleted = true
            }

            var waited = 0
            while (!scanCompleted && waited < MAX_SCAN_WAIT_MS) {
                delay(200)
                waited += 200
            }

            @SuppressLint("MissingPermission")
            val results = wifiManager.scanResults ?: emptyList()
            scanResults = results
            lastScanTimestamp = System.currentTimeMillis()

            return results.map { result ->
                val ssid = result.SSID?.removeSurrounding("\"") ?: "<hidden>"
                val isHidden = ssid.isBlank() || ssid == "<hidden>" || ssid == ""
                WifiNetwork(
                    ssid = if (isHidden) "<hidden network>" else ssid,
                    bssid = result.BSSID ?: "unknown",
                    signalStrength = result.level,
                    frequency = result.frequency,
                    channel = frequencyToChannel(result.frequency),
                    securityType = getSecurityType(result.capabilities ?: ""),
                    isHidden = isHidden,
                    isCurrentlyConnected = result.BSSID != null && result.BSSID == connectedBssid,
                    capabilities = result.capabilities ?: "",
                    timestamp = lastScanTimestamp
                )
            }.sortedByDescending { it.signalStrength }
        } finally {
            try {
                context.unregisterReceiver(receiver)
            } catch (e: Exception) {
                Log.w(TAG, "Failed to unregister receiver: ${e.message}")
            }
            scanReceiver = null
        }
    }

    private fun hasRequiredPermissions(): Boolean {
        val needsNearbyWifi = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
        val locationPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            Manifest.permission.ACCESS_FINE_LOCATION
        } else {
            Manifest.permission.ACCESS_COARSE_LOCATION
        }

        val hasLocation = ContextCompat.checkSelfPermission(context, locationPermission) == PackageManager.PERMISSION_GRANTED
        val hasNearbyWifi = if (needsNearbyWifi) {
            ContextCompat.checkSelfPermission(context, Manifest.permission.NEARBY_WIFI_DEVICES) == PackageManager.PERMISSION_GRANTED
        } else {
            true
        }

        return hasLocation && hasNearbyWifi
    }

    private fun frequencyToChannel(freq: Int): Int {
        return when {
            freq >= 2412 && freq <= 2484 -> (freq - 2412) / 5 + 1
            freq >= 5160 && freq <= 5885 -> (freq - 5000) / 5
            freq == 5945 -> 149
            freq >= 5950 && freq <= 7115 -> (freq - 5950) / 5 + 149
            else -> -1
        }
    }

    private fun getSecurityType(capabilities: String): String {
        val caps = capabilities.uppercase()
        return when {
            caps.contains("WPA3") -> "WPA3"
            caps.contains("WPA2") -> "WPA2"
            caps.contains("WPA") -> "WPA"
            caps.contains("WEP") -> "WEP"
            caps.contains("EAP") -> "EAP"
            caps.contains("SAE") -> "WPA3"
            caps.contains("OWE") -> "OWE"
            caps.isEmpty() || caps == "[" -> "Open"
            else -> "Unknown"
        }
    }
}
