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
        val problems = mutableListOf<String>()

        val ssdp = async { ssdpDiscover() }
        val sweep = async { sweepLocalSubnet() }

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
        val replies = mutableListOf<Pair<String, String>>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.soTimeout = timeoutMillis * 2
                val group = InetAddress.getByName("239.255.255.250")
                val message = buildString {
                    append("M-SEARCH * HTTP/1.1\r\n")
                    append("HOST: 239.255.255.250:1900\r\n")
                    append("MAN: \"ssdp:discover\"\r\n")
                    append("MX: 2\r\n")
                    append("ST: urn:dial-multiscreen-org:service:dial:1\r\n")
                    append("\r\n")
                }
                val payload = message.toByteArray(Charsets.UTF_8)
                socket.send(DatagramPacket(payload, payload.size, group, 1900))

                val deadline = System.currentTimeMillis() + timeoutMillis * 2L
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val response = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(response) }
                        .onSuccess {
                            val body = String(response.data, 0, response.length, Charsets.UTF_8)
                            val host = response.address?.hostAddress ?: return@onSuccess
                            replies.add(host to body)
                        }
                }
            }
        }

        replies.mapNotNull { (host, body) ->
            // A description document often names the make where the headers only name
            // the stack, so it is fetched when the reply offers one.
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
     * Probe every host on this device's own /24 for a known remote port.
     *
     * A /24 is 254 hosts, which is why the probes run concurrently: serially at a
     * second each that is four minutes, and nobody waits four minutes for a remote.
     * The connect timeout is short because a closed port refuses immediately and an
     * absent host times out.
     */
    private suspend fun sweepLocalSubnet(): List<DiscoveredTv> = coroutineScope {
        val prefix = localSubnetPrefix() ?: return@coroutineScope emptyList()
        val targets = (1..254).map { "$prefix.$it" }

        targets.map { host ->
            async {
                for ((port, vendor) in portHints) {
                    val reachable = runCatching {
                        Socket().use { socket ->
                            socket.connect(InetSocketAddress(host, port), 250)
                            true
                        }
                    }.getOrDefault(false)
                    if (!reachable) continue

                    // The port answered, so identity is confirmed rather than assumed:
                    // two vendors share ports, and a port open on a machine that is not
                    // a television would otherwise be reported as one.
                    return@async identify(host, port, vendor)
                }
                null
            }
        }.awaitAll().filterNotNull()
    }

    /**
     * Confirm what is on an open port, and name it.
     *
     * The Roku check is decisive because its device-info document is unambiguous. For
     * every other vendor the port and the vendor's own port hint are all that can be
     * read without a handshake, so that is what is reported and no more.
     */
    private fun identify(host: String, port: Int, hint: TvVendor): DiscoveredTv? {
        return when (hint) {
            TvVendor.ROKU -> {
                val body = runCatching { fetch("http://$host:8060/query/device-info", post = false) }
                    .getOrNull()
                if (body == null || !body.contains("<device-info", ignoreCase = true)) {
                    return null
                }
                val name = tag(body, "friendly-device-name")
                    ?: tag(body, "model-name")
                    ?: "Roku"
                DiscoveredTv(
                    vendor = TvVendor.ROKU,
                    name = name,
                    host = host,
                    port = 8060,
                    evidence = "answered Roku's device-info on port 8060",
                    needsPairing = false,
                )
            }

            /**
             * A port that is open and belongs to one vendor's remote protocol.
             *
             * Reported with the caveat in the evidence line rather than a confident name,
             * because a port being open is strong evidence and not proof.
             */
            else -> DiscoveredTv(
                vendor = hint,
                name = "${hint.name.lowercase().replaceFirstChar { it.uppercase() }} on $host",
                host = host,
                port = port,
                evidence = "port $port is open, which is ${hint.name}'s remote port",
                needsPairing = hint != TvVendor.ANDROID_TV,
            )
        }
    }

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

    private fun fetch(url: String, post: Boolean = false): String {
        val connection = URL(url).openConnection() as HttpURLConnection
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
