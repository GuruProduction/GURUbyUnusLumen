// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import java.net.Inet4Address
import java.net.NetworkInterface

/**
 * What kind of interface an address arrived on, which decides whether it is the LAN.
 *
 * ISSUE 330. `netHosts` reported `subnet: 10.73.104` while every host it returned was on
 * `192.168.0`, and `tvDiscover` carried the same wrong value into its `problems[]` text.
 * Two tools derived the subnet independently, and both took the first interface that had
 * a private IPv4 address. A Tor engine running on the phone creates exactly such an
 * interface, so the tunnel won the race and the wifi was never looked at.
 *
 * That is worse than a wrong label. The sweep then runs over the tunnel's range while the
 * hosts in the result arrived over the wifi from SSDP and mDNS, so the result is a
 * complete-looking answer about the wrong thing. [TUNNEL] and [CELLULAR] exist so the
 * choice can exclude them by name instead of hoping they enumerate late.
 */
enum class InterfaceRole {
    /** A wifi interface. This is the LAN a person means when they say "my network". */
    WIFI,
    /** Wired ethernet, which on a phone is an OTG adapter and is still a real LAN. */
    ETHERNET,
    /** Mobile data. Never the network to sweep: that range belongs to the carrier. */
    CELLULAR,
    /**
     * A VPN, Tor or container bridge.
     *
     * `tun`, `tap`, `ppp`, `wg`, `zt`, `tailscale`, `ipsec` and their friends. A tunnel
     * carries a private address and answers a socket, so it passes every check a naive
     * "is this RFC1918" test makes, and it is the wrong network every time.
     */
    TUNNEL,
    /** Something else with an address, kept as a last resort rather than discarded. */
    OTHER,
}

/**
 * The subnet this phone is actually on, and how that was decided.
 *
 * [chosen] and [skipped] are both reported because #330 asks for it directly: if more
 * than one usable interface exists, say which was taken rather than silently picking one.
 * A reader who is told `chosen: wlan0 (192.168.0)` can trust the sweep; a reader who is
 * told only `10.73.104` cannot tell whether the answer is about their house.
 */
data class LocalSubnet(
    val prefix: String?,
    val interfaceName: String?,
    val role: InterfaceRole?,
    /** Every interface considered, with the reason it lost, for a caller that wants to explain. */
    val skipped: List<String> = emptyList(),
) {
    val found: Boolean get() = prefix != null
}

/**
 * Chooses the LAN interface once, so the whole network family agrees on one answer.
 *
 * WHY THIS IS A SHARED OBJECT AND NOT A FUNCTION ON EACH TOOL. #330's own words: the wrong
 * subnet reached both `netHosts` and `tvDiscover`, "which means the subnet derivation is
 * shared code and the defect is upstream of both tools". It was not shared; it was copied.
 * Two copies drifted, and the copy the app ran was the one nothing tested. Now there is one
 * rule and one set of tests.
 *
 * THE PREFERENCE ORDER, and why it is this order rather than the enumeration order:
 *
 *  1. Wifi, because that is the network a person is asking about and the only one a home
 *     device can be on.
 *  2. Ethernet, because an OTG adapter is a real LAN and a phone plugged into one should
 *     scan it.
 *  3. OTHER, as a last resort, so an unusual device is not left with nothing.
 *
 * Cellular and tunnelled interfaces are never chosen. Neither is the network a user means,
 * and both would otherwise win on enumeration order.
 */
object LocalNetwork {

    /**
     * Classify an interface by ITS NAME.
     *
     * Android does not expose a "is this wifi" flag to a normal app, so the name is what
     * there is. The lists below are the names that actually appear: `wlan0` and `ap0` on
     * most devices, `wlan1` for a second radio, `eth0` for an adapter, `rmnet_data0` and
     * `ccmni0` for mobile data, `tun0` for a VPN and Tor.
     */
    fun roleOf(interfaceName: String): InterfaceRole {
        val name = interfaceName.lowercase()
        return when {
            name.startsWith("wlan") || name.startsWith("ap") || name.startsWith("wl") ->
                InterfaceRole.WIFI
            name.startsWith("eth") -> InterfaceRole.ETHERNET
            name.startsWith("rmnet") || name.startsWith("ccmni") ||
                name.startsWith("pdp") || name.startsWith("wwan") ||
                name.startsWith("seth") -> InterfaceRole.CELLULAR
            // THE ONE THE BUG WAS ABOUT. A Tor or VPN interface carries a private address and
            // is reachable, so nothing but its name distinguishes it from the LAN.
            name.startsWith("tun") || name.startsWith("tap") ||
                name.startsWith("ppp") || name.startsWith("wg") ||
                name.startsWith("zt") || name.startsWith("tailscale") ||
                name.startsWith("ipsec") || name.startsWith("utun") ||
                name.startsWith("docker") || name.startsWith("br-") ||
                name.startsWith("veth") -> InterfaceRole.TUNNEL
            else -> InterfaceRole.OTHER
        }
    }

    /**
     * One interface's relevant facts, so the choice is decidable without Android.
     *
     * Only IPv4 addresses are carried, because a /24 sweep is an IPv4 idea and an IPv6
     * address tells you nothing about which IPv4 subnet to scan.
     */
    data class Candidate(
        val name: String,
        val isUp: Boolean,
        val isLoopback: Boolean,
        val ipv4Addresses: List<String>,
    )

    /**
     * Choose the LAN from [candidates].
     *
     * The rule, in one sentence: the best role wins, and a loopback, a down interface, a
     * cellular one and a tunnel are never candidates at all.
     */
    fun choose(candidates: List<Candidate>): LocalSubnet {
        val skipped = mutableListOf<String>()

        val usable = candidates.mapNotNull { candidate ->
            val role = roleOf(candidate.name)
            when {
                candidate.isLoopback -> {
                    skipped += "${candidate.name}: loopback"
                    null
                }
                !candidate.isUp -> {
                    skipped += "${candidate.name}: down"
                    null
                }
                role == InterfaceRole.CELLULAR -> {
                    skipped += "${candidate.name}: mobile data, which is never the local network"
                    null
                }
                role == InterfaceRole.TUNNEL -> {
                    // Named explicitly because this is issue 330: the tunnel was chosen and
                    // the result was reported as the house.
                    skipped += "${candidate.name}: tunnel or vpn, which carries a private " +
                        "address but is not the local network"
                    null
                }
                else -> {
                    val address = candidate.ipv4Addresses.firstOrNull { isPrivateIpv4(it) }
                    if (address == null) {
                        skipped += "${candidate.name}: no private IPv4 address"
                        null
                    } else {
                        candidate to address
                    }
                }
            }
        }

        if (usable.isEmpty()) {
            return LocalSubnet(prefix = null, interfaceName = null, role = null, skipped = skipped)
        }

        // Wifi first, then ethernet, then anything else. `minByOrNull` rather than a sort,
        // because the list is tiny and the intent is clearer as a preference order.
        val ranked = usable.sortedBy { rank(roleOf(it.first.name)) }
        val (chosen, address) = ranked.first()

        // The others are reported so a caller can say which interface it used. A scan that
        // does not name its interface is a scan nobody can check.
        ranked.drop(1).forEach { (candidate, other) ->
            skipped += "${candidate.name} ($other): also usable, not chosen because " +
                "${chosen.name} ranks higher"
        }

        return LocalSubnet(
            prefix = address.substringBeforeLast('.'),
            interfaceName = chosen.name,
            role = roleOf(chosen.name),
            skipped = skipped,
        )
    }

    /** Lower wins. Wifi, then ethernet, then anything that is not cellular or tunnelled. */
    private fun rank(role: InterfaceRole): Int = when (role) {
        InterfaceRole.WIFI -> 0
        InterfaceRole.ETHERNET -> 1
        InterfaceRole.OTHER -> 2
        InterfaceRole.CELLULAR -> 3
        InterfaceRole.TUNNEL -> 4
    }

    /**
     * Whether an address is in a private range a home network can use.
     *
     * 172 is checked as a RANGE rather than a prefix: 172.1.x and 172.217.x are public
     * addresses, and sweeping one would be a scan of somebody else's network.
     */
    fun isPrivateIpv4(host: String): Boolean {
        val octets = host.split('.').mapNotNull { it.toIntOrNull() }
        if (octets.size != 4) return false
        if (octets.any { it !in 0..255 }) return false
        return when {
            octets[0] == 10 -> true
            octets[0] == 192 && octets[1] == 168 -> true
            octets[0] == 172 && octets[1] in 16..31 -> true
            else -> false
        }
    }

    /**
     * Read the live interfaces into [Candidate]s.
     *
     * The only part that touches the platform, kept here so both callers share it and a
     * JVM test can drive [choose] with a hand-written list instead.
     */
    fun liveCandidates(): List<Candidate> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList().map { iface ->
            Candidate(
                name = iface.name,
                isUp = runCatching { iface.isUp }.getOrDefault(false),
                isLoopback = runCatching { iface.isLoopback }.getOrDefault(false),
                ipv4Addresses = runCatching {
                    iface.inetAddresses.toList()
                        .filterIsInstance<Inet4Address>()
                        .mapNotNull { it.hostAddress }
                }.getOrDefault(emptyList()),
            )
        }
    }.getOrDefault(emptyList())

    /** The live choice, for production code. */
    fun current(): LocalSubnet = choose(liveCandidates())

    /**
     * The live wifi interface, for a socket that must leave by it.
     *
     * Separate from [choose] because a caller may want the interface object itself rather
     * than its subnet, and because this one has to return a real [NetworkInterface].
     */
    fun liveWifiInterface(): NetworkInterface? = runCatching {
        val target = current()
        val name = target.interfaceName ?: return@runCatching null
        NetworkInterface.getByName(name)
    }.getOrNull()
}
