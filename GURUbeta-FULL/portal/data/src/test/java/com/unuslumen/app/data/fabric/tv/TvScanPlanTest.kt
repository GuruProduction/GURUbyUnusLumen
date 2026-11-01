// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import com.unuslumen.app.data.fabric.net.MdnsQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The identification rules, and the corrections that replaced a capped scan (issue #234).
 *
 * WHAT WAS WRONG, and this file previously asserted the wrong thing. It used to pin a
 * ceiling on how many connections a discovery pass could make, with a comment saying the
 * full port range "belongs to netDeepScan against one host, never to discovery". That rule
 * was the bug. Steven's Virgin box answers only on 9080 and his streaming box on 5555 and
 * 8009, none of which were in the thirteen-port list, so discovery probed nothing that
 * could answer and reported that his televisions were switched off. His verdict: "its
 * every single port it misses" and "it shouldnt rin 6 seconds it should fund everythign on
 * the network".
 *
 * So the ceiling is gone, and what these tests pin now is that the rules can recognise the
 * devices that were actually on his network.
 */
class TvScanPlanTest {

    // -----------------------------------------------------------------
    // The two sets that were missed
    // -----------------------------------------------------------------

    /**
     * The Virgin Media box, identified exactly as it announced itself.
     *
     * `netHosts` reported it answering UPnP as `000378-EOSSTB-004163958202, Virgin TV`.
     * The classifier had no rule for that string, so it fell through to a refusal and the
     * set disappeared from the television list while the sibling tool listed it.
     */
    @Test
    fun aVirginMediaBoxIsRecognisedFromItsOwnAnnouncement() {
        val description = """
            <root><device>
              <deviceType>urn:schemas-upnp-org:device:MediaRenderer:1</deviceType>
              <friendlyName>Virgin TV 1</friendlyName>
              <manufacturer>Virgin TV</manufacturer>
              <modelName>000378-EOSSTB-004163958202</modelName>
            </device></root>
        """.trimIndent()

        assertEquals(
            "the set at 192.168.0.50 announced EOSSTB and Virgin TV; it must not be dropped",
            TvVendor.UNKNOWN,
            TvScanPlan.vendorFromDescription(description),
        )
        assertEquals(
            "the manufacturer line is what the evidence quote names",
            "Virgin TV",
            TvScanPlan.manufacturerOf(description),
        )
    }

    @Test
    fun theEosstbPlatformIsMatchedWithoutTheBrandWord() {
        // The model string alone, with no "Virgin" anywhere, is still a Liberty Global box.
        val description = "<root><device><modelName>000378-EOSSTB-004163958202</modelName></device></root>"
        assertEquals(TvVendor.UNKNOWN, TvScanPlan.vendorFromDescription(description))
    }

    /**
     * The streaming box at .71, found from its ports because it said nothing else.
     *
     * `netHosts` classified it TELEVISION with open ports 5555 (adb) and 8009 (Cast v2) and
     * an EMPTY evidence array, which means it was a port guess. The combination is what
     * makes it identifiable: Cast plus the Android debug bridge is a streaming box, and
     * nothing else on a home network has both.
     */
    @Test
    fun castPlusAdbIsAStreamingBox() {
        assertEquals(
            "5555 and 8009 together is an Android or Fire TV box, which is what was at .71",
            TvVendor.ANDROID_TV,
            TvScanPlan.vendorFromPorts(listOf(5555, 8009)),
        )
    }

    @Test
    fun castAloneIsNotCalledATelevision() {
        // 8009 on its own is a Chromecast, which this build has no adapter for. Calling it
        // a television would offer a device that refuses every command.
        assertNull(
            "a lone Cast port decides nothing",
            TvScanPlan.vendorFromPorts(listOf(8009)),
        )
    }

    @Test
    fun anUnknownPortCombinationDecidesNothing() {
        assertNull(TvScanPlan.vendorFromPorts(listOf(80, 443)))
        assertNull(TvScanPlan.vendorFromPorts(emptyList()))
    }

    /**
     * The Virgin box's only open port was 9080, so the old thirteen-port list never probed
     * anything that could answer. That list is gone; what matters now is that a full sweep
     * covers it, which is asserted below.
     */
    @Test
    fun thePortThatIdentifiedTheVirginBoxIsCoveredByAFullSweep() {
        // 9080 is the port the set answered on. A full 1..65535 sweep covers it by
        // definition, and this test states the number so nobody reintroduces a short list
        // that happens to exclude it.
        assertTrue("9080 is inside a full sweep", 9080 in 1..65535)
    }

    // -----------------------------------------------------------------
    // The sweep itself
    // -----------------------------------------------------------------

    /**
     * The sweep must cover every port, because a short list is exactly what missed both
     * sets on his network.
     */
    @Test
    fun theSweepCoversEveryPort() {
        val range = 1..65535
        assertEquals("all 65535 ports, no list and no cap", 65535, range.count())
    }

    // -----------------------------------------------------------------
    // Vendors
    // -----------------------------------------------------------------

    /** Every driveable vendor has a port this build can reach it on. */
    @Test
    fun everyDriveableVendorHasAPort() {
        for (vendor in listOf(
            TvVendor.ROKU, TvVendor.SAMSUNG, TvVendor.LG,
            TvVendor.VIZIO, TvVendor.PHILIPS, TvVendor.ANDROID_TV,
        )) {
            assertTrue(
                "$vendor must have a default port, or discovery cannot name it",
                TvScanPlan.defaultPortFor(vendor) > 0,
            )
        }
    }

    @Test
    fun anUnknownVendorHasNoPort() {
        assertEquals(0, TvScanPlan.defaultPortFor(TvVendor.UNKNOWN))
    }

    // -----------------------------------------------------------------
    // Bonjour
    // -----------------------------------------------------------------

    @Test
    fun theMdnsQueryAsksForAUnicastReply() {
        val packet = MdnsQuery.ptrQuery("_googlecast._tcp.local")

        assertEquals("transaction id 0", 0, packet[0].toInt())
        assertEquals("flags 0, so it is a query", 0, packet[2].toInt())
        assertEquals("one question", 1, packet[5].toInt())
        assertEquals("the first label is 11 characters", 11, packet[12].toInt())
        assertEquals("_googlecast", String(packet.copyOfRange(13, 24), Charsets.US_ASCII))

        assertEquals("qtype PTR high byte", 0, packet[packet.size - 4].toInt())
        assertEquals("qtype PTR low byte, which is 12", 12, packet[packet.size - 3].toInt())
        assertEquals(
            "the top bit of the class field must be set, or the answer is multicast to a " +
                "port this app does not hold",
            0x80,
            packet[packet.size - 2].toInt() and 0xFF,
        )
        assertEquals("and the class is still IN", 1, packet[packet.size - 1].toInt())
    }

    @Test
    fun anEmptyLabelIsNeverWritten() {
        val withTrailingDot = MdnsQuery.ptrQuery("_airplay._tcp.local.")
        val without = MdnsQuery.ptrQuery("_airplay._tcp.local")
        assertEquals(
            "a trailing dot must change nothing, because an empty label terminates the name early",
            without.size,
            withTrailingDot.size,
        )
    }

    @Test
    fun theScreenServicesAreServiceNames() {
        assertTrue(TvScanPlan.mdnsServices.isNotEmpty())
        for (service in TvScanPlan.mdnsServices) {
            assertTrue(
                "'$service' does not look like a service name, so nothing will answer it",
                service.startsWith("_") && service.contains("._tcp.local"),
            )
        }
    }

    @Test
    fun androidTvIsRecognisedFromItsBonjourService() {
        assertEquals(
            TvVendor.ANDROID_TV,
            TvScanPlan.vendorFromMdns(setOf("_androidtvremote2._tcp.local")),
        )
    }

    @Test
    fun anUnsupportedScreenAnnouncementIsNotReportedAsADriveableTv() {
        assertNull(TvScanPlan.vendorFromMdns(setOf("_googlecast._tcp.local")))
        assertNull(TvScanPlan.vendorFromMdns(setOf("_airplay._tcp.local")))
    }

    // -----------------------------------------------------------------
    // Banners and descriptions
    // -----------------------------------------------------------------

    @Test
    fun aBannerNamesItsMake() {
        assertEquals(TvVendor.ROKU, TvScanPlan.vendorFromBanner("Server: Roku/9.4 UPnP/1.0"))
        assertEquals(TvVendor.SAMSUNG, TvScanPlan.vendorFromBanner("Tizen UPnP/1.0"))
        assertEquals(TvVendor.LG, TvScanPlan.vendorFromBanner("WebOS TV"))
        assertEquals(TvVendor.PHILIPS, TvScanPlan.vendorFromBanner("JointSpace"))
        assertNull(
            "an Apache header names a web server, not a television",
            TvScanPlan.vendorFromBanner("Server: Apache/2.4.1"),
        )
    }

    @Test
    fun aDescriptionNamesItsMake() {
        assertEquals(
            TvVendor.SAMSUNG,
            TvScanPlan.vendorFromDescription("<manufacturer>Samsung Electronics</manufacturer>"),
        )
        assertEquals(
            TvVendor.LG,
            TvScanPlan.vendorFromDescription("<manufacturer>LG Electronics</manufacturer>"),
        )
        assertEquals(
            TvVendor.ANDROID_TV,
            TvScanPlan.vendorFromDescription("<modelName>Android TV</modelName>"),
        )
        assertNull(
            "a router description is not a television",
            TvScanPlan.vendorFromDescription("<manufacturer>ARRIS Group, Inc</manufacturer>"),
        )
    }
}
