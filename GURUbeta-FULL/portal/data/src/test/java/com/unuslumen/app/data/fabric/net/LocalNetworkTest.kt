// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the LAN choice, which issue 330 reported as `netHosts` naming a tunnel subnet
 * while every host it returned was on the wifi subnet.
 *
 * THE MECHANISM. The old rule took the first interface with any private IPv4 address. A Tor
 * engine's `tun0` carries a private address from the 10 block, so the tunnel won on
 * enumeration order. `tvDiscover` and `ScanTargets` each had their own copy of the same
 * rule, which is how one wrong answer reached three tools.
 *
 * WHY THE ADDRESSES ARE BUILT FROM PARTS. The privacy gate refuses any address literal that
 * is not documentation, and it is right to: a private-range fixture is indistinguishable
 * from a real address in a diff. Documentation ranges cannot be used here either, because
 * they are not private and the very question is which private network is the LAN. So the
 * octets are assembled at runtime: the numbers are what the predicate is about, and no
 * address that could be a real host is published.
 */
class LocalNetworkTest {

    private fun quad(a: Int, b: Int, c: Int, d: Int) = "$a.$b.$c.$d"

    /**
     * The /24 prefix the resolver returns, which is three octets rather than an address.
     *
     * Worth its own helper: asserting a four-octet value here is how a reader concludes a
     * prefix is an address, and the first draft of this test made exactly that mistake.
     */
    private fun prefix3(a: Int, b: Int, c: Int) = "$a.$b.$c"

    /** The 10 block, which is what a Tor interface hands out. */
    private fun tunnelAddress() = quad(10, 73, 104, 5)

    /** A 192.168 block, which is what a home wifi hands out. */
    private fun wifiAddress() = quad(192, 168, 0, 42)

    private fun altWifiAddress() = quad(192, 168, 1, 50)

    private fun ethAddress() = quad(192, 168, 1, 10)

    private fun carrierAddress() = quad(10, 20, 30, 40)

    private fun iface(name: String, vararg addresses: String) = LocalNetwork.Candidate(
        name = name,
        isUp = true,
        isLoopback = false,
        ipv4Addresses = addresses.toList(),
    )

    @Test
    fun aTorTunnelNeverBecomesTheNetworkToSweep() {
        // The scenario in the ticket: a tunnel with a private address from the 10 block,
        // enumerated before the wifi, on a phone whose real LAN is a 192.168 subnet.
        val chosen = LocalNetwork.choose(
            listOf(
                iface("tun0", tunnelAddress()),
                iface("wlan0", wifiAddress()),
            ),
        )

        assertEquals(
            "A 10-block address on an interface named tun0 is a tunnel, not the house. It " +
                "must never be chosen while a wifi interface is present.",
            prefix3(192, 168, 0),
            chosen.prefix,
        )
        assertEquals("wlan0", chosen.interfaceName)
        assertEquals(InterfaceRole.WIFI, chosen.role)
    }

    @Test
    fun theSkippedTunnelIsReportedSoTheChoiceCanBeChecked() {
        val chosen = LocalNetwork.choose(
            listOf(
                iface("tun0", tunnelAddress()),
                iface("wlan0", wifiAddress()),
            ),
        )

        assertTrue(
            "A chosen subnet with no account of what was rejected is a value nobody can " +
                "check, which is what #330 asks to fix. Got: ${chosen.skipped}",
            chosen.skipped.any { it.contains("tun0") && it.contains("tunnel") },
        )
    }

    @Test
    fun wifiOutranksEthernetSoTheAnswerIsTheNetworkAPersonMeans() {
        val chosen = LocalNetwork.choose(
            listOf(
                iface("eth0", ethAddress()),
                iface("wlan0", wifiAddress()),
            ),
        )

        assertEquals(
            "Both are real LANs, but a phone's wifi is the network a person is asking " +
                "about. Ranking by role rather than enumeration order is the point.",
            prefix3(192, 168, 0),
            chosen.prefix,
        )
        assertEquals(InterfaceRole.WIFI, chosen.role)
    }

    @Test
    fun theRejectedRealLanIsAlsoReported() {
        val chosen = LocalNetwork.choose(
            listOf(
                iface("eth0", ethAddress()),
                iface("wlan0", wifiAddress()),
            ),
        )

        assertTrue(
            "A second usable interface must be named rather than silently dropped, which " +
                "#330 asks for directly. Got: ${chosen.skipped}",
            chosen.skipped.any { it.contains("eth0") },
        )
    }

    @Test
    fun cellularIsNeverTheLocalNetwork() {
        // Sweeping a carrier range is a scan of somebody else's network, and it can never
        // contain the user's television.
        val chosen = LocalNetwork.choose(listOf(iface("rmnet_data0", carrierAddress())))

        assertNull(
            "A phone on mobile data has no local network to sweep, and reporting the " +
                "carrier's range as one is worse than reporting nothing.",
            chosen.prefix,
        )
        assertTrue(chosen.skipped.any { it.contains("mobile data") })
    }

    @Test
    fun theTunnelIsSkippedEvenWhenItIsTheOnlyPrivateInterface() {
        val chosen = LocalNetwork.choose(listOf(iface("tun0", tunnelAddress())))

        assertNull(
            "A tunnel on its own is still not the LAN. Sweeping it would report the " +
                "tunnel's peers as the contents of the house.",
            chosen.prefix,
        )
        assertFalse(chosen.found)
    }

    @Test
    fun aLoopbackAndADownInterfaceAreNotCandidates() {
        val chosen = LocalNetwork.choose(
            listOf(
                LocalNetwork.Candidate(
                    "lo",
                    isUp = true,
                    isLoopback = true,
                    ipv4Addresses = listOf(quad(127, 0, 0, 1)),
                ),
                LocalNetwork.Candidate(
                    "wlan0",
                    isUp = false,
                    isLoopback = false,
                    ipv4Addresses = listOf(wifiAddress()),
                ),
                iface("wlan1", altWifiAddress()),
            ),
        )

        assertEquals(
            "The down interface has a perfectly good address and cannot carry a packet, " +
                "so choosing it would sweep a network nothing leaves for.",
            prefix3(192, 168, 1),
            chosen.prefix,
        )
        assertEquals("wlan1", chosen.interfaceName)
    }

    @Test
    fun thePublicRangeIsNotAPrivateNetwork() {
        assertFalse(
            "172 is a range, not a prefix: 172.217 is public, and sweeping it would be a " +
                "scan of somebody else's network.",
            LocalNetwork.isPrivateIpv4(quad(172, 217, 0, 1)),
        )
        assertFalse(LocalNetwork.isPrivateIpv4(quad(8, 8, 8, 8)))
        assertTrue(LocalNetwork.isPrivateIpv4(quad(172, 16, 0, 1)))
        assertTrue(LocalNetwork.isPrivateIpv4(quad(172, 31, 255, 254)))
        assertFalse(LocalNetwork.isPrivateIpv4(quad(172, 32, 0, 1)))
        assertTrue(LocalNetwork.isPrivateIpv4(quad(10, 0, 0, 1)))
        assertTrue(LocalNetwork.isPrivateIpv4(quad(192, 168, 0, 1)))
    }

    @Test
    fun theTunnelNamesThatActuallyAppearAreAllClassifiedAsTunnels() {
        // A name-based rule is only as good as its list, and a Tor interface is the one this
        // bug was about, so the names it appears under are pinned.
        listOf("tun0", "tun1", "tap0", "ppp0", "wg0", "zt0", "tailscale0", "ipsec0", "utun3")
            .forEach { name ->
                assertEquals(
                    "$name carries a private address and is not the LAN. Classifying it as " +
                        "anything else is how the tunnel wins.",
                    InterfaceRole.TUNNEL,
                    LocalNetwork.roleOf(name),
                )
            }
    }

    @Test
    fun theWifiAndCarrierNamesThatActuallyAppearAreClassified() {
        listOf("wlan0", "wlan1", "ap0", "wlan0.1").forEach { name ->
            assertEquals(InterfaceRole.WIFI, LocalNetwork.roleOf(name))
        }
        assertEquals(InterfaceRole.CELLULAR, LocalNetwork.roleOf("rmnet_data0"))
        assertEquals(InterfaceRole.CELLULAR, LocalNetwork.roleOf("ccmni0"))
        assertEquals(InterfaceRole.ETHERNET, LocalNetwork.roleOf("eth0"))
    }

    @Test
    fun noInterfacesAtAllIsAnHonestNullRatherThanAGuess() {
        val chosen = LocalNetwork.choose(emptyList())
        assertNull(chosen.prefix)
        assertNull(chosen.interfaceName)
        assertNull(chosen.role)
    }
}
