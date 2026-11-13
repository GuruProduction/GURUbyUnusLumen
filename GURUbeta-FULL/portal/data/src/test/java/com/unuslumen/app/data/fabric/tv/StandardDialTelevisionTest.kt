// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three advertisements from issue 219, held as tests.
 *
 * WHAT THE TICKET REPORTED. `tvDiscover` returned one of three televisions that were live on
 * the same network in the same minute, and reported `problems: []` so nothing said the other
 * two had been seen and dropped. The difference it identified was the device type: the Roku
 * reports `urn:roku-com:device:player:1-0` and was kept, while both dropped sets report
 * `urn:schemas-upnp-org:device:tvdevice:1`, the standard UPnP type for a television.
 *
 * WHAT IS ALREADY FIXED, AND WHAT WAS NOT. The drops themselves are gone: #331 stopped
 * requiring a friendly name before accepting a DIAL announcement, so all three now classify.
 * `needsPairing` did not follow, because the Virgin boxes are `UNKNOWN` and the old rule gave
 * every unknown vendor a pairing requirement. That is the same false requirement #243 and #250
 * both named, one vendor further along, and it is what this change fixes.
 */
class StandardDialTelevisionTest {

    /** Every one of the three advertises this and nothing else in the ST header. */
    private val dial = "HTTP/1.1 200 OK\r\n" +
        "SERVER: Linux/1.0 UPnP/1.1\r\n" +
        "ST: urn:dial-multiscreen-org:service:dial:1\r\n\r\n"

    private val host = "203.0.113.5"

    /** The Virgin box's document from the ticket, verbatim in shape. */
    private val virgin = """
        <root xmlns="urn:schemas-upnp-org:device-1-0"
              xmlns:r="urn:restful-tv-org:schemas:upnp-dd"><device>
          <deviceType>urn:schemas-upnp-org:device:tvdevice:1</deviceType>
          <friendlyName>Virgin TV 2</friendlyName>
          <manufacturer>Virgin TV</manufacturer>
          <modelName>EOS-1008C</modelName>
        </device></root>
    """.trimIndent()

    /** The Roku's document, which is the one that was always kept. */
    private val roku = """
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
          <deviceType>urn:roku-com:device:player:1-0</deviceType>
          <friendlyName>Room TV</friendlyName>
          <manufacturer>SHARP</manufacturer>
          <modelName>GE30X</modelName>
        </device></root>
    """.trimIndent()

    /** A standard UPnP television that declares no make at all. */
    private val namelessStandardTv = """
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
          <deviceType>urn:schemas-upnp-org:device:tvdevice:1</deviceType>
          <friendlyName>Bedroom TV</friendlyName>
        </device></root>
    """.trimIndent()

    @Test
    fun allThreeAdvertisementsProduceATelevision() {
        // The headline defect: one of three, with no indication the other two existed.
        listOf("virgin" to virgin, "roku" to roku, "nameless" to namelessStandardTv)
            .forEach { (label, description) ->
                assertNotNull(
                    "$label answered a DIAL advertisement and served a description, so it is " +
                        "a television. Dropping it is the defect #219 reports.",
                    TvDiscoveryClassifier.classify(host, dial, description),
                )
            }
    }

    @Test
    fun aStandardTvdeviceTypeIsAcceptedWithNoMakeNamed() {
        // A set can be a television by device type alone. This is what the ticket calls out
        // as the difference between the one kept and the two dropped.
        val found = TvDiscoveryClassifier.classify(host, dial, namelessStandardTv)
        assertEquals(TvVendor.UNKNOWN, found?.vendor)
        assertEquals(
            "A set that speaks DIAL is reachable on the DIAL endpoint, whatever its make.",
            8009,
            found?.port,
        )
    }

    @Test
    fun aVirginBoxDoesNotClaimAPairingRequirement() {
        // The false requirement #243 and #250 both named, at the vendor neither of them covered.
        val found = TvDiscoveryClassifier.classify(host, dial, virgin)!!
        assertFalse(
            "A DIAL launch is an unauthenticated POST, so there is no pairing step. Telling " +
                "a person to accept a prompt that never appears is the defect.",
            found.needsPairing,
        )
    }

    @Test
    fun aDialSetWithNoFetchableDescriptionAlsoClaimsNoPairing() {
        // The case that actually bit on this network: the description never arrived, so the
        // device was classified from its announcement alone.
        val found = TvDiscoveryClassifier.classify(host, dial, null)!!
        assertFalse(found.needsPairing)
    }

    @Test
    fun theRokuIsStillDrivenOnItsOwnPortAndNeedsNoPairing() {
        // The one that always worked must not be disturbed by the change.
        val found = TvDiscoveryClassifier.classify(host, dial, roku)!!
        assertEquals(TvVendor.ROKU, found.vendor)
        assertEquals(8060, found.port)
        assertFalse(found.needsPairing)
    }

    @Test
    fun aPrinterAnsweringTheSameAdvertisementIsStillRefused() {
        // Widening what a DIAL advertisement proves must not admit everything that answers it.
        assertNull(
            TvDiscoveryClassifier.classify(
                host,
                dial,
                "<root><device><friendlyName>Office Printer</friendlyName>" +
                    "<manufacturer>HP</manufacturer></device></root>",
            ),
        )
    }

    @Test
    fun theRokuNameFromTheTicketRoundTrips() {
        // The ticket's Roku name is the encoded one from #223, and it is in this document too.
        val encoded = roku.replace("Room TV", "Room&apos;s TV")
        val found = TvDiscoveryClassifier.classify(host, dial, encoded)!!
        assertTrue(
            "The name must be the one a person can type back. Got: ${found.name}",
            found.name == "Room's TV",
        )
    }

    private fun assertNull(value: Any?) {
        org.junit.Assert.assertNull(value)
    }
}
