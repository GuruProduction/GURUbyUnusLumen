// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.discovery.ArpDiscoveryManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the subnet arithmetic, because issue 34 was a mask that described the
 * entire IPv4 space.
 *
 * WHAT WAS BROKEN. `networkScan` reported `192.168.0.179/0.0.0.0`. Two faults
 * combined: `dhcpInfo.netmask` is deprecated and returns zero on modern Android,
 * and the integer-to-quad converter read the bytes in the wrong order. Either
 * one alone produces an unusable subnet.
 *
 * The fix reads `ip -4 addr`, which reports the real prefix, and these tests hold
 * the arithmetic that turns an address plus prefix into a network and a
 * membership test.
 */
class InterfaceAddressTest {

    @Test
    fun aSlashTwentyFourProducesTheNetworkNotTheHostAddress() {
        val a = ArpDiscoveryManager.InterfaceAddress("192.168.0.179", 24)
        assertEquals(
            "The reported subnet must be the NETWORK, not the host address. " +
                "Reporting the host as a subnet was half of issue 34.",
            "192.168.0.0/24",
            a.network,
        )
    }

    @Test
    fun aZeroPrefixNeverAppearsAsAValidMaskForALanAddress() {
        val a = ArpDiscoveryManager.InterfaceAddress("192.168.0.179", 24)
        assertFalse(
            "A /0 subnet describes the whole IPv4 space and is never a LAN answer.",
            a.network.endsWith("/0"),
        )
    }

    @Test
    fun membershipIsLimitedToTheRealPrefix() {
        val a = ArpDiscoveryManager.InterfaceAddress("192.168.0.179", 24)
        assertTrue("own address is on the network", a.contains("192.168.0.179"))
        assertTrue("gateway is on the network", a.contains("192.168.0.1"))
        assertFalse(
            "a stale ARP entry from another network must not be reported as a local device",
            a.contains("10.0.0.5"),
        )
        assertFalse("a different /24 is not local", a.contains("192.168.1.20"))
    }

    @Test
    fun aSlashSixteenKeepsTheWiderNetwork() {
        val a = ArpDiscoveryManager.InterfaceAddress("172.16.5.9", 16)
        assertEquals("172.16.0.0/16", a.network)
        assertTrue(a.contains("172.16.99.4"))
        assertFalse(a.contains("172.17.0.1"))
    }

    @Test
    fun aMalformedAddressDoesNotThrow() {
        val a = ArpDiscoveryManager.InterfaceAddress("not-an-ip", 24)
        assertFalse("garbage must never match", a.contains("192.168.0.1"))
    }
}
