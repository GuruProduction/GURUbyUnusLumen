package com.unuslumen.app.util.shell

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume

/**
 * Discovers ADB Wireless Debugging services via mDNS.
 * Finds both the connection port (adb-tls-connect) and pairing port (adb-tls-pairing).
 *
 * On Android 11+, the ADB daemon advertises two mDNS services:
 * - _adb-tls-connect._tcp: The port for ADB connections (usually 5555)
 * - _adb-tls-pairing._tcp: The port for pairing (random, shown in Developer Options)
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbMdnsDiscovery(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
    }

    private var nsdManager: NsdManager? = null

    /**
     * Discover the pairing port via mDNS.
     * Returns the port number, or null if not found.
     * timeoutMs=0 means no timeout (AGI is patient).
     */
    suspend fun discoverPairingPort(timeoutMs: Long = 0): Int? {
        return discoverPort(TLS_PAIRING, timeoutMs)
    }

    /**
     * Discover the ADB connection port via mDNS.
     * Returns the port number, or null if not found.
     * timeoutMs=0 means no timeout (AGI is patient).
     */
    suspend fun discoverConnectPort(timeoutMs: Long = 0): Int? {
        return discoverPort(TLS_CONNECT, timeoutMs)
    }

    /**
     * Discover a port for the given service type via mDNS.
     * timeoutMs=0 means no timeout — AGI doesn't have timeouts.
     */
    private suspend fun discoverPort(serviceType: String, timeoutMs: Long): Int? {
        return try {
            if (timeoutMs > 0) {
                withTimeout(timeoutMs) {
                    doDiscoverPort(serviceType)
                }
            } else {
                doDiscoverPort(serviceType)
            }
        } catch (e: Exception) {
            Log.w(TAG, "AdbMdns: discovery failed for $serviceType: ${e.message}")
            null
        }
    }

    /**
     * Actually perform mDNS discovery. No timeout — waits as long as needed.
     */
    @SuppressLint("NewApi")
    private suspend fun doDiscoverPort(serviceType: String): Int? = suspendCancellableCoroutine { continuation ->
        val manager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
        nsdManager = manager

        var resolvedPort: Int? = null
        var discoveryListenerRef: NsdManager.DiscoveryListener? = null

        val discoveryListener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) {
                Log.d(TAG, "AdbMdns: discovery started for $serviceType")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "AdbMdns: service found: ${serviceInfo.serviceName}")

                // Resolve the service to get the port
                manager.resolveService(serviceInfo, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) {
                        Log.w(TAG, "AdbMdns: resolve failed: errorCode=$errorCode")
                    }

                    override fun onServiceResolved(serviceInfo: NsdServiceInfo) {
                        val port = serviceInfo.port
                        Log.d(TAG, "AdbMdns: resolved ${serviceInfo.serviceName} port=$port")
                        if (resolvedPort == null && !continuation.isCompleted) {
                            resolvedPort = port
                            try {
                                discoveryListenerRef?.let { manager.stopServiceDiscovery(it) }
                            } catch (_: Exception) {}
                            continuation.resume(port)
                        }
                    }
                })
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                Log.d(TAG, "AdbMdns: service lost: ${serviceInfo.serviceName}")
            }

            override fun onDiscoveryStopped(serviceType: String) {
                Log.d(TAG, "AdbMdns: discovery stopped for $serviceType")
                if (resolvedPort == null && !continuation.isCompleted) {
                    continuation.resume(null)
                }
            }

            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "AdbMdns: start discovery failed: errorCode=$errorCode")
                if (!continuation.isCompleted) {
                    continuation.resume(null)
                }
            }

            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
                Log.e(TAG, "AdbMdns: stop discovery failed: errorCode=$errorCode")
                if (!continuation.isCompleted) {
                    continuation.resume(null)
                }
            }
        }

        discoveryListenerRef = discoveryListener

        continuation.invokeOnCancellation {
            try {
                manager.stopServiceDiscovery(discoveryListener)
            } catch (_: Exception) {}
        }

        manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, discoveryListener)
    }

    /**
     * Stop any ongoing discovery.
     */
    fun stop() {
        nsdManager = null
    }
}