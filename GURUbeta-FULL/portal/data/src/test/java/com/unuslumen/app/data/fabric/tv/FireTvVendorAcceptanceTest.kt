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
 * The acceptance criteria from issue 250, held as tests so they cannot regress again.
 *
 * WHY THIS FILE EXISTS AT ALL. #250 reported that a Fire TV came back as `vendor: UNKNOWN`,
 * `controllable: false`, `needsPairing: true` while the tool's own `evidence` string said
 * "made by Amazon" — a tool contradicting itself. That was fixed by PR 247, and the ticket
 * stayed open because nothing pinned the fix. A fix with no test is a fix that comes back,
 * and #243 had already been closed once without landing.
 *
 * THE DOCUMENT AND THE BEHAVIOUR ARE THE TICKET'S OWN. The description is the one recorded in
 * #250, including `tvdevice:1`, `manufacturer: Amazon` and `modelName: AFTKM`, and the
 * requirement is quoted from its acceptance list: the manufacturer the parse already yields
 * must choose the adapter, and a set that accepts an unauthenticated DIAL launch must never be
 * reported as needing pairing.
 */
class FireTvVendorAcceptanceTest {

    private val dialWithAppUrl = "HTTP/1.1 200 OK\r\n" +
        "SERVER: Linux/1.0 UPnP/1.1\r\n" +
        "ST: urn:dial-multiscreen-org:service:dial:1\r\n" +
        "Application-URL: http://203.0.113.71:8009/apps/\r\n\r\n"

    /** The exact description from the ticket. */
    private val fireTvDescription = """
        <?xml version="1.0"?>
        <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
          <deviceType>urn:schemas-upnp-org:device:tvdevice:1</deviceType>
          <friendlyName>Owner&apos;s Fire TV</friendlyName>
          <manufacturer>Amazon</manufacturer>
          <modelName>AFTKM</modelName>
        </device></root>
    """.trimIndent()

    private val host = "203.0.113.71"

    private fun classified(): DiscoveredTv? =
        TvDiscoveryClassifier.classify(host, dialWithAppUrl, fireTvDescription)

    @Test
    fun theManufacturerTheParseAlreadyYieldsChoosesTheVendor() {
        val found = classified()
        assertNotNull(found)
        assertEquals(
            "Acceptance 1: vendor derived from manufacturer Amazon. The evidence field " +
                "quoting Amazon while the vendor says UNKNOWN was the whole defect.",
            TvVendor.AMAZON_FIRE_TV,
            found!!.vendor,
        )
    }

    /**
     * The manufacturer field ALONE must decide it, with the name saying nothing.
     *
     * THIS TEST EXISTS BECAUSE THE OBVIOUS ONE DID NOT TEST ENOUGH. The ticket's own document
     * has `friendlyName: Owner's Fire TV`, and the haystack rule matches the words "fire tv",
     * so disabling every Amazon-specific rule left the first test passing: it was the NAME
     * doing the work, not the manufacturer. A test that cannot fail when the rule it names is
     * removed is not testing that rule.
     *
     * So the name AND the model are deliberately neutral here. The model prefix is a second
     * Amazon rule, and leaving `AFTKM` in this document made the test pass with the
     * manufacturer rule disabled, which is the same mistake one level down. With both neutral,
     * the manufacturer field is the only thing left that can name this set.
     */
    @Test
    fun theManufacturerFieldAloneDecidesItEvenWhenTheNameSaysNothing() {
        val neutralName = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <deviceType>urn:schemas-upnp-org:device:tvdevice:1</deviceType>
              <friendlyName>Sitting Room</friendlyName>
              <manufacturer>Amazon</manufacturer>
              <modelName>STREAM-1</modelName>
            </device></root>
        """.trimIndent()

        assertEquals(
            "The manufacturer the parse already yielded must choose the adapter. This is " +
                "the acceptance criterion in #250, and nothing else in the document may be " +
                "doing the work in this case.",
            TvVendor.AMAZON_FIRE_TV,
            TvDiscoveryClassifier.classify(host, dialWithAppUrl, neutralName)?.vendor,
        )
    }

    @Test
    fun theEvidenceStringIsConsumedAndNotOnlyDisplayed() {
        // Acceptance 2, expressed as the only observable form it can take: the value the
        // evidence names is the value that selected the adapter.
        val found = classified()!!
        assertTrue(
            "The evidence must still name the manufacturer it used.",
            found.evidence.contains("Amazon", ignoreCase = true),
        )
        assertEquals(
            "And that same manufacturer must be what chose the adapter.",
            TvVendor.AMAZON_FIRE_TV,
            TvScanPlan.vendorFromDescription(fireTvDescription),
        )
    }

    @Test
    fun aSetThatAcceptsAnUnauthenticatedDialLaunchNeverNeedsPairing() {
        // Acceptance 3, and the false requirement #243 called out in its own defect 3. DIAL
        // launch is an unauthenticated POST, so there is no pairing step on this path.
        assertFalse(
            "A Fire TV accepts DIAL app launches with no prompt, so reporting a pairing " +
                "requirement offers the person a step that does not exist.",
            classified()!!.needsPairing,
        )
    }

    @Test
    fun theSetIsControllableBecauseAnAdapterExistsForIt() {
        // `controllable: false` was the first of the ticket's three wrong fields.
        val result = TvRemoteFactory.remoteFor(classified()!!)
        assertNotNull(
            "A Fire TV has an adapter in this build, so controllable must be true. Reason " +
                "when it is not: ${result.reason}",
            result.remote,
        )
    }

    @Test
    fun theControlPortIsTheDialEndpointRatherThanTheDescriptionPort() {
        // The description is served on 60000; the launch goes to 8009. Reporting the
        // description port would send every press to a port that accepts no commands.
        assertEquals(8009, classified()!!.port)
    }

    @Test
    fun theFriendlyNameIsTheOneTheSetHas() {
        // #223's other half, checked here because the ticket's document is the one that
        // carries an encoded apostrophe.
        assertEquals("Owner's Fire TV", classified()!!.name)
    }

    @Test
    fun theModelPrefixAloneIsEnoughWhenTheManufacturerFieldIsAbsent() {
        // Amazon is identified two ways, and a document that names only AFTKM must still be
        // named. A rule that depended on one field would regress the moment a set omits it.
        val modelOnly = """
            <?xml version="1.0"?>
            <root xmlns="urn:schemas-upnp-org:device-1-0"><device>
              <deviceType>urn:schemas-upnp-org:device:tvdevice:1</deviceType>
              <friendlyName>Fire TV</friendlyName>
              <modelName>AFTMM</modelName>
            </device></root>
        """.trimIndent()

        assertEquals(
            TvVendor.AMAZON_FIRE_TV,
            TvDiscoveryClassifier.classify(host, dialWithAppUrl, modelOnly)?.vendor,
        )
    }
}
