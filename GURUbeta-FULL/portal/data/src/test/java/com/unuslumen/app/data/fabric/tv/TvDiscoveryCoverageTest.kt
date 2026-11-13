// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Holds the coverage rule, because issue 331 was `tvDiscover` finding one of three DIAL
 * televisions and none of two AirPlay receivers.
 *
 * THE THREE MISSES, EACH PROVEN HERE:
 *
 *  1. A DIAL announcement with no description document was refused, because the old rule
 *     demanded a friendly name and a friendly name only arrives with the description. Both
 *     Virgin boxes answered SSDP and were dropped one line later.
 *  2. An AirPlay service was queried over Bonjour and then mapped to no vendor, so both
 *     receivers were discarded after spending the network's power to reply.
 *  3. An unknown screen was given control port 0, which no command can address.
 *
 * No address literals are used: the privacy gate refuses any that is not documentation, and
 * a fixture address is indistinguishable from a real one in a diff.
 */
class TvDiscoveryCoverageTest {

    private fun ssdp(st: String, server: String = "Linux/1.0 UPnP/1.1 SomeStack/1.0") =
        """
        HTTP/1.1 200 OK
        SERVER: $server
        ST: $st
        USN: uuid:example::$st
        """.trimIndent()

    @Test
    fun aDialAnnouncementIsAScreenEvenWithNoDescriptionFetched() {
        // The Virgin box case: DIAL declared, description unreachable, name unknown.
        val found = TvDiscoveryClassifier.classify(
            host = "203.0.113.50",
            ssdpBody = ssdp("urn:dial-multiscreen-org:service:dial:1"),
            descriptionXml = null,
        )

        assertNotNull(
            "A device that declared the DIAL multiscreen service is a screen the moment it " +
                "answers. Requiring a friendly name here is what dropped both Virgin boxes.",
            found,
        )
        assertEquals(TvVendor.UNKNOWN, found!!.vendor)
    }

    @Test
    fun aDialAnnouncementGetsARealControlPortRatherThanZero() {
        val found = TvDiscoveryClassifier.classify(
            host = "203.0.113.50",
            ssdpBody = ssdp("urn:dial-multiscreen-org:service:dial:1"),
            descriptionXml = null,
        )!!

        assertEquals(
            "DIAL is served on 8009 by every device that speaks it. A port of 0 is not a " +
                "control port and cannot be addressed by any command.",
            8009,
            found.port,
        )
    }

    @Test
    fun aFireTvIsStillNamedAmazonRatherThanUnknown() {
        // The control: making DIAL acceptable must not swallow the one set that was already
        // identified correctly.
        val description = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <friendlyName>Snewman's Fire TV</friendlyName>
              <manufacturer>Amazon</manufacturer>
              <modelName>AFTKM</modelName>
            </device></root>
        """.trimIndent()

        val found = TvDiscoveryClassifier.classify(
            host = "203.0.113.71",
            ssdpBody = ssdp("urn:dial-multiscreen-org:service:dial:1"),
            descriptionXml = description,
        )!!

        assertEquals(TvVendor.AMAZON_FIRE_TV, found.vendor)
        assertEquals(8009, found.port)
    }

    @Test
    fun anAirplayAnnouncementIsAScreenRatherThanNothing() {
        val found = TvDiscoveryClassifier.classify(
            host = "203.0.113.210",
            ssdpBody = ssdp("_airplay._tcp.local"),
            descriptionXml = null,
        )

        assertNotNull(
            "An AirPlay service is a screen announcing itself. Returning null here is why " +
                "both receivers were queried and then discarded.",
            found,
        )
        assertEquals(TvVendor.UNKNOWN, found!!.vendor)
    }

    @Test
    fun anAirplaySetReportsItsRealPort() {
        val found = TvDiscoveryClassifier.classify(
            host = "203.0.113.210",
            ssdpBody = ssdp("_airplay._tcp.local"),
            descriptionXml = null,
        )!!

        assertEquals(
            "AirPlay control is RTSP on 7000, which the announcement itself names.",
            7000,
            found.port,
        )
    }

    @Test
    fun theBonjourRuleAcceptsAirplayInsteadOfReturningNull() {
        assertEquals(
            "_airplay was queried and then mapped to nothing, so the reply was thrown away " +
                "after the network spent power answering it.",
            TvVendor.UNKNOWN,
            TvScanPlan.vendorFromMdns(setOf("_airplay._tcp.local")),
        )
        assertEquals(
            TvVendor.UNKNOWN,
            TvScanPlan.vendorFromMdns(setOf("_raop._tcp.local")),
        )
    }

    @Test
    fun androidTvStillWinsOverAirplayWhenBothAreAnnounced() {
        // A set that offers both is a set this build can actually drive, so the drivable
        // identity must not be downgraded by the weaker one.
        assertEquals(
            TvVendor.ANDROID_TV,
            TvScanPlan.vendorFromMdns(
                setOf("_androidtvremote2._tcp.local", "_airplay._tcp.local"),
            ),
        )
    }

    @Test
    fun aChromecastIsStillNotClaimedAsATelevision() {
        // The guard that keeps the list honest must survive. Google Cast is on speakers and
        // displays alike and names no screen identity, so it is not a television.
        assertNull(TvScanPlan.vendorFromMdns(setOf("_googlecast._tcp.local")))
    }

    @Test
    fun aPrinterAnsweringDialWithNoScreenIdentityIsStillRefused() {
        // Widening what counts as a screen must not let every UPnP device in. A printer that
        // answered a DIAL search target but carries no DIAL service type and no screen
        // string is not a television.
        assertNull(
            TvDiscoveryClassifier.classify(
                host = "203.0.113.60",
                ssdpBody = ssdp("urn:schemas-upnp-org:device:Printer:1"),
                descriptionXml = null,
            ),
        )
    }

    @Test
    fun theAirplayServicesAreActuallyQueried() {
        // A rule that recognises an announcement is useless if the announcement is never
        // asked for. Both names must be in the query set.
        val services = TvScanPlan.mdnsServices
        assertEquals(
            "The AirPlay service must be queried or the rule above can never fire.",
            true,
            services.any { it.startsWith("_airplay") },
        )
        assertEquals(
            true,
            services.any { it.startsWith("_raop") },
        )
    }
}
