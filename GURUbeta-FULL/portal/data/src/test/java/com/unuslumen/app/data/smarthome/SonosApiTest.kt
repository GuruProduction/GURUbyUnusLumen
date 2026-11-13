// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.smarthome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 186's Sonos half, against the shapes a real speaker sends.
 *
 * The envelope and the escaped-XML-in-an-element reply are the two places this protocol is easy to
 * get wrong, so both have their own tests rather than being left to a device to discover.
 */
class SonosApiTest {

    private val speaker = SonosApi.Speaker(host = "192.0.2.20", name = "Kitchen")

    @Test
    fun theControlPathsAreTheUpnpOnes() {
        assertEquals("/MediaRenderer/AVTransport/Control", SonosApi.controlPath(SonosApi.AV_TRANSPORT))
        assertEquals("/MediaRenderer/RenderingControl/Control", SonosApi.controlPath(SonosApi.RENDERING_CONTROL))
        assertEquals("/ZoneGroupTopology/Control", SonosApi.controlPath(SonosApi.ZONE_GROUP_TOPOLOGY))
    }

    @Test
    fun theSpeakerAddressesItsOwnPort() {
        assertEquals("http://192.0.2.20:1400", speaker.baseUrl)
    }

    @Test
    fun theSoapActionHeaderNamesBothServiceAndAction() {
        // A request with the action in the body but not the header is refused with a bare 500,
        // which is why the pair is asserted together.
        assertEquals(
            "\"urn:schemas-upnp-org:service:AVTransport:1#Play\"",
            SonosApi.soapAction(SonosApi.AV_TRANSPORT, "Play"),
        )
    }

    @Test
    fun theEnvelopeCarriesTheActionTwiceInTheRightPlaces() {
        val envelope = SonosApi.envelope(
            SonosApi.AV_TRANSPORT,
            "Play",
            mapOf("InstanceID" to "0", "Speed" to "1"),
        )
        assertTrue("the action is the body element: $envelope", envelope.contains("<u:Play"))
        assertTrue("and it names the service in its namespace: $envelope", envelope.contains(SonosApi.AV_TRANSPORT))
        assertTrue("the arguments are children of it: $envelope", envelope.contains("<InstanceID>0</InstanceID>"))
        assertTrue(envelope.contains("<Speed>1</Speed>"))
        assertTrue("and the envelope is well formed: $envelope", envelope.endsWith("</s:Envelope>"))
    }

    @Test
    fun aValueWithXmlCharactersIsEscaped() {
        // A track title or a speaker name can contain any of these, and an unescaped ampersand makes
        // the whole envelope unparseable at the server.
        val envelope = SonosApi.envelope(
            SonosApi.AV_TRANSPORT,
            "SetAVTransportURI",
            mapOf("CurrentURI" to "x?a=1&b=<2>"),
        )
        assertTrue(envelope.contains("x?a=1&amp;b=&lt;2&gt;"))
        assertTrue("the raw ampersand must not survive: $envelope", !envelope.contains("&b="))
    }

    @Test
    fun volumeIsTheSpeakersOwnZeroToHundred() {
        // Sonos takes 0-100 directly, unlike Hue's 0-254. Rescaling by habit would make every
        // speaker silent at what a person thought was half.
        val (_, args) = SonosApi.setVolume(30)
        assertEquals("30", args["DesiredVolume"])
        assertEquals("Master", args["Channel"])
        assertEquals(
            "100",
            SonosApi.setVolume(150).second["DesiredVolume"],
        )
        assertEquals("0", SonosApi.setVolume(-5).second["DesiredVolume"])
    }

    @Test
    fun everyTransportVerbMapsToItsUpnpAction() {
        assertEquals(SonosApi.AV_TRANSPORT to "Play", SonosApi.transportAction("play"))
        assertEquals(SonosApi.AV_TRANSPORT to "Pause", SonosApi.transportAction("PAUSE"))
        assertEquals(SonosApi.AV_TRANSPORT to "Stop", SonosApi.transportAction("stop"))
        assertNull("an unknown verb is refused rather than guessed at", SonosApi.transportAction("skip"))
    }

    @Test
    fun playCarriesTheSpeedArgumentThatPauseDoesNot() {
        assertEquals("1", SonosApi.transportArguments("play")["Speed"])
        assertNull(
            "Pause takes only the transport instance, and sending Speed to it is a rejected action",
            SonosApi.transportArguments("pause")["Speed"],
        )
    }

    @Test
    fun theTransportStateIsReportedInWords() {
        assertEquals("playing", SonosApi.describeTransportState("PLAYING"))
        assertEquals("paused", SonosApi.describeTransportState("PAUSED_PLAYBACK"))
        assertEquals("stopped", SonosApi.describeTransportState("STOPPED"))
        assertEquals("nothing queued", SonosApi.describeTransportState("NO_MEDIA_PRESENT"))
        assertEquals(
            "a state this does not know is passed through rather than guessed",
            "SOME_NEW_STATE",
            SonosApi.describeTransportState("SOME_NEW_STATE"),
        )
    }

    @Test
    fun anElementIsReadAndUnescaped() {
        val reply = "<CurrentTransportState>PLAYING</CurrentTransportState>"
        assertEquals("PLAYING", SonosApi.elementText(reply, "CurrentTransportState"))
        assertNull(SonosApi.elementText(reply, "CurrentTrackTitle"))
    }

    @Test
    fun anAmpersandIsUnescapedLastSoAnEntityIsNotDoubleDecoded() {
        // `&amp;lt;` is the literal text "&lt;", not "<". Decoding the ampersand first would turn it
        // into a bracket that was never in the answer.
        assertEquals("&lt;", SonosApi.unescapeXml("&amp;lt;"))
        assertEquals("<b>", SonosApi.unescapeXml("&lt;b&gt;"))
        assertEquals("Tom & Jerry", SonosApi.unescapeXml("Tom &amp; Jerry"))
    }

    @Test
    fun theZoneGroupStateIsEscapedXmlInsideAnElement() {
        // THIS IS THE TRAP. ZoneGroupState carries an escaped document, so reading the OUTER reply
        // for ZoneGroupMember elements finds none and looks like "no speakers". The inner document
        // has to be unescaped first.
        val body = """
            <s:Envelope><s:Body><u:GetZoneGroupStateResponse><ZoneGroupState>
            &lt;ZoneGroups&gt;&lt;ZoneGroup Coordinator="RINCON_1" ID="RINCON_1:1"&gt;
            &lt;ZoneGroupMember UUID="RINCON_1" Location="http://192.0.2.20:1400/xml/device_description.xml" ZoneName="Kitchen" /&gt;
            &lt;ZoneGroupMember UUID="RINCON_2" Location="http://192.0.2.21:1400/xml/device_description.xml" ZoneName="Lounge" /&gt;
            &lt;/ZoneGroup&gt;&lt;/ZoneGroups&gt;
            </ZoneGroupState></u:GetZoneGroupStateResponse></s:Body></s:Envelope>
        """.trimIndent()

        val speakers = SonosApi.speakersInZoneGroupState(body)
        assertEquals("both speakers must be found in the INNER document", 2, speakers.size)
        assertEquals(SonosApi.Speaker("192.0.2.20", "Kitchen"), speakers[0])
        assertEquals(SonosApi.Speaker("192.0.2.21", "Lounge"), speakers[1])
    }

    @Test
    fun anAttributeIsReadFromATag() {
        val attrs = "UUID=\"RINCON_1\" Location=\"http://192.0.2.20:1400/x\" ZoneName=\"Kitchen\""
        assertEquals("Kitchen", SonosApi.attributeValue(attrs, "ZoneName"))
        assertEquals("http://192.0.2.20:1400/x", SonosApi.attributeValue(attrs, "Location"))
        assertNull(SonosApi.attributeValue(attrs, "ModelName"))
    }

    @Test
    fun aSpeakerNameWithAnAmpersandIsUnescaped() {
        val body = """<ZoneGroupState>&lt;ZoneGroupMember Location="http://192.0.2.20:1400/x" ZoneName="Tom &amp; Jerry's" /&gt;</ZoneGroupState>"""
        val speakers = SonosApi.speakersInZoneGroupState(body)
        assertEquals(1, speakers.size)
        assertEquals("Tom & Jerry's", speakers[0].name)
    }

    @Test
    fun anEmptyOrUnrecognisedReplyYieldsNoSpeakersRatherThanACrash() {
        assertEquals(emptyList<SonosApi.Speaker>(), SonosApi.speakersInZoneGroupState(""))
        assertEquals(
            emptyList<SonosApi.Speaker>(),
            SonosApi.speakersInZoneGroupState("<s:Envelope></s:Envelope>"),
        )
    }

    @Test
    fun anUnreachableSpeakerIsANetworkFact() {
        val sentence = SonosApi.failureSentence(
            "192.0.2.20",
            null,
            "",
            threw = "java.net.ConnectException: failed to connect",
        )
        assertTrue(
            "an unreachable speaker must say so rather than blaming the action: $sentence",
            sentence.contains("could not be reached"),
        )
    }

    @Test
    fun aRejectedActionQuotesTheSpeakersOwnReason() {
        // A UPnP error arrives as HTTP 500 with an errorDescription, and that text is the only thing
        // that explains what was wrong with the request.
        val body = """
            <s:Envelope><s:Body><s:Fault><detail>
            <UPnPError><errorCode>402</errorCode><errorDescription>Invalid Args</errorDescription></UPnPError>
            </detail></s:Fault></s:Body></s:Envelope>
        """.trimIndent()
        val sentence = SonosApi.failureSentence("192.0.2.20", 500, body)
        assertTrue("the speaker's own reason must be quoted: $sentence", sentence.contains("Invalid Args"))
    }

    @Test
    fun thePortIsTheUpnpOne() {
        assertEquals(1400, SonosApi.PORT)
    }
}
