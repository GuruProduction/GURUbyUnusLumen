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
 * The discovery cost and the Bonjour question. Issue #225.
 *
 * WHY THIS EXISTS AS A TEST RATHER THAN AS A COMMENT. The defect was a scan that ran for
 * hours, and the reason it ran for hours was that nothing pinned how many connections a
 * discovery pass makes. A number in a test is the only thing that stops the next person
 * quietly adding the full port range back.
 *
 * WHAT IT PROVES. That a scan is bounded to a number a phone can actually do, that the
 * port list contains every vendor's remote port, and that the mDNS query asks for a
 * unicast reply, which is the one bit that makes Bonjour work from an app.
 *
 * WHAT IT CANNOT PROVE. That a set answers. That needs his telly.
 */
class TvScanPlanTest {

    /**
     * A discovery pass must be cheap enough to run whenever somebody asks.
     *
     * THE NUMBER. A /24 is 254 addresses. The old sweep multiplied that by 65535, which is
     * sixteen and a half million connections and hours of wall clock. The ceiling asserted
     * here is ten thousand, which leaves room to add a few ports and no room to re-add the
     * full range.
     */
    @Test
    fun aDiscoveryPassIsBoundedToSomethingAPhoneCanDo() {
        val connections = TvScanPlan.probeCount(254)
        assertTrue(
            "a discovery pass made $connections connections, which is the runaway scan again. " +
                "The full port range belongs to netDeepScan against one host, never to discovery.",
            connections <= 10_000,
        )
        assertTrue(
            "and it must still be wide enough to be worth running, and got $connections",
            connections >= 2_000,
        )
    }

    /**
     * Every vendor's own remote port must be probed, or a set on it is only found by luck.
     *
     * This is the list the sweep exists for, so a vendor added to the enum without its port
     * being here is a make that silently never appears.
     */
    @Test
    fun everyDriveableVendorPortIsProbed() {
        val driveable = listOf(
            TvVendor.ROKU, TvVendor.SAMSUNG, TvVendor.LG,
            TvVendor.VIZIO, TvVendor.PHILIPS, TvVendor.ANDROID_TV,
        )
        for (vendor in driveable) {
            val port = TvScanPlan.defaultPortFor(vendor)
            assertTrue(
                "$vendor answers on $port, which is not in the probe list, so a set of that " +
                    "make would never be found",
                port in TvScanPlan.probePorts,
            )
        }
    }

    /** A make nobody has an adapter for has no default port to answer on. */
    @Test
    fun anUnknownVendorHasNoPort() {
        assertEquals(0, TvScanPlan.defaultPortFor(TvVendor.UNKNOWN))
    }

    /**
     * The Bonjour query must ask for a unicast reply.
     *
     * THIS BIT IS THE WHOLE REASON BONJOUR WORKS. A standard responder multicasts its answer
     * to 224.0.0.251:5353, and a phone app holding an ephemeral socket never sees it, which
     * is exactly the "mDNS found nothing" result from the first device test. Class 0x8001
     * asks for the answer to come straight back.
     */
    @Test
    fun theMdnsQueryAsksForAUnicastReply() {
        val packet = MdnsQuery.ptrQuery("_googlecast._tcp.local")

        assertEquals("transaction id 0", 0, packet[0].toInt())
        assertEquals("flags 0, so it is a query", 0, packet[2].toInt())
        assertEquals("one question", 1, packet[5].toInt())

        assertEquals("the first label is 11 characters", 11, packet[12].toInt())
        assertEquals(
            "_googlecast",
            String(packet.copyOfRange(13, 24), Charsets.US_ASCII),
        )

        // QTYPE PTR is 12, and QCLASS must carry the unicast-response bit. Both are 16-bit
        // fields, so the value sits in the second of each pair of bytes.
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

    /** A name with a trailing dot must not produce an empty label in the middle of the packet. */
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

    /** Every screen service the discovery asks about is a real service name. */
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

    /**
     * An Android TV announces itself over Bonjour and nothing else, so the service maps to
     * a vendor this build can name.
     */
    @Test
    fun androidTvIsRecognisedFromItsBonjourService() {
        assertEquals(
            TvVendor.ANDROID_TV,
            TvScanPlan.vendorFromMdns(setOf("_androidtvremote2._tcp.local")),
        )
    }

    /**
     * A Chromecast and an Apple TV announce themselves and have no adapter here, so they
     * must not be reported as televisions that then refuse every command.
     */
    @Test
    fun anUnsupportedScreenAnnouncementIsNotReportedAsADriveableTv() {
        assertNull(TvScanPlan.vendorFromMdns(setOf("_googlecast._tcp.local")))
        assertNull(TvScanPlan.vendorFromMdns(setOf("_airplay._tcp.local")))
    }

    /** A banner naming a make is classified by it. */
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
}
