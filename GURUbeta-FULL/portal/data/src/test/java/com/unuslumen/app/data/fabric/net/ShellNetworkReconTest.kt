// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The recon parsers, pinned against real command output. Requirement 1 and 2 of the spec.
 *
 * WHY THESE ARE PARSERS AND NOT STRING MATCHING IN A TOOL. `/proc/net/arp` has a flag field
 * that decides whether an entry is a real device or an unanswered question, and getting that
 * wrong puts ghosts in the list. `ip -o addr show` carries the prefix, which is the only way
 * to know the real subnet rather than assuming a /24. Both are text, so both are provable
 * here without a device.
 */
class ShellNetworkReconTest {

    private val recon = ShellNetworkRecon()

    // ------------------------------------------------------------------
    // /proc/net/arp
    // ------------------------------------------------------------------

    /** Real output from an Android device, header included. */
    private val realArp = """
        IP address       HW type     Flags       HW address            Mask     Device
        192.168.0.1      0x1         0x2         a4:05:d6:24:4c:9a     *        wlan0
        192.168.0.27     0x1         0x2         b6:3f:9d:49:ab:57     *        wlan0
        192.168.0.115    0x1         0x2         d8:31:34:aa:bb:cc     *        wlan0
        192.168.0.200    0x1         0x0         00:00:00:00:00:00     *        wlan0
    """.trimIndent()

    @Test
    fun theArpTableIsParsedWithItsFlags() {
        val neighbours = recon.parseArp(realArp)
        assertEquals("three resolved neighbours, not four", 3, neighbours.size)

        val router = neighbours.first { it.address == "192.168.0.1" }
        assertEquals("a4:05:d6:24:4c:9a", router.mac)
        assertEquals("wlan0", router.device)

        // The Roku's prefix is registered, so a mystery address becomes a name. Not every
        // OUI is in the table and an unregistered one must come back null rather than
        // being guessed at, which is why the router is not asserted to resolve.
        val roku = neighbours.first { it.address == "192.168.0.115" }
        assertEquals("Roku", roku.vendor)
    }

    /**
     * AN ENTRY WITH FLAG 0x0 IS NOT A DEVICE. The kernel creates it when it asks and gets no
     * answer, so its MAC is all zeros. Counting it puts a ghost in the list that a person
     * then tries to control.
     */
    @Test
    fun anUnresolvedArpEntryIsNotADevice() {
        val neighbours = recon.parseArp(realArp)
        assertTrue(
            "the unresolved 192.168.0.200 must be absent",
            neighbours.none { it.address == "192.168.0.200" },
        )
    }

    /** The header line must never be read as a device. */
    @Test
    fun theArpHeaderIsSkipped() {
        assertTrue(recon.parseArp(realArp).none { it.address.startsWith("IP") })
    }

    /** Empty output is an empty list, not a crash. */
    @Test
    fun emptyArpOutputYieldsNoNeighbours() {
        assertTrue(recon.parseArp("").isEmpty())
        assertTrue(recon.parseArp("IP address       HW type     Flags       HW address").isEmpty())
    }

    /** A vendor must be named from the MAC prefix, which is how a mystery host is identified. */
    @Test
    fun aMacPrefixNamesTheVendor() {
        assertEquals("Roku", MacVendor.lookup("d8:31:34:aa:bb:cc"))
        assertEquals("Roku", MacVendor.lookup("D8:31:34:AA:BB:CC"))
        assertEquals("Roku", MacVendor.lookup("d8-31-34-aa-bb-cc"))
        assertEquals("Samsung", MacVendor.lookup("78:1f:db:11:22:33"))
        assertEquals("Amazon", MacVendor.lookup("74:c2:46:00:00:01"))
        assertNull("an unregistered prefix must not be guessed", MacVendor.lookup("02:00:00:00:00:01"))
        assertTrue("the table must not be accidentally empty", MacVendor.size > 50)
    }

    // ------------------------------------------------------------------
    // ip neigh
    // ------------------------------------------------------------------

    /** The modern spelling of the same fact, with its state word. */
    @Test
    fun ipNeighIsParsed() {
        val raw = """
            192.168.0.1 dev wlan0 lladdr a4:05:d6:24:4c:9a REACHABLE
            192.168.0.115 dev wlan0 lladdr d8:31:34:aa:bb:cc STALE
            192.168.0.300 dev wlan0 FAILED
        """.trimIndent()
        val neighbours = recon.parseIpNeigh(raw)
        assertEquals("two with a lladdr", 2, neighbours.size)
        assertEquals("192.168.0.115", neighbours[1].address)
        assertEquals("d8:31:34:aa:bb:cc", neighbours[1].mac)
    }

    /** A FAILED line has no lladdr, so it is not a device. */
    @Test
    fun aFailedNeighbourIsNotADevice() {
        val raw = "192.168.0.300 dev wlan0 FAILED"
        assertTrue(recon.parseIpNeigh(raw).isEmpty())
    }

    // ------------------------------------------------------------------
    // ip -o addr show
    // ------------------------------------------------------------------

    /** The interface list gives the real subnet rather than an assumed /24. */
    @Test
    fun theInterfaceListGivesTheRealSubnet() {
        val raw = """
            1: lo    inet 127.0.0.1/8 scope host lo\       valid_lft forever
            2: dummy0    inet 192.168.7.1/24 scope global dummy0\       valid_lft forever
            3: wlan0    inet 192.168.0.179/24 brd 192.168.0.255 scope global wlan0\       valid_lft forever
            4: rmnet0    inet 10.123.45.67/30 scope global rmnet0\       valid_lft forever
        """.trimIndent()
        val interfaces = recon.parseInterfaces(raw)

        // Four, including loopback, because the parse reports every IPv4 interface the
        // command returned rather than filtering one out. Loopback is reported and a caller
        // decides what to do with it: hiding it would be the parser making a policy choice.
        assertEquals("four IPv4 interfaces including loopback", 4, interfaces.size)
        val wlan = interfaces.first { it.name == "wlan0" }
        assertEquals("192.168.0.179", wlan.address)
        assertEquals(24, wlan.prefix)
        assertEquals("192.168.0.0", wlan.subnet)

        val mobile = interfaces.first { it.name == "rmnet0" }
        assertEquals(
            "a /30 network must be computed, not assumed to be /24",
            "10.123.45.64",
            mobile.subnet,
        )
    }

    /** The subnet arithmetic must be real, because a wrong one scans the wrong network. */
    @Test
    fun theSubnetArithmeticIsCorrect() {
        assertEquals("192.168.0.0", recon.subnetFor("192.168.0.179", 24))
        assertEquals("10.0.0.0", recon.subnetFor("10.1.2.3", 8))
        assertEquals("172.16.0.0", recon.subnetFor("172.16.5.9", 12))
        assertEquals("192.168.1.128", recon.subnetFor("192.168.1.200", 25))
        assertEquals("172.16.0.0", recon.subnetFor("172.16.5.9", 16))
        assertNull("a bad prefix is refused", recon.subnetFor("192.168.0.1", 33))
        assertNull("a bad address is refused", recon.subnetFor("not.an.address", 24))
    }

    /** A loopback address is not a network to scan. */
    @Test
    fun loopbackIsReportedButIsNotTheLocalNetwork() {
        val raw = "1: lo    inet 127.0.0.1/8 scope host lo"
        val interfaces = recon.parseInterfaces(raw)
        assertEquals(1, interfaces.size)
        assertEquals("127.0.0.0", interfaces.first().subnet)
    }

    // ------------------------------------------------------------------
    // Deep scan service names
    // ------------------------------------------------------------------

    /** A port must be named in a result, because a bare number tells nobody anything. */
    @Test
    fun everyPortThatMattersHasAServiceName() {
        assertEquals("roku-ecp", ServiceNames.forPort(8060))
        assertEquals("samsung-remote", ServiceNames.forPort(8001))
        assertEquals("lg-webos", ServiceNames.forPort(3000))
        assertEquals("androidtv-remote", ServiceNames.forPort(6466))
        assertEquals("rtsp", ServiceNames.forPort(554))
        assertEquals("ipp", ServiceNames.forPort(631))
        assertEquals("smb", ServiceNames.forPort(445))
        assertEquals(
            "an unassigned port must say so rather than inventing a name",
            "unassigned",
            ServiceNames.forPort(54321),
        )
    }

    /** The service table must cover the ports a home network actually exposes. */
    @Test
    fun theServiceTableCoversTheHouse() {
        for (port in listOf(80, 443, 22, 445, 554, 631, 1900, 5353, 8009, 8060, 8001, 3000, 6466, 9100)) {
            assertTrue(
                "port $port must have a name, or a scan result explains nothing",
                ServiceNames.forPort(port) != "unassigned",
            )
        }
    }
}
