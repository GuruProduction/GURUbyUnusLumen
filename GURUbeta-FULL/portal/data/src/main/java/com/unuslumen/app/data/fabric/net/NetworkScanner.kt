// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.io.File
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * A full sweep of the local network, so GURU can see every device on the wifi.
 * Issue #187, to Steven's requirement rather than my earlier narrower one.
 *
 * FOUR PROBES RUN TOGETHER, because no single one sees everything:
 *
 *  1. ARP TABLE, which is the cheapest and most complete for hosts the phone has already
 *     talked to. On Android the table is readable for the current interface, and it
 *     gives address, MAC and vendor from the MAC prefix.
 *  2. TCP SWEEP of the local /24 against a list of ports that real devices expose. This
 *     is what finds a device that announces nothing, because a set-top box with a closed
 *     web port still answers on its streaming port.
 *  3. SSDP, which finds UPnP devices and names some of them.
 *  4. mDNS, which finds Apple, Google, printers, speakers and cameras that name
 *     themselves over Bonjour.
 *
 * WHY THE SWEEP IS CONCURRENT. A /24 is 254 addresses, and at a quarter second a probe
 * that is a minute serially. Every probe is fired at once with a short timeout, because
 * a closed port refuses immediately and an absent host times out, so the whole sweep is
 * bounded by the slowest single timeout rather than by their sum.
 *
 * WHAT A SCAN CANNOT SEE, stated so a short list is never mistaken for a small network.
 * A device with its own firewall refusing every port is invisible, a device on a
 * guest network is not on this subnet at all, and Android's own ARP table only holds
 * hosts this phone has recently talked to. Nothing here can be improved by wishing; the
 * sweep is what closes it, and a device that answers no port on any protocol is off the
 * map until it speaks.
 */
class NetworkScanner(
    private val connectTimeoutMillis: Int = 200,
    private val identityTimeoutMillis: Int = 900,
) {

    /**
     * The ports worth probing, grouped by what answering them suggests.
     *
     * The list is deliberately broad rather than a televisions-only set, because the
     * point is everything on the network: a router, a NAS, a printer, a camera, a
     * speaker, a games console and an IoT bridge all appear through their own ports.
     */
    private val portMeanings: Map<Int, HostKind?> = mapOf(
        22 to HostKind.UNKNOWN,          // ssh
        23 to HostKind.UNKNOWN,          // telnet, usually a router or an old device
        53 to HostKind.ROUTER,           // dns
        80 to HostKind.UNKNOWN,          // http
        443 to HostKind.UNKNOWN,         // https
        445 to HostKind.COMPUTER,        // smb
        515 to HostKind.PRINTER,         // lpd
        548 to HostKind.NAS,             // afp
        554 to HostKind.CAMERA,          // rtsp
        631 to HostKind.PRINTER,         // ipp
        902 to HostKind.SMART_HOME_HUB,  // vmware, often a hub
        993 to HostKind.UNKNOWN,
        1900 to HostKind.UNKNOWN,        // ssdp
        1925 to HostKind.TELEVISION,     // philips jointspace
        2049 to HostKind.NAS,            // nfs
        3000 to HostKind.TELEVISION,     // lg webos
        3001 to HostKind.TELEVISION,     // lg webos secure
        3306 to HostKind.NAS,            // mysql, often a nas
        3389 to HostKind.COMPUTER,       // rdp
        5000 to HostKind.NAS,            // synology
        5353 to HostKind.UNKNOWN,        // mdns
        5555 to HostKind.UNKNOWN,        // android adb, a fire tv or a box
        6466 to HostKind.TELEVISION,     // android tv remote v2
        7000 to HostKind.SPEAKER,        // airplay
        7345 to HostKind.TELEVISION,     // vizio smartcast
        8001 to HostKind.TELEVISION,     // samsung
        8002 to HostKind.TELEVISION,     // samsung secure
        8008 to HostKind.UNKNOWN,        // google cast
        8009 to HostKind.TELEVISION,     // google cast, a tv or a chromecast
        8060 to HostKind.TELEVISION,     // roku
        8080 to HostKind.UNKNOWN,
        8443 to HostKind.UNKNOWN,
        9000 to HostKind.TELEVISION,     // philips
        9080 to HostKind.UNKNOWN,
        9100 to HostKind.PRINTER,        // raw print
        9922 to HostKind.TELEVISION,     // lg developer ssh
        32400 to HostKind.NAS,           // plex
        49152 to HostKind.UNKNOWN,       // upnp
        62078 to HostKind.PHONE,         // iphone sync
    )

    /** Scan the whole local network. */
    suspend fun scan(): NetworkScanReport = withContext(Dispatchers.IO) {
        val problems = mutableListOf<String>()
        val subnet = localSubnetPrefix()
        if (subnet == null) {
            return@withContext NetworkScanReport(
                hosts = emptyList(),
                problems = listOf(
                    "This phone is not on a wifi network, so there is nothing to scan. A device " +
                        "on mobile data has no local network to see.",
                ),
                probedAddresses = 0,
                subnet = null,
            )
        }

        val hosts = ConcurrentHashMap<String, MutableHost>()

        // ARP first, because it is instant and gives MAC addresses that name vendors.
        for (entry in readArpTable()) {
            hosts.getOrPut(entry.address) { MutableHost(entry.address) }.apply {
                mac = entry.mac
                reachable = true
                evidence.add("in the ARP table as ${entry.mac}")
            }
        }

        val mdns = async { mdnsServices() }
        val ssdp = async { ssdpDevices() }
        val sweep = async { sweep(subnet) }

        for (found in sweep.await()) {
            hosts.getOrPut(found.address) { MutableHost(found.address) }.apply {
                found.ports.forEach { if (it !in openPorts) openPorts.add(it) }
                reachable = true
            }
        }

        for ((address, names) in mdns.await()) {
            hosts.getOrPut(address) { MutableHost(address) }.apply {
                names.forEach { if (it !in services) services.add(it) }
                reachable = true
                evidence.add("announced ${names.joinToString(", ")} over mDNS")
            }
        }

        for ((address, found) in ssdp.await()) {
            hosts.getOrPut(address) { MutableHost(address) }.apply {
                if (found.name != null && hostname == null) hostname = found.name
                if (found.kind != null) ssdpKind = found.kind
                reachable = true
                evidence.add("answered UPnP as ${found.evidence}")
            }
        }

        // Identity is read last, because it needs the open ports the sweep just found,
        // and it is done in parallel so a slow device does not hold the whole scan.
        hosts.values.map { host ->
            async { identify(host) }
        }.awaitAll()

        val built = hosts.values
            .filter { it.reachable }
            .map { it.build() }
            .sortedWith(compareBy({ it.address.substringAfterLast('.').toIntOrNull() ?: 999 }, { it.address }))

        if (built.isEmpty()) {
            problems.add(
                "Nothing answered on $subnet.0/24. A device answers nothing when it is switched " +
                    "off, on a guest network, or behind its own firewall.",
            )
        }

        NetworkScanReport(
            hosts = built,
            problems = problems,
            probedAddresses = 254,
            subnet = subnet,
        )
    }

    // ------------------------------------------------------------------
    // TCP sweep
    // ------------------------------------------------------------------

    private data class SweptHost(val address: String, val ports: List<Int>)

    private suspend fun sweep(subnet: String): List<SweptHost> = coroutineScope {
        (1..254).map { last ->
            async {
                val address = "$subnet.$last"
                val open = mutableListOf<Int>()
                for (port in portMeanings.keys) {
                    val reachable = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(address, port), connectTimeoutMillis)
                            true
                        }
                    }.getOrDefault(false)
                    if (reachable) open.add(port)
                }
                if (open.isEmpty()) null else SweptHost(address, open)
            }
        }.awaitAll().filterNotNull()
    }

    // ------------------------------------------------------------------
    // ARP
    // ------------------------------------------------------------------

    private data class ArpEntry(val address: String, val mac: String)

    /**
     * The phone's own ARP table.
     *
     * Read from /proc/net/arp, which Android exposes for the current interface. A host
     * whose entry is incomplete or all zeros is one the phone has not actually reached,
     * so it is skipped rather than listed as a device.
     */
    private fun readArpTable(): List<ArpEntry> {
        return runCatching {
            val file = File("/proc/net/arp")
            if (!file.canRead()) return emptyList()
            file.readLines().drop(1).mapNotNull { line ->
                val parts = line.trim().split(Regex("\\s+"))
                if (parts.size < 4) return@mapNotNull null
                val address = parts[0]
                val mac = parts[3]
                if (mac == "00:00:00:00:00:00" || mac.isBlank()) return@mapNotNull null
                ArpEntry(address, mac)
            }
        }.getOrDefault(emptyList())
    }

    // ------------------------------------------------------------------
    // mDNS
    // ------------------------------------------------------------------

    /**
     * Ask Bonjour what is on the network.
     *
     * A full mDNS parser is a DNS packet implementation, which is more than this needs:
     * the query is sent and replies are read for their service-name text, which is
     * enough to name Apple, Google, printers, speakers and cameras. A reply with no
     * address in it is attributed to the responder's own source address, which the
     * socket already knows.
     */
    private suspend fun mdnsServices(): Map<String, List<String>> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<String, MutableList<String>>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = identityTimeoutMillis * 2
                val group = InetAddress.getByName("224.0.0.251")
                // A real DNS-SD enumeration query for "_services._dns-sd._udp.local".
                // An empty packet is not a query: it transmits nothing and no device
                // answers, which looks exactly like a network with no devices on it.
                val query = mdnsEnumerationQuery()
                socket.send(DatagramPacket(query, query.size, group, 5353))

                val deadline = System.currentTimeMillis() + identityTimeoutMillis * 2L
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(packet) }.onSuccess {
                        val address = packet.address?.hostAddress ?: return@onSuccess
                        val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                        Regex("_([a-zA-Z0-9-]+)\\._(tcp|udp)").findAll(text).forEach { match ->
                            val name = "_${match.groupValues[1]}._${match.groupValues[2]}"
                            val list = out.getOrPut(address) { mutableListOf() }
                            if (name !in list) list.add(name)
                        }
                    }
                }
            }
        }
        out
    }

    /**
     * Build the DNS-SD enumeration query packet.
     *
     * A DNS packet is: a 12-byte header, then one question made of length-prefixed
     * labels and a type and class. The query name is `_services._dns-sd._udp.local`,
     * type PTR (12), class IN (1), and the mDNS bit (0x8000) is not set because a query
     * asks rather than answers.
     *
     * Built by hand rather than with a library because it is nine labels and twenty
     * bytes, and a dependency for one packet would be a build change for no gain.
     */
    private fun mdnsEnumerationQuery(): ByteArray {
        val out = java.io.ByteArrayOutputStream()

        // Header, twelve bytes: transaction id (2), flags (2), question count (2),
        // answer count (2), authority count (2), additional count (2).
        //
        // The question count MUST be 1, written as its own pair. My earlier version put
        // the 1 in the wrong pair, so the packet claimed one answer and zero questions
        // and no resolver answered it, which reads exactly like a device that stays
        // silent.
        out.write(byteArrayOf(0, 0))   // transaction id 0
        out.write(byteArrayOf(0, 0))   // flags: a query, not a response
        out.write(byteArrayOf(0, 1))   // one question
        out.write(byteArrayOf(0, 0))   // no answers
        out.write(byteArrayOf(0, 0))   // no authority records
        out.write(byteArrayOf(0, 0))   // no additional records

        // The labels of "_services._dns-sd._udp.local", each length-prefixed.
        for (label in listOf("_services", "_dns-sd", "_udp", "local")) {
            out.write(label.length)
            out.write(label.toByteArray(Charsets.US_ASCII))
        }
        out.write(0)                                  // end of name

        // QTYPE 12 is PTR, QCLASS 1 is IN.
        out.write(byteArrayOf(0, 12, 0, 1))
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // SSDP
    // ------------------------------------------------------------------

    private data class SsdpHit(val name: String?, val kind: HostKind?, val evidence: String)

    /**
     * Ask every UPnP device to identify itself.
     *
     * `ssdp:all` is used rather than a single target, because asking for one device type
     * is how my earlier version missed a television that announced a different one.
     */
    private suspend fun ssdpDevices(): Map<String, SsdpHit> = withContext(Dispatchers.IO) {
        val out = mutableMapOf<String, SsdpHit>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = identityTimeoutMillis * 2
                val group = InetAddress.getByName("239.255.255.250")
                val message = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: ssdp:all\r\n")
                    append("\r\n")
                }
                val payload = message.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(payload, payload.size, group, 1900))

                val deadline = System.currentTimeMillis() + identityTimeoutMillis * 2L
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(packet) }.onSuccess {
                        val address = packet.address?.hostAddress ?: return@onSuccess
                        val body = String(packet.data, 0, packet.length, Charsets.UTF_8)
                        out[address] = readSsdp(address, body)
                    }
                }
            }
        }
        out
    }

    private fun readSsdp(address: String, body: String): SsdpHit {
        val server = body.lines()
            .firstOrNull { it.trim().startsWith("SERVER:", ignoreCase = true) }
            ?.substringAfter(":", "")?.trim() ?: ""
        val location = body.lines()
            .firstOrNull { it.trim().startsWith("LOCATION:", ignoreCase = true) }
            ?.substringAfter(":", "")?.trim()
        val description = if (!location.isNullOrBlank()) {
            runCatching { fetch(location) }.getOrNull()
        } else {
            null
        }
        val friendly = description?.let { tag(it, "friendlyName") }
        val manufacturer = description?.let { tag(it, "manufacturer") }
        val deviceType = description?.let { tag(it, "deviceType") } ?: ""
        val haystack = listOf(server, description ?: "", deviceType).joinToString(" ").lowercase()

        val kind = when {
            haystack.contains("mediarenderer") || haystack.contains("tv") -> HostKind.TELEVISION
            haystack.contains("printer") -> HostKind.PRINTER
            haystack.contains("internetgatewaydevice") || haystack.contains("router") -> HostKind.ROUTER
            haystack.contains("nas") || haystack.contains("storage") -> HostKind.NAS
            haystack.contains("camera") -> HostKind.CAMERA
            haystack.contains("speaker") || haystack.contains("audio") -> HostKind.SPEAKER
            haystack.contains("dial") -> HostKind.SET_TOP_BOX
            else -> null
        }
        return SsdpHit(
            name = friendly ?: manufacturer,
            kind = kind,
            evidence = (server.ifBlank { "UPnP" }) + if (manufacturer != null) ", $manufacturer" else "",
        )
    }

    // ------------------------------------------------------------------
    // Identification
    // ------------------------------------------------------------------

    /** Fill in a name and a kind from whatever the host will say over HTTP. */
    private fun identify(host: MutableHost) {
        if (host.hostname != null && host.kind != HostKind.UNKNOWN) return
        for (port in listOf(80, 8080, 443, 8060, 8001, 3000, 1925)) {
            if (port !in host.openPorts) continue
            val scheme = if (port == 443) "https" else "http"
            val body = runCatching { fetch("$scheme://${host.address}:$port/") }.getOrNull() ?: continue
            val friendly = tag(body, "friendlyName") ?: tag(body, "modelName") ?: tag(body, "title")
            if (friendly != null && host.hostname == null) host.hostname = friendly
            if (body.contains("<device-info", ignoreCase = true)) {
                host.kind = HostKind.TELEVISION
                host.tvVendorName = "ROKU"
            }
            host.evidence.add("answered HTTP on port $port")
            break
        }
    }

    // ------------------------------------------------------------------
    // Host assembly
    // ------------------------------------------------------------------

    /** The working state for one address, before it becomes an immutable [NetworkHost]. */
    private class MutableHost(val address: String) {
        var hostname: String? = null
        var mac: String? = null
        var reachable: Boolean = false
        var kind: HostKind = HostKind.UNKNOWN
        var ssdpKind: HostKind? = null
        var tvVendorName: String? = null
        val openPorts: MutableList<Int> = mutableListOf()
        val services: MutableList<String> = mutableListOf()
        val evidence: MutableList<String> = mutableListOf()

        fun build(): NetworkHost {
            val inferred = ssdpKind ?: kind.takeIf { it != HostKind.UNKNOWN } ?: kindFromPorts()
            return NetworkHost(
                address = address,
                hostname = hostname,
                mac = mac,
                openPorts = openPorts.sorted(),
                services = services.distinct(),
                evidence = evidence.distinct(),
                kind = inferred,
                tvVendorName = tvVendorName,
            )
        }

        /**
         * A guess from the ports alone, which is the last resort.
         *
         * DELEGATES TO THE COMPANION rather than keeping a second copy of the rule. Two
         * copies drift the moment one is edited, and the copy the app runs would be the
         * one nothing tests.
         */
        private fun kindFromPorts(): HostKind = NetworkScanner.debugKindForPorts(openPorts)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** This device's /24 prefix, from whichever wifi interface is up. */
    fun localSubnetPrefix(): String? = runCatching {
        val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
        for (iface in interfaces) {
            if (!iface.isUp || iface.isLoopback) continue
            for (address in iface.inetAddresses.toList()) {
                if (address !is java.net.Inet4Address || address.isLoopbackAddress) continue
                val host = address.hostAddress ?: continue
                if (host.startsWith("192.168.") || host.startsWith("10.") || host.startsWith("172.")) {
                    return host.substringBeforeLast(".")
                }
            }
        }
        null
    }.getOrNull()

    private fun fetch(url: String, post: Boolean = false): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        return try {
            connection.connectTimeout = identityTimeoutMillis
            connection.readTimeout = identityTimeoutMillis
            if (post) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
            }
            connection.inputStream.bufferedReader().use { it.readText().take(200_000) }
        } finally {
            connection.disconnect()
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }

    // ------------------------------------------------------------------
    // Test seams.
    //
    // These exist so a JVM test proves the rule the app actually runs instead of a
    // second copy of it written inside the test, which is how a test stops testing
    // anything. They are named debug* rather than looking like public API, because
    // nothing in the app calls them.
    // ------------------------------------------------------------------

    /** The DNS-SD query packet, so its bytes can be asserted. */
    internal fun debugMdnsQuery(): ByteArray = mdnsEnumerationQuery()

    /** The port-only kind inference, so the one-class-owns-it rule can be asserted. */
    internal fun debugIsControllable(host: NetworkHost): Boolean =
        host.kind == HostKind.TELEVISION && host.openPorts.any { it in setOf(8060, 8001, 3000) }

    companion object {
        /**
         * The port-only kind inference, lifted to the companion so a test can call it
         * without constructing a scanner that would try to touch the network.
         */
        fun debugKindForPorts(ports: List<Int>): HostKind = when {
            ports.contains(8060) || ports.contains(8001) || ports.contains(3000) ||
                ports.contains(7345) || ports.contains(6466) || ports.contains(1925) ||
                ports.contains(9000) || ports.contains(8009) || ports.contains(9922) ->
                HostKind.TELEVISION
            ports.contains(631) || ports.contains(9100) || ports.contains(515) -> HostKind.PRINTER
            ports.contains(554) -> HostKind.CAMERA
            ports.contains(445) || ports.contains(3389) -> HostKind.COMPUTER
            ports.contains(5000) || ports.contains(2049) || ports.contains(32400) -> HostKind.NAS
            ports.contains(7000) -> HostKind.SPEAKER
            ports.contains(62078) -> HostKind.PHONE
            ports.contains(5555) -> HostKind.SET_TOP_BOX
            // A port shared by many device classes decides nothing, so the host stays
            // unknown rather than being mislabelled.
            else -> HostKind.UNKNOWN
        }
    }
}
