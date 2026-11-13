// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.smarthome

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 179's Hue half, with the bridge's real response shapes copied from its documentation.
 *
 * The whole point of this object is that a decision about a light bulb is testable without a light
 * bulb, so every fixture below is the body the bridge actually sends rather than one I invented.
 */
class HueApiTest {

    private val bridge = HueApi.Bridge(host = "192.0.2.10", applicationKey = "abc123")

    @Test
    fun thePathsAreWhatTheBridgeDocuments() {
        assertEquals("/api/abc123/lights", HueApi.lightsPath(bridge))
        assertEquals("/api/abc123/groups", HueApi.roomsPath(bridge))
        assertEquals("/api/abc123/scenes", HueApi.scenesPath(bridge))
        assertEquals("/api/abc123/lights/3/state", HueApi.lightStatePath(bridge, "3"))
        assertEquals("/api/abc123/groups/1/action", HueApi.roomActionPath(bridge, "1"))
    }

    @Test
    fun aBridgeWithoutAKeyIsDistinguishableFromOneWithAKey() {
        assertFalse(HueApi.Bridge(host = "192.0.2.10").hasKey)
        assertTrue(bridge.hasKey)
    }

    @Test
    fun theButtonNotPressedReplyIsAwaitingNotFailed() {
        // The bridge's real answer until the button is pressed. Reporting this as a failure would
        // send a person looking for a fault in an app that is working as designed.
        val body = """[{"error":{"type":101,"address":"","description":"link button not pressed"}}]"""
        val outcome = HueApi.readPairingResponse(body)
        assertEquals(HueApi.PairingState.AWAITING_BUTTON, outcome.state)
        assertNull("no key exists yet", outcome.applicationKey)
        assertTrue(
            "the sentence must name the next move, which is the person's: ${outcome.sentence}",
            outcome.sentence.contains("button"),
        )
    }

    @Test
    fun aSuccessfulPairingCarriesTheKey() {
        val body = """[{"success":{"username":"1028d66426293e821ecfd9ef1a0731df"}}]"""
        val outcome = HueApi.readPairingResponse(body)
        assertEquals(HueApi.PairingState.PAIRED, outcome.state)
        assertEquals("1028d66426293e821ecfd9ef1a0731df", outcome.applicationKey)
        assertTrue(outcome.sentence.contains("key"))
    }

    @Test
    fun aSuccessWithoutAKeyIsUnexpectedRatherThanPaired() {
        // A bridge that reports success and sends nothing is not usable, and claiming PAIRED would
        // produce a key-less bridge that fails every later call.
        val outcome = HueApi.readPairingResponse("""[{"success":{}}]""")
        assertEquals(HueApi.PairingState.UNEXPECTED, outcome.state)
        assertNull(outcome.applicationKey)
    }

    @Test
    fun anUnrecognisedPairingReplyIsUnexpectedAndQuotesWhatArrived() {
        val outcome = HueApi.readPairingResponse("<html>router login</html>")
        assertEquals(HueApi.PairingState.UNEXPECTED, outcome.state)
        assertTrue(
            "the sentence must quote the reply, so a wrong address is visible: ${outcome.sentence}",
            outcome.sentence.contains("html"),
        )
    }

    @Test
    fun percentMapsOntoTheBridgesOwnScale() {
        // Hue brightness runs 0-254 while a person says 0-100. Getting this wrong makes every
        // brightness request land at less than half what was asked for.
        assertEquals(0, HueApi.percentToHueBrightness(0))
        assertEquals(127, HueApi.percentToHueBrightness(50))
        assertEquals(254, HueApi.percentToHueBrightness(100))
    }

    @Test
    fun theDimmestRequestDoesNotTurnTheLightOff() {
        // A naive integer division gives 2 for 1 percent, which is right; a floor of 0 would turn
        // the light off when a caller asked for the dimmest possible setting.
        assertEquals(3, HueApi.percentToHueBrightness(1))
        assertTrue(
            "1 percent must never become 0, which means off",
            HueApi.percentToHueBrightness(1) > 0,
        )
    }

    @Test
    fun outOfRangePercentagesAreClampedRatherThanRejected() {
        assertEquals(254, HueApi.percentToHueBrightness(150))
        assertEquals(0, HueApi.percentToHueBrightness(-5))
    }

    @Test
    fun onlyTheFieldsGivenAreSent() {
        // A PUT that omits `on` leaves the light as it is. Sending every field would silently turn
        // a light on when the caller only asked to dim one that was already on.
        assertEquals("""{"bri":127}""", HueApi.lightStateBody(brightnessPercent = 50))
        assertEquals("""{"on":true}""", HueApi.lightStateBody(on = true))
        assertEquals(
            """{"on":false,"bri":254}""",
            HueApi.lightStateBody(on = false, brightnessPercent = 100),
        )
    }

    @Test
    fun colourTemperatureIsClampedToWhatBulbsAccept() {
        assertEquals("""{"ct":153}""", HueApi.lightStateBody(temperatureMired = 50))
        assertEquals("""{"ct":500}""", HueApi.lightStateBody(temperatureMired = 9000))
        assertEquals("""{"ct":300}""", HueApi.lightStateBody(temperatureMired = 300))
    }

    @Test
    fun aRevokedKeyIsARepairingNotAnEmptyHouse() {
        // The bridge answers error type 1 when a key has been revoked from the Hue app. Reporting
        // that as "no lights found" sends a person looking for their bulbs.
        val body = """[{"error":{"type":1,"address":"/lights","description":"unauthorized user"}}]"""
        assertTrue(HueApi.needsRepairing(body))
        val sentence = HueApi.failureSentence("192.0.2.10", 200, body)
        assertTrue(
            "the sentence must say the key has to be re-paired: $sentence",
            sentence.contains("paired again"),
        )
    }

    @Test
    fun aConnectionFailureNamesTheNetworkRatherThanTheBulbs() {
        val sentence = HueApi.failureSentence(
            "192.0.2.10",
            null,
            "",
            threw = "java.net.ConnectException: failed to connect",
        )
        assertTrue(
            "an unreachable bridge is a network problem and must say so: $sentence",
            sentence.contains("could not be reached"),
        )
        assertTrue(sentence.contains("same network"))
    }

    @Test
    fun anHttpErrorQuotesTheStatusAndTheBody() {
        val sentence = HueApi.failureSentence("192.0.2.10", 500, "internal error")
        assertTrue(sentence.contains("500"))
        assertTrue(sentence.contains("internal error"))
    }

    @Test
    fun theLightListIsReadByIdKeySoTheRightBulbIsAddressed() {
        // The bridge addresses a light by its KEY in every later request. An id invented from an
        // index would change the wrong bulb.
        val body = """
            {"1":{"state":{"on":true},"name":"Kitchen ceiling"},
             "3":{"state":{"on":false},"name":"Hallway lamp"}}
        """.trimIndent()
        val entries = HueApi.entriesIn(body)
        assertEquals(2, entries.size)
        assertEquals(HueApi.NamedEntry("1", "Kitchen ceiling"), entries[0])
        assertEquals(HueApi.NamedEntry("3", "Hallway lamp"), entries[1])
    }

    @Test
    fun aNameWithAnEscapedQuoteIsReadCorrectly() {
        val body = """{"7":{"name":"Steven's \"reading\" lamp"}}"""
        val entries = HueApi.entriesIn(body)
        assertEquals(1, entries.size)
        assertEquals("Steven's \"reading\" lamp", entries[0].name)
    }

    @Test
    fun anEmptyLightResponseIsAnEmptyListNotACrash() {
        assertEquals(emptyList<HueApi.NamedEntry>(), HueApi.entriesIn("{}"))
        assertEquals(emptyList<HueApi.NamedEntry>(), HueApi.entriesIn(""))
    }

    @Test
    fun theNumericReaderFindsATypeFieldWhateverTheSpacing() {
        assertEquals(101, HueApi.extractNumber("""{"type":101}""", "type"))
        assertEquals(101, HueApi.extractNumber("""{"type": 101}""", "type"))
        assertNull(HueApi.extractNumber("""{"other":1}""", "type"))
    }

    @Test
    fun theProductNameInThePairingBodyCarriesNoInstallIdentity() {
        // Same reasoning as the web user agent: the devicetype names the software and nothing about
        // which install it is, so a bridge's list of keys cannot be used to fingerprint a person.
        assertEquals("""{"devicetype":"guru#phone"}""", HueApi.PAIRING_BODY)
        assertFalse(HueApi.PAIRING_BODY.contains("Android"))
        assertFalse(HueApi.PAIRING_BODY.contains("Build"))
    }
}
