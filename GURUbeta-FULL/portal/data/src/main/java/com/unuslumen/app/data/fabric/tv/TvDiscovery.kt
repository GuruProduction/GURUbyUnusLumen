// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.net.URL
import java.util.concurrent.ConcurrentHashMap

/**
 * Finding the televisions on the local network, and working out which vendor each one
 * is. Issue #187.
 *
 * WHY DISCOVERY IS THE HARD PART AND THE PROTOCOLS ARE NOT. A universal remote app's
 * value is that a person never tells it what make their telly is. That means the
 * discovery step has to identify the set itself, and there is no single way to do it:
 * SSDP answers for some, mDNS for others, and several vendors expose a plain HTTP
 * endpoint whose response gives them away.
 *
 * SO THREE PROBES RUN, and a device is classified by whichever answers first:
 *
 *  1. SSDP M-SEARCH to 239.255.255.250:1900, which is how Samsung, LG and Philips
 *     announce themselves. The reply's SERVER header and the LOCATION document name
 *     the make.
 *  2. Port probes on the known remote ports, because a set that answers on 8060 is a
 *     Roku whatever it calls itself, and that is more reliable than any name.
 *  3. HTTP identity reads, which confirm a port probe and yield a human name.
 *
 * WHAT IT REFUSES TO DO. It does not guess. A set that answers nothing is not reported
 * as an unknown television, because a device in the list that cannot be controlled
 * reads as a broken app. And it never reports a vendor whose adapter is missing as if
 * it were controllable: that is reported as a set it found but cannot drive, with the
 * make named, so the gap is visible instead of silent.
 */
class TvDiscovery(
    private val timeoutMillis: Int = 1200,
) {

    /** Every vendor's remote port, so a probe can name the make from the port alone. */
    private val portHints: Map<Int, TvVendor> = mapOf(
        8060 to TvVendor.ROKU,
        8001 to TvVendor.SAMSUNG,
        8002 to TvVendor.SAMSUNG,
        3000 to TvVendor.LG,
        3001 to TvVendor.LG,
        7345 to TvVendor.VIZIO,
        9000 to TvVendor.PHILIPS,
        1925 to TvVendor.PHILIPS,
        6466 to TvVendor.ANDROID_TV,
        6467 to TvVendor.ANDROID_TV,
    )

    /**
     * Discover every television this phone can reach.
     *
     * SSDP and the port sweep run together, because SSDP alone misses sets that do not
     * answer multicast and the sweep alone cannot name a make on a port two vendors
     * share.
     */
    suspend fun discover(): DiscoveryReport = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, DiscoveredTv>()
        val problems = java.util.Collections.synchronizedList(mutableListOf<String>())

        val ssdp = async { ssdpDiscover() }
        val sweep = async { sweepLocalSubnet(problems) }

        for (device in ssdp.await()) {
            found[device.host] = device
        }
        for (device in sweep.await()) {
            // A device SSDP already named keeps that richer answer, because a name from
            // the set's own description document beats a port guess.
            found.putIfAbsent(device.host, device)
        }

        if (found.isEmpty()) {
            problems.add(
                "No television answered on this network. A set answers only while it is awake and " +
                    "on the same wifi as the phone, and many need their network remote feature " +
                    "switched on in their own settings first.",
            )
        }

        DiscoveryReport(found.values.sortedBy { it.name }, problems)
    }

    data class DiscoveryReport(val devices: List<DiscoveredTv>, val problems: List<String>)

    // ------------------------------------------------------------------
    // SSDP
    // ------------------------------------------------------------------

    /**
     * Ask every UPnP device on the network to identify itself.
     *
     * The M-SEARCH is sent to the multicast group and each reply carries a ST header
     * naming its device type, which is what separates a MediaRenderer (a telly) from a
     * router or a printer.
     */
    private suspend fun ssdpDiscover(): List<DiscoveredTv> = coroutineScope {
        val replies = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())

        // ASK FOR EVERY DEVICE TYPE, NOT ONE.
        //
        // THE BUG THIS FIXES. This used to send a single M-SEARCH for the DIAL service, so a
        // television that announces itself as a MediaRenderer or as a Basic device never got
        // asked at all. His Sharp answered because it happens to advertise DIAL; a set that
        // advertises something else was invisible.
        //
        // `ssdp:all` is what a scanner asks, and each reply still names its own type so the
        // classifier can tell a telly from a printer.
        val searchTargets = listOf(
            "ssdp:all",
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:schemas-upnp-org:device:Basic:1",
        )

        runCatching {
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMillis * 2
                val group = InetAddress.getByName("239.255.255.250")
                for (target in searchTargets) {
                    val message = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        append("MX: 2\r\n")
                        append("ST: $target\r\n")
                        append("\r\n")
                    }
                    val payload = message.toByteArray(Charsets.UTF_8)
                    runCatching { socket.send(DatagramPacket(payload, payload.size, group, 1900)) }
                }

                val deadline = System.currentTimeMillis() + timeoutMillis * 2L
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val response = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(response) }.onSuccess {
                        val body = String(response.data, 0, response.length, Charsets.UTF_8)
                        val host = response.address?.hostAddress ?: return@onSuccess
                        // A set answers once per search target, so the same reply arrives several
                        // times. Deduplicating by host plus body keeps the classifier honest.
                        val key = host to body
                        if (replies.none { it.first == host }) replies.add(key)
                    }
                }
            }
        }

        replies.mapNotNull { (host, body) ->
            val location = body.lines()
                .firstOrNull { it.trim().startsWith("LOCATION:", ignoreCase = true) }
                ?.substringAfter(":", "")
                ?.trim()
            val description = if (!location.isNullOrBlank()) {
                runCatching { fetch(location) }.getOrNull()
            } else {
                null
            }
            runCatching { TvDiscoveryClassifier.classify(host, body, description) }.getOrNull()
        }
    }

    // ------------------------------------------------------------------
    // Port sweep
    // ------------------------------------------------------------------

    /**
     * Two passes over the local subnet, because one is not enough.
     *
     * PASS 1 probes every host on EVERY known device port, concurrently. That is 254 hosts
     * times a handful of ports, which finishes in seconds, and it finds any set that has its
     * remote port open.
     *
     * PASS 2 takes the hosts that answered anything at all and sweeps every one of their
     * 65535 ports, asking each open port to identify itself. That is how a set on a non-standard
     * port is found, and it is bounded to hosts that already proved they exist, because
     * 254 times 65535 connects would take hours and melt the router.
     *
     * THE BUG THIS REPLACES. The old sweep returned on the FIRST port that answered, so a host
     * whose first open port was not its remote port was never tested as a television at all.
     * His downstairs Roku was found by nothing because of it.
     */
    private suspend fun sweepLocalSubnet(problems: MutableList<String>): List<DiscoveredTv> {
        val prefix = localSubnetPrefix()
        if (prefix == null) {
            problems.add(
                "This phone is not on a wifi network with an IPv4 address, so there is no subnet " +
                    "to sweep. A device on mobile data cannot see the local network.",
            )
            return emptyList()
        }

        // ---- Pass 1: every known device port on every host ----
        val widePorts = portHints.keys.toList() + EXTRA_DEVICE_PORTS
        val answered = coroutineScope {
            (1..254).map { last ->
                async {
                    val host = "$prefix.$last"
                    val open = widePorts.filter { port -> isOpen(host, port) }
                    if (open.isEmpty()) null else host to open
                }
            }.awaitAll().filterNotNull()
        }

        if (answered.isEmpty()) {
            problems.add(
                "Nothing answered on $prefix.0/24, across ${widePorts.size} device ports on every " +
                    "address. A device answers nothing when it is switched off, on a different " +
                    "network, or behind its own firewall.",
            )
            return emptyList()
        }

        // ---- Identify from the ports already known to be open ----
        val found = mutableListOf<DiscoveredTv>()
        val unidentified = mutableListOf<String>()

        for ((host, openPorts) in answered) {
            var identified = false
            for (port in openPorts) {
                val hint = portHints[port] ?: continue
                val device = identify(host, port, hint)
                if (device != null) {
                    found.add(device)
                    identified = true
                    // One set per host, so a vendor with two remote ports cannot list it twice.
                    break
                }
            }
            if (!identified) unidentified.add(host)
        }

        // ---- Pass 2: full sweep on anything that answered but was not identified ----
        //
        // THIS IS THE PART THAT MAKES DISCOVERY COMPLETE. A set on a port nobody has heard of is
        // found here and nowhere else.
        for (host in unidentified) {
            val deep = deepScan(host)
            if (deep != null) found.add(deep)
        }

        return found
    }

    /**
     * Confirm what is on an open port, and name it.
     *
     * The Roku check is decisive because its device-info document is unambiguous. For the other
     * vendors a port that is open is strong evidence and not proof, so the evidence line says
     * exactly that rather than claiming a confident name.
     */
    private fun identify(host: String, port: Int, hint: TvVendor): DiscoveredTv? = when (hint) {
        TvVendor.ROKU -> {
            // Direct, because a private address cannot be routed over a Tor circuit and the
            // failure would look exactly like a set that is switched off.
            val body = runCatching { fetch("http://$host:$port/query/device-info") }.getOrNull()
            if (body == null || !body.contains("<device-info", ignoreCase = true)) {
                null
            } else {
                DiscoveredTv(
                    vendor = TvVendor.ROKU,
                    name = tag(body, "friendly-device-name")
                        ?: tag(body, "model-name")
                        ?: "Roku",
                    host = host,
                    port = port,
                    evidence = "answered Roku's device-info on port $port",
                    needsPairing = false,
                )
            }
        }

        else -> DiscoveredTv(
            vendor = hint,
            name = "${hint.name.lowercase().replaceFirstChar { it.uppercase() }} on $host",
            host = host,
            port = port,
            evidence = "port $port is open, which is ${hint.name}'s remote port",
            needsPairing = hint != TvVendor.ANDROID_TV,
        )
    }

    /** Whether a TCP connection is accepted, which a closed port answers instantly. */
    private fun isOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            true
        }
    }.getOrDefault(false)

    /**
     * Sweep every port on one host and work out what it is from what its services say.
     *
     * The banners are the point: an open port is one bit, and a banner is identity. A device that
     * says "Roku" in an HTTP header is a Roku whatever port it chose.
     */
    private fun deepScan(host: String): DiscoveredTv? {
        val open = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
        val gate = kotlinx.coroutines.sync.Semaphore(DEEP_SCAN_CONCURRENCY)

        // Chunked so the semaphore is actually respected. Firing 65535 coroutines at once is the
        // shape that got a hand-rolled loop OOM-killed at exit 137 on this phone.
        kotlinx.coroutines.runBlocking {
            (1..65535).chunked(2048).forEach { chunk ->
                coroutineScope {
                    chunk.map { port ->
                        async {
                            gate.acquire()
                            try {
                                if (isOpen(host, port)) open.add(port)
                            } finally {
                                gate.release()
                            }
                        }
                    }.awaitAll()
                }
            }
        }

        if (open.isEmpty()) return null

        // Ask each open port what it is, most likely ports first so a real answer arrives early.
        val ordered = open.sortedBy { port ->
            when (port) {
                in portHints -> 0
                in EXTRA_DEVICE_PORTS -> 1
                else -> 2
            }
        }

        for (port in ordered.take(DEEP_SCAN_BANNER_LIMIT)) {
            val banner = readBanner(host, port) ?: continue
            val vendor = vendorFromBanner(banner)
            if (vendor != null) {
                return DiscoveredTv(
                    vendor = vendor,
                    name = nameFromBanner(banner) ?: "${vendor.name.lowercase().replaceFirstChar { it.uppercase() }} on $host",
                    host = host,
                    port = port,
                    evidence = "port $port answered with ${banner.firstLine().take(80)}",
                    needsPairing = vendor != TvVendor.ROKU,
                )
            }
        }
        return null
    }

    /** Read whatever a port says when asked, over HTTP first and raw second. */
    private fun readBanner(host: String, port: Int): String? {
        // An HTTP request first, because most remote interfaces answer one and name themselves.
        val http = runCatching {
            val connection = URL("http://$host:$port/").openConnection() as HttpURLConnection
            connection.connectTimeout = BANNER_TIMEOUT_MILLIS
            connection.readTimeout = BANNER_TIMEOUT_MILLIS
            connection.inputStream.bufferedReader().use { it.readText().take(4000) }
        }.getOrNull()
        if (!http.isNullOrBlank()) return http

        // Then a raw read, which catches SSH, RTSP and anything with its own banner.
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
                socket.soTimeout = BANNER_TIMEOUT_MILLIS
                val buffer = ByteArray(2048)
                val read = socket.getInputStream().read(buffer)
                if (read <= 0) null else String(buffer, 0, read, Charsets.ISO_8859_1)
            }
        }.getOrNull()
    }

    /** Which vendor a banner names, or null when it names none of them. */
    private fun vendorFromBanner(banner: String): TvVendor? {
        val haystack = banner.lowercase()
        return when {
            haystack.contains("roku") -> TvVendor.ROKU
            haystack.contains("samsung") || haystack.contains("tizen") -> TvVendor.SAMSUNG
            haystack.contains("webos") || haystack.contains("lg electronics") -> TvVendor.LG
            haystack.contains("vizio") || haystack.contains("smartcast") -> TvVendor.VIZIO
            haystack.contains("philips") || haystack.contains("jointspace") -> TvVendor.PHILIPS
            haystack.contains("androidtv") || haystack.contains("android tv") ||
                haystack.contains("google tv") -> TvVendor.ANDROID_TV
            else -> null
        }
    }

    /** A human name from a banner, when the banner carries one. */
    private fun nameFromBanner(banner: String): String? =
        tag(banner, "friendlyName")
            ?: tag(banner, "friendly-device-name")
            ?: tag(banner, "modelName")
            ?: tag(banner, "model-name")

    private fun String.firstLine(): String = lineSequence().firstOrNull()?.trim() ?: ""

    /** This device's /24, from the phone's own wifi address. */
    private fun localSubnetPrefix(): String? {
        return runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (address in iface.inetAddresses.toList()) {
                    val host = address.hostAddress ?: continue
                    if (address is java.net.Inet4Address && host.startsWith("192.168.")) {
                        return host.substringBeforeLast(".")
                    }
                    // A 10.x network is handled the same way, with the same /24 cut.
                    if (address is java.net.Inet4Address && host.startsWith("10.")) {
                        return host.substringBeforeLast(".")
                    }
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Fetch a local document, bypassing the proxy.
     *
     * THE BUG THIS FIXES LOST A REAL TELEVISION. Discovery opened a Roku's port with a raw socket,
     * which needs no proxy, then fetched its device-info over HTTP, which went through Tor and
     * died. The set was found and then discarded, so only sets that announced themselves over
     * SSDP ever appeared.
     */
    private fun fetch(url: String, post: Boolean = false): String {
        val connection = com.unuslumen.app.data.fabric.net.LocalRoute.open(url)
            ?: throw IllegalStateException("no connection could be built to $url")
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            if (post) {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.setFixedLengthStreamingMode(0)
                connection.outputStream.close()
            }
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }

    companion object {
        /** A short connect timeout, because a closed port refuses instantly. */
        private const val CONNECT_TIMEOUT_MILLIS = 140

        /** How long to wait for a banner before moving on. */
        private const val BANNER_TIMEOUT_MILLIS = 600

        /** How many ports to sweep at once, bounded because this phone has been OOM-killed before. */
        private const val DEEP_SCAN_CONCURRENCY = 128

        /** How many open ports on a host are asked to identify themselves before giving up. */
        private const val DEEP_SCAN_BANNER_LIMIT = 24

        /**
         * Ports beyond the vendor remote ports worth probing on every host.
         *
         * These are the ones a television or a media box commonly exposes something on, so the
         * first pass finds a set before the expensive full sweep has to run.
         */
        private val EXTRA_DEVICE_PORTS = listOf(
            80, 443, 1900, 5000, 7000, 8008, 8009, 8080, 8081, 8443, 9080, 9999, 55000, 49152, 49153,
        )

        /** The port a vendor's remote protocol answers on. */
        fun defaultPortFor(vendor: TvVendor): Int = when (vendor) {
            TvVendor.ROKU -> 8060
            TvVendor.SAMSUNG -> 8001
            TvVendor.LG -> 3000
            TvVendor.VIZIO -> 7345
            TvVendor.PHILIPS -> 1925
            TvVendor.ANDROID_TV -> 6466
            TvVendor.UNKNOWN -> 0
        }
    }
}
