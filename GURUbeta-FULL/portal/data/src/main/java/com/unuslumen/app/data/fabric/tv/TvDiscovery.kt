// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
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
 * is. Issue #187, rebuilt for speed on 2026-11-01.
 *
 * WHY DISCOVERY IS THE HARD PART AND THE PROTOCOLS ARE NOT. A universal remote app's
 * value is that a person never tells it what make their telly is. That means the
 * discovery step has to identify the set itself, and there is no single way to do it.
 *
 * THE DEFECT THIS REPLACES, AND IT WAS MINE. Steven said to probe every port. I took
 * that literally and swept all 65535 ports on every host that answered anything, which
 * on a real home network is minutes per device and hours in total. A tool that runs for
 * hours is not thorough, it is broken. His words: "i didnt mean scan 1m oprts i meant
 * scan every port that sonethgin would usually use".
 *
 * WHAT RUNS INSTEAD. Three probes run at the same moment and the answer is whatever
 * answers first:
 *
 *  1. SSDP M-SEARCH for `ssdp:all`, which is how Samsung, LG, Philips and Roku announce
 *     themselves, and whose reply document names the make.
 *  2. mDNS, which is how Android TV and Google TV devices announce themselves, and which
 *     SSDP never sees.
 *  3. A port sweep across the whole /24 of the roughly twenty ports a television or a
 *     streaming box actually opens. That is about six thousand connections rather than
 *     sixteen million, and it finishes in about a second rather than never.
 *
 * WHAT IT WILL NOT DO. It will not sweep all 65535 ports, because that is what made it
 * unusable. The exhaustive walk still exists, deliberately, but as its own tool that
 * takes one host somebody has chosen, which is [com.unuslumen.app.data.fabric.net.DeepPortScanner]
 * behind `netDeepScan`. Discovery answers "what is on my network" in seconds; the deep
 * scan answers "what exactly is this box" for one box at a time. Mixing the two is what
 * produced the tool Steven could not use.
 *
 * IT DOES NOT GUESS EITHER. A set that answers nothing is not reported as an unknown
 * television, because a device in the list that cannot be controlled reads as a broken
 * app. And a vendor whose adapter is missing is reported as found and not drivable, with
 * the make named, so the gap is visible instead of silent.
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
        1925 to TvVendor.PHILIPS,
        1926 to TvVendor.PHILIPS,
        9000 to TvVendor.PHILIPS,
        6466 to TvVendor.ANDROID_TV,
        6467 to TvVendor.ANDROID_TV,
    )

    /**
     * Discover every television this phone can reach.
     *
     * The three probes are started together and each records into the same map as soon as
     * it identifies a set, so the slowest probe decides the wall clock rather than the sum
     * of all three. On a normal home network that is about two seconds.
     */
    suspend fun discover(): DiscoveryReport = withContext(Dispatchers.IO) {
        val found = ConcurrentHashMap<String, DiscoveredTv>()
        val ranks = ConcurrentHashMap<String, Int>()
        val problems = java.util.Collections.synchronizedList(mutableListOf<String>())

        // One entry per host, and the more informative answer wins. A port guess is worth
        // less than a set that named itself, because a shared port can be two vendors and
        // a name from the set's own document cannot.
        fun record(candidate: DiscoveredTv, rank: Int) {
            val previous = ranks[candidate.host] ?: -1
            if (rank >= previous) {
                ranks[candidate.host] = rank
                found[candidate.host] = candidate
            }
        }

        val finished = withTimeoutOrNull(TOTAL_BUDGET_MILLIS) {
            coroutineScope {
                launch { sweepKnownPorts(problems).forEach { record(it.tv, it.rank) } }
                launch { ssdpDiscover().forEach { record(it, RANK_NAMED) } }
                launch { mdnsDiscover().forEach { record(it, RANK_NAMED) } }
            }
        }

        // A HARD CEILING ON THE WHOLE PASS.
        //
        // Every individual probe is already bounded, but a network can still produce a
        // pathological case: a router that drops packets instead of refusing them, or a UPnP
        // reply pointing at a host that accepts a connection and then says nothing. Steven's
        // complaint was that the tool ran forever and brought nothing forward, so the pass is
        // capped and whatever was identified by then is what gets returned. A partial answer
        // in seconds beats a complete one that never arrives.
        if (finished == null) {
            problems.add(
                "Discovery stopped after ${TOTAL_BUDGET_MILLIS / 1000} seconds with " +
                    "${found.size} television(s) identified. A device that stays silent this " +
                    "long is off, on another network, or does not answer these protocols.",
            )
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

    /** A finding and how much it is worth against another answer for the same host. */
    private data class Ranked(val tv: DiscoveredTv, val rank: Int)

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
        val searchTargets = listOf(
            "ssdp:all",
            "upnp:rootdevice",
            "urn:schemas-upnp-org:device:MediaRenderer:1",
            "urn:dial-multiscreen-org:service:dial:1",
            "urn:schemas-upnp-org:device:Basic:1",
        )

        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = SSDP_WINDOW_MILLIS
                val group = InetAddress.getByName("239.255.255.250")
                for (target in searchTargets) {
                    val message = buildString {
                        append("M-SEARCH * HTTP/1.1\r\n")
                        append("HOST: 239.255.255.250:1900\r\n")
                        append("MAN: \"ssdp:discover\"\r\n")
                        // MX 1 rather than 2, because MX is the window a device is allowed to
                        // wait before answering and this probe now has a budget. A device
                        // answers in milliseconds in practice; MX only sets the ceiling.
                        append("MX: 1\r\n")
                        append("ST: $target\r\n")
                        append("\r\n")
                    }
                    val payload = message.toByteArray(Charsets.UTF_8)
                    runCatching { socket.send(DatagramPacket(payload, payload.size, group, 1900)) }
                }

                val deadline = System.currentTimeMillis() + SSDP_WINDOW_MILLIS.toLong()
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val response = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(response) }.onSuccess {
                        val body = String(response.data, 0, response.length, Charsets.UTF_8)
                        val host = response.address?.hostAddress ?: return@onSuccess
                        // A set answers once per search target, so the same reply arrives several
                        // times. Deduplicating by host keeps the classifier honest.
                        if (replies.none { it.first == host }) replies.add(host to body)
                    }
                }
            }
        }

        // THE DESCRIPTIONS ARE FETCHED TOGETHER, NOT ONE AFTER ANOTHER.
        //
        // Each reply carries a LOCATION pointing at the device's description document, and
        // reading them in sequence meant one slow or dead host held up every device behind it
        // in the list. A router that accepts the connection and never answers cost the whole
        // window, serially, once per device. Fetching them at the same moment makes the pass
        // bounded by the slowest single host rather than by their sum.
        coroutineScope {
            replies.map { (host, body) ->
                async {
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
            }.awaitAll().filterNotNull()
        }
    }

    // ------------------------------------------------------------------
    // mDNS
    // ------------------------------------------------------------------

    /**
     * Ask Bonjour which screens are on the network.
     *
     * WHY THIS EXISTS AT ALL. An Android TV or Google TV device announces itself over
     * Bonjour and frequently answers no UPnP search at all, so SSDP alone never sees it.
     * His first device test found "mDNS found nothing", which was not the network being
     * quiet: a standard mDNS answer is multicast to 224.0.0.251:5353, and a phone app
     * holding only an ephemeral socket never receives it.
     *
     * THE FIX IS ONE BIT. RFC 6762 section 5.4 lets a querier ask for a unicast reply by
     * setting the top bit of the question's class field, so the responder answers straight
     * back to the socket that asked. That is what [TvScanPlan.dnsPtrQuery] writes.
     */
    private suspend fun mdnsDiscover(): List<DiscoveredTv> = coroutineScope {
        val hits = ConcurrentHashMap<String, MutableSet<String>>()

        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = MDNS_WINDOW_MILLIS
                val group = InetAddress.getByName("224.0.0.251")
                for (service in TvScanPlan.mdnsServices) {
                    val query = com.unuslumen.app.data.fabric.net.MdnsQuery.ptrQuery(service)
                    runCatching { socket.send(DatagramPacket(query, query.size, group, 5353)) }
                }

                val deadline = System.currentTimeMillis() + MDNS_WINDOW_MILLIS.toLong()
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(packet) }.onSuccess {
                        val address = packet.address?.hostAddress ?: return@onSuccess
                        val text = String(packet.data, 0, packet.length, Charsets.ISO_8859_1)
                        for (service in TvScanPlan.mdnsServices) {
                            if (text.contains(service, ignoreCase = true)) {
                                hits.getOrPut(address) { mutableSetOf() }.add(service)
                            }
                        }
                    }
                }
            }
        }

        hits.mapNotNull { (host, services) ->
            val vendor = TvScanPlan.vendorFromMdns(services) ?: return@mapNotNull null
            DiscoveredTv(
                vendor = vendor,
                name = "${vendor.name.lowercase().replaceFirstChar { it.uppercase() }} on $host",
                host = host,
                port = TvScanPlan.defaultPortFor(vendor),
                evidence = "announced ${services.joinToString(", ")} over Bonjour",
                needsPairing = vendor != TvVendor.ROKU,
            )
        }
    }

    // ------------------------------------------------------------------
    // Port sweep
    // ------------------------------------------------------------------

    /**
     * Probe the ports a television actually opens, across the whole /24.
     *
     * WHY THIS AND NOT EVERY PORT. 254 addresses times the twenty ports in
     * [TvScanPlan.probePorts] is about five thousand connections, which finishes in about
     * a second. Adding the other 65515 ports is what turned a fast answer into one that
     * never arrived, and a set on an obscure port is still found by `netDeepScan` against
     * that one host when somebody asks about it specifically.
     *
     * THE BUG THIS ALSO FIXES. The old sweep returned on the FIRST port that answered, so a
     * host whose first open port was not its remote port was never tested as a television
     * at all. His downstairs Roku was found by nothing because of it. Every port on every
     * host is now checked and the results are pooled per host.
     */
    private suspend fun sweepKnownPorts(problems: MutableList<String>): List<Ranked> {
        val prefix = localSubnetPrefix()
        if (prefix == null) {
            problems.add(
                "This phone is not on a wifi network with an IPv4 address, so there is no subnet " +
                    "to sweep. A device on mobile data cannot see the local network.",
            )
            return emptyList()
        }

        val pairs = (1..254).flatMap { last ->
            val host = "$prefix.$last"
            TvScanPlan.probePorts.map { host to it }
        }

        // Chunked rather than one coroutine per pair, because a socket apiece for five
        // thousand probes at once is how a phone gets OOM-killed. Each chunk is bounded and
        // entirely concurrent, so the wall clock is the chunk count times the timeout.
        val open = mutableListOf<Pair<String, Int>>()
        for (chunk in pairs.chunked(SWEEP_CHUNK)) {
            open += coroutineScope {
                chunk.map { (host, port) ->
                    async { if (isOpen(host, port)) host to port else null }
                }.awaitAll().filterNotNull()
            }
        }

        if (open.isEmpty()) {
            problems.add(
                "Nothing answered on $prefix.0/24, across ${TvScanPlan.probePorts.size} ports on " +
                    "every address. A device answers nothing when it is switched off, on a " +
                    "different network, or behind its own firewall.",
            )
            return emptyList()
        }

        return open.groupBy({ it.first }, { it.second })
            .mapNotNull { (host, ports) -> identifyHost(host, ports) }
    }

    /**
     * Work out what one host is from the ports it has open.
     *
     * A vendor remote port is decisive because it exists for one make and nothing else. A
     * web port is not: port 80 is on a router, a printer and a television alike, so a host
     * with only a web port open is asked to name itself before it is called a television.
     */
    private fun identifyHost(host: String, ports: List<Int>): Ranked? {
        // Most specific first, so a host exposing both 8060 and 80 is named by 8060.
        val ordered = ports.sortedBy { port -> portHints[port]?.let { confidenceOf(it) } ?: 99 }

        for (port in ordered) {
            val hint = portHints[port] ?: continue
            val device = identify(host, port, hint) ?: continue
            return Ranked(device, if (hint == TvVendor.ROKU) RANK_NAMED else RANK_PORT)
        }

        for (port in ports.filter { it in TvScanPlan.webPorts }) {
            val banner = readBanner(host, port) ?: continue
            val vendor = TvScanPlan.vendorFromBanner(banner) ?: continue
            return Ranked(
                DiscoveredTv(
                    vendor = vendor,
                    name = nameFromBanner(banner)
                        ?: "${vendor.name.lowercase().replaceFirstChar { it.uppercase() }} on $host",
                    host = host,
                    port = port,
                    evidence = "port $port answered with ${banner.lineSequence().first().take(80)}",
                    needsPairing = vendor != TvVendor.ROKU,
                ),
                RANK_NAMED,
            )
        }

        return null
    }

    /**
     * Confirm what is on an open vendor port, and name it.
     *
     * The Roku check is decisive because its device-info document is unambiguous. For the
     * other vendors a port that is open is strong evidence and not proof, so the evidence
     * line says exactly that rather than claiming a confident name.
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

    /**
     * How much a vendor port on its own is worth, most decisive first.
     *
     * The order follows how specific each port is to one make. 8060 is Roku and nothing
     * else. 8001 is Samsung in practice but is also a generic web port on some kit. 9000 is
     * Philips on new firmware and something else on other hardware. Sorting by this means a
     * host exposing two of them is named by the more specific one.
     */
    private fun confidenceOf(vendor: TvVendor): Int = when (vendor) {
        TvVendor.ROKU -> 0
        TvVendor.SAMSUNG -> 1
        TvVendor.LG -> 2
        TvVendor.ANDROID_TV -> 3
        TvVendor.VIZIO -> 4
        TvVendor.PHILIPS -> 5
        TvVendor.UNKNOWN -> 9
    }

    /** Whether a TCP connection is accepted, which a closed port answers instantly. */
    private fun isOpen(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MILLIS)
            true
        }
    }.getOrDefault(false)

    /** Ask a web port to say what it is, over HTTP, and return whatever came back. */
    private fun readBanner(host: String, port: Int): String? = runCatching {
        val scheme = if (port == 443 || port == 8443) "https" else "http"
        val connection = com.unuslumen.app.data.fabric.net.LocalRoute.open("$scheme://$host:$port/")
            ?: return null
        try {
            connection.connectTimeout = BANNER_TIMEOUT_MILLIS
            connection.readTimeout = BANNER_TIMEOUT_MILLIS
            connection.inputStream.bufferedReader().use { it.readText().take(4000) }
        } finally {
            connection.disconnect()
        }
    }.getOrNull()

    private fun nameFromBanner(banner: String): String? =
        tag(banner, "friendlyName")
            ?: tag(banner, "friendly-device-name")
            ?: tag(banner, "modelName")
            ?: tag(banner, "model-name")

    /** This device's /24, from whichever wifi interface is up. */
    private fun localSubnetPrefix(): String? {
        return runCatching {
            val interfaces = java.net.NetworkInterface.getNetworkInterfaces().toList()
            for (iface in interfaces) {
                if (!iface.isUp || iface.isLoopback) continue
                for (address in iface.inetAddresses.toList()) {
                    if (address !is java.net.Inet4Address || address.isLoopbackAddress) continue
                    val host = address.hostAddress ?: continue
                    if (isPrivateIpv4(host)) return host.substringBeforeLast(".")
                }
            }
            null
        }.getOrNull()
    }

    /**
     * Whether an address is one of the private ranges a home network uses.
     *
     * 172 is checked properly rather than by prefix, because 172.1.x and 172.217.x are
     * public addresses and sweeping them would be a scan of somebody else's network.
     */
    private fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            else -> false
        }
    }

    /**
     * Fetch a local document, bypassing the proxy.
     *
     * THE BUG THIS FIXES LOST A REAL TELEVISION. Discovery opened a Roku's port with a raw
     * socket, which needs no proxy, then fetched its device-info over HTTP, which went
     * through Tor and died. The set was found and then silently discarded, so only sets
     * that announced themselves over SSDP ever appeared.
     */
    private fun fetch(url: String): String {
        val connection = com.unuslumen.app.data.fabric.net.LocalRoute.open(url)
            ?: throw IllegalStateException("no connection could be built to $url")
        return try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
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
        private const val BANNER_TIMEOUT_MILLIS = 500

        /** How many probes are in flight at once, bounded so the phone is never OOM-killed. */
        private const val SWEEP_CHUNK = 512

        /** How long SSDP listens, which is the ceiling MX advertises plus a little. */
        private const val SSDP_WINDOW_MILLIS = 1800

        /** How long Bonjour listens, because an mDNS answer arrives in milliseconds. */
        private const val MDNS_WINDOW_MILLIS = 1500

        /**
         * The ceiling on the whole discovery pass, in milliseconds.
         *
         * SIX SECONDS, because a person asking "what tellys have I got" will wait that long
         * and will not wait a minute. The individual windows are deliberately shorter than
         * this so the cap is a backstop for a pathological network rather than the normal
         * path: a healthy pass returns in about two seconds and never sees it.
         */
        private const val TOTAL_BUDGET_MILLIS = 6000L

        /** A set that named itself, or was confirmed by a document it served. */
        private const val RANK_NAMED = 2

        /** A set named only by a vendor port being open. */
        private const val RANK_PORT = 1
    }
}

/**
 * The scan plan: which ports are worth asking, and who answers to what.
 *
 * Pure, with no sockets and no Android, so a JVM test pins the rules the app actually
 * runs rather than a second copy written inside the test.
 */
object TvScanPlan {

    /**
     * The ports a television or a streaming box opens, and roughly nothing else.
     *
     * EVERY ENTRY IS EITHER A VENDOR'S OWN REMOTE PORT OR A PORT THAT NAMES A SCREEN. The
     * list is deliberately short: this runs against all 254 addresses, so each extra port
     * is another 254 connections, and the point of this pass is a fast answer rather than
     * an exhaustive one.
     */
    val probePorts: List<Int> = listOf(
        8060,        // Roku ECP
        8001, 8002,  // Samsung Tizen remote, plain and TLS
        3000, 3001,  // LG webOS
        7345,        // Vizio SmartCast
        1925, 1926,  // Philips JointSpace, plain and TLS
        9000,        // Philips JointSpace on newer firmware
        6466, 6467,  // Android TV Remote
        8008, 8009,  // Google Cast
        7000,        // AirPlay, which is how an Apple TV names itself
        80, 443,     // whatever the box calls itself over HTTP
    )

    /** The ports worth asking to name themselves in writing. */
    val webPorts: List<Int> = listOf(80, 443)

    /** The Bonjour services that identify a screen, shared with the network scanner. */
    val mdnsServices: List<String>
        get() = com.unuslumen.app.data.fabric.net.MdnsQuery.screenServices

    /** How many connections a sweep of [hostCount] addresses makes, so the cost is visible. */
    fun probeCount(hostCount: Int): Int = hostCount * probePorts.size

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

    /**
     * Which vendor a Bonjour service set names, or null when it names none this build can
     * drive. A Chromecast and an Apple TV announce themselves and both have no adapter, so
     * they return null here rather than appearing as a set that refuses.
     */
    fun vendorFromMdns(services: Set<String>): TvVendor? = when {
        services.any { it.startsWith("_androidtvremote2") } -> TvVendor.ANDROID_TV
        else -> null
    }

    /** Which vendor a banner names, or null when it names none of them. */
    fun vendorFromBanner(banner: String): TvVendor? {
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

    /**
     * Build a DNS-SD PTR query, delegated to the one shared builder.
     *
     * The unicast-response bit that makes Bonjour answers reachable at all is explained in
     * [com.unuslumen.app.data.fabric.net.MdnsQuery], and this delegates rather than keeping
     * a second copy of the byte layout that could drift from it.
     */
    fun dnsPtrQuery(name: String): ByteArray =
        com.unuslumen.app.data.fabric.net.MdnsQuery.ptrQuery(name)
}
