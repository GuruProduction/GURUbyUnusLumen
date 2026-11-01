// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The control adapters' decisions, proved on the JVM. Network control spec, requirements
 * 3, 4, 5 and 7.
 *
 * EVERY FAILURE HERE WOULD HAVE BEEN INVISIBLE ON A DEVICE. A wrong magic packet wakes
 * nothing, a wrong SOAP envelope is refused with no error shown, a malformed MAC
 * broadcasts into the void, and a fake-200 device reports control it does not have. Those
 * are exactly the defects this project keeps removing, so the arithmetic and the text are
 * pinned here rather than discovered on his network.
 */
class NetworkControlTest {

    // ------------------------------------------------------------------
    // Requirement 7: the fake-200 guard, using his real Virgin box behaviour
    // ------------------------------------------------------------------

    /**
     * THE DEFECT FROM HIS OWN NETWORK. The Virgin boxes at 192.168.0.50 and
     * 192.168.0.154 answer HTTP 200 with "status=ok" to every path, including invented
     * gibberish. A capability check that trusts the 200 reports control of a box that
     * answers everything and does nothing.
     */
    @Test
    fun aDeviceThatAnswersEverythingIsReportedAsUninformative() {
        val fake200 = EndpointFingerprint.Response(200, "status=ok")
        val verdict = EndpointFingerprint.judge("/apps", fake200, fake200)

        assertFalse(
            "a device whose real path answers exactly like its nonsense path must not be trusted",
            verdict.trustworthy,
        )
        assertTrue(
            "and the sentence must say why, and got: ${verdict.sentence}",
            verdict.sentence.contains("carry no information"),
        )
        assertTrue(
            "the evidence must quote the body it could not trust, and got: ${verdict.sentence}",
            verdict.sentence.contains("status=ok"),
        )
        assertTrue(
            "the sentence must name the nonsense path, so a reader can see the comparison",
            verdict.sentence.contains(EndpointFingerprint.NONSENSE_PATH),
        )
    }

    /** A device that refuses the nonsense path outright is the best case. */
    @Test
    fun aDeviceThatRefusesNonsenseIsTrusted() {
        val real = EndpointFingerprint.Response(200, "<device-info><power-mode>PowerOn</power-mode></device-info>")
        val nonsense = EndpointFingerprint.Response(0, "")
        val verdict = EndpointFingerprint.judge("/query/device-info", real, nonsense)

        assertTrue("a discriminating device is trusted", verdict.trustworthy)
        assertTrue(verdict.sentence.contains("refused"))
    }

    /** A device that answers a real path differently from nonsense is trusted. */
    @Test
    fun aDifferentAnswerIsTrusted() {
        val real = EndpointFingerprint.Response(200, "<html><title>Router admin</title></html>")
        val nonsense = EndpointFingerprint.Response(200, "status=ok")
        val verdict = EndpointFingerprint.judge("/", real, nonsense)

        assertTrue(verdict.trustworthy)
        assertTrue(verdict.sentence.contains("differently"))
    }

    /** Whitespace alone must not make two identical answers look different. */
    @Test
    fun whitespaceDoesNotFakeADifference() {
        val real = EndpointFingerprint.Response(200, "  status=ok \n")
        val nonsense = EndpointFingerprint.Response(200, "status=ok")
        assertFalse(
            "the same answer with different whitespace is the same answer",
            EndpointFingerprint.judge("/apps", real, nonsense).trustworthy,
        )
    }

    /** A 404 for nonsense and a 404 for real is still no proof of anything. */
    @Test
    fun matchingFailuresAreNotTrusted() {
        val notFound = EndpointFingerprint.Response(404, "")
        assertFalse(
            "two identical 404s tell you nothing about what the device can do",
            EndpointFingerprint.judge("/apps", notFound, notFound).trustworthy,
        )
    }

    // ------------------------------------------------------------------
    // Requirement 5: Wake-on-LAN
    // ------------------------------------------------------------------

    /**
     * The magic packet is exactly 102 bytes: six 0xFF, then the MAC sixteen times.
     *
     * A packet of the wrong length wakes nothing, and nothing about it fails loudly.
     */
    @Test
    fun theMagicPacketIsSixSyncBytesThenTheMacSixteenTimes() {
        val packet = WakeOnLan.magicPacket("AA:BB:CC:DD:EE:FF")
        assertNotNull(packet)
        assertEquals("6 sync bytes plus 16 copies of 6", 6 + 16 * 6, packet!!.size)

        for (index in 0 until 6) {
            assertEquals("byte $index is 0xFF", 0xFF, packet[index].toInt() and 0xFF)
        }
        val mac = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        for (repeat in 0 until 16) {
            val slice = packet.copyOfRange(6 + repeat * 6, 6 + repeat * 6 + 6)
            assertTrue(
                "copy $repeat of the MAC must be identical",
                slice.contentEquals(mac),
            )
        }
    }

    /** A MAC is accepted with colons, dashes or none, because people write all three. */
    @Test
    fun aMacIsAcceptedInEveryCommonForm() {
        val expected = byteArrayOf(0xAA.toByte(), 0xBB.toByte(), 0xCC.toByte(), 0xDD.toByte(), 0xEE.toByte(), 0xFF.toByte())
        for (form in listOf("AA:BB:CC:DD:EE:FF", "aa-bb-cc-dd-ee-ff", "AABBCCDDEEFF", "aa.bb.cc.dd.ee.ff")) {
            assertTrue("'$form' must parse", WakeOnLan.parseMac(form)?.contentEquals(expected) == true)
        }
    }

    /**
     * A malformed MAC is refused rather than guessed.
     *
     * Broadcasting a packet built from a half-read MAC wakes nothing and looks like a
     * device that will not wake, which is the wrong place to go looking.
     */
    @Test
    fun aMalformedMacIsRefused() {
        assertNull(WakeOnLan.parseMac("AA:BB:CC:DD:EE"))
        assertNull(WakeOnLan.parseMac("GG:BB:CC:DD:EE:FF"))
        assertNull(WakeOnLan.parseMac(""))
        assertNull(WakeOnLan.magicPacket("not a mac"))
    }

    // ------------------------------------------------------------------
    // Requirement 3: the UPnP SOAP envelope
    // ------------------------------------------------------------------

    /**
     * The envelope shape decides whether a device accepts the call.
     *
     * Every part is load-bearing: the SOAP envelope namespace, the encoding style, the
     * action element namespaced to the SERVICE TYPE rather than to the action, and one
     * element per argument.
     */
    @Test
    fun theSoapEnvelopeHasTheShapeDevicesAccept() {
        val client = UpnpSoapClient()
        val envelope = client.buildEnvelope(
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            action = "Play",
            arguments = mapOf("InstanceID" to "0", "Speed" to "1"),
        )

        assertTrue(
            "the SOAP envelope namespace is required",
            envelope.contains("http://schemas.xmlsoap.org/soap/envelope/"),
        )
        assertTrue(
            "the encoding style is required by most stacks",
            envelope.contains("http://schemas.xmlsoap.org/soap/encoding/"),
        )
        assertTrue(
            "the action element must be prefixed and namespaced to the service type",
            envelope.contains("<u:Play xmlns:u=\"urn:schemas-upnp-org:service:AVTransport:1\">"),
        )
        assertTrue("arguments are elements", envelope.contains("<InstanceID>0</InstanceID>"))
        assertTrue(envelope.contains("<Speed>1</Speed>"))
        assertTrue("the body must close", envelope.contains("</s:Envelope>"))
    }

    /** An argument carrying XML characters must be escaped or the envelope is invalid. */
    @Test
    fun anArgumentIsEscaped() {
        val client = UpnpSoapClient()
        val envelope = client.buildEnvelope(
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            action = "SetAVTransportURI",
            arguments = mapOf("CurrentURI" to "http://example.com/a?b=1&c=2"),
        )
        assertTrue(
            "an ampersand must be escaped, or the device rejects the whole envelope",
            envelope.contains("b=1&amp;c=2"),
        )
        assertFalse("a raw ampersand must not reach the device", envelope.contains("b=1&c=2"))
    }

    /** A relative control URL must be resolved against the device, not sent to the phone. */
    @Test
    fun aRelativeControlUrlIsResolvedAgainstTheDevice() {
        val client = UpnpSoapClient()
        assertEquals(
            "http://192.168.0.71:49152/upnp/control/AVTransport1",
            client.resolve("http://192.168.0.71:49152", "/upnp/control/AVTransport1"),
        )
        assertEquals(
            "an absolute URL is left alone",
            "http://192.168.0.71:9197/dmr",
            client.resolve("http://192.168.0.71:49152", "http://192.168.0.71:9197/dmr"),
        )
        assertEquals(
            "a bare path is joined",
            "http://192.168.0.71:49152/control",
            client.resolve("http://192.168.0.71:49152", "control"),
        )
    }

    /** The short service name is what a caller thinks of, not the full URN. */
    @Test
    fun aServiceShortNameIsReadable() {
        val service = UpnpSoapClient.Service(
            serviceType = "urn:schemas-upnp-org:service:AVTransport:1",
            serviceId = "urn:upnp-org:serviceId:AVTransport",
            controlUrl = "http://192.168.0.71/control",
        )
        assertEquals("AVTransport", service.shortName)
    }

    // ------------------------------------------------------------------
    // Requirement 1 and the list he asked for
    // ------------------------------------------------------------------

    /**
     * The adapter catalogue must be honest: everything it marks built is real, and the
     * gaps are named rather than quietly missing.
     */
    @Test
    fun theAdapterCatalogueBuildsWhatItClaimsAndNamesEveryGap() {
        val built = AdapterCatalogue.built.map { it.id }
        assertTrue("Roku must be built, it is the reference implementation", "roku-ecp" in built)
        assertTrue("Samsung must be built", "samsung-tizen" in built)
        assertTrue("LG must be built", "lg-webos" in built)
        assertTrue("the active scanner must be built", "active-scan" in built)
        assertTrue("Wake-on-LAN must be built", "wake-on-lan" in built)
        assertTrue("the SOAP caller must be built", "upnp-soap" in built)
        assertTrue("DIAL must be built", "dial-launch" in built)

        assertTrue("Android TV must be built, it covers his Fire TV", "android-tv-remote" in built)

        val gaps = AdapterCatalogue.gaps.map { it.id }
        assertTrue(
            "the gaps must name the real remaining work, and got $gaps",
            gaps.contains("vizio-smartcast") && gaps.contains("chromecast") && gaps.contains("airplay"),
        )
        assertTrue(
            "Xbox discovery, power on and the crypto channel are built",
            built.contains("xbox-smartglass"),
        )

        val sentence = AdapterCatalogue.gapSentence()
        assertTrue(
            "the gap sentence must be usable in a report, and got: $sentence",
            sentence.contains("still gaps"),
        )
    }

    /** Every adapter states what it drives, so the list is a work list rather than a label. */
    @Test
    fun everyAdapterSaysWhatItDrivesAndWhy() {
        for (adapter in AdapterCatalogue.all) {
            assertTrue("${adapter.id} must name a target", adapter.target.isNotBlank())
            assertTrue("${adapter.id} must name a mechanism", adapter.mechanism.isNotBlank())
            assertTrue(
                "${adapter.id} must say something useful in its detail, and got: ${adapter.detail}",
                adapter.detail.length > 40,
            )
        }
    }

    /**
     * His hardware has no emitter and the catalogue must say so rather than advertising
     * infrared control that cannot work.
     */
    @Test
    fun infraredIsOnlyClaimedThroughANetworkBlaster() {
        val ir = AdapterCatalogue.all.firstOrNull { it.id == "ir-network-blaster" }
        assertNotNull("the IR row must exist", ir)
        assertTrue(
            "the IR row must name the network path and the missing hardware, and got: ${ir!!.detail}",
            ir.detail.contains("blaster") && ir.detail.contains("does not have"),
        )
    }
}
