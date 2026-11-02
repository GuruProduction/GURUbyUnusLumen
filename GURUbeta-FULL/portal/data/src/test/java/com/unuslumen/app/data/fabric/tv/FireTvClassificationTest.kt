// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pins issues #243 and #242, using the real device documents from the reports.
 *
 * #243: a Fire TV was reported as ANDROID_TV because port 8009 was open, and the note
 * that came with it claimed the set needed a pairing code. The device had already said
 * `manufacturer: Amazon` and `modelName: AFTKM` on a port the scan walked.
 *
 * #242: a device that was found and not drivable was reported as a bare error string,
 * so the caller lost the device entirely.
 */
class FireTvClassificationTest {

    /**
     * The exact description document from issue #243.
     *
     * Kept verbatim, including the `r:` namespace, because that is what the set served
     * and a classifier that only works on a tidied copy has not been tested.
     */
    private val fireTvDescription = """
        <root xmlns="urn:schemas-upnp-org:device-1-0" xmlns:r="urn:restful-tv-org:schemas:upnp-dd">
          <specVersion><major>1</major><minor>0</minor></specVersion>
          <device>
            <deviceType>urn:schemas-upnp-org:device:tvdevice:1</deviceType>
            <friendlyName>Fire TV</friendlyName>
            <manufacturer>Amazon</manufacturer>
            <modelName>AFTKM</modelName>
            <UDN>uuid:redacted</UDN>
          </device>
        </root>
    """.trimIndent()

    private val fireTvSsdp = """
        HTTP/1.1 200 OK
        LOCATION: http://127.0.0.1:60000/dd.xml
        SERVER: Linux/4.14 UPnP/1.0
        ST: urn:dial-multiscreen-org:service:dial:1
    """.trimIndent()

    // ------------------------------------------------------------------
    // #243 — the make comes from what the device declared
    // ------------------------------------------------------------------

    /**
     * The reported set must classify as a Fire TV, not as an Android TV.
     *
     * This is the defect in one assertion: the old classifier had no Amazon rule, so the
     * manufacturer string fell through to `androidtv` matching nothing and the port guess
     * decided it instead.
     */
    @Test
    fun theReportedFireTvClassifiesAsAFireTvNotAnAndroidTv() {
        val tv = TvDiscoveryClassifier.classify("127.0.0.1", fireTvSsdp, fireTvDescription)
        assertTrue("the reported Fire TV did not classify at all", tv != null)
        assertEquals(
            "the set declares manufacturer: Amazon and modelName: AFTKM, so a port guess " +
                "must never override that",
            TvVendor.AMAZON_FIRE_TV,
            tv!!.vendor,
        )
    }

    /** The declared make wins over a port, which is the rule the fix rests on. */
    @Test
    fun theDescriptionNamesTheFireTvEvenWithAnAmbiguousPortSet() {
        val fromPorts = TvScanPlan.vendorFromDescription(fireTvDescription)
        assertEquals(TvVendor.AMAZON_FIRE_TV, fromPorts)
    }

    /** Amazon's model prefix alone is enough, which matters when a document omits the maker. */
    @Test
    fun theModelPrefixAloneIdentifiesAFireTv() {
        val noManufacturer = "<device><modelName>AFTMM</modelName><friendlyName>Fire TV</friendlyName></device>"
        assertEquals(TvVendor.AMAZON_FIRE_TV, TvScanPlan.vendorFromDescription(noManufacturer))
    }

    /** Every Fire TV model family the prefix covers must work, not just the reported one. */
    @Test
    fun everyFireTvModelPrefixIsRecognised() {
        for (model in listOf("AFTKM", "AFTMM", "AFTKA", "AFTMMST", "AFTT")) {
            val doc = "<device><modelName>$model</modelName></device>"
            assertEquals(
                "$model is a Fire TV model and must be recognised",
                TvVendor.AMAZON_FIRE_TV,
                TvScanPlan.vendorFromDescription(doc),
            )
        }
    }

    /** An Android TV that declares itself as one must not be dragged into the Amazon branch. */
    @Test
    fun aRealAndroidTvIsStillAnAndroidTv() {
        val doc = "<device><friendlyName>Android TV</friendlyName>" +
            "<manufacturer>Google</manufacturer><modelName>Chromecast</modelName></device>"
        assertEquals(TvVendor.ANDROID_TV, TvScanPlan.vendorFromDescription(doc))
    }

    /** A word merely containing the letters AFT is not a Fire TV model. */
    @Test
    fun aWordContainingAftIsNotAFireTvModel() {
        val doc = "<device><friendlyName>Drafting screen</friendlyName><modelName>DAFT1</modelName></device>"
        assertNotEquals(
            "DAFT1 contains 'aft' but is not an Amazon model prefix",
            TvVendor.AMAZON_FIRE_TV,
            TvScanPlan.vendorFromDescription(doc),
        )
    }

    // ------------------------------------------------------------------
    // #243 — the control story is not a pairing code
    // ------------------------------------------------------------------

    /** A Fire TV accepts DIAL launches, so it must not be listed as needing a pairing code. */
    @Test
    fun aFireTvIsNotReportedAsNeedingAPairing() {
        val tv = TvDiscoveryClassifier.classify("127.0.0.1", fireTvSsdp, fireTvDescription)
        assertEquals(
            "DIAL app launches need no pairing. Its button channel does, and that belongs in " +
                "the refusal for a press rather than on the whole set.",
            false,
            tv!!.needsPairing,
        )
    }

    /** The adapter exists, so the factory must hand one back rather than a refusal. */
    @Test
    fun theFactoryReturnsARealAdapterForAFireTv() {
        val tv = DiscoveredTv(
            vendor = TvVendor.AMAZON_FIRE_TV,
            name = "Fire TV",
            host = "127.0.0.1",
            port = 8009,
            evidence = "answered UPnP",
        )
        val result = TvRemoteFactory.remoteFor(tv)
        assertTrue(
            "a Fire TV is drivable over DIAL, so the factory must return an adapter; " +
                "it returned the reason '${result.reason}'",
            result.remote != null,
        )
        assertEquals(TvVendor.AMAZON_FIRE_TV, result.remote!!.vendor)
    }

    /** Commands go to DIAL on 8009, which is where a launch is accepted. */
    @Test
    fun theControlPortForAFireTvIsDial() {
        assertEquals(8009, TvScanPlan.defaultPortFor(TvVendor.AMAZON_FIRE_TV))
    }

    /** The spoken app names map to Amazon's own DIAL names. */
    @Test
    fun theSpokenAppNamesMapToAmazonNames() {
        assertEquals("Netflix", FireTvRemote.amazonAppId("netflix"))
        assertEquals("Prime Video", FireTvRemote.amazonAppId("Prime Video"))
        assertEquals("Prime Video", FireTvRemote.amazonAppId("amazon"))
        assertEquals("YouTube", FireTvRemote.amazonAppId("youtube"))
        assertNull("an app Amazon does not ship must be refused by name", FireTvRemote.amazonAppId("iplayer from 2009"))
    }

    /**
     * A press is refused, and the refusal names what does work.
     *
     * The original defect was a sentence claiming the device could not be driven at all.
     * The honest answer names the one control that does work.
     */
    @Test
    fun aPressOnAFireTvNamesTheControlThatDoesWork() = kotlinx.coroutines.runBlocking {
        val outcome = FireTvRemote("127.0.0.1").press(TvButton.POWER)
        assertTrue("a press must fail honestly", outcome is TvCommandOutcome.Failed)
        val reason = (outcome as TvCommandOutcome.Failed).reason
        assertTrue("the refusal must name the pairing channel", reason.contains("pairing", ignoreCase = true))
        assertTrue("the refusal must name what does work", reason.contains("tvLaunch", ignoreCase = true) || reason.contains("DIAL", ignoreCase = true))
    }

    // ------------------------------------------------------------------
    // #242 — a found device is never reduced to prose
    // ------------------------------------------------------------------

    /** A device the factory cannot drive still carries a reason that names it. */
    @Test
    fun anUndrivableDeviceReturnsAReasonNamingIt() {
        val undrivable = DiscoveredTv(
            vendor = TvVendor.VIZIO,
            name = "Vizio on 10.0.0.5",
            host = "10.0.0.5",
            port = 7345,
            evidence = "port 7345 is open",
        )
        val factoryResult = TvRemoteFactory.remoteFor(undrivable)
        assertTrue("a Vizio has no adapter, so there must be a reason", !factoryResult.reason.isNullOrBlank())
        assertTrue("the reason must name the set", factoryResult.reason!!.contains("Vizio"))
    }

    /**
     * A found device survives into `televisions`, and the answer is not a bare error.
     *
     * THE REPORTED FAILURE, exactly: `{"error":"Televisions were found but none of them
     * can be driven..."}` with no televisions, no count, no host. A host that answered is
     * data, and data is never replaced by a sentence.
     */
    @Test
    fun aFoundButUndrivableSetIsStillReturnedAsADevice() {
        val found = listOf(
            TvFound(
                id = "tv:vizio:10.0.0.5",
                name = "Vizio on 10.0.0.5",
                vendor = "VIZIO",
                host = "10.0.0.5",
                controllable = false,
                needsPairing = false,
                evidence = "port 7345 is open",
                note = "no Vizio adapter in this build",
            ),
        )

        val result = TvDiscoveryResultBuilder.build(found, emptyList())

        assertEquals("the device must survive into the answer", 1, result.count)
        assertEquals("the device must be in televisions", 1, result.televisions.size)
        assertEquals("10.0.0.5", result.televisions.first().host)
        assertEquals(
            "not being drivable is a property of the device, not a failure of the scan, so " +
                "the scan reports no error",
            null,
            result.error,
        )
        assertTrue(
            "the reason must appear in problems, where a reader looks for what was seen " +
                "and not used",
            result.problems.any { it.contains("10.0.0.5") && it.contains("VIZIO") },
        )
    }

    /** A single drivable set among undrivable ones makes the scan a success. */
    @Test
    fun oneDrivableSetMakesTheScanASuccess() {
        val found = listOf(
            TvFound("tv:vizio:10.0.0.5", "Vizio", "VIZIO", "10.0.0.5", false, false, "ports", "no adapter"),
            TvFound("tv:roku:10.0.0.6", "Roku", "ROKU", "10.0.0.6", true, false, "device-info", null),
        )
        val result = TvDiscoveryResultBuilder.build(found, emptyList())
        assertTrue("a Roku can be driven, so the scan succeeded", result.success)
        assertEquals("both sets are reported", 2, result.count)
        assertEquals(null, result.error)
    }

    /** A scan that genuinely found nothing is the one case that carries an error. */
    @Test
    fun aScanThatFoundNothingCarriesTheReason() {
        val result = TvDiscoveryResultBuilder.build(
            found = emptyList(),
            scanProblems = listOf("Nothing answered on 10.0.0.0/24."),
        )
        assertEquals("nothing to report", 0, result.count)
        assertTrue("nothing found is a failure", !result.success)
        assertEquals(
            "with no devices there is no answer but the reason, so error is where it goes",
            "Nothing answered on 10.0.0.0/24.",
            result.error,
        )
    }

    /** Scan problems and per-device problems are both reported, never one instead of the other. */
    @Test
    fun scanProblemsAndDeviceProblemsAreBothReported() {
        val found = listOf(
            TvFound("tv:philips:10.0.0.7", "Philips", "PHILIPS", "10.0.0.7", false, false, "ports", "no adapter"),
        )
        val result = TvDiscoveryResultBuilder.build(found, listOf("a host dropped every packet"))
        assertTrue("the scan's own warning survives", result.problems.any { it.contains("dropped every packet") })
        assertTrue("the device's reason is added", result.problems.any { it.contains("10.0.0.7") })
    }
}
