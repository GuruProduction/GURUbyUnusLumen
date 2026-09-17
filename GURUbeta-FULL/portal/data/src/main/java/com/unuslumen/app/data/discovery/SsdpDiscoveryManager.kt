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
    val discoveredAt: Long
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

        val host = extractHostFromLocation(location, sourceIp)

        // Create initial device entry
        val device = SsdpDevice(
            usn = usn,
            st = st,
            location = location,
            server = server,
            host = host,
            friendlyName = "",
            manufacturer = "",
            modelName = "",
            discoveredAt = System.currentTimeMillis()
        )
        discoveredDevices[usn] = device
        Log.d(TAG, "Found SSDP device: $usn at $host (ST: $st)")

        // Fetch device description XML for rich metadata
        if (location.isNotBlank()) {
            executor.execute {
                fetchDeviceDescription(usn, location)
            }
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
    private fun fetchDeviceDescription(usn: String, location: String) {
        try {
            val url = URL(location)
            val connection = url.openConnection() as HttpURLConnection
            connection.requestMethod = "GET"

            val responseCode = connection.responseCode
            if (responseCode != 200) {
                Log.d(TAG, "Device description for $usn returned $responseCode")
                connection.disconnect()
                return
            }

            val xml = connection.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
            connection.disconnect()

            val friendlyName = extractXmlTag(xml, "friendlyName")
            val manufacturer = extractXmlTag(xml, "manufacturer")
            val modelName = extractXmlTag(xml, "modelName")

            // Update the device with rich metadata
            val existing = discoveredDevices[usn] ?: return
            val updated = existing.copy(
                friendlyName = friendlyName,
                manufacturer = manufacturer,
                modelName = modelName
            )
            discoveredDevices[usn] = updated

            Log.d(TAG, "Enriched device $usn: $friendlyName ($manufacturer $modelName)")
        } catch (e: Exception) {
            Log.d(TAG, "Failed to fetch device description for $usn: ${e.message}")
        }
    }

    /**
     * Extracts the text content of an XML tag from a device description document.
     * Handles namespaces by matching on the local tag name only.
     */
    private fun extractXmlTag(xml: String, tagName: String): String {
        // Match <tagName>value</tagName> or <ns:tagName>value</ns:tagName>
        val regex = Regex("<[^>]*$tagName[^>]*>([^<]*)<[^>]*$tagName[^>]*>", RegexOption.IGNORE_CASE)
        return regex.find(xml)?.groupValues?.getOrNull(1)?.trim() ?: ""
    }
}