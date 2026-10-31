// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.bridge.BridgeFrame
import com.unuslumen.app.data.bridge.MAX_FRAME_CHARS
import com.unuslumen.app.data.bridge.PROTOCOL_VERSION
import com.unuslumen.app.data.bridge.ParseResult
import com.unuslumen.app.data.bridge.authoriseHello
import com.unuslumen.app.data.bridge.constantTimeEquals
import com.unuslumen.app.data.bridge.encodeFrame
import com.unuslumen.app.data.bridge.maskToken
import com.unuslumen.app.data.bridge.parseFrame
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the CLI bridge wire protocol, which two implementations in two languages
 * have to agree on for years.
 *
 * The transport is a socket and the frames are text, so the parts that can go
 * wrong are the parsing, the version gate and the token check. All three are pure
 * and all three are tested here without a device.
 */
class BridgeProtocolTest {

    private val token = "abcdefghijklmnopqrstuvwxyz0123456789ABCDEFG"

    // ---- round trip ----

    @Test
    fun aHelloSurvivesARoundTrip() {
        val frame = BridgeFrame.Hello(agent = "lux", token = token)
        val line = encodeFrame(frame)
        assertTrue("a frame ends with a newline", line.endsWith("\n"))

        val parsed = parseFrame(line)
        assertTrue("parse failed: $parsed", parsed is ParseResult.Ok)
        val back = (parsed as ParseResult.Ok).frame
        assertEquals(BridgeFrame.Hello(agent = "lux", version = PROTOCOL_VERSION, token = token), back)
    }

    @Test
    fun aMessageSurvivesARoundTrip() {
        val frame = BridgeFrame.Message(text = "picked up #175", from = "lux", at = 1793436230000L)
        val parsed = parseFrame(encodeFrame(frame))
        assertEquals(frame, (parsed as ParseResult.Ok).frame)
    }

    @Test
    fun aStatusReplySurvivesARoundTrip() {
        val frame = BridgeFrame.Status(inflight = listOf("#175"), queued = listOf("#174"), blocked = emptyList())
        val parsed = parseFrame(encodeFrame(frame))
        assertEquals(frame, (parsed as ParseResult.Ok).frame)
    }

    @Test
    fun theTypeDiscriminatorIsOnTheWire() {
        // A client in another language reads `type` to know what it got.
        val line = encodeFrame(BridgeFrame.Hello(agent = "lux", token = token))
        assertTrue("hello must serialise with type=hello, got: $line", line.contains("\"type\":\"hello\""))
    }

    // ---- bad input ----

    @Test
    fun anEmptyLineIsRejectedRatherThanTreatedAsNoFrame() {
        val parsed = parseFrame("\n")
        assertTrue(parsed is ParseResult.Bad)
    }

    @Test
    fun garbageIsRejectedWithACode() {
        val parsed = parseFrame("not json at all")
        assertTrue(parsed is ParseResult.Bad)
        assertEquals(BridgeFrame.CODE_BAD_FRAME, (parsed as ParseResult.Bad).code)
    }

    @Test
    fun anOversizedFrameIsRefusedBeforeParsing() {
        val huge = "x".repeat(MAX_FRAME_CHARS + 1)
        val parsed = parseFrame(huge)
        assertTrue(parsed is ParseResult.Bad)
        assertEquals(
            "an unbounded frame is how one peer exhausts the other's heap",
            BridgeFrame.CODE_TOO_LONG,
            (parsed as ParseResult.Bad).code,
        )
    }

    @Test
    fun anUnknownFieldIsToleratedSoTheProtocolCanGrow() {
        val line = """{"type":"message","text":"hi","from":"lux","at":1,"futureField":true}""" + "\n"
        val parsed = parseFrame(line)
        assertTrue(
            "a new optional field on one side must not break the other",
            parsed is ParseResult.Ok,
        )
    }

    // ---- authorisation ----

    @Test
    fun aCorrectHelloIsAuthorised() {
        assertNull(authoriseHello(BridgeFrame.Hello(agent = "lux", token = token), token))
    }

    @Test
    fun aWrongTokenIsRefused() {
        val rejection = authoriseHello(BridgeFrame.Hello(agent = "lux", token = "wrong"), token)
        assertNotNull(rejection)
        assertEquals(BridgeFrame.CODE_BAD_TOKEN, rejection!!.code)
    }

    @Test
    fun aVersionMismatchIsRefusedBEFORETheTokenIsJudged() {
        val rejection = authoriseHello(
            BridgeFrame.Hello(agent = "lux", version = PROTOCOL_VERSION + 1, token = "wrong"),
            token,
        )
        assertEquals(
            "Version must be checked before the token. Otherwise a mismatched client " +
                "is told its secret is wrong and its operator hunts the wrong problem.",
            BridgeFrame.CODE_VERSION,
            rejection!!.code,
        )
    }

    @Test
    fun aFirstFrameThatIsNotHelloIsRefused() {
        val rejection = authoriseHello(BridgeFrame.Message("hi", "lux", 1), token)
        assertEquals(BridgeFrame.CODE_BAD_FRAME, rejection!!.code)
    }

    // ---- constant time compare ----

    @Test
    fun equalStringsCompareEqual() {
        assertTrue(constantTimeEquals(token, token))
    }

    @Test
    fun differentStringsOfTheSameLengthFail() {
        assertFalse(constantTimeEquals(token, token.dropLast(1) + "X"))
    }

    @Test
    fun aPrefixIsNotEqual() {
        assertFalse(constantTimeEquals(token.take(10), token))
    }

    // ---- token masking ----

    @Test
    fun aTokenIsNeverFormattedWhole() {
        val masked = maskToken(token)
        assertFalse("the full token must never appear in a log line", masked.contains(token))
        assertTrue(masked.contains("..."))
    }

    @Test
    fun aShortValueIsFullyHidden() {
        assertEquals("***", maskToken("short"))
    }
}
