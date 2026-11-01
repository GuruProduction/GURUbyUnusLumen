// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap

/**
 * Finding the televisions on the local network, and working out which vendor each one
 * is.
 *
 * THE TWO FAILURES THIS REPLACES, both mine.
 *
 * The first was a sweep of a hand-picked list of thirteen ports. Steven's network has a
 * Virgin Media box answering only on 9080 and a streaming box answering on 5555 and
 * 8009, and neither port was in the list, so discovery probed nothing that could answer
 * and reported "0 televisions… they are off or on another network" while `netHosts`, on
 * the same subnet at the same moment, listed both. His words when he saw it: "its every
 * single port it misses" and "i didnt mean scan 1m oprts i meant scan every port that
 * sonethign would usually use".
 *
 * The second was a six-second ceiling on the whole pass. A cap is not thoroughness and it
 * cannot find a device it did not reach. His verdict: "it shouldnt rin 6 seconds it should
 * fund everythign on the network".
 *
 * WHAT RUNS NOW, and why it can do this without being as slow as his first complaint.
 *
 *  1. HOST DISCOVERY. ARP where it is readable, then a probe of the ports a live host
 *     almost always has, across the /24. This finds which addresses exist at all, which
 *     is the step that makes everything after it tractable: a house has a handful of
 *     live hosts and 254 addresses, and probing all 65535 ports against the 240 that are
 *     switched off is what makes a full scan impossible.
 *
 *  2. A FULL 1..65535 PORT SWEEP, but only against the hosts step 1 found alive. That is
 *     the literal "every single port" he asked for, applied where it can actually return
 *     something. Every open port is reported, not just the ones on a list.
 *
 *  3. SSDP and Bonjour run alongside, because a set that names itself is identified
 *     without a port guess at all.
 *
 * NO CEILING on the whole pass. Each individual socket call is still bounded, because an
 * unbounded socket is how the phone gets OOM-killed, but there is no wall-clock cut that
 * can hide a device. The cost is controlled by concurrency and by only scanning hosts
 * that answered, not by giving up early.
 *
 * IT REPORTS AS IT GOES. [discover] takes a progress callback and calls it for every
 * device it identifies and at each stage, so a long pass shows its findings while it
 * runs rather than making a person wait for a single answer at the end.
 */
class TvDiscovery(
    private val connectTimeoutMillis: Int = 220,
    private val bannerTimeoutMillis: Int = 500,
    /** How many sockets are in flight at once, so a full sweep cannot OOM the phone. */
    private val concurrency: Int = 192,
) {

    /** Every vendor's remote port, so a set can be named from the port alone. */
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
        8009 to TvVendor.ANDROID_TV,
        9080 to TvVendor.UNKNOWN,
    )

    /**
     * Discover every television this phone can reach, reporting as it goes.
     *
     * [onProgress] is called with one readable line per finding and per stage. It runs on
     * the same dispatcher as the scan, so a caller that wants to show it should post to
     * whatever thread its UI needs.
     */
    suspend fun discover(
        onProgress: (String) -> Unit = {},
    ): DiscoveryReport = withContext(Dispatchers.IO) {
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
                val isNew = found.put(candidate.host, candidate) == null
                if (isNew) {
                    onProgress(
                        "found ${candidate.name} at ${candidate.host} (${candidate.vendor.name.lowercase()})",
                    )
                }
            }
        }

        val prefix = localSubnetPrefix()
        if (prefix == null) {
            problems.add(
                "This phone is not on a wifi network with an IPv4 address, so there is no subnet " +
                    "to sweep. A device on mobile data cannot see the local network.",
            )
            return@withContext DiscoveryReport(emptyList(), problems)
        }

        // The named and announced probes run first and at the same time as host
        // discovery, because a set that answers SSDP or Bonjour names itself and needs no
        // port sweep at all.
        coroutineScope {
            launch {
                runCatching { ssdpDiscover() }.getOrDefault(emptyList())
                    .forEach { record(it, RANK_NAMED) }
            }
            launch {
                runCatching { mdnsDiscover() }.getOrDefault(emptyList())
                    .forEach { record(it, RANK_NAMED) }
            }
        }

        onProgress("looking for live hosts on $prefix.0/24")

        val liveHosts = findLiveHosts(prefix)
        if (liveHosts.isEmpty()) {
            problems.add(
                "Nothing answered on $prefix.0/24 at any of the ports a device normally has, so " +
                    "no address on this subnet could be swept further. A device that answers " +
                    "nothing is off, on another network, or behind its own firewall.",
            )
            return@withContext DiscoveryReport(found.values.sortedBy { it.name }, problems)
        }

        onProgress(
            "${liveHosts.size} live host(s) on the network; scanning all ${FULL_PORT_RANGE} ports " +
                "on each",
        )

        // THE FULL SWEEP, against live hosts only.
        scanEveryPort(liveHosts, portHints, found, ranks, problems, onProgress) { candidate, rank ->
            record(candidate, rank)
        }

        if (found.isEmpty()) {
            problems.add(
                "Every port was probed on all ${liveHosts.size} live host(s) and none of them is a " +
                    "television this build can identify. A set that answers no identifying port, " +
                    "or that needs its network remote feature switched on in its own settings, " +
                    "looks exactly like this.",
            )
        }

        DiscoveryReport(found.values.sortedBy { it.name }, problems)
    }

    data class DiscoveryReport(val devices: List<DiscoveredTv>, val problems: List<String>)

    private data class Ranked(val tv: DiscoveredTv, val rank: Int)

    // ------------------------------------------------------------------
    // Host discovery — the step that makes a full sweep possible
    // ------------------------------------------------------------------

    /**
     * The addresses on this subnet that exist.
     *
     * ARP first because it is instant and free, then a probe of the ports a live host
     * almost always has. A host that appears in neither is not worth 65535 connections.
     *
     * The ARP table is often unreadable at app UID, which is why the port probe exists at
     * all: it does not need permission, only a host that answers.
     */
    private suspend fun findLiveHosts(prefix: String): List<String> {
        val live = ConcurrentHashMap.newKeySet<String>()

        for (entry in readArpTable()) {
            if (entry.startsWith("$prefix.")) live.add(entry)
        }

        val gate = Semaphore(concurrency)
        val addresses = (1..254).map { "$prefix.$it" }
        val discovered = coroutineScope {
            addresses.map { address ->
                async {
                    gate.withPermit {
                        if (isAlive(address)) address else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        live.addAll(discovered)
        return live.toList().sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 999 }
    }

    /** Whether a host answers on any port a device practically always has open. */
    private fun isAlive(address: String): Boolean =
        ALIVENESS_PORTS.any { isOpen(address, it) }

    private fun readArpTable(): List<String> = runCatching {
        val file = java.io.File("/proc/net/arp")
        if (!file.canRead()) return emptyList()
        file.readLines().drop(1).mapNotNull { line ->
            val parts = line.trim().split(Regex("\\s+"))
            if (parts.size < 4) return@mapNotNull null
            val mac = parts[3]
            if (mac == "00:00:00:00:00:00" || mac.isBlank()) return@mapNotNull null
            parts[0]
        }
    }.getOrDefault(emptyList())

    // ------------------------------------------------------------------
    // The full port sweep
    // ------------------------------------------------------------------

    /**
     * Every port on every live host, with a banner read from each that opens.
     *
     * This is the literal "every single port" pass. It is bounded by concurrency rather
     * than by a port list or a wall clock, which is the difference between missing a set
     * and finding one.
     */
    private suspend fun scanEveryPort(
        hosts: List<String>,
        hints: Map<Int, TvVendor>,
        found: MutableMap<String, DiscoveredTv>,
        ranks: MutableMap<String, Int>,
        problems: MutableList<String>,
        onProgress: (String) -> Unit,
        record: (DiscoveredTv, Int) -> Unit,
    ) {
        val gate = Semaphore(concurrency)

        for ((index, host) in hosts.withIndex()) {
            onProgress("scanning ${index + 1} of ${hosts.size}: $host")

            // Every port on this host, in batches so the deferred list stays bounded
            // without ever shortening the RANGE. Batching here is about memory, never
            // about coverage: all 65535 are always attempted.
            val openPorts = mutableListOf<Int>()
            for (chunk in (1..FULL_PORT_RANGE).chunked(PORT_CHUNK)) {
                openPorts += coroutineScope {
                    chunk.map { port ->
                        async {
                            gate.withPermit {
                                if (isOpen(host, port)) port else null
                            }
                        }
                    }.awaitAll().filterNotNull()
                }
            }

            if (openPorts.isEmpty()) {
                onProgress("$host answered no port at all")
                continue
            }

            onProgress(
                "$host has ${openPorts.size} open port(s): " +
                    openPorts.take(24).joinToString(", ") +
                    if (openPorts.size > 24) " and ${openPorts.size - 24} more" else "",
            )

            identifyHost(host, openPorts, hints)?.let { ranked ->
                record(ranked.tv, ranked.rank)
            }
        }
    }

    /**
     * Work out what one host is, now that every port it has is known.
     *
     * A vendor remote port is decisive because it exists for one make. A web port is not:
     * 80 is on a router, a printer and a television alike, so a host with only a web port
     * is asked to name itself before it is called a television. A host whose only evidence
     * is an ambiguous port combination says so rather than presenting a guess with the
     * same confidence as a named self-announcement.
     */
    private fun identifyHost(host: String, ports: List<Int>, hints: Map<Int, TvVendor>): Ranked? {
        // Most specific first, so a host exposing both 8060 and 80 is named by 8060.
        val ordered = ports.sortedBy { port -> hints[port]?.let { confidenceOf(it) } ?: 99 }

        for (port in ordered) {
            val hint = hints[port] ?: continue
            if (hint == TvVendor.UNKNOWN) continue
            identify(host, port, hint)?.let { device ->
                return Ranked(device, if (hint == TvVendor.ROKU) RANK_NAMED else RANK_PORT)
            }
        }

        // A web port may still name the set in a banner or a device description.
        for (port in ports) {
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

        // A UPnP description on any open port can still name a set, which is how a Virgin
        // Media box that advertises EOSSTB is identified.
        for (port in ports) {
            val description = fetchDeviceDescription(host, port) ?: continue
            val vendor = TvScanPlan.vendorFromDescription(description) ?: continue
            return Ranked(
                DiscoveredTv(
                    vendor = vendor,
                    name = nameFromDescription(description)
                        ?: "${vendor.name.lowercase().replaceFirstChar { it.uppercase() }} on $host",
                    host = host,
                    port = port,
                    evidence = "port $port served a UPnP description naming " +
                        (TvScanPlan.manufacturerOf(description) ?: vendor.name),
                    needsPairing = vendor != TvVendor.ROKU && vendor != TvVendor.ANDROID_TV,
                ),
                RANK_NAMED,
            )
        }

        // Ports alone, and only where the combination is one this build can name.
        val inferred = TvScanPlan.vendorFromPorts(ports) ?: return null
        return Ranked(
            DiscoveredTv(
                vendor = inferred,
                name = "${inferred.name.lowercase().replaceFirstChar { it.uppercase() }} on $host",
                host = host,
                port = TvScanPlan.defaultPortFor(inferred),
                // Says plainly that this is a port inference and not an observation, so a
                // person reading it knows the difference.
                evidence = "no identifying reply, so this is INFERRED from open ports " +
                    ports.take(8).joinToString(", ") + " rather than from anything the device said",
                needsPairing = inferred != TvVendor.ROKU && inferred != TvVendor.ANDROID_TV,
            ),
            RANK_PORT,
        )
    }

    private fun identify(host: String, port: Int, hint: TvVendor): DiscoveredTv? = when (hint) {
        TvVendor.ROKU -> {
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
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            true
        }
    }.getOrDefault(false)

    /** Ask a port to say what it is, returning whatever came back. */
    private fun readBanner(host: String, port: Int): String? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), connectTimeoutMillis)
            socket.soTimeout = bannerTimeoutMillis
            val request = "GET / HTTP/1.0\r\nHost: $host\r\nUser-Agent: GURU\r\n\r\n"
            runCatching {
                socket.getOutputStream().apply {
                    write(request.toByteArray(Charsets.US_ASCII))
                    flush()
                }
            }
            val buffer = ByteArray(4096)
            val read = socket.getInputStream().read(buffer)
            if (read <= 0) null else String(buffer, 0, read, Charsets.ISO_8859_1)
        }
    }.getOrNull()

    /** Read a UPnP description document from a port that serves one. */
    private fun fetchDeviceDescription(host: String, port: Int): String? = runCatching {
        val body = fetch("http://$host:$port/")
        if (body.contains("<root", ignoreCase = true) || body.contains("deviceType", ignoreCase = true)) {
            body
        } else {
            null
        }
    }.getOrNull()

    private fun nameFromBanner(banner: String): String? =
        tag(banner, "friendlyName")
            ?: tag(banner, "friendly-device-name")
            ?: tag(banner, "modelName")
            ?: tag(banner, "model-name")

    private fun nameFromDescription(description: String): String? =
        tag(description, "friendlyName") ?: tag(description, "modelName")

    /** This device's /24, from whichever interface is up. */
    fun localSubnetPrefix(): String? = runCatching {
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
     * through Tor and died. The set was found and then silently discarded.
     */
    private fun fetch(url: String): String {
        val connection = com.unuslumen.app.data.fabric.net.LocalRoute.open(url)
            ?: throw IllegalStateException("no connection could be built to $url")
        return try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = bannerTimeoutMillis
            connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE)
            .find(xml)?.groupValues?.getOrNull(1)?.trim()?.ifBlank { null }

    // ------------------------------------------------------------------
    // SSDP and Bonjour
    // ------------------------------------------------------------------

    private suspend fun ssdpDiscover(): List<DiscoveredTv> = coroutineScope {
        val replies = java.util.Collections.synchronizedList(mutableListOf<Pair<String, String>>())

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
                        if (replies.none { it.first == host }) replies.add(host to body)
                    }
                }
            }
        }

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

    companion object {
        /** How long SSDP listens, which is the ceiling MX advertises plus a little. */
        private const val SSDP_WINDOW_MILLIS = 1800

        /** How long Bonjour listens, because an mDNS answer arrives in milliseconds. */
        private const val MDNS_WINDOW_MILLIS = 1500

        /** The range a full sweep covers. All of it, which is the point. */
        private const val FULL_PORT_RANGE = 65535

        /** How many port probes are assembled per batch before they are awaited. */
        private const val PORT_CHUNK = 2048

        /**
         * The ports probed to decide whether a host exists at all.
         *
         * These are not a substitute for the full sweep; they are the cheap filter that
         * makes it possible. A host answering none of them gets no full sweep, because
         * 65535 connections against a switched-off address returns nothing and costs the
         * whole timeout.
         */
        private val ALIVENESS_PORTS = listOf(
            80, 443, 22, 23, 53, 445, 554, 631, 1900, 5000, 5555, 8009, 8060, 8080, 9080, 49152,
        )

        /** A set that named itself, or was confirmed by a document it served. */
        private const val RANK_NAMED = 2

        /** A set named only by inference from its open ports. */
        private const val RANK_PORT = 1
    }
}
