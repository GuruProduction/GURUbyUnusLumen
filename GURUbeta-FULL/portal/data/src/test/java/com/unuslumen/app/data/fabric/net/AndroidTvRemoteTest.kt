// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android TV protocol's encoding, proved on the JVM against the protobuf specification.
 *
 * WHY THE BYTES MATTER MORE THAN THE LOGIC. A television does not reply "your protobuf was
 * malformed". It closes the connection, which looks exactly like a set that is switched off.
 * So the varints, the field tags and the framing are asserted here rather than discovered
 * against his Fire TV.
 */
class AndroidTvRemoteTest {

    private val remote = AndroidTvRemote(host = "192.168.0.71")

    // ------------------------------------------------------------------
    // Protobuf primitives
    // ------------------------------------------------------------------

    /**
     * A protobuf varint is base-128 with a continuation bit.
     *
     * These are the documented encodings: 0 is one byte, 127 is one byte, 128 is two, and
     * 300 is the canonical two-byte example from the specification.
     */
    @Test
    fun varintsMatchTheSpecification() {
        assertArrayEquals("0 encodes as one zero byte", byteArrayOf(0), remote.varint(0))
        assertArrayEquals("127 is the largest single byte", byteArrayOf(127), remote.varint(127))
        assertArrayEquals(
            "128 needs two bytes, 0x80 0x01",
            byteArrayOf(0x80.toByte(), 0x01),
            remote.varint(128),
        )
        assertArrayEquals(
            "300 is the specification's own example: 0xAC 0x02",
            byteArrayOf(0xAC.toByte(), 0x02),
            remote.varint(300),
        )
        assertArrayEquals(
            "622 needs two bytes",
            byteArrayOf(0xEE.toByte(), 0x04),
            remote.varint(622),
        )
    }

    /**
     * A varint field's tag is the field number shifted left three, with wire type 0.
     *
     * Field 1 as a varint is tag 0x08, which is the tag a device expects, and getting the
     * shift wrong sends a field number the device ignores.
     */
    @Test
    fun aVarintFieldTagIsFieldNumberShiftedLeftThree() {
        assertArrayEquals(
            "field 1, wire type 0, value 1 is 0x08 0x01",
            byteArrayOf(0x08, 0x01),
            remote.fieldVarint(1, 1),
        )
        assertArrayEquals(
            "field 2, wire type 0, value 3 is 0x10 0x03",
            byteArrayOf(0x10, 0x03),
            remote.fieldVarint(2, 3),
        )
        assertArrayEquals(
            "field 10, wire type 0, value 26 is 0x50 0x1A",
            byteArrayOf(0x50, 0x1A),
            remote.fieldVarint(10, 26),
        )
    }

    /** A length-delimited field carries the tag, the length, then the bytes. */
    @Test
    fun aLengthDelimitedFieldCarriesItsLength() {
        val encoded = remote.fieldBytes(2, "abc".toByteArray())
        // Field 2, wire type 2 is 0x12, then length 3, then the bytes.
        assertArrayEquals(
            byteArrayOf(0x12, 0x03, 0x61, 0x62, 0x63),
            encoded,
        )
    }

    // ------------------------------------------------------------------
    // The messages
    // ------------------------------------------------------------------

    /**
     * RemoteKeyInject is field 10 and carries the keycode in field 1 and the direction in
     * field 2.
     *
     * The keycode values are Android's own, which this protocol sends directly: 26 is power,
     * 24 and 25 are volume, 23 is the D-pad centre. A wrong code is a button that does
     * nothing on a television that is definitely listening.
     */
    @Test
    fun aKeyInjectionCarriesTheAndroidKeycode() {
        val power = remote.remoteKeyInject(keyCode = 26, direction = 3)
        // Outer field 10 tag with wire type 2 is 0x52, then length 4, then 0x08 26 0x10 3.
        assertEquals("outer tag for field 10 is 0x52", 0x52, power[0].toInt())
        assertEquals("inner length is four bytes", 4, power[1].toInt())
        assertEquals("inner field 1 tag", 0x08, power[2].toInt())
        assertEquals("the keycode is 26", 26, power[3].toInt())
        assertEquals("inner field 2 tag", 0x10, power[4].toInt())
        assertEquals("the direction is SHORT, which is 3", 3, power[5].toInt())
    }

    /** Every button this adapter offers must map to a real Android keycode. */
    @Test
    fun everyButtonMapsToARealKeycode() {
        val expected = mapOf(
            "power" to 26,
            "volume up" to 24,
            "volume down" to 25,
            "mute" to 164,
            "up" to 19,
            "down" to 20,
            "left" to 21,
            "right" to 22,
            "ok" to 23,
            "back" to 4,
            "home" to 3,
            "menu" to 82,
            "play" to 85,
            "input" to 178,
        )
        for ((button, code) in expected) {
            assertEquals("'$button' must map to $code", code, AndroidTvKeys.forButton(button))
        }
        assertNull("an unknown button must not be guessed", AndroidTvKeys.forButton("netflix"))
    }

    /**
     * The outer frame is two length-delimited fields: the protocol version then the message.
     *
     * A caller that reverses them connects, sends, and is ignored, with no error anywhere.
     */
    @Test
    fun theOuterFrameCarriesVersionThenMessage() {
        val output = java.io.ByteArrayOutputStream()
        remote.writeMessage(output, remote.remoteKeyInject(26))
        val frame = output.toByteArray()

        // The frame is a length varint, then field 1 (version), then field 2 (the message).
        val first = frame[0].toInt()
        assertTrue("the frame must have a length prefix", first in 1..0x7F)
        assertEquals("the inner payload is that length", first, frame.size - 1)

        // THE VERSION IS A VARINT, NOT A NESTED MESSAGE. Field 1 with wire type 0 is tag 0x08
        // followed directly by the value 2. Writing it as length-delimited (tag 0x0A) produces
        // a frame a set parses as a different message and silently drops, which is why this
        // one byte is pinned.
        assertEquals("outer field 1 tag for a varint", 0x08, frame[1].toInt())
        assertEquals("the protocol version is 2", 2, frame[2].toInt())

        // Field 2 with wire type 2 is tag 0x12, then a length, then the message.
        assertEquals("outer field 2 tag carries the message", 0x12, frame[3].toInt())
        assertTrue("the message must be non-empty", frame.size > 4)
    }

    /** The configure message must carry its magic code, or a set rejects it silently. */
    @Test
    fun theConfigureMessageCarriesItsMagicCode() {
        val configure = remote.remoteConfigure()
        assertTrue("configure must be non-empty", configure.isNotEmpty())
        assertEquals("configure is field 1 of RemoteMessage", 0x0A, configure[0].toInt())
        // 622 encodes as 0xEE 0x04, and it must appear in the payload.
        val containsCode = configure.toList().windowed(2).any {
            it[0].toInt() and 0xFF == 0xEE && it[1].toInt() and 0xFF == 0x04
        }
        assertTrue("the magic code 622 must be present, or the set ignores the connection", containsCode)
    }

    /**
     * A pairing reply's status must be named, because "pairing failed" sends a person
     * hunting for the wrong thing when the real answer is a wrong code.
     */
    @Test
    fun aPairingStatusIsNamed() {
        // Status 1 is OK, encoded as field 1 varint 1.
        assertEquals("OK", remote.describeStatus(byteArrayOf(0x08, 0x01)))
        assertEquals(
            "a bad code must say so",
            "BAD_CODE (the six digits did not match)",
            remote.describeStatus(byteArrayOf(0x08, 0x02)),
        )
        assertEquals("an unknown status must quote its number", "status 9", remote.describeStatus(byteArrayOf(0x08, 0x09)))
    }

    /** The certificate the set issues is field 4 and must be extracted intact. */
    @Test
    fun theIssuedCertificateIsExtracted() {
        val certificate = byteArrayOf(1, 2, 3, 4, 5)
        // Field 4 with wire type 2 is 0x22, then the length, then the bytes.
        val reply = byteArrayOf(0x08, 0x01) + byteArrayOf(0x22, certificate.size.toByte()) + certificate
        val extracted = remote.extractCertificate(reply)
        assertNotNull("the certificate must be found", extracted)
        assertArrayEquals(certificate, extracted)
    }

    /** A reply with no certificate must yield null rather than an empty array. */
    @Test
    fun aReplyWithoutACertificateYieldsNull() {
        assertNull(remote.extractCertificate(byteArrayOf(0x08, 0x01)))
    }

    /** A pairing code that is not six digits is refused before any connection is opened. */
    @Test
    fun aMalformedPairingCodeIsRefused() = kotlinx.coroutines.runBlocking {
        val short = remote.pair("12345")
        assertNull(short.device)
        assertTrue(
            "the refusal must say a code is six digits, and got: ${short.reason}",
            short.reason!!.contains("six digits"),
        )

        val letters = remote.pair("abcdef")
        assertNull(letters.device)
        assertTrue(letters.reason!!.contains("six digits"))
    }

    /**
     * The ALPN name is part of the handshake, and a modern set declines the connection
     * without it. This pins the constant so it cannot be quietly changed.
     */
    @Test
    fun theRemoteChannelUsesItsAlpnName() {
        val field = AndroidTvRemote::class.java.declaredFields
            .firstOrNull { it.name.contains("ALPN", ignoreCase = true) }
        assertNotNull("the ALPN constant must exist", field)
        field!!.isAccessible = true
        assertEquals("androidtvremote2", field.get(null))
    }
}
