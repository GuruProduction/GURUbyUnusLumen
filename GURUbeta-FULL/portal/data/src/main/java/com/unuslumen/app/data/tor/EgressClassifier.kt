// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tor

import com.unuslumen.app.preferences.domain.model.EgressMode
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * The single routing decision every outbound connection asks for.
 *
 * WHY THIS EXISTS (#215). Tor cannot reach RFC1918 space. A SOCKS proxy asked
 * to CONNECT to a private LAN address either refuses or fails, and `socks5h` means DNS
 * resolution happens at the exit node, so even a `.local` name is dead before
 * the address is examined. The result was that discovery could find a
 * television on the LAN and then nothing in the app could talk to it: `curl`
 * returned `000`, `httpGet` returned "Tor not running", and both read like the
 * device was offline.
 *
 * The rule is one rule, applied in one place, and it is deliberately not a flag
 * each tool has to remember: a bypass that half the tools forget is the bug
 * that produced the silent `000` in the first place.
 *
 * This object is pure. No Android, no Koin, no I/O of its own, so the decision
 * can be proved on the JVM in seconds without a device or an emulator.
 */
object EgressClassifier {

    /** Where a request must be sent to leave the device. */
    enum class Route {
        /** Straight onto the socket. Loopback and the local network. */
        DIRECT,

        /** Through Tor's SOCKS listener. Everything on the public internet. */
        TOR,
    }

    // ---------------------------------------------------------------------
    // Address classification
    // ---------------------------------------------------------------------

    /**
     * The route for an already-resolved address.
     *
     * Everything that Java considers loopback, link-local or site-local is
     * direct, plus two ranges Java does not cover: carrier-grade NAT, which
     * Tailscale and some mobile carriers hand out, and IPv6 unique-local
     * addresses, which are the IPv6 equivalent of 10/8.
     */
    fun routeForAddress(address: InetAddress): Route = when {
        address.isLoopbackAddress -> Route.DIRECT
        address.isLinkLocalAddress -> Route.DIRECT
        address.isSiteLocalAddress -> Route.DIRECT
        isCarrierGradeNat(address) -> Route.DIRECT
        isUniqueLocalIpv6(address) -> Route.DIRECT
        // The "this host" block and the benchmarking range some VPN clients use internally.
        // Neither can be routed on the public internet, so asking Tor to reach one fails in
        // exactly the silent way a private address does. These two rules came from LocalRoute,
        // which owned them before it delegated here, and dropping them made the this-host
        // address resolve as public and get handed to the proxy.
        isThisHostBlock(address) -> Route.DIRECT
        isBenchmarkingRange(address) -> Route.DIRECT
        else -> Route.TOR
    }

    /** The "this host" block: any address whose first octet is zero. */
    private fun isThisHostBlock(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != 4) return false
        return bytes[0].toInt() == 0
    }

    /** The benchmarking range, which some VPN clients use internally. */
    private fun isBenchmarkingRange(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 198 && (second == 18 || second == 19)
    }

    /** 100.64.0.0/10, used by Tailscale and by several mobile carriers. */
    private fun isCarrierGradeNat(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != 4) return false
        val first = bytes[0].toInt() and 0xFF
        val second = bytes[1].toInt() and 0xFF
        return first == 100 && second in 64..127
    }

    /** fc00::/7, the IPv6 equivalent of a private address. */
    private fun isUniqueLocalIpv6(address: InetAddress): Boolean {
        val bytes = address.address
        if (bytes.size != 16) return false
        return (bytes[0].toInt() and 0xFE) == 0xFC
    }

    // ---------------------------------------------------------------------
    // Host classification
    // ---------------------------------------------------------------------

    /**
     * The route for a host as written by the caller, before any DNS lookup.
     *
     * A name that is not an IP literal and does not end in a local suffix is
     * assumed public, because assuming local for an unknown name would send a
     * clearnet request direct. Callers that can afford a lookup should use
     * [routeForHostResolved] instead, which catches a public-looking name that
     * split-horizon DNS points at the router.
     */
    fun routeForHost(host: String?): Route = if (isLocalName(host)) Route.DIRECT else Route.TOR

    /**
     * The route for a host that looked public, decided after a local lookup.
     *
     * This is the case a `NO_PROXY` list cannot cover and the reason a single
     * classifier was needed rather than an environment variable: `NO_PROXY`
     * compares the literal string the caller passed, so a name that resolves
     * into the LAN still goes to the proxy. Resolving first and inspecting the
     * result is what makes the decision correct.
     *
     * Returns TOR when the lookup fails, because a name that cannot be resolved
     * is not evidence that it is local.
     */
    fun routeForHostResolved(
        host: String?,
        resolve: (String) -> List<InetAddress>,
    ): Route {
        if (host == null) return Route.TOR
        if (isLocalName(host)) return Route.DIRECT

        val addresses = try {
            resolve(host)
        } catch (_: Exception) {
            return Route.TOR
        }
        if (addresses.isEmpty()) return Route.TOR

        // Any private answer means the request was aimed at this network. A
        // mixed answer is treated as local so a split-horizon host still works.
        return if (addresses.any { routeForAddress(it) == Route.DIRECT }) Route.DIRECT else Route.TOR
    }

    /** True when the host addresses something on the device or on this network. */
    fun isLocalName(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val h = host.trim().lowercase().removeSurrounding("[", "]")
        if (h.isEmpty()) return false

        if (h == "localhost" || h == "localhost.localdomain") return true

        parseIpv4(h)?.let { return routeForAddress(it) == Route.DIRECT }
        parseIpv6(h)?.let { return routeForAddress(it) == Route.DIRECT }

        // Anything that is not a plausible hostname is not evidence of a local
        // target. Without this, the bare-name rule below turned a malformed
        // string such as "not a host" into a direct connection, which is the
        // direction a classifier must never fail in.
        if (!isPlausibleHostname(h)) return false

        // A name with no dot cannot be resolved by public DNS; it is either an
        // mDNS/NetBIOS neighbour or a search-domain name. Both mean this LAN.
        if (!h.contains('.')) return true

        // Suffixes that only ever name something on the local network. `.local`
        // is mDNS, `.lan` and `.home` are the usual router defaults, and
        // `.home.arpa` is the registered one for home networks.
        for (index in LOCAL_SUFFIXES.indices) {
            if (h == LOCAL_SUFFIXES_BARE[index] || h.endsWith(LOCAL_SUFFIXES[index])) return true
        }

        if (h == "host.docker.internal" || h == "gateway.docker.internal") return true

        return false
    }

    /**
     * Whether a string could name a host at all.
     *
     * Letters, digits, hyphen and dot, with no space and no empty label. This
     * is deliberately permissive about what a valid name is and strict about
     * what it is not, because anything rejected here falls through to Tor.
     *
     * ALLOCATION-FREE BY CONSTRUCTION. The first version walked `h.split('.')`,
     * which takes the `String` overload, which compiles a fresh `Pattern` and
     * allocates a `List` plus a `String` per label. This function sits under
     * `isLocalName`, which sits under `decide`, which sits under the proxy
     * selector, which OkHttp calls once per connection attempt AND RETRIES FOR
     * EVER while it treats a thrown selector as a route failure. That loop is
     * what OOMed the app: 466 suppressed ProtocolExceptions from one fetch, then
     * a 32-byte substring failed. Do not put a split, a regex or any other
     * allocation back into this path.
     */
    private fun isPlausibleHostname(h: String): Boolean {
        if (h.isEmpty() || h.length > 253) return false
        var labelLength = 0
        var i = 0
        while (i < h.length) {
            val c = h[i]
            when {
                c == '.' -> {
                    if (labelLength == 0) return false
                    labelLength = 0
                }
                c.isWhitespace() -> return false
                c.isLetterOrDigit() || c == '-' || c == '_' -> {
                    labelLength++
                    if (labelLength > 63) return false
                }
                else -> return false
            }
            i++
        }
        // A trailing dot leaves the last label empty, which is not a host.
        return labelLength != 0
    }

    private val LOCAL_SUFFIXES = listOf(
        ".local",
        ".lan",
        ".home",
        ".home.arpa",
        ".internal",
        ".localdomain",
    )

    /**
     * The bare forms of [LOCAL_SUFFIXES], precomputed once.
     *
     * `suffix.removePrefix(".")` was evaluated inside the per-host loop on the
     * proxy-selector path, which allocated a String per suffix per connection
     * attempt. Six strings, once, removes that entirely.
     */
    private val LOCAL_SUFFIXES_BARE: List<String> = LOCAL_SUFFIXES.map { it.removePrefix(".") }

    /**
     * Parse a dotted-quad strictly.
     *
     * A loose prefix test on `10.` or `192.168.` also matches a hostname like
     * `10.example.com`, and it silently accepts `192.168.1.999`. Requiring four
     * numeric octets in range means only a real IPv4 literal can be classified
     * as one, and anything else falls through to the name rules.
     */
    private fun parseIpv4(host: String): InetAddress? {
        // 255.255.255.255 is 15 characters; anything shorter than 7 is not a quad.
        if (host.length < 7 || host.length > 15) return null

        val octets = IntArray(4)
        var octetIndex = 0
        var value = 0
        var digits = 0
        var i = 0
        while (i < host.length) {
            val c = host[i]
            if (c == '.') {
                if (digits == 0 || digits > 3) return null
                octets[octetIndex] = value
                octetIndex++
                if (octetIndex > 3) return null
                value = 0
                digits = 0
            } else {
                if (c < '0' || c > '9') return null
                // Leading zeros read as decimal, exactly as `toIntOrNull` did
                // before this was rewritten. Only the allocation changed, so no
                // string that was an address before has stopped being one.
                value = value * 10 + (c - '0')
                digits++
                if (digits > 3 || value > 255) return null
            }
            i++
        }

        if (octetIndex != 3 || digits == 0 || digits > 3) return null
        octets[3] = value

        return try {
            InetAddress.getByAddress(byteArrayOf(
                octets[0].toByte(),
                octets[1].toByte(),
                octets[2].toByte(),
                octets[3].toByte(),
            ))
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parse an IPv6 literal, including the `::` compression and a trailing
     * dotted-quad, without letting Java do a DNS lookup for a name that merely
     * contains a colon.
     */
    private fun parseIpv6(host: String): InetAddress? {
        if (!host.contains(':')) return null
        return try {
            // getByName on a string containing ':' is an address literal, never
            // a hostname, so this cannot touch DNS.
            InetAddress.getByName(host)
        } catch (_: Exception) {
            null
        }
    }

    // ---------------------------------------------------------------------
    // The decision, posture included
    // ---------------------------------------------------------------------

    /**
     * The full decision for one host under one posture. THIS IS WHAT EVERY OUTBOUND PATH CALLS.
     *
     * WHY THE POSTURE IS AN ARGUMENT RATHER THAN A READ. It keeps this object pure, so the
     * whole truth table is provable on the JVM with no device, no DataStore and no Tor. The
     * live posture comes from EgressPolicy, which the caller consults and passes in.
     *
     * THE RULES, and every combination of host and posture is one of them:
     *
     *  1. A local or loopback host is DIRECT under every posture, because Tor cannot reach
     *     private space. Asking it to is a guaranteed failure, not a privacy win.
     *  2. A public host under AUTO or TOR_ONLY rides TOR. With no circuit the decision says so
     *     via [EgressDecision.awaitsCircuit] and the request WAITS for one. It is never sent
     *     direct and it is never refused, because Tor is infrastructure rather than a feature.
     *  3. A public host under CLEARNET goes DIRECT on the owner's override, and the decision
     *     carries unproxied = true so no caller can report it as private.
     *  4. A credential-bearing path ([forcesTor]) ignores the override entirely and behaves
     *     as TOR_ONLY. A token over his own address identifies him to a service, which is a
     *     different act from fetching a public page, and it is not a trade this command makes.
     *
     * [torReady] is passed in for the same purity reason, so a test can prove the waiting
     * branch without a Tor daemon.
     *
     * THERE IS NO REFUSAL PATH HERE, AND THAT IS THE OWNER'S RULING. An earlier version refused
     * a public host with no circuit and told the reader to turn clearnet on. He rejected it: a
     * dead circuit is a bug to recover from, not a condition to hand back. So the no-circuit
     * case now reports that the request will wait, and the recovery happens behind it.
     */
    fun decide(
        host: String?,
        mode: EgressMode,
        torReady: Boolean,
        forcesTor: Boolean = false,
    ): EgressDecision {
        if (isLocalName(host)) {
            return EgressDecision.direct(
                mode = mode,
                reason = "This address is on the device or on the local network, which Tor cannot " +
                    "reach, so it goes direct.",
            )
        }

        val effective = if (forcesTor) EgressMode.TOR_ONLY else mode

        return when (effective) {
            EgressMode.AUTO, EgressMode.TOR_ONLY -> {
                if (torReady) {
                    EgressDecision.tor(
                        mode = mode,
                        reason = if (forcesTor) {
                            "This request carries a credential, so it rides Tor and never goes direct."
                        } else {
                            "This is a public address, so it rides Tor."
                        },
                    )
                } else {
                    // WAIT, DO NOT REFUSE. Tor is infrastructure; a missing circuit is a condition
                    // to recover from. The selector blocks for a bounded window and proceeds the
                    // moment a circuit exists, and circuits are rebuilt in the background so that
                    // window is normally short or not needed at all.
                    EgressDecision.await(
                        mode = mode,
                        reason = if (forcesTor) {
                            "This request carries a credential, so it rides Tor and never goes direct. " +
                                "Tor has no circuit ready yet, so this waits for one."
                        } else {
                            "This is a public address, so it rides Tor. Tor has no circuit ready yet, " +
                                "so this waits for one rather than falling back to a direct connection."
                        },
                    )
                }
            }

            EgressMode.CLEARNET -> EgressDecision.clearnetOverride(
                "This is a public address and clearnet is on, so this request went direct on the " +
                    "device's own connection rather than through Tor.",
            )
        }
    }

    // ---------------------------------------------------------------------
    // Shell command classification
    // ---------------------------------------------------------------------

    private val SCHEME_URL = Regex("""[a-zA-Z][a-zA-Z0-9+.\-]*://([^/\s'"\\]+)""")

    /**
     * Whitespace split for shell commands, compiled once.
     *
     * This was `command.split(Regex("\\s+"))` inline, so the pattern compiled on
     * every call. `hostsInCommand` runs under `commandNeedsTor` for every shell
     * command the app executes.
     */
    private val WHITESPACE = Regex("\\s+")

    /**
     * Every host a shell command appears to target.
     *
     * Used to decide whether a `curl` or `wget` in `busyboxExec` needs Tor. The
     * parse is deliberately narrow: a scheme URL, or a bare `host:port` or bare
     * IP following the command, which covers the way these are actually typed.
     * Anything it cannot see is treated as public by [commandNeedsTor], so a
     * miss can only make the check stricter, never leak.
     */
    fun hostsInCommand(command: String): List<String> {
        val found = mutableListOf<String>()
        for (match in SCHEME_URL.findAll(command)) {
            found += stripUserInfoAndPort(match.groupValues[1])
        }
        if (found.isNotEmpty()) return found.distinct()

        // No scheme, so look for bare addresses: an IPv4 literal or a
        // hostname:port token.
        for (token in command.split(WHITESPACE)) {
            val cleaned = token.trim('"', '\'', '`', ';', '&', '|', ')', '(')
            if (cleaned.isEmpty()) continue
            val withoutPort = cleaned.substringBefore(':')
            if (withoutPort.isEmpty()) continue
            val looksLikeIp = withoutPort.count { it == '.' } == 3 &&
                withoutPort.all { it.isDigit() || it == '.' }
            val looksLikeHostPort = cleaned.contains(':') &&
                cleaned.substringAfter(':').takeWhile { it.isDigit() }.isNotEmpty() &&
                withoutPort.any { it.isLetter() || it == '.' }
            if (looksLikeIp || looksLikeHostPort) found += withoutPort
        }
        return found.filter { it.isNotEmpty() }.distinct()
    }

    private fun stripUserInfoAndPort(raw: String): String =
        raw.substringAfterLast('@').substringBefore(':')

    /**
     * Whether a shell command that is about to run `curl` or `wget` needs a
     * live Tor circuit first.
     *
     * A command aimed entirely at the local network does not, and requiring it
     * was the reason a LAN fetch failed with "Tor is not running" even when the
     * target was the router. A command with no address this can see does need
     * it, because the alternative is running it unproxied.
     */
    fun commandNeedsTor(command: String): Boolean {
        val hosts = hostsInCommand(command)
        if (hosts.isEmpty()) return true
        return hosts.any { !isLocalName(it) }
    }

    /**
     * The `NO_PROXY` value that keeps a proxied command off the local network.
     *
     * Both cases are set by the caller, because curl reads the lowercase form
     * and other tooling reads the uppercase one. The list is always emitted so
     * a command that mixes a public and a local address gets both right.
     */
    const val NO_PROXY_VALUE: String =
        "localhost,127.0.0.1,::1,10.0.0.0/8,172.16.0.0/12,192.168.0.0/16," +
            "169.254.0.0/16,100.64.0.0/10,*.local,*.lan,*.home,*.home.arpa,*.internal"

    // ---------------------------------------------------------------------
    // Failure reporting
    // ---------------------------------------------------------------------

    /**
     * Name the actual cause of a failed request.
     *
     * Four different facts used to collapse into one empty result: Tor refused
     * the hop, the host was unreachable, the host answered but nothing was
     * listening, and the name did not resolve. The model reasons from this
     * sentence, so reporting the wrong one sends it looking at the network when
     * the fault is a route, or at the device when the fault is a closed port.
     */
    fun failureSentence(host: String, port: Int?, route: Route, error: Throwable?): String {
        val target = if (port != null && port > 0) "$host:$port" else host
        val message = error?.message?.lowercase().orEmpty()

        // A proxy refusal is checked first and on both routes, because it is
        // the cause that looks most like a dead device and is not one.
        val proxyRefused = message.contains("proxy") ||
            message.contains("socks") ||
            message.contains("unable to tunnel")
        if (route == Route.TOR && proxyRefused) {
            return "The Tor proxy refused the connection to $target, so the request never reached it. " +
                "Tor cannot route to a private address. This target may be on the local network and " +
                "should have been classified local."
        }

        val dnsFailed = error is UnknownHostException ||
            message.contains("unable to resolve host") ||
            message.contains("nodename nor servname") ||
            message.contains("no address associated with hostname")
        if (dnsFailed) {
            return "Could not resolve the name $host. The name lookup itself failed, so nothing was " +
                "attempted against any address. This is a DNS result, not a device that refused."
        }

        val refused = message.contains("econnrefused") ||
            message.contains("connection refused")
        if (refused) {
            return "$target refused the connection. The host answered and nothing is listening on " +
                "that port, which means the address is right and the service is not running there."
        }

        val unreachable = message.contains("ehostunreach") ||
            message.contains("network is unreachable") ||
            message.contains("no route to host")
        if (unreachable) {
            return "$target is not reachable from this device. Either it is not on this network, or " +
                "the network itself has no route to it."
        }

        if (error is SocketTimeoutException || message.contains("timed out")) {
            val via = if (route == Route.TOR) "through Tor" else "directly"
            return "$target did not answer before the timeout, contacted $via. A silent host and a " +
                "dropped packet look identical from here."
        }

        val via = if (route == Route.TOR) "through Tor" else directly()
        val detail = error?.message?.takeIf { it.isNotBlank() } ?: error?.javaClass?.simpleName ?: "no detail"
        return "The request to $target failed $via: $detail"
    }

    private fun directly(): String = "directly"
}
