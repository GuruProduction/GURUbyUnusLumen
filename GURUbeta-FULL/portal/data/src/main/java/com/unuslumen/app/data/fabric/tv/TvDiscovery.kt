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
 * Finding the televisions on the local network, and working out which vendor each one is.
 *
 * WHAT THIS DOES, and why it finishes in seconds.
 *
 *  1. HOST DISCOVERY. Every address on the /24 is asked whether it serves any port a consumer
 *     device actually runs. A host that answers one is a device worth identifying; a host that
 *     answers none is switched off or absent.
 *
 *  2. IDENTIFICATION. Each device is asked what it is: its UPnP description first, because a
 *     device that names itself is authoritative, then its banner, then, only where no reply names
 *     a make, the exclusive remote port it is listening on.
 *
 *  3. SSDP AND BONJOUR run alongside, because a set that announces itself needs no port probe at all.
 *
 * THE 65,535-PORT SWEEP WAS THE BUG, AND IT WAS MY MISREADING OF WHAT HE ASKED FOR.
 *
 * An earlier version swept every port on every host, having read "it misses every single port" as
 * a demand for all 65,535. He had already said what he meant: "i didnt mean scan 1m oprts i meant
 * scan every port that sonethign would usually use". A full sweep is sixteen and a half million
 * connections on a subnet whose gateway answers for every address, which is what an emulator's
 * user-mode network and some home routers do. That is not thoroughness, it is a call that never
 * returns: the run it was holding went INTERRUPTED, a tool stayed marked RUNNING, and a message the
 * person had typed sat queued with nothing to deliver it.
 *
 * So [DEVICE_PORTS] is the list, it is deliberately generous — every remote, cast, display,
 * discovery, media and admin port a television, speaker, console, set-top box, printer or NAS is
 * known to use — and it is a list rather than a range because that is what was asked for and
 * because a list finishes.
 *
 * IT REPORTS AS IT GOES. [discover] takes a progress callback and calls it for every device it
 * identifies and at each stage, so a pass shows its findings while it runs.
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
        // 8009 IS DELIBERATELY ABSENT. Issue #243: it is DIAL, the App-Launch endpoint, and
        // Fire TV, Chromecast, Android TV and Virgin boxes all open it. Mapping it to any one
        // make is what reported a Fire TV as an Android TV. A set that speaks DIAL is asked
        // for its description document instead, which names the manufacturer.
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
                "Nothing answered on $prefix.0/24 at any device port, so there is nothing to " +
                    "identify. A device that answers nothing is off, on another network, or " +
                    "behind its own firewall.",
            )
            return@withContext DiscoveryReport(found.values.sortedBy { it.name }, problems)
        }

        onProgress(
            "${liveHosts.size} device(s) on the network; checking ${DEVICE_PORTS.size} ports on each",
        )

        // THE FULL SWEEP, against live hosts only.
        scanEveryPort(liveHosts, portHints, found, ranks, problems, onProgress) { candidate, rank ->
            record(candidate, rank)
        }
        if (found.isEmpty()) {
            problems.add(
                "Every device port was probed on all ${liveHosts.size} device(s) and none of them " +
                    "is a television this build can identify. A set that answers no identifying " +
                    "port, or that needs its network remote feature switched on in its own " +
                    "settings, looks exactly like this.",
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
     * The addresses on this subnet that serve a port a consumer device actually runs.
     *
     * ARP gives the ones the phone has already spoken to, which is instant and free. Everything
     * else is found by asking each address whether it serves any port in [DEVICE_PORTS]: a host
     * that answers one is a device, and a host that answers none is switched off or absent.
     *
     * NO GENERIC LIVENESS PROBE. This used to decide an address was alive if it answered any of a
     * hand-picked list, and on a subnet whose gateway answers for every address that made all 254
     * look alive. The port list is the question now, so an address is only carried forward if
     * something on it answered that a device would.
     */
    private suspend fun findLiveHosts(prefix: String): List<String> {
        val known = ConcurrentHashMap.newKeySet<String>()

        for (entry in readArpTable()) {
            if (entry.startsWith("$prefix.")) known.add(entry)
        }

        val gate = Semaphore(concurrency)
        val addresses = (1..254).map { "$prefix.$it" }
        val answering = coroutineScope {
            addresses.map { address ->
                async {
                    gate.withPermit {
                        if (isAlive(address)) address else null
                    }
                }
            }.awaitAll().filterNotNull()
        }
        known.addAll(answering)
        return known.toList().sortedBy { it.substringAfterLast('.').toIntOrNull() ?: 999 }
    }

    /** Whether a host serves any port a consumer device is known to run. */
    private suspend fun isAlive(address: String): Boolean = coroutineScope {
        /*
         * ALL OF THE LIST AT ONCE, AND AN EARLY EXIT. Probing sixteen ports in sequence against a
         * host that is switched off costs sixteen timeouts; asking them together costs one, and
         * the first yes cancels the rest. That difference is the whole reason a /24 pass finishes
         * in seconds rather than minutes.
         */
        val probes = DEVICE_PORTS.map { port ->
            async {
                if (isOpen(address, port)) port else null
            }
        }
        var found: Int? = null
        for (probe in probes) {
            val hit = probe.await()
            if (hit != null) {
                found = hit
                probes.forEach { it.cancel() }
                break
            }
        }
        found != null
    }

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
    // Identification
    // ------------------------------------------------------------------

    /**
     * Ask every device port on every device host, and identify what answers.
     *
     * NO CLOCK AND NO FULL RANGE. The work is bounded by [DEVICE_PORTS] and by how many addresses
     * answered, which is a few thousand connections rather than sixteen million.
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

            val openPorts = coroutineScope {
                DEVICE_PORTS.map { port ->
                    async {
                        gate.withPermit {
                            if (isOpen(host, port)) port else null
                        }
                    }
                }.awaitAll().filterNotNull()
            }

            if (openPorts.isEmpty()) {
                onProgress("$host answered no device port")
                continue
            }

            onProgress(
                "$host has ${openPorts.size} device port(s) open: " +
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
        // WHAT THE DEVICE SAYS ABOUT ITSELF BEATS ANY GUESS ABOUT IT. Issue #243.
        //
        // THIS ORDER IS THE WHOLE FIX. The port hints used to run FIRST, so a host with 8009
        // open was named ANDROID_TV before anything read the description document that said
        // `manufacturer: Amazon`. The set had already answered the question and was not being
        // asked. A declared manufacturer or model is authoritative, a port is not, so the
        // document is read first and the ports are only consulted when it names nothing.
        //
        // The cost is unchanged: this is the same filtered list of ports the banner pass
        // already used, not a new sweep.
        for (port in ports.filter { it in IDENTIFYING_WEB_PORTS }) {
            val description = fetchDeviceDescription(host, port) ?: continue
            val vendor = TvScanPlan.vendorFromDescription(description) ?: continue
            // The port a command goes to is the vendor's remote port, not necessarily the
            // one the description was served from: a Fire TV serves it on 60000 and accepts
            // DIAL on 8009.
            val controlPort = if (vendor == TvVendor.AMAZON_FIRE_TV || vendor == TvVendor.ANDROID_TV) {
                TvScanPlan.defaultPortFor(vendor).takeIf { it in ports } ?: port
            } else {
                port
            }
            return Ranked(
                DiscoveredTv(
                    vendor = vendor,
                    name = nameFromDescription(description)
                        ?: "${vendor.name.lowercase().replaceFirstChar { it.uppercase() }} on $host",
                    host = host,
                    port = controlPort,
                    evidence = "port $port served a UPnP description naming " +
                        (TvScanPlan.manufacturerOf(description) ?: vendor.name),
                    needsPairing = vendor != TvVendor.ROKU && vendor != TvVendor.ANDROID_TV &&
                        vendor != TvVendor.AMAZON_FIRE_TV,
                ),
                RANK_NAMED,
            )
        }

        // A banner may also name the make, which is the same kind of evidence.
        for (port in ports.filter { it in IDENTIFYING_WEB_PORTS }) {
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
                    needsPairing = vendor != TvVendor.ROKU && vendor != TvVendor.AMAZON_FIRE_TV,
                ),
                RANK_NAMED,
            )
        }

        // A VENDOR REMOTE PORT IS DECISIVE ONLY WHERE IT IS EXCLUSIVE, which is why this
        // runs after the device has had its say. Most specific first, so a host exposing
        // both 8060 and 80 is named by 8060.
        val ordered = ports.sortedBy { port -> hints[port]?.let { confidenceOf(it) } ?: 99 }

        for (port in ordered) {
            val hint = hints[port] ?: continue
            if (hint == TvVendor.UNKNOWN) continue
            identify(host, port, hint)?.let { device ->
                return Ranked(device, if (hint == TvVendor.ROKU) RANK_NAMED else RANK_PORT)
            }
        }

        // Ports alone, and only where the combination is one this build can name.
        val inferred = TvScanPlan.vendorFromPorts(ports) ?: return null

        // A BOX THE PORTS CANNOT NAME IS STILL A BOX THAT ANSWERED.
        //
        // Issue #243: 8009 plus 5555 proves an Android-based streaming device and not which
        // make, so [TvScanPlan.vendorFromPorts] answers UNKNOWN rather than picking one. That
        // answer must not become a device with port 0: a port of 0 is not a control port, and
        // reporting one is the same class of fault as reporting a make the device never
        // claimed. The port used is one that was actually observed to be open.
        val controlPort = TvScanPlan.defaultPortFor(inferred).let { chosen ->
            if (chosen > 0 && chosen in ports) chosen else ports.firstOrNull() ?: 0
        }

        return Ranked(
            DiscoveredTv(
                vendor = inferred,
                name = "${inferred.name.lowercase().replaceFirstChar { it.uppercase() }} on $host",
                host = host,
                port = controlPort,
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
            // A Fire TV takes DIAL app launches with no pairing at all, so it must not be
            // listed as needing one. Its button channel does need a pairing code, and that
            // belongs in the refusal for a press rather than as a flag on the whole set.
            needsPairing = hint != TvVendor.ANDROID_TV && hint != TvVendor.AMAZON_FIRE_TV,
        )
    }

    private fun confidenceOf(vendor: TvVendor): Int = when (vendor) {
        TvVendor.ROKU -> 0
        TvVendor.SAMSUNG -> 1
        TvVendor.LG -> 2
        TvVendor.AMAZON_FIRE_TV -> 3
        TvVendor.ANDROID_TV -> 4
        TvVendor.VIZIO -> 5
        TvVendor.PHILIPS -> 6
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
            // BOUNDED. A slow-drip response can deliver a large body inside the
            // socket timeout, so the ceiling is enforced on bytes as well as time.
            val buffer = ByteArray(4096)
            val read = socket.getInputStream().read(buffer)
            if (read <= 0) null else String(buffer, 0, minOf(read, buffer.size), Charsets.ISO_8859_1)
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

    /**
     * This device's /24, from the interface that is actually the LAN.
     *
     * DELEGATES TO [com.unuslumen.app.data.fabric.net.LocalNetwork], AND THAT IS #330's
     * THIRD COPY. This used to take the first interface with any private IPv4 address, and
     * a Tor `tun0` holding a 10.x address satisfies that, so the sweep ran over the tunnel
     * while the sets in the result answered over the wifi. The ticket records the consequence
     * in this tool's own wording: its `problems[]` text named the tunnel subnet while it
     * returned a Fire TV on the wifi subnet, which cannot both be true.
     *
     * One rule, one place, one set of tests, so three tools can never disagree again.
     */
    fun localSubnetPrefix(): String? =
        com.unuslumen.app.data.fabric.net.LocalNetwork.current().prefix

    /**
     * Fetch a local document, bypassing the proxy.
     *
     * THE BUG THIS FIXES LOST A REAL TELEVISION. Discovery opened a Roku's port with a raw
     * socket, which needs no proxy, then fetched its device-info over HTTP, which went
     * through Tor and died. The set was found and then silently discarded.
     */
    /**
     * Fetch a local document, reading a BOUNDED amount of it.
     *
     * THE OUT OF MEMORY ERROR THIS FIXES. This used to call `readText()`, which
     * allocates the whole response body into one String with no ceiling. That was
     * survivable when discovery only asked a handful of known television ports.
     * It became fatal when the sweep started asking every open port on every live
     * host: one local port that streams a large body, which on a home network
     * includes a media server or a NAS, filled the 256 MB heap and killed the
     * process mid-scan. The crash reported a 288-byte allocation failing with
     * under 3 MB free, the signature of an earlier unbounded read having eaten it.
     *
     * A UPnP description and a banner are a few kilobytes. This reads at most
     * [MAX_LOCAL_DOCUMENT_BYTES] and stops, which is both cheaper and correct:
     * nothing in this file needs more of a document than that to identify a set.
     */
    private fun fetch(url: String): String {
        val connection = com.unuslumen.app.data.fabric.net.LocalRoute.open(url)
            ?: throw IllegalStateException("no connection could be built to $url")
        return try {
            connection.connectTimeout = connectTimeoutMillis
            connection.readTimeout = bannerTimeoutMillis
            connection.inputStream.use { stream ->
                com.unuslumen.app.data.fabric.net.readTextBounded(stream, MAX_LOCAL_DOCUMENT_BYTES)
            }
        } finally {
            connection.disconnect()
        }
    }

    /**
     * Ask a port for its banner with a bounded read.
     *
     * The socket read already had a soTimeout, but a slow-drip response can still
     * deliver a large body inside that window, so the ceiling is enforced on bytes
     * as well as on time. Both are needed: the timeout stops a hang, the limit
     * stops the heap.
     */
    private fun readBannerBounded(socket: Socket): String? {
        val buffer = ByteArray(4096)
        val read = socket.getInputStream().read(buffer)
        return if (read <= 0) null else String(buffer, 0, minOf(read, 4096), Charsets.ISO_8859_1)
    }

    private fun tag(xml: String, name: String): String? =
        Regex("<$name[^>]*>([^<]*)</$name>", RegexOption.IGNORE_CASE)
            // DECODED AT EXTRACTION, NOT AT THE RESOLVER (#216, #223). A set whose real name
        // contains an apostrophe serves it as `&apos;`, and leaving it encoded made the name
        // impossible to address while looking like a string the person could type.
        .find(xml)?.groupValues?.getOrNull(1)?.let { XmlText.decodeAndTrim(it) }?.ifBlank { null }

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
                    // THE LOCATION IS NORMALISED BEFORE ANYTHING READS IT (#331, #333).
                    //
                    // A UPnP LOCATION is specified to be an absolute URL, but real devices
                    // send a bare host:port, and URL() reads the IP as a protocol so the
                    // fetch never happens and the device stays unnamed. SsdpDiscoveryManager
                    // was fixed for this in #333 and this second copy still had it, which is
                    // how the Virgin boxes answered SSDP and arrived with no description for
                    // the classifier to read. Delegating means one rule rather than three.
                    val location = com.unuslumen.app.data.discovery.SsdpDescriptions
                        .normaliseLocation(
                            body.lines()
                                .firstOrNull { it.trim().startsWith("LOCATION:", ignoreCase = true) }
                                ?.substringAfter(":", "")
                                ?.trim(),
                        )
                    val description = if (location != null) {
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

            // AN AIRPLAY SET HAS A REAL PORT AND NO VENDOR (#331).
            //
            // defaultPortFor(UNKNOWN) is 0, and a port of 0 is not a control port: it is the
            // same class of fault as naming a make the device never claimed. An AirPlay
            // receiver announced itself on RTSP 7000 in the very record being read, so that
            // is the port, and the vendor stays UNKNOWN because the service type names no
            // make. A service set this build cannot place keeps 0 rather than inventing one.
            val airplay = services.any { it.startsWith("_airplay") || it.startsWith("_raop") }
            val port = if (vendor == TvVendor.UNKNOWN && airplay) 7000
                else TvScanPlan.defaultPortFor(vendor)

            DiscoveredTv(
                vendor = vendor,
                name = "${vendor.name.lowercase().replaceFirstChar { it.uppercase() }} on $host",
                host = host,
                port = port,
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

        /**
         * Every port a consumer device on a home network is known to run.
         *
         * A LIST, NOT A RANGE, AND THAT IS THE WHOLE POINT. His words were "i didnt mean scan 1m
         * oprts i meant scan every port that sonethign would usually use". A 1..65535 sweep is
         * sixteen and a half million connections across a /24, it cannot finish while holding an
         * agent's run, and it finds nothing a list of real device ports misses.
         *
         * GENEROUS ON PURPOSE, because missing a port means missing a television. It covers the
         * remote-control and app-launch ports for every major make, the cast and display protocols,
         * the discovery services, the media servers and the admin interfaces a set-top box, speaker,
         * console, printer or NAS exposes. A device that runs something outside this list is a
         * device that would answer SSDP or Bonjour instead, and both of those run first.
         */
        private val DEVICE_PORTS: List<Int> = listOf(
            // Television remote control and app launch, by make.
            8060,                                   // Roku ECP
            8001, 8002,                             // Samsung remote
            3000, 3001,                             // LG webOS
            1925, 1926, 9000,                       // Philips
            7345,                                   // Vizio
            6466, 6467,                             // Android TV remote
            8009,                                   // DIAL, and Google Cast
            8008, 8009,                             // Chromecast
            9080,                                   // Virgin Media / Horizon
            60000,                                  // Amazon Fire TV DIAL
            // Casting and display.
            7000,                                   // AirPlay / RAOP
            7100,                                   // AirPlay video
            7236, 7250,                             // Spotify Connect
            1900,                                   // SSDP
            5353,                                   // Bonjour
            // Streaming, media servers and set-top boxes.
            554, 8554,                              // RTSP
            1935,                                   // RTMP
            5000,                                   // UPnP media / Synology
            8200, 8300,                             // DLNA servers
            32400,                                  // Plex
            8096,                                   // Jellyfin
            8920,                                   // Emby
            9197, 9198,                             // Sony
            // Smart speakers and home hubs.
            8009, 8443, 10000,                      // Google Home
            4070,                                   // Amazon Echo
            // Consoles.
            3074, 3075,                             // Xbox Live
            9295, 9296, 9297,                       // PlayStation Remote Play
            // Printers and network storage, because they are devices on the network too.
            515, 631, 9100,                         // printing
            139, 445,                               // SMB
            548,                                    // AFP
            2049,                                   // NFS
            // Administration, so a device is not missed for being headless.
            22, 23, 80, 443, 8080, 8081, 8443, 8888, 49152,
        ).distinct().sorted()

        /**
         * The ports worth asking to identify themselves, once a host has answered.
         *
         * A television that names itself does it over HTTP. Asking every open port
         * for a banner and a description meant one GET per open port across the
         * whole 65535 sweep, which is the most expensive thing this file did and
         * the path an unbounded body read took the process down on.
         */
        private val IDENTIFYING_WEB_PORTS = setOf(
            80, 443, 8008, 8080, 8081, 8443, 9080, 49152, 5000, 1900, 8060, 8001, 8002,
            3000, 3001, 6466, 6467, 8009, 1925, 1926, 9000, 7345,
        )

        /**
         * The most of a local document this file will read.
         *
         * A device description is kilobytes. This ceiling is generous for that and
         * small enough that no single response can threaten the heap.
         */
        private const val MAX_LOCAL_DOCUMENT_BYTES = 64 * 1024

        /** A set that named itself, or was confirmed by a document it served. */
        private const val RANK_NAMED = 2

        /** A set named only by inference from its open ports. */
        private const val RANK_PORT = 1
    }
}
