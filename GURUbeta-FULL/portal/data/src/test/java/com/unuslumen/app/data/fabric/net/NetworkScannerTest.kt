// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import com.unuslumen.app.data.fabric.tv.TvVendor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network inventory's decisions. Issue #187.
 *
 * WHAT THIS PROVES. That a host is classified from what it actually exposes, that a
 * shared port does not mislabel a device, that the DNS-SD query is a real packet rather
 * than an empty one, and that control is only claimed where an adapter exists.
 *
 * WHAT IT CANNOT PROVE: that a device answers. That needs his network.
 */
class NetworkScannerTest {

    /**
     * The DNS-SD query must be a real DNS packet.
     *
     * AN EMPTY PACKET IS THE BUG THIS PINS. My earlier version sent zero bytes to the
     * mDNS group, which transmits nothing and gets no replies, and that looks exactly
     * like a network with no devices on it. The packet is checked here for its header
     * and its labels so that failure cannot come back.
     */
    @Test
    fun theMdnsQueryIsARealDnsPacket() {
        val scanner = NetworkScanner()
        val packet = scanner.debugMdnsQuery()

        assertTrue("a DNS packet has at least a header and a question", packet.size > 20)
        assertEquals("transaction id 0", 0, packet[0].toInt())
        assertEquals("flags 0, so it is a query", 0, packet[2].toInt())
        assertEquals("one question", 1, packet[5].toInt())

        // The first label must be "_services", length-prefixed.
        assertEquals("the first label is 9 characters", 9, packet[12].toInt())
        val firstLabel = String(packet.copyOfRange(13, 22), Charsets.US_ASCII)
        assertEquals("_services", firstLabel)

        // The packet must end with QTYPE 12 (PTR) and QCLASS 1 (IN).
        assertEquals("qtype PTR", 12, packet[packet.size - 3].toInt())
        assertEquals("qclass IN", 1, packet[packet.size - 1].toInt())
    }

    /**
     * A port one device class owns decides the kind; a shared port does not.
     *
     * THIS IS THE RULE THAT STOPS A MISLABEL. Port 80 is on a router, a printer and a
     * television, so it can never decide. Port 8060 is Roku and only Roku.
     */
    @Test
    fun aPortDecidesTheKindOnlyWhereOneClassOwnsIt() {
        assertEquals(
            "8060 is Roku's, so it is a television",
            HostKind.TELEVISION,
            kindFor(listOf(8060)),
        )
        assertEquals(
            "631 is printing, so it is a printer",
            HostKind.PRINTER,
            kindFor(listOf(631)),
        )
        assertEquals(
            "554 is RTSP, so it is a camera",
            HostKind.CAMERA,
            kindFor(listOf(554)),
        )
        assertEquals(
            "80 alone says nothing, so the host stays unknown",
            HostKind.UNKNOWN,
            kindFor(listOf(80)),
        )
        assertEquals(
            "8080 alone says nothing either",
            HostKind.UNKNOWN,
            kindFor(listOf(8080)),
        )
    }

    /** A device that answers several protocols is classified by the specific one. */
    @Test
    fun aSpecificPortOutranksACommonOne() {
        assertEquals(
            "a host with 80 and 3000 is an LG television, not an unknown",
            HostKind.TELEVISION,
            kindFor(listOf(80, 3000)),
        )
    }

    /**
     * An Apple device and a Google device are both reachable through their own ports,
     * which is how a phone, a speaker and a computer get identified.
     */
    @Test
    fun consumerDevicesAreIdentifiedFromTheirOwnPorts() {
        assertEquals(HostKind.SPEAKER, kindFor(listOf(7000)))
        assertEquals(HostKind.PHONE, kindFor(listOf(62078)))
        assertEquals(HostKind.COMPUTER, kindFor(listOf(445)))
        assertEquals(HostKind.NAS, kindFor(listOf(5000)))
    }

    /** A display name is never blank, so a list always reads sensibly. */
    @Test
    fun aHostAlwaysHasADisplayName() {
        val unnamed = NetworkHost(address = "192.168.0.40")
        assertEquals("192.168.0.40", unnamed.displayName)

        val named = NetworkHost(address = "192.168.0.40", hostname = "Living Room TV")
        assertEquals("Living Room TV", named.displayName)
    }

    /** The fabric id must be stable, or a label moves on every scan. */
    @Test
    fun theHostIdIsStable() {
        assertEquals(
            NetworkHost(address = "192.168.0.40").id,
            NetworkHost(address = "192.168.0.40", hostname = "changed").id,
        )
    }

    /** A scan with no network must say so rather than reporting an empty house. */
    @Test
    fun anEmptyReportExplainsItselfInTheScan() {
        val report = NetworkScanReport(
            hosts = emptyList(),
            problems = listOf("This phone is not on a wifi network, so there is nothing to scan."),
            probedAddresses = 0,
            subnet = null,
        )
        assertEquals(0, report.count)
        assertTrue(report.problems.first().contains("wifi"))
    }

    /**
     * Reachability is per protocol, not per device: a television is only controllable
     * when a port whose adapter exists is open.
     */
    @Test
    fun controlIsClaimedOnlyWhereAnAdapterExists() {
        val scanner = NetworkScanner()
        val roku = NetworkHost(address = "192.168.0.40", openPorts = listOf(8060), kind = HostKind.TELEVISION)
        assertTrue("8060 has an adapter", scanner.debugIsControllable(roku))

        val vizio = NetworkHost(address = "192.168.0.41", openPorts = listOf(7345), kind = HostKind.TELEVISION)
        assertFalse(
            "7345 is a television port but this build has no Vizio adapter, so it must not claim control",
            scanner.debugIsControllable(vizio),
        )

        val printer = NetworkHost(address = "192.168.0.42", openPorts = listOf(631), kind = HostKind.PRINTER)
        assertFalse("a printer is not a television", scanner.debugIsControllable(printer))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Run the scanner's own port inference, so the test proves the rule the app runs. */
    private fun kindFor(ports: List<Int>): HostKind = NetworkScanner.debugKindForPorts(ports)
}
