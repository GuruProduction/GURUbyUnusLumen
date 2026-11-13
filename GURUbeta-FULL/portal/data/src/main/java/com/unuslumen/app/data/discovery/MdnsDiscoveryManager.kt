// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import org.koin.core.annotation.Single
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.LinkedBlockingQueue

/**
 * Discovered mDNS/Bonjour device on the local network.
 */
data class DiscoveredDevice(
    val serviceName: String,
    val serviceType: String,
    val host: String,
    val port: Int,
    val discoveredAt: Long
)

/**
 * Wraps Android's [NsdManager] to discover mDNS/Bonjour services on the local network.
 *
 * Listens for common service types: _airplay._tcp, _googlecast._tcp, _sonos._tcp,
 * _hue._tcp, _ipp._tcp, _http._tcp, _raop._tcp.
 *
 * Thread-safe. Uses a [ConcurrentHashMap] for the device registry and a serial
 * resolve queue so that only one [NsdManager.resolveService] call is in flight
 * at a time (required on older Android versions which fail when overlapping
 * resolves are issued).
 */
@Single
class MdnsDiscoveryManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "MdnsDiscoveryManager"

        private val SERVICE_TYPES = listOf(
            "_airplay._tcp.",
            "_googlecast._tcp.",
            "_sonos._tcp.",
            "_hue._tcp.",
            "_ipp._tcp.",
            "_http._tcp.",
            "_raop._tcp."
        )
    }

    /**
     * In-memory registry of discovered devices, keyed by service name.
     */
    private val discoveredDevices = ConcurrentHashMap<String, DiscoveredDevice>()

    /**
     * Active discovery listeners, one per service type, so they can all be stopped.
     */
    private val activeListeners = mutableListOf<NsdManager.DiscoveryListener>()

    /**
     * Pending service infos awaiting resolution. Serialised to avoid overlapping
     * [NsdManager.resolveService] calls which fail on older Android versions.
     */
    private val resolveQueue = LinkedBlockingQueue<Pair<NsdServiceInfo, String>>()

    @Volatile
    private var isResolving = false

    @Volatile
    private var isDiscovering = false

    /**
     * Whether startDiscovery has ever been called.
     *
     * SEPARATE FROM [isDiscovering] ON PURPOSE. `isDiscovering` is false both before the
     * first start and after a stop, and those are different answers: before the first
     * start, an empty device list says nothing about the network; after a stop, it says
     * the listener was deliberately taken down. Issue 222 was an empty array covering
     * all of these.
     */
    @Volatile
    private var everStarted = false

    /**
     * Service types Android accepted, and those it refused with its own error code.
     *
     * PER TYPE, because Android refuses them individually. A partial bind is the case
     * where silence on one type is least trustworthy, so the reader has to be told which
     * types are actually being watched before treating a missing device as absent.
     */
    private val listeningTypes = java.util.concurrent.CopyOnWriteArrayList<String>()
    private val refusedTypes = java.util.concurrent.CopyOnWriteArrayList<String>()

    /**
     * Multicast lock required to receive mDNS multicast packets.
     * Android WiFi drivers filter multicast by default to save battery.
     * Without this lock, NsdManager starts cleanly but finds zero devices.
     */
    private var multicastLock: WifiManager.MulticastLock? = null

    private val nsdManager: NsdManager? by lazy {
        try {
            context.getSystemService(Context.NSD_SERVICE) as? NsdManager
        } catch (e: Exception) {
            Log.e(TAG, "Failed to obtain NsdManager", e)
            null
        }
    }

    /**
     * Starts mDNS discovery for all configured service types.
     *
     * Safe to call multiple times: if discovery is already running this is a no-op.
     * Throws [IllegalStateException] only if the system [NsdManager] is unavailable.
     */
    fun startDiscovery() {
        val manager = nsdManager
        if (manager == null) {
            Log.e(TAG, "NsdManager is null — cannot start discovery")
            throw IllegalStateException("NsdManager is not available on this device")
        }

        synchronized(activeListeners) {
            if (isDiscovering) {
                Log.d(TAG, "Discovery already in progress, ignoring startDiscovery()")
                return
            }
            isDiscovering = true
            everStarted = true
            activeListeners.clear()
            listeningTypes.clear()
            refusedTypes.clear()
            // Clear previous results so we start fresh
            discoveredDevices.clear()
        }

        // Acquire multicast lock so the WiFi driver passes mDNS packets to us
        acquireMulticastLock()

        for (serviceType in SERVICE_TYPES) {
            val listener = createDiscoveryListener(serviceType)
            try {
                manager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
                synchronized(activeListeners) {
                    activeListeners.add(listener)
                }
                // discoverServices returning does NOT mean the listener bound: Android
                // reports a refusal asynchronously through onStartDiscoveryFailed. So a
                // type is only counted as listening once that callback has not fired by
                // the time the report is read, and the callback records the refusal.
                listeningTypes.add(serviceType)
            } catch (e: Exception) {
                // A throw here is the synchronous form of the same refusal, so it is
                // recorded rather than swallowed: issue 222 was this exception reaching
                // nothing but the log.
                Log.e(TAG, "Failed to start discovery for $serviceType", e)
                listeningTypes.remove(serviceType)
                refusedTypes.add("$serviceType (${e.javaClass.simpleName}: ${e.message ?: "no detail"})")
            }
        }
    }

    /**
     * What the listener is actually doing, for a caller that must not read an empty list
     * as a statement about the network.
     *
     * The decision itself is [NetworkReads.listenerReport], which is pure so a JVM test
     * pins it with no NsdManager and no device.
     */
    fun report(): ListenerReport = NetworkReads.listenerReport(
        started = everStarted && isDiscovering,
        listening = listeningTypes.toList(),
        refused = refusedTypes.toList(),
        found = discoveredDevices.size,
    )

    /**
     * Stops all active discovery listeners.
     * Already-discovered devices remain in the registry until [startDiscovery] is called again.
     */
    fun stopDiscovery() {
        val manager = nsdManager ?: run {
            Log.w(TAG, "NsdManager is null — nothing to stop")
            return
        }

        synchronized(activeListeners) {
            if (!isDiscovering) {
                return
            }
            isDiscovering = false
            for (listener in activeListeners) {
                try {
                    manager.stopServiceDiscovery(listener)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to stop service discovery listener", e)
                }
            }
            activeListeners.clear()
        }

        resolveQueue.clear()
        isResolving = false

        // Unregister any active ServiceInfoCallbacks (API 33+)
        synchronized(activeCallbacks) {
            for (callback in activeCallbacks) {
                try {
                    manager.unregisterServiceInfoCallback(callback)
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to unregister ServiceInfoCallback", e)
                }
            }
            activeCallbacks.clear()
        }

        // Release the multicast lock so WiFi can go back to battery-saving mode
        releaseMulticastLock()
    }

    /**
     * Returns a snapshot list of all discovered devices.
     */
    fun getDiscoveredDevices(): List<DiscoveredDevice> {
        return discoveredDevices.values.toList().sortedBy { it.serviceName }
    }

    // ---- Internal ----

    /**
     * Acquires a [WifiManager.MulticastLock] so that mDNS multicast packets reach the app.
     * Android WiFi drivers drop multicast traffic by default to save power.
     */
    @SuppressLint("WifiManagerPotentialLeak")
    private fun acquireMulticastLock() {
        if (multicastLock != null && multicastLock?.isHeld == true) {
            Log.d(TAG, "Multicast lock already held")
            return
        }
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager == null) {
                Log.w(TAG, "WifiManager unavailable, cannot acquire multicast lock")
                return
            }
            val lock = wifiManager.createMulticastLock("guru_mdns_lock")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
            Log.d(TAG, "Multicast lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock", e)
        }
    }

    /**
     * Releases the [WifiManager.MulticastLock] if held, allowing WiFi to resume power-saving mode.
     */
    private fun releaseMulticastLock() {
        try {
            val lock = multicastLock
            if (lock != null && lock.isHeld) {
                lock.release()
                Log.d(TAG, "Multicast lock released")
            }
            multicastLock = null
        } catch (e: Exception) {
            Log.e(TAG, "Failed to release multicast lock", e)
        }
    }

    private fun createDiscoveryListener(serviceType: String): NsdManager.DiscoveryListener {
        return object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {
                Log.d(TAG, "Discovery started: $type")
            }

            override fun onDiscoveryStopped(type: String) {
                Log.d(TAG, "Discovery stopped: $type")
            }

            /**
             * The refusal, arriving asynchronously long after discoverServices returned.
             *
             * THIS IS WHY ISSUE 222 EXISTED. The platform accepts the call and then reports
             * the failure here, so a manager that only logged this could not tell a bound
             * listener from a refused one. Recording it is what makes the report honest,
             * and the type moves out of `listeningTypes` because it is not being watched.
             */
            override fun onStartDiscoveryFailed(type: String, errorCode: Int) {
                Log.e(TAG, "Discovery start failed for $type, error=$errorCode")
                listeningTypes.remove(serviceType)
                val entry = "$serviceType (error $errorCode)"
                if (!refusedTypes.contains(entry)) refusedTypes.add(entry)
            }

            override fun onStopDiscoveryFailed(type: String, errorCode: Int) {
                Log.e(TAG, "Discovery stop failed for $type, error=$errorCode")
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                val type = serviceInfo.serviceType
                Log.d(TAG, "Service found: $name ($type)")
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    // API 33+: register a callback directly, no queue needed
                    registerCallback(serviceInfo, serviceType)
                } else {
                    // API < 33: use the serial resolve queue
                    enqueueResolve(serviceInfo, serviceType)
                }
            }

            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                val name = serviceInfo.serviceName
                Log.d(TAG, "Service lost: $name")
                discoveredDevices.remove(name)
            }
        }
    }

    /**
     * Active service info callbacks for API 33+ (replaces resolveService).
     */
    private val activeCallbacks = mutableListOf<NsdManager.ServiceInfoCallback>()

    /**
     * Registers a ServiceInfoCallback for a found service on API 33+.
     * No queue needed, callbacks can register in parallel.
     */
    @SuppressLint("NewApi")
    private fun registerCallback(serviceInfo: NsdServiceInfo, registeredType: String) {
        val manager = nsdManager ?: return
        val callback = object : NsdManager.ServiceInfoCallback {
            override fun onServiceUpdated(serviceInfo: NsdServiceInfo) {
                val device = buildDevice(serviceInfo, registeredType)
                if (device != null) {
                    discoveredDevices[device.serviceName] = device
                    Log.d(TAG, "Resolved (callback): ${device.serviceName} -> ${device.host}:${device.port}")
                }
            }

            override fun onServiceLost() {
                Log.d(TAG, "Service lost via callback: ${serviceInfo.serviceName}")
                discoveredDevices.remove(serviceInfo.serviceName)
            }

            override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) {
                Log.e(TAG, "ServiceInfoCallback registration failed for ${serviceInfo.serviceName}, error=$errorCode")
            }

            override fun onServiceInfoCallbackUnregistered() {
                Log.d(TAG, "ServiceInfoCallback unregistered for ${serviceInfo.serviceName}")
            }
        }
        try {
            manager.registerServiceInfoCallback(serviceInfo, java.util.concurrent.Executors.newSingleThreadExecutor(), callback)
            synchronized(activeCallbacks) {
                activeCallbacks.add(callback)
            }
        } catch (e: Exception) {
            Log.e(TAG, "registerServiceInfoCallback threw for ${serviceInfo.serviceName}", e)
        }
    }

    /**
     * Adds a service to the resolve queue and kicks off processing if idle.
     * Only used on API < 33 where resolveService requires serial execution.
     */
    private fun enqueueResolve(serviceInfo: NsdServiceInfo, registeredType: String) {
        resolveQueue.offer(serviceInfo to registeredType)
        processResolveQueue()
    }

    /**
     * Drains the resolve queue serially. Only one resolve is in flight at a time
     * to satisfy the constraint on older Android versions.
     */
    @Synchronized
    private fun processResolveQueue() {
        if (isResolving) return
        val manager = nsdManager ?: return

        val pair = resolveQueue.poll() ?: return
        val (serviceInfo, registeredType) = pair

        isResolving = true

        val resolveListener = object : NsdManager.ResolveListener {
            override fun onServiceResolved(resolvedInfo: NsdServiceInfo) {
                try {
                    val device = buildDevice(resolvedInfo, registeredType)
                    if (device != null) {
                        discoveredDevices[device.serviceName] = device
                        Log.d(TAG, "Resolved: ${device.serviceName} -> ${device.host}:${device.port}")
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "Error building device from resolved service", e)
                } finally {
                    isResolving = false
                    processResolveQueue()
                }
            }

            override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                Log.e(TAG, "Resolve failed for ${info.serviceName}, error=$errorCode")
                isResolving = false
                processResolveQueue()
            }
        }

        try {
            @Suppress("DEPRECATION")
            manager.resolveService(serviceInfo, resolveListener)
        } catch (e: Exception) {
            Log.e(TAG, "resolveService threw for ${serviceInfo.serviceName}", e)
            isResolving = false
            processResolveQueue()
        }
    }

    /**
     * Extracts host and port from a resolved [NsdServiceInfo].
     * Uses hostAddress on API 33+ (non-deprecated), falls back to host on older versions.
     */
    @SuppressLint("NewApi")
    private fun buildDevice(info: NsdServiceInfo, registeredType: String): DiscoveredDevice? {
        val serviceName = info.serviceName
        val serviceType = info.serviceType.ifBlank { registeredType }
        val port = info.port

        @Suppress("DEPRECATION")
        val host = info.host?.hostAddress

        if (host.isNullOrBlank()) {
            Log.d(TAG, "Resolved service $serviceName has no host, skipping")
            return null
        }

        return DiscoveredDevice(
            serviceName = serviceName,
            serviceType = serviceType,
            host = host,
            port = port,
            discoveredAt = System.currentTimeMillis()
        )
    }
}