// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.discovery

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.util.Log
import org.koin.core.annotation.Single
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.MulticastSocket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

/**
 * Discovered SSDP/UPnP device on the local network.
 */
data class SsdpDevice(
    val usn: String,
    val st: String,
    val location: String,
    val server: String,
    val host: String,
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val discoveredAt: Long,
    /**
     * Why the name fields are what they are (#333).
     *
     * An empty [friendlyName] used to be indistinguishable from "the description was
     * never fetched", and on the reported network that was the difference between the
     * LG sets and the Amazon ones. Defaults to [DescriptionState.NOT_FETCHED] because
     * a device is created before its description is requested, and claiming it was
     * fetched before it was would be the same dishonesty in the other direction.
     */
    val descriptionState: DescriptionState = DescriptionState.NOT_FETCHED,
    /** The reason behind [descriptionState], in one line a caller can show. */
    val descriptionDetail: String? = null,
)

/**
 * Discovers SSDP/UPnP devices on the local network by sending M-SEARCH multicast
 * packets to 239.255.255.250:1900 and parsing responses.
 *
 * Discovers Samsung TVs, DLNA servers, routers, and any UPnP-compatible device.
 * Also fetches device description XML from the LOCATION URL to extract friendly
 * name, manufacturer, and model.
 */
@Single
class SsdpDiscoveryManager(
    private val context: Context
) {
    companion object {
        private const val TAG = "SsdpDiscoveryManager"
        private const val MULTICAST_ADDRESS = "239.255.255.250"
        private const val MULTICAST_PORT = 1900

        private val SEARCH_TARGETS = listOf(
            "ssdp:all",
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:schemas-upnp-org:device:MediaServer:1",
            "urn:schemas-upnp-org:device:InternetGatewayDevice:1",
            "urn:schemas-upnp-org:device:Basic:1",
            "urn:dial-multiscreen-org:service:dial:1"
        )
    }

    private val discoveredDevices = ConcurrentHashMap<String, SsdpDevice>()

    @Volatile
    private var isDiscovering = false

    private var multicastLock: WifiManager.MulticastLock? = null

    private val executor = Executors.newCachedThreadPool { r ->
        Thread(r, "ssdp-${System.nanoTime()}").apply { isDaemon = true }
    }

    /**
     * Starts SSDP discovery. Sends M-SEARCH packets for common UPnP search targets
     * and listens for responses. Returns immediately, discovery runs in background.
     */
    fun startDiscovery() {
        if (isDiscovering) {
            Log.d(TAG, "Discovery already in progress, ignoring startDiscovery()")
            return
        }
        isDiscovering = true
        discoveredDevices.clear()
        acquireMulticastLock()

        executor.execute {
            sendMSearchAndWait()
        }
    }

    /**
     * Stops SSDP discovery.
     */
    fun stopDiscovery() {
        isDiscovering = false
        releaseMulticastLock()
    }

    /**
     * Returns a snapshot list of all discovered SSDP devices.
     */
    fun getDiscoveredDevices(): List<SsdpDevice> {
        return discoveredDevices.values.toList().sortedBy { it.friendlyName.ifBlank { it.host } }
    }

    // ---- Internal ----

    @SuppressLint("WifiManagerPotentialLeak")
    private fun acquireMulticastLock() {
        if (multicastLock != null && multicastLock?.isHeld == true) return
        try {
            val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            if (wifiManager == null) {
                Log.w(TAG, "WifiManager unavailable, cannot acquire multicast lock")
                return
            }
            val lock = wifiManager.createMulticastLock("guru_ssdp_lock")
            lock.setReferenceCounted(false)
            lock.acquire()
            multicastLock = lock
            Log.d(TAG, "Multicast lock acquired")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to acquire multicast lock", e)
        }
    }

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

    /**
     * Sends M-SEARCH packets for all search targets and waits for responses.
     * Runs on a background thread. Listens for both unicast responses to our
     * M-SEARCH and passive NOTIFY broadcasts from devices.
     */
    private fun sendMSearchAndWait() {
        val group = InetAddress.getByName(MULTICAST_ADDRESS)

        // Use a DatagramSocket on an ephemeral port for sending and receiving unicast responses
        DatagramSocket().use { socket ->
            socket.broadcast = true

            // Send M-SEARCH for each target
            for (st in SEARCH_TARGETS) {
                if (!isDiscovering) return
                val searchMessage = buildMSearchMessage(st)
                val packet = DatagramPacket(
                    searchMessage.toByteArray(Charsets.UTF_8),
                    searchMessage.length,
                    group,
                    MULTICAST_PORT
                )
                try {
                    socket.send(packet)
                    Log.d(TAG, "Sent M-SEARCH for: $st")
                } catch (e: Exception) {
                    Log.e(TAG, "Failed to send M-SEARCH for $st", e)
                }
            }

            // Listen for responses until stopped
            val buffer = ByteArray(8192)
            while (isDiscovering) {
                val responsePacket = DatagramPacket(buffer, buffer.size)
                try {
                    socket.receive(responsePacket)
                    val response = String(responsePacket.data, 0, responsePacket.length, Charsets.UTF_8)
                    parseSsdpResponse(response, responsePacket.address?.hostAddress ?: "")
                } catch (e: java.net.SocketTimeoutException) {
                    continue
                } catch (e: Exception) {
                    Log.e(TAG, "Error receiving SSDP response", e)
                }
            }
        }

        // Also listen on the multicast socket for passive NOTIFY broadcasts
        if (isDiscovering) {
            listenForNotifyBroadcasts()
        }

        isDiscovering = false
        Log.d(TAG, "SSDP discovery complete. Found ${discoveredDevices.size} devices.")
    }

    /**
     * Listens for passive NOTIFY alive/byebye broadcasts on the SSDP multicast address.
     * Runs for the remainder of the discovery timeout.
     */
    private fun listenForNotifyBroadcasts() {
        try {
            MulticastSocket(MULTICAST_PORT).use { socket ->
                val group = InetAddress.getByName(MULTICAST_ADDRESS)
                socket.joinGroup(group)

                val buffer = ByteArray(8192)
                while (isDiscovering) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    try {
                        socket.receive(packet)
                        val data = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        if (data.startsWith("NOTIFY")) {
                            parseSsdpResponse(data, packet.address?.hostAddress ?: "")
                        }
                    } catch (e: java.net.SocketTimeoutException) {
                        continue
                    }
                }
                socket.leaveGroup(group)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to listen for NOTIFY broadcasts", e)
        }
    }

    /**
     * Builds an SSDP M-SEARCH message for the given search target.
     */
    private fun buildMSearchMessage(st: String): String {
        return StringBuilder().apply {
            append("M-SEARCH * HTTP/1.1\r\n")
            append("HOST: $MULTICAST_ADDRESS:$MULTICAST_PORT\r\n")
            append("MAN: \"ssdp:discover\"\r\n")
            append("MX: 3\r\n")
            append("ST: $st\r\n")
            append("\r\n")
        }.toString()
    }

    /**
     * Parses an SSDP response (either M-SEARCH response or NOTIFY alive) and
     * adds the device to the registry if it is new.
     */
    private fun parseSsdpResponse(response: String, sourceIp: String) {
        val headers = parseSsdpHeaders(response)

        val usn = headers["USN"] ?: return
        val st = headers["ST"] ?: headers["NT"] ?: ""
        val location = headers["LOCATION"] ?: ""
        val server = headers["SERVER"] ?: ""

        // Skip byebye messages
        val nts = headers["NTS"]
        if (nts == "ssdp:byebye") {
            discoveredDevices.remove(usn)
            return
        }

        // Already discovered this device
        if (discoveredDevices.containsKey(usn)) return

        // The LOCATION is normalised before anything reads it, because a bare host:port is
        // what issue 333 is about: URL() treats the IP as a protocol, the fetch never
        // happens, and the device stays unnamed while a neighbour sending a proper URL is
        // named. Normalising once here means both the host and the fetch agree.
        val normalised = SsdpDescriptions.normaliseLocation(location)
        val host = SsdpDescriptions.hostOf(normalised, sourceIp)

        // Create initial device entry. NOT_FETCHED, not FETCHED: the description has not
        // been read yet, and a default that claims otherwise would be the same lie as an
        // empty name with no reason attached.
        val device = SsdpDevice(
            usn = usn,
            st = st,
            location = normalised ?: location,
            server = server,
            host = host,
            friendlyName = "",
            manufacturer = "",
            modelName = "",
            discoveredAt = System.currentTimeMillis(),
            descriptionState = if (normalised == null) {
                DescriptionState.BAD_LOCATION
            } else {
                DescriptionState.NOT_FETCHED
            },
            descriptionDetail = if (normalised == null && location.isNotBlank()) {
                "the LOCATION header was \"$location\""
            } else {
                null
            },
        )
        discoveredDevices[usn] = device
        Log.d(TAG, "Found SSDP device: $usn at $host (ST: $st, location=$normalised)")

        // Fetch device description XML for rich metadata
        if (normalised != null) {
            executor.execute {
                fetchDeviceDescription(usn, normalised)
            }
        } else if (location.isBlank()) {
            // No LOCATION at all is a fact about the device, and it is recorded so the
            // caller can tell it from a failed fetch.
            discoveredDevices[usn] = device.copy(descriptionState = DescriptionState.NO_LOCATION)
        }
    }

    /**
     * Parses SSDP HTTP-style headers from a response string.
     */
    private fun parseSsdpHeaders(response: String): Map<String, String> {
        val headers = mutableMapOf<String, String>()
        val lines = response.lines()
        for (line in lines.drop(1)) { // Skip the first line (status line)
            val colonIndex = line.indexOf(':')
            if (colonIndex > 0) {
                val key = line.substring(0, colonIndex).trim().uppercase()
                val value = line.substring(colonIndex + 1).trim()
                headers[key] = value
            }
        }
        return headers
    }

    /**
     * Extracts the host IP from a LOCATION URL, falling back to the source IP.
     */
    private fun extractHostFromLocation(location: String, sourceIp: String): String {
        return try {
            val url = URL(location)
            url.host
        } catch (e: Exception) {
            sourceIp
        }
    }

    /**
     * Fetches the device description XML from the LOCATION URL and extracts
     * friendlyName, manufacturer, and modelName.
     */
    private fun fetchDeviceDescription(usn: String, normalisedLocation: String) {
        // Every early return below records WHY it returned. The old version logged and
        // returned, which is precisely how issue 333 stayed invisible: the caller saw an
        // empty name and no reason, so a device whose description was never fetched was
        // indistinguishable from a device that declares no name.
        fun fail(state: DescriptionState, detail: String?) {
            val existing = discoveredDevices[usn] ?: return
            discoveredDevices[usn] = existing.copy(descriptionState = state, descriptionDetail = detail)
            Log.d(TAG, "Description for $usn not read: $state ${detail.orEmpty()}")
        }

        val url = runCatching { URL(normalisedLocation) }.getOrNull()
            ?: return fail(DescriptionState.BAD_LOCATION, normalisedLocation)

        // THE LOCATION HEADER IS THE DEVICE'S OWN CHOICE, NOT OURS. This used to fetch
        // whatever URL a device advertised with no check at all, so a device on the network
        // could hand GURU a public address and have it fetched from the device's real one.
        // A description belongs on this network, so anything else is refused outright rather
        // than classified and routed: this path has no business reaching the internet.
        if (!com.unuslumen.app.data.tor.EgressClassifier.isLocalName(url.host)) {
            return fail(DescriptionState.REFUSED_OFF_NETWORK, url.host)
        }

        val xml = try {
            val connection = url.openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
            connection.requestMethod = "GET"
            connection.connectTimeout = 3000
            connection.readTimeout = 3000

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                connection.disconnect()
                return fail(DescriptionState.HTTP_ERROR, "HTTP $responseCode from ${url.host}")
            }

            // BOUNDED, like every other body read in this app. readText() allocates the whole
            // document with no ceiling, and this is a response from a device on the wifi, so a
            // malfunctioning or hostile device could otherwise stream until the heap dies.
            val body = connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                val buffer = CharArray(64 * 1024)
                val read = reader.read(buffer)
                if (read <= 0) "" else String(buffer, 0, read)
            }
            connection.disconnect()
            body
        } catch (e: Exception) {
            return fail(DescriptionState.HTTP_ERROR, e.message ?: e.javaClass.simpleName)
        }

        val parsed = SsdpDescriptions.parse(xml)
        val state = SsdpDescriptions.stateOf(parsed)

        val existing = discoveredDevices[usn] ?: return
        discoveredDevices[usn] = existing.copy(
            friendlyName = parsed?.friendlyName.orEmpty(),
            manufacturer = parsed?.manufacturer.orEmpty(),
            modelName = parsed?.modelName.orEmpty(),
            descriptionState = state,
            descriptionDetail = if (state == DescriptionState.UNPARSEABLE) {
                "${xml.length} characters from ${url.host} that did not parse as a device description"
            } else {
                null
            },
        )

        Log.d(TAG, "Description for $usn: $state (${parsed?.friendlyName.orEmpty()})")
    }
}