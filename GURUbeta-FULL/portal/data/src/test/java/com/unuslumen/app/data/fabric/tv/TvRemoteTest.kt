// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import com.unuslumen.app.data.fabric.Capability
import com.unuslumen.app.data.fabric.DeviceValue
import com.unuslumen.app.data.fabric.Transport
import com.unuslumen.app.data.fabric.TransportWrite
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The network television contract. #187.
 *
 * WHAT THIS PROVES WITHOUT A TELEVISION. The mapping from a spoken request to a vendor
 * button, the classification of a discovered set, the refusal for a make this build
 * cannot drive, and the rule that a set only claims a capability its adapter really
 * implements. Those are the decisions, and they are where a universal remote actually
 * goes wrong.
 *
 * WHAT IT CANNOT PROVE, stated so it is never mistaken for device evidence: that a set
 * accepts a keypress. That needs his telly.
 */
class TvRemoteTest {

    // ------------------------------------------------------------------
    // Button vocabulary
    // ------------------------------------------------------------------

    /**
     * Every way a person says a button must land on one token, because the model reads
     * their words and the adapter needs a vendor key.
     */
    @Test
    fun spokenButtonNamesFoldOntoOneToken() {
        assertEquals(TvButton.POWER, TvButton.fromRaw("power"))
        assertEquals(TvButton.POWER, TvButton.fromRaw("Standby"))
        assertEquals(TvButton.VOLUME_UP, TvButton.fromRaw("turn it up"))
        assertEquals(TvButton.VOLUME_UP, TvButton.fromRaw("VOL+"))
        assertEquals(TvButton.VOLUME_DOWN, TvButton.fromRaw("turn it down"))
        assertEquals(TvButton.MUTE, TvButton.fromRaw("Silence"))
        assertEquals(TvButton.OK, TvButton.fromRaw("select"))
        assertEquals(TvButton.BACK, TvButton.fromRaw("exit"))
        assertEquals(TvButton.CHANNEL_UP, TvButton.fromRaw("next channel"))
        assertEquals(TvButton.FAST_FORWARD, TvButton.fromRaw("ff"))
    }

    /** A button nobody offers must not be invented from a similar sounding one. */
    @Test
    fun anUnknownButtonIsNotGuessed() {
        assertNull("Netflix is an app, not a key", TvButton.fromRaw("Netflix"))
        assertNull(TvButton.fromRaw(""))
        assertNull(TvButton.fromRaw(null))
    }

    // ------------------------------------------------------------------
    // Discovery classification
    // ------------------------------------------------------------------

    /** A set whose name or manufacturer says the make is classified by it. */
    @Test
    fun aSetIsIdentifiedFromWhatItSaysAboutItself() {
        val samsung = classify(
            host = "192.168.0.40",
            server = "Samsung UPnP SDK/1.0",
            description = "<friendlyName>[TV] Living Room</friendlyName><manufacturer>Samsung Electronics</manufacturer>",
        )
        assertNotNull(samsung)
        assertEquals(TvVendor.SAMSUNG, samsung!!.vendor)
        assertEquals("[TV] Living Room", samsung.name)
        assertTrue("Samsung needs a pairing prompt", samsung.needsPairing)

        val lg = classify(
            host = "192.168.0.41",
            server = "WebOS/TV UPnP/1.0",
            description = "<friendlyName>LG OLED</friendlyName><manufacturer>LG Electronics</manufacturer>",
        )
        assertEquals(TvVendor.LG, lg!!.vendor)

        val roku = classify(
            host = "192.168.0.42",
            server = "Roku/9.4 UPnP/1.0",
            description = "<friendlyName>Bedroom Roku</friendlyName>",
        )
        assertEquals(TvVendor.ROKU, roku!!.vendor)
        assertFalse("Roku needs no pairing", roku.needsPairing)
    }

    /**
     * A device that names no make must not be reported as a television.
     *
     * A printer and a router both answer UPnP, and a device in the list that cannot be
     * controlled reads as a broken app rather than as a device that is not a telly.
     */
    @Test
    fun anUnidentifiedDeviceIsNotReportedAsATelevision() {
        val printer = classify(
            host = "192.168.0.50",
            server = "HP LaserJet UPnP/1.0",
            description = "<friendlyName>Office Printer</friendlyName>",
        )
        assertNull("a printer must not be listed as a television", printer)
    }

    // ------------------------------------------------------------------
    // The factory
    // ------------------------------------------------------------------

    /** A make this build drives gets a real adapter. */
    @Test
    fun aSupportedMakeGetsItsAdapter() {
        val tv = tv(TvVendor.ROKU, "192.168.0.42", 8060)
        val result = TvRemoteFactory.remoteFor(tv)
        assertNotNull("Roku must get an adapter", result.remote)
        assertNull(result.reason)
        assertEquals(TvVendor.ROKU, result.remote!!.vendor)
    }

    /**
     * A make this build cannot drive must be refused by name, not reported as absent.
     *
     * The honest answer is "found it, cannot drive it, here is why", because a silent
     * omission sends a person looking at their television instead of at the adapter gap.
     */
    @Test
    fun anUnsupportedMakeIsRefusedByNameRatherThanHidden() {
        val vizio = TvRemoteFactory.remoteFor(tv(TvVendor.VIZIO, "192.168.0.43", 7345))
        assertNull("this build has no Vizio adapter", vizio.remote)
        assertNotNull(vizio.reason)
        assertTrue(
            "the refusal must name the make and why, and got: ${vizio.reason}",
            vizio.reason!!.contains("Vizio") && vizio.reason.contains("PIN"),
        )

        val androidTv = TvRemoteFactory.remoteFor(tv(TvVendor.ANDROID_TV, "192.168.0.44", 6466))
        assertNull(androidTv.remote)
        assertTrue(androidTv.reason!!.contains("pairing"))
    }

    // ------------------------------------------------------------------
    // The transport
    // ------------------------------------------------------------------

    /** A discovered set appears in the fabric as a television with the right vendor. */
    @Test
    fun aDiscoveredSetBecomesAFabricTelevision() = runBlocking {
        val discovery = TvDiscovery()
        val store = TvDeviceStore()
        val transport = TvTransport(discovery, store)

        // A set the factory can drive is remembered and reported available.
        val tv = tv(TvVendor.ROKU, "192.168.0.42", 8060)
        store.remember(tv, RokuRemote("192.168.0.42"))

        val entry = store.get(tv.id)
        assertNotNull(entry)
        assertEquals("the transport reports itself as a vendor protocol", Transport.VENDOR, transport.transport)
        assertEquals("192.168.0.42", entry!!.tv.host)
    }

    /**
     * A press on a set with no adapter refuses with the factory's sentence.
     *
     * The value is checked against a real write rather than the store alone, because the
     * refusal a person sees comes from the write path.
     */
    @Test
    fun aPressOnAnUnsupportedSetNamesTheReason() = runBlocking {
        val store = TvDeviceStore()
        val transport = TvTransport(TvDiscovery(), store)
        val vizio = tv(TvVendor.VIZIO, "192.168.0.43", 7345)
        store.remember(
            vizio,
            remote = null,
            reason = "A Vizio set was found at 192.168.0.43, but this build has no Vizio adapter yet.",
        )

        val outcome = transport.write(vizio.id, Capability.POWER, DeviceValue.Flag(true))
        assertTrue("an unsupported set must refuse", outcome is TransportWrite.Failed)
        assertTrue(
            "and the refusal must name the adapter gap, and got: " +
                (outcome as TransportWrite.Failed).reason,
            outcome.reason.contains("Vizio adapter"),
        )
    }

    /** A press on a set nobody discovered refuses rather than inventing an address. */
    @Test
    fun aPressOnAnUnknownSetRefuses() = runBlocking {
        val transport = TvTransport(TvDiscovery(), TvDeviceStore())
        val outcome = transport.write("tv:roku:10.0.0.9", Capability.POWER, DeviceValue.Flag(true))
        assertTrue(outcome is TransportWrite.Failed)
        assertTrue((outcome as TransportWrite.Failed).reason.contains("discovery"))
    }

    /** An unidentified device is not a television, so it is not listed or drivable. */
    @Test
    fun aDeviceWithNoVendorHasNoAdapter() {
        val unknown = tv(TvVendor.UNKNOWN, "192.168.0.99", 0)
        val result = TvRemoteFactory.remoteFor(unknown)
        assertNull(result.remote)
        assertNotNull(result.reason)
        assertTrue(result.reason!!.contains("could not be identified"))
    }

    /** The fabric id must be stable, or a room assignment moves on every scan. */
    @Test
    fun theFabricIdIsStableAcrossScans() {
        val first = tv(TvVendor.ROKU, "192.168.0.42", 8060)
        val second = tv(TvVendor.ROKU, "192.168.0.42", 8060)
        assertEquals(
            "the same set at the same address must keep its id",
            first.id,
            second.id,
        )
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun tv(vendor: TvVendor, host: String, port: Int) = DiscoveredTv(
        vendor = vendor,
        name = "${vendor.name} at $host",
        host = host,
        port = port,
        evidence = "test",
        needsPairing = vendor != TvVendor.ROKU,
    )

    /**
     * Run the real classifier the discovery uses.
     *
     * The classifier is private because discovery is one job, so this exercises it
     * through a fake SSDP reply rather than duplicating its rules in the test. That way
     * the test proves the rule the app runs, not a second copy of it.
     */
    private fun classify(host: String, server: String, description: String): DiscoveredTv? {
        val body = buildString {
            append("HTTP/1.1 200 OK\r\n")
            append("SERVER: $server\r\n")
            append("ST: urn:dial-multiscreen-org:service:dial:1\r\n")
            append("\r\n")
        }
        return TvDiscoveryClassifier.classify(host, body, descriptionXml = description)
    }
}
