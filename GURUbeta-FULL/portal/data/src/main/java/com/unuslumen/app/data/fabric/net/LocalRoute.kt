// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.InetAddress
import java.net.Socket
import java.net.URL

/**
 * Whether a host is on the local network or on the internet, decided once, for every tool.
 * Issue 215.
 *
 * THE DEFECT THIS EXISTS TO REMOVE. Every outbound path in the app is injected with HTTP_PROXY,
 * HTTPS_PROXY and ALL_PROXY pointing at Tor's SOCKS listener. That is correct for the public
 * internet and fatal for the local network, because Tor will not route to a private address.
 * So the app could find every device on the network with mDNS, SSDP and raw sockets, and then
 * fail to talk to any of them over HTTP.
 *
 * That is exactly what happened on a real Roku: discovery opened port 8060 with a raw socket,
 * then fetched its device-info over HTTP, and the HTTP call died at the proxy with nothing but a
 * "000" to show for it. The set was found and then silently thrown away.
 *
 * THE ONE RULE. Resolve the name locally, look at the address it actually points at, and decide:
 * private, loopback, link-local or CGNAT goes direct, and everything else goes through Tor. Doing
 * it in one place is what stops half the tools forgetting, and a forgotten bypass fails as a
 * silent empty response that looks like an offline device.
 *
 * WHY LOCAL RESOLUTION IS SAFE. For a public host the connection still goes through Tor, and
 * socks5h still resolves the name at the exit node, so no DNS query leaks. The local lookup here
 * is a separate classification pass used only to choose a route, and its answer is thrown away.
 */
object LocalRoute {

    /** Where a request should go. */
    enum class Route {
        /** Straight onto the wifi, because the address cannot leave it anyway. */
        DIRECT,
        /** Through Tor, because it is a public address. */
        TOR,
    }

    /** What happened when a local request failed, so a caller can say which of four it was. */
    enum class Failure {
        /** The proxy refused or could not complete the handshake. This is the issue 215 defect. */
        PROXY_REFUSED,
        /** Nothing answered at that address at all. */
        HOST_UNREACHABLE,
        /** The host answered but the port is closed. */
        PORT_CLOSED,
        /** The name did not resolve. */
        DNS_FAILED,
    }

    /**
     * The route for one host.
     *
     * A name that does not resolve comes back as [Route.DIRECT] with [Resolution.failed], because
     * sending an unresolvable name to the proxy would hide a local typo behind a Tor failure.
     */
    fun routeFor(host: String): Resolution {
        val trimmed = host.trim().trim('[', ']')
        if (trimmed.isEmpty()) return Resolution(Route.DIRECT, null, "the host was empty")

        // A bare name with no dot is a machine on this network: a router called "nas", a printer
        // called "office". Those never leave the lan, so they are direct without asking DNS.
        val lookalike = !trimmed.contains('.') ||
            trimmed.endsWith(".local", true) ||
            trimmed.endsWith(".lan", true) ||
            trimmed.endsWith(".home", true) ||
            trimmed.endsWith(".internal", true)

        val literal = runCatching { InetAddress.getByName(trimmed) }.getOrNull()
        if (literal == null) {
            return Resolution(
                if (lookalike) Route.DIRECT else Route.TOR,
                null,
                "the name did not resolve locally",
            )
        }

        return Resolution(
            if (isLocalAddress(literal)) Route.DIRECT else Route.TOR,
            literal,
            null,
        )
    }

    /**
     * Whether an address belongs to a private space that cannot leave the device.
     *
     * The ranges are the ones that genuinely cannot be routed on the internet, which is what
     * makes sending them to Tor pointless rather than merely slow.
     */
    fun isLocalAddress(address: InetAddress): Boolean {
        if (address.isLoopbackAddress) return true
        if (address.isLinkLocalAddress) return true
        if (address.isSiteLocalAddress) return true
        if (!(address is Inet4Address)) return false

        val bytes = address.address
        val a = bytes[0].toInt() and 0xFF
        val b = bytes[1].toInt() and 0xFF

        // CGNAT, 100.64.0.0/10, which Tailscale and some carriers use for local peers.
        if (a == 100 && b in 64..127) return true
        // 0.0.0.0/8, the "this host" block.
        if (a == 0) return true
        // 198.18.0.0/15, the benchmarking range some VPN clients use internally.
        if (a == 198 && (b == 18 || b == 19)) return true
        return false
    }

    data class Resolution(val route: Route, val address: InetAddress?, val problem: String?)

    /**
     * Open an HTTP connection to a host, bypassing the proxy when the host is local.
     *
     * THIS IS THE METHOD EVERY LOCAL TOOL SHOULD USE. It sets the JVM's proxy to DIRECT for a
     * local host rather than relying on NO_PROXY, because NO_PROXY matches the literal string a
     * caller passes: a hostname resolving to a private address would still be sent to the proxy
     * and fail with the same silent 000.
     */
    @Suppress("DEPRECATION")
    fun open(url: String): HttpURLConnection? = runCatching {
        val parsed = URL(url)
        val resolution = routeFor(parsed.host)
        if (resolution.route == Route.DIRECT) {
            // A direct connection for this one call, without disturbing any global setting.
            java.net.Proxy.NO_PROXY
        } else {
            // The system proxy, which is Tor, is what a public request must use.
            null
        }.let { proxy ->
            if (proxy == null) {
                parsed.openConnection() as HttpURLConnection
            } else {
                parsed.openConnection(proxy) as HttpURLConnection
            }
        }
    }.getOrNull()

    /**
     * Fetch a local resource, refusing a public one.
     *
     * Local tools call this rather than building a connection themselves, so a tool that only
     * works on the lan cannot accidentally reach the internet through Tor.
     */
    suspend fun fetchLocal(url: String, timeoutMillis: Int = 3000): Fetch = withContext(Dispatchers.IO) {
        val parsed = runCatching { URL(url) }.getOrNull()
            ?: return@withContext Fetch("", Failure.DNS_FAILED, "that is not a URL")

        val resolution = routeFor(parsed.host)
        if (resolution.route == Route.TOR) {
            return@withContext Fetch(
                "",
                Failure.DNS_FAILED,
                "${parsed.host} is a public address, and a local tool reaching the internet is not " +
                    "what was asked for",
            )
        }

        val connection = runCatching { parsed.openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection }
            .getOrNull()
            ?: return@withContext Fetch("", Failure.PROXY_REFUSED, "the connection could not be built")

        try {
            connection.connectTimeout = timeoutMillis
            connection.readTimeout = timeoutMillis
            val code = runCatching { connection.responseCode }.getOrNull()
            if (code == null || code !in 200..299) {
                // A refused proxy surfaces as a null status or a 000, which is the exact symptom
                // issue 215 was filed from. Naming it here is what stops the next person hunting
                // a device that is working perfectly.
                return@withContext Fetch(
                    "",
                    if (code == null) Failure.PROXY_REFUSED else Failure.PORT_CLOSED,
                    "the request did not complete, which for a local address means the proxy was " +
                        "asked to reach private space or the port is closed",
                )
            }
            // BOUNDED WHILE READING, NOT AFTERWARDS. `readText().take(n)` allocates the whole body
            // into one String and truncates the copy afterwards, so a device that streams a large
            // payload still fills the heap before the truncation runs. That is exactly the
            // OutOfMemoryError this app has crashed from, down to the 288-byte allocation that
            // failed once the heap was already gone.
            val body = runCatching {
                connection.inputStream.use { stream -> readTextBounded(stream, 400_000) }
            }.getOrDefault("")
            Fetch(body, null, null)
        } finally {
            connection.disconnect()
        }
    }

    data class Fetch(val body: String, val failure: Failure?, val reason: String?)

    /** Whether a TCP port accepts a connection, which needs no proxy at all. */
    fun isPortOpen(host: String, port: Int, timeoutMillis: Int = 200): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(java.net.InetSocketAddress(host, port), timeoutMillis)
            true
        }
    }.getOrDefault(false)
}
