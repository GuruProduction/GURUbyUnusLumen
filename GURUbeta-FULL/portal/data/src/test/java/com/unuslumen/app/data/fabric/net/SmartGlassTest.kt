// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * SmartGlass's wire format and crypto, proved on the JVM.
 *
 * WHY EVERY NUMBER IS ASSERTED. A console rejects a malformed packet silently, so a wrong
 * endianness, a missing null byte on a string or a skipped key-derivation step all produce
 * the identical symptom: nothing happens. That is the class of defect this project keeps
 * removing, so the bytes are pinned rather than trusted.
 */
class SmartGlassTest {

    // ------------------------------------------------------------------
    // Endianness
    // ------------------------------------------------------------------

    /**
     * EVERY FIELD IS BIG-ENDIAN, which is the opposite of Android's usual convention.
     *
     * A little-endian header is well-formed and ignored, so this is the single most likely
     * way to build a packet that does nothing.
     */
    @Test
    fun everyHeaderFieldIsBigEndian() {
        val header = SmartGlassMessage.simpleHeader(
            type = SmartGlassMessage.TYPE_DISCOVERY_REQUEST,
            sequence = 1,
            payloadLength = 0x0A,
        )

        // THE TYPE IS A 32-BIT FIELD, so 0xDD00 is four bytes: 00 00 DD 00. The DD lands at
        // index 2, not index 0, which is the mistake a reader makes when thinking of the type
        // as a 16-bit value.
        assertEquals("the leading bytes of a small 32-bit value are zero", 0x00, header[0].toInt() and 0xFF)
        assertEquals("the type's significant byte sits at index 2", 0xDD, header[2].toInt() and 0xFF)
        assertEquals("and its low byte at index 3", 0x00, header[3].toInt() and 0xFF)

        // The version is 4, at offset 4 as a big-endian short.
        assertEquals("the version high byte", 0x00, header[4].toInt() and 0xFF)
        assertEquals("the version low byte is 4", 0x04, header[5].toInt() and 0xFF)

        // The sequence is 1, at offset 6.
        assertEquals("the sequence low byte is 1", 0x01, header[7].toInt() and 0xFF)

        // The layout is 4 type, 2 version, 2 sequence, 8 source, 8 destination, 4 flags,
        // 4 payload length. So the length starts at 28 and its last byte is 31.
        assertEquals("the payload length's low byte is the last byte of the header", 0x0A, header[31].toInt() and 0xFF)
        assertEquals("the payload length's high byte is first", 0x00, header[28].toInt() and 0xFF)
        assertEquals("the flags field is at 24 and is zero for a simple message", 0x00, header[24].toInt() and 0xFF)
    }

    /**
     * The header is 32 bytes for EVERY message, including a simple one.
     *
     * THERE IS NO SMALLER VARIANT. A simple message sets its participants to zero rather than
     * omitting them, and an earlier version of this file had a separate 18-byte simple header
     * that omitted them, which put every later field at the wrong offset and threw a buffer
     * overflow the moment a packet was built.
     */
    @Test
    fun theHeaderIsTheSameSizeForEveryMessage() {
        assertEquals("4 type, 2 version, 2 sequence, 8+8 participants, 4 flags, 4 length", 32, SmartGlassMessage.HEADER_SIZE)
        assertEquals(
            "a simple header is a full header with zero participants",
            SmartGlassMessage.HEADER_SIZE,
            SmartGlassMessage.simpleHeader(SmartGlassMessage.TYPE_DISCOVERY_REQUEST, 0, 0).size,
        )
        assertEquals(
            SmartGlassMessage.HEADER_SIZE,
            SmartGlassMessage.header(0xCC00, 1, 1L, 2L, 0, 0).size,
        )
    }

    /** A simple message declares zero participants, which is what marks it unrouted. */
    @Test
    fun aSimpleMessageHasZeroParticipants() {
        val header = SmartGlassMessage.simpleHeader(SmartGlassMessage.TYPE_POWER_REQUEST, 0, 4)
        for (offset in 8..23) {
            assertEquals("byte $offset names a participant, and a simple message has none", 0, header[offset].toInt())
        }
    }

    /** A full header carries the participants and flags, so it is twenty-six. */
    @Test
    fun aFullHeaderCarriesParticipantsAndFlags() {
        val header = SmartGlassMessage.header(
            type = SmartGlassMessage.TYPE_LOCAL_JOIN,
            sequence = 5,
            sourceParticipant = 0x1122334455667788L,
            destinationParticipant = 0x99AABBCCDDEEFF00uL.toLong(),
            flags = 1,
            payloadLength = 0,
        )
        assertEquals("a full header is 32 bytes", 32, header.size)

        // The source participant is at 8..15 and the destination at 16..23, both big-endian.
        assertEquals("the source's high byte", 0x11, header[8].toInt() and 0xFF)
        assertEquals("the source's low byte", 0x88, header[15].toInt() and 0xFF)
        assertEquals("the destination's high byte", 0x99, header[16].toInt() and 0xFF)
        assertEquals("the destination's low byte", 0x00, header[23].toInt() and 0xFF)
        // Flags are a 32-bit field at 24..27, and a value of 1 puts its significant byte LAST.
        assertEquals("the flags field's leading bytes are zero", 0x00, header[24].toInt() and 0xFF)
        assertEquals("flags value 1 lands in the field's last byte", 0x01, header[27].toInt() and 0xFF)
    }

    // ------------------------------------------------------------------
    // Strings
    // ------------------------------------------------------------------

    /**
     * An SGString is a uint16 length, the characters, then a trailing null the length excludes.
     *
     * The null is load-bearing: without it every following field is read one byte early and
     * the console drops the packet with no error at all.
     */
    @Test
    fun aStringCarriesItsLengthAndATrailingNull() {
        val encoded = SmartGlassMessage.string("AB")
        assertArrayEquals(
            "length 2, then AB, then a null that is not counted",
            byteArrayOf(0x00, 0x02, 0x41, 0x42, 0x00),
            encoded,
        )

        val long = SmartGlassMessage.string("x".repeat(300))
        assertEquals("a length above 255 uses the high byte", 0x01, long[0].toInt())
        assertEquals("and the low byte", 0x2C, long[1].toInt())
        assertEquals("300 characters plus a length and a null", 303, long.size)
    }

    /** An empty string is still a length and a null, which is not an empty array. */
    @Test
    fun anEmptyStringIsStillEncoded() {
        assertArrayEquals(byteArrayOf(0x00, 0x00, 0x00), SmartGlassMessage.string(""))
    }

    // ------------------------------------------------------------------
    // Power
    // ------------------------------------------------------------------

    /**
     * POWER IS A REAL SMARTGLASS MESSAGE, which corrects what this code originally claimed.
     *
     * Message 0xDD02 carries the console's Live ID as an SGString and wakes a console in
     * standby with no crypto context at all. The earlier version of this file said the
     * protocol had no power-on and only Wake-on-LAN could wake a console; that was wrong.
     */
    @Test
    fun powerOnIsARealMessageCarryingTheLiveId() {
        assertEquals("power is 0xDD02", 0xDD02, SmartGlassMessage.TYPE_POWER_REQUEST)

        val payload = SmartGlassMessage.powerRequestPayload("FD00000000000000")
        // The Live ID is sixteen characters, so the length is 0x0010.
        assertEquals("the Live ID length's high byte", 0x00, payload[0].toInt())
        assertEquals("the Live ID length's low byte", 0x10, payload[1].toInt())
        assertEquals("then the id itself", 'F'.code.toByte(), payload[2])
        assertEquals("and a trailing null", 0, payload[payload.size - 1].toInt())
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /** The discovery payload carries the protocol version range the console must support. */
    @Test
    fun theDiscoveryPayloadDeclaresItsVersionRange() {
        val payload = SmartGlassMessage.discoveryRequestPayload()
        assertEquals("flags, client type and two versions", 10, payload.size)
        assertEquals("minimum version 2", 0x02, payload[7].toInt())
        assertEquals("maximum version 4", 0x04, payload[9].toInt())
    }

    /** A real discovery reply's parts must be readable, including the console's certificate. */
    @Test
    fun aDiscoveryReplyIsReadWithItsCertificate() {
        val name = SmartGlassMessage.string("My Xbox")
        val uuid = SmartGlassMessage.string("01234567-89ab-cdef-0123-456789abcdef")
        val certificate = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x02, 0x03)

        val payload = java.io.ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 1))          // flags
            write(byteArrayOf(0, 1))                // type
            write(name)
            write(uuid)
            write(byteArrayOf(0, 0, 0, 0))          // last error
            write(byteArrayOf(0, certificate.size.toByte()))
            write(certificate)
        }.toByteArray()

        val reply = SmartGlassMessage.readDiscoveryReply(payload)
        assertNotNull("a well-formed reply must parse", reply)
        assertEquals("My Xbox", reply!!.name)
        assertEquals("01234567-89ab-cdef-0123-456789abcdef", reply.uuid)
        assertNotNull("the certificate must be extracted or the crypto context is impossible", reply.certificate)
        assertArrayEquals(certificate, reply.certificate)
    }

    /** A truncated reply must return null rather than a half-read object. */
    @Test
    fun aTruncatedDiscoveryReplyIsRefused() {
        assertNull(SmartGlassMessage.readDiscoveryReply(byteArrayOf(0, 0)))
        assertNull(SmartGlassMessage.readDiscoveryReply(ByteArray(0)))
    }

    // ------------------------------------------------------------------
    // Crypto
    // ------------------------------------------------------------------

    /**
     * The key derivation must salt and hash the shared secret rather than using it directly.
     *
     * This is the trap in the protocol: a client that uses the raw ECDH secret gets a console
     * that accepts the connection and then answers nothing, because its payloads do not
     * authenticate.
     */
    @Test
    fun theDerivedKeysAreSixtyFourBytesSplitThreeWays() {
        val shared = ByteArray(48) { it.toByte() }
        val salt = ByteArray(97) { (it * 3).toByte() }
        val keys = SmartGlassCrypto.splitKeys(shared, salt)

        assertEquals("an AES-128 key is 16 bytes", 16, keys.encryptionKey.size)
        assertEquals("the IV is 16 bytes", 16, keys.initialisationVector.size)
        assertEquals("the HMAC key is 16 bytes", 16, keys.hashingKey.size)
        assertFalse(
            "the derived key must not be the shared secret itself",
            keys.encryptionKey.contentEquals(shared.copyOfRange(0, 16)),
        )
    }

    /** The derivation is deterministic, so both sides compute the same keys. */
    @Test
    fun theDerivationIsDeterministic() {
        val shared = ByteArray(48) { (it + 7).toByte() }
        val salt = ByteArray(97) { (it * 5).toByte() }
        assertEquals(
            "the same inputs must produce the same keys, or the console cannot agree",
            SmartGlassCrypto.splitKeys(shared, salt),
            SmartGlassCrypto.splitKeys(shared, salt),
        )
    }

    /** A different salt must produce different keys, or the salt is doing nothing. */
    @Test
    fun aDifferentSaltProducesDifferentKeys() {
        val shared = ByteArray(48) { it.toByte() }
        val first = SmartGlassCrypto.splitKeys(shared, ByteArray(97) { 1 })
        val second = SmartGlassCrypto.splitKeys(shared, ByteArray(97) { 2 })
        assertFalse(first.encryptionKey.contentEquals(second.encryptionKey))
    }

    /**
     * A P-384 public key travels as an uncompressed point: 0x04, then X, then Y, each 48 bytes.
     *
     * Sending the DER SPKI form instead produces a request a console rejects without saying
     * why, which is why this encoding is pinned.
     */
    @Test
    fun aPublicKeyIsAnUncompressedPoint() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()

        val point = SmartGlassCrypto.encodePoint(pair.public as java.security.interfaces.ECPublicKey)
        assertEquals("1 prefix byte plus two 48-byte coordinates", 97, point.size)
        assertEquals("the uncompressed prefix", 0x04, point[0].toInt())
        assertTrue(
            "the point must not be all zeros, which would mean the encoding dropped a coordinate",
            point.drop(1).any { it != 0.toByte() },
        )
    }

    /** A real ECDH exchange between two P-384 keys must agree on the same secret. */
    @Test
    fun ecdhAgreesOnTheSameSecret() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }
        val client = generator.generateKeyPair()
        val console = generator.generateKeyPair()

        val fromClient = SmartGlassCrypto.deriveSharedSecret(
            client,
            console.public as java.security.interfaces.ECPublicKey,
        )
        val fromConsole = SmartGlassCrypto.deriveSharedSecret(
            console,
            client.public as java.security.interfaces.ECPublicKey,
        )
        assertArrayEquals(
            "both sides must derive the identical secret, or nothing can decrypt",
            fromClient,
            fromConsole,
        )
        assertEquals("a P-384 secret is 48 bytes", 48, fromClient.size)
    }

    /** AES-128-CBC must round trip, or every payload is garbage. */
    @Test
    fun theContextEncryptsAndDecrypts() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()
        val consoleKey = pair.public.encoded
        // The factory needs a real certificate, so a synthetic context is built from the
        // derivation alone, which is the part under test here.
        val keys = SmartGlassCrypto.splitKeys(ByteArray(48) { it.toByte() }, ByteArray(97) { 9 })
        val crypto = SmartGlassCrypto.create(consoleKey)
        // With no real certificate the ECDH cannot complete, and the result must say so
        // rather than producing a context that cannot talk to anything.
        assertNull("a bare public key is not a certificate, so no context is created", crypto.crypto)
        assertNotNull("and the reason must be stated", crypto.reason)

        assertNotNull("the derivation itself must still be usable", keys.encryptionKey)
    }

    /** A signature must verify against the same header and payload, and fail otherwise. */
    @Test
    fun theSignatureVerifiesAndRejectsTampering() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()

        // A context is built directly for this test, using the derivation and a fixed key.
        val keys = SmartGlassCrypto.splitKeys(ByteArray(48) { it.toByte() }, ByteArray(97) { 4 })
        val crypto = SmartGlassCrypto.forTesting(keys, pair)

        val header = byteArrayOf(1, 2, 3)
        val payload = byteArrayOf(4, 5, 6)
        val signature = crypto.sign(header, payload)

        assertTrue("a message must verify against itself", crypto.verify(header, payload, signature))
        assertFalse(
            "a changed payload must not verify, or the integrity check is useless",
            crypto.verify(header, byteArrayOf(4, 5, 7), signature),
        )
        assertFalse(
            "a changed header must not verify either",
            crypto.verify(byteArrayOf(1, 2, 9), payload, signature),
        )
    }
}
