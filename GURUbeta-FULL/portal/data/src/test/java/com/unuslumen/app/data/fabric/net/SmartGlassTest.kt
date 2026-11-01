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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.spec.ECGenParameterSpec

/**
 * SmartGlass's wire format and crypto, pinned to the corrected protocol.
 *
 * EVERY ASSERTION HERE CORRESPONDS TO SOMETHING THAT WOULD FAIL SILENTLY ON HARDWARE. A console
 * does not report a malformed packet, it drops it. So a wrong key type, a wrong header layout, a
 * message type written as a packet type, or padding added to an aligned payload all look
 * identical from this side, which is nothing at all.
 *
 * The corrections encoded here came from the reference implementation's own struct definitions
 * and from an external review that caught several of them.
 */
class SmartGlassTest {

    // ------------------------------------------------------------------
    // Headers
    // ------------------------------------------------------------------

    /**
     * A simple header is SIX bytes: type, unprotected length, version.
     *
     * Only a connect packet adds the protected length, making it eight. Writing that field
     * unconditionally makes every other simple packet two bytes too long.
     */
    @Test
    fun aSimpleHeaderIsSixBytesAndAConnectHeaderIsEight() {
        assertEquals("type 2, unprotected length 2, version 2", 6, SmartGlassMessage.SIMPLE_HEADER_SIZE)
        assertEquals("a connect packet adds the protected length", 8, SmartGlassMessage.CONNECT_HEADER_SIZE)

        assertEquals(
            "a discovery header is six bytes",
            6,
            SmartGlassMessage.simpleHeader(SmartGlassMessage.TYPE_DISCOVERY_REQUEST, 10).size,
        )
        assertEquals(
            "a connect header is eight bytes",
            8,
            SmartGlassMessage.simpleHeader(SmartGlassMessage.TYPE_CONNECT_REQUEST, 130, 16).size,
        )
    }

    /** The version in every header is 2, and a wrong version is dropped unanswered. */
    @Test
    fun theHeaderVersionIsTwo() {
        assertEquals("the protocol version is 2", 2, SmartGlassMessage.VERSION)
        val header = SmartGlassMessage.simpleHeader(SmartGlassMessage.TYPE_DISCOVERY_REQUEST, 0)
        assertEquals(
            "the version field carries 2",
            2,
            ByteBuffer.wrap(header, 4, 2).order(ByteOrder.BIG_ENDIAN).short.toInt(),
        )
    }

    /**
     * The message header is 26 bytes in the reference's field order.
     *
     * A different order is well-formed and ignored, and the console gives no clue which field it
     * disliked.
     */
    @Test
    fun theMessageHeaderIsTwentySixBytesInOrder() {
        assertEquals(
            "2 type, 2 protected length, 4 sequence, 4 target, 4 source, 2 flags, 8 channel",
            26,
            SmartGlassMessage.MESSAGE_HEADER_SIZE,
        )

        val header = SmartGlassMessage.messageHeader(
            type = SmartGlassMessage.TYPE_MESSAGE,
            protectedLength = 16,
            sequence = 5,
            targetParticipant = 0x11223344,
            sourceParticipant = 0x55667788,
            flags = SmartGlassMessage.Flags(needAck = true, messageType = SmartGlassMessage.MSG_GAMEPAD),
            channelId = 0x010203040506070L,
        )
        assertEquals("the header is 26 bytes", 26, header.size)

        val buffer = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN)
        assertEquals("the packet type is 0xD00D", 0xD00D, buffer.short.toInt() and 0xFFFF)
        assertEquals("then the protected length", 16, buffer.short.toInt() and 0xFFFF)
        assertEquals("then the sequence", 5, buffer.int)
        assertEquals("then the target participant", 0x11223344, buffer.int)
        assertEquals("then the source participant", 0x55667788, buffer.int)

        // THE FLAGS FIELD IS TWO BYTES AND MUST BE CONSUMED. Skipping it reads the channel id
        // two bytes early, which produces a plausible-looking but wrong number, and that is the
        // mistake the earlier version of this test made.
        val flags = buffer.short.toInt() and 0xFFFF
        assertEquals("then the flags short", 0xF0A, flags and 0x0FFF)

        assertEquals("and the channel id last", 0x010203040506070L, buffer.long)
    }

    /** The flags bitfield: version in the top two bits, need-ack at 13, the type in the low 12. */
    @Test
    fun theFlagsCarryVersionThenMessageType() {
        val plain = SmartGlassMessage.Flags(messageType = SmartGlassMessage.MSG_GAMEPAD).toShort()
        assertEquals("the version is the top two bits", 2, (plain ushr 14) and 0x3)
        assertEquals("the message type is the low twelve", 0xF0A, plain and 0x0FFF)
        assertEquals("need-ack is clear", 0, (plain ushr 13) and 1)

        val acked = SmartGlassMessage.Flags(needAck = true, messageType = 0x03).toShort()
        assertEquals("need-ack sets bit 13", 1, (acked ushr 13) and 1)
        assertEquals("and the type still reads", 0x03, acked and 0x0FFF)
    }

    /**
     * A MESSAGE TYPE IS NOT A PACKET TYPE.
     *
     * Every message packet is 0xD00D and the flags field names the kind. An earlier version used
     * 0xCC0D as a packet type and put the kind in the payload, which a console ignores.
     */
    @Test
    fun aMessageTypeLivesInTheFlagsNotThePacketType() {
        assertEquals("every message packet is 0xD00D", 0xD00D, SmartGlassMessage.TYPE_MESSAGE)
        assertEquals("local join is 0x03", 0x03, SmartGlassMessage.MSG_LOCAL_JOIN)
        assertEquals("start channel is 0x26", 0x26, SmartGlassMessage.MSG_START_CHANNEL)
        assertEquals("power off is 0x39", 0x39, SmartGlassMessage.MSG_POWER_OFF)
        assertEquals("the gamepad is 0xF0A", 0xF0A, SmartGlassMessage.MSG_GAMEPAD)
    }

    // ------------------------------------------------------------------
    // Strings and padding
    // ------------------------------------------------------------------

    /** An SGString is a length, the characters, and a null the length excludes. */
    @Test
    fun aStringCarriesItsLengthAndATrailingNull() {
        assertArrayEquals(
            byteArrayOf(0x00, 0x02, 0x41, 0x42, 0x00),
            SmartGlassMessage.string("AB"),
        )
        val long = SmartGlassMessage.string("x".repeat(300))
        assertEquals("a length above 255 uses the high byte", 0x01, long[0].toInt())
        assertEquals("and the low byte", 0x2C, long[1].toInt())
        assertEquals("300 characters plus a length and a null", 303, long.size)
    }

    /**
     * PADDING IS ONLY ADDED WHEN THE LENGTH IS NOT ALREADY ALIGNED.
     *
     * Adding a whole block to an aligned payload makes the console decrypt a block of padding as
     * data, which it then rejects. Both cases are one line apart in the same function.
     */
    @Test
    fun paddingIsOnlyAddedWhenNeeded() {
        assertEquals(
            "an aligned payload must not grow",
            16,
            SmartGlassMessage.pad(ByteArray(16)).size,
        )

        val padded = SmartGlassMessage.pad(ByteArray(13))
        assertEquals("a short payload is padded to the block", 16, padded.size)
        assertEquals("the pad bytes hold the pad length", 3, padded[15].toInt())
        assertEquals("and it is repeated", 3, padded[13].toInt())
    }

    // ------------------------------------------------------------------
    // Connect request
    // ------------------------------------------------------------------

    /**
     * THE KEY TYPE FOR P-384 IS 0x01, NOT 2.
     *
     * The values are 0x00, 0x01 and 0x02 for P-256, P-384 and P-521. Writing 2 declares P-521, so
     * the console reads 96 bytes as the wrong curve and rejects the request.
     */
    @Test
    fun theP384KeyTypeIsOne() {
        assertEquals("P-384 is key type 0x01", 0x01, SmartGlassMessage.KEY_TYPE_P384)
        assertEquals("a P-384 key is 96 raw bytes", 0x60, SmartGlassMessage.P384_PUBLIC_KEY_SIZE)
    }

    /** The connect request's unprotected payload has a fixed shape. */
    @Test
    fun theConnectRequestUnprotectedHasItsShape() {
        val uuid = ByteArray(16) { it.toByte() }
        val key = ByteArray(96) { 7 }
        val iv = ByteArray(16) { 9 }

        val payload = SmartGlassMessage.connectRequestUnprotected(
            clientUuid = uuid,
            publicKeyType = SmartGlassMessage.KEY_TYPE_P384,
            publicKey = key,
            iv = iv,
        )

        assertEquals("16 uuid, 2 type, 96 key, 16 iv", 130, payload.size)
        assertArrayEquals("the uuid comes first", uuid, payload.copyOfRange(0, 16))
        assertEquals("then the key type's high byte", 0x00, payload[16].toInt())
        assertEquals("then its low byte, which is 1", 0x01, payload[17].toInt())
        assertArrayEquals("and the iv last", iv, payload.copyOfRange(114, 130))
    }

    /** A 97-byte key, which is the uncompressed point form, must be refused. */
    @Test
    fun aKeyWithTheUncompressedPrefixIsRefused() {
        val error = runCatching {
            SmartGlassMessage.connectRequestUnprotected(
                clientUuid = ByteArray(16),
                publicKeyType = SmartGlassMessage.KEY_TYPE_P384,
                publicKey = ByteArray(97),
                iv = ByteArray(16),
            )
        }.exceptionOrNull()

        assertNotNull("a 97-byte key must be refused rather than sent", error)
        assertTrue(
            "the message must name the prefix trap",
            error?.message?.contains("0x04") == true,
        )
    }

    // ------------------------------------------------------------------
    // Crypto
    // ------------------------------------------------------------------

    /**
     * The derivation salts with two fixed constants and splits SHA-512 into 16, 16 and THIRTY-TWO.
     *
     * The HMAC key is 32 bytes, not 16: the hash is 64 bytes and the first 32 are the two AES
     * keys. A 16-byte HMAC key produces signatures no console can verify.
     */
    @Test
    fun theDerivationSplitsSixtyFourBytesThreeWays() {
        val shared = ByteArray(48) { it.toByte() }
        val keys = SmartGlassCrypto.deriveKeys(shared)

        assertEquals("an AES-128 key is 16 bytes", 16, keys.encryptionKey.size)
        assertEquals("the IV key is 16 bytes", 16, keys.ivKey.size)
        assertEquals("the HMAC key is 32 bytes", 32, keys.hashingKey.size)
        assertFalse(
            "the derived key must not be the shared secret itself",
            keys.encryptionKey.contentEquals(shared.copyOfRange(0, 16)),
        )
    }

    /** The derivation is deterministic, and a different secret gives different keys. */
    @Test
    fun theDerivationIsDeterministicAndSecretDependent() {
        val first = SmartGlassCrypto.deriveKeys(ByteArray(48) { it.toByte() })
        val again = SmartGlassCrypto.deriveKeys(ByteArray(48) { it.toByte() })
        val other = SmartGlassCrypto.deriveKeys(ByteArray(48) { (it + 1).toByte() })

        assertEquals("the same secret must produce the same keys", first, again)
        assertFalse(first.encryptionKey.contentEquals(other.encryptionKey))
    }

    /** A real ECDH exchange between two P-384 keys must agree on the same secret. */
    @Test
    fun ecdhAgreesOnTheSameSecret() {
        val generator = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }
        val client = generator.generateKeyPair()
        val console = generator.generateKeyPair()

        val a = SmartGlassCrypto.deriveSharedSecret(
            client,
            console.public as java.security.interfaces.ECPublicKey,
        )
        val b = SmartGlassCrypto.deriveSharedSecret(
            console,
            client.public as java.security.interfaces.ECPublicKey,
        )
        assertArrayEquals("both sides must derive the identical secret", a, b)
        assertEquals("a P-384 secret is 48 bytes", 48, a.size)
    }

    /** The key sent in the request is 96 bytes with no prefix. */
    @Test
    fun theSentKeyIsNinetySixBytesWithNoPrefix() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()

        val key = SmartGlassCrypto.encodeKeyForRequest(pair.public as java.security.interfaces.ECPublicKey)
        assertEquals("96 raw bytes, no 0x04 prefix", 96, key.size)
        assertTrue("the key must carry real coordinate data", key.any { it != 0.toByte() })
    }

    /** A signature verifies against its own payload and fails against a changed one. */
    @Test
    fun theSignatureVerifiesAndRejectsTampering() {
        val crypto = testCrypto()
        val payload = byteArrayOf(1, 2, 3, 4)
        val signature = crypto.sign(payload)

        assertTrue("a message must verify against itself", crypto.verify(payload, signature))
        assertFalse(
            "a changed payload must not verify, or the integrity check is useless",
            crypto.verify(byteArrayOf(1, 2, 3, 5), signature),
        )
    }

    /** AES must round trip under an explicit IV, or every payload is garbage. */
    @Test
    fun theContextEncryptsAndDecryptsUnderAnIv() {
        val crypto = testCrypto()
        val iv = ByteArray(16) { 5 }
        val plain = ByteArray(20) { it.toByte() }
        val padded = SmartGlassMessage.pad(plain)
        val encrypted = crypto.encryptWith(iv, padded)

        assertEquals("the ciphertext is whole blocks", 0, encrypted.size % 16)
        assertFalse("the ciphertext differs from the plaintext", encrypted.contentEquals(padded))
        assertArrayEquals("decryption restores it", padded, crypto.decryptWith(iv, encrypted))
    }

    /** The message IV is derived from the header, so a different header gives a different IV. */
    @Test
    fun theMessageIvDependsOnTheHeader() {
        val crypto = testCrypto()
        assertFalse(
            "two headers must derive different IVs, or encryption is not bound to the message",
            crypto.messageIv(ByteArray(26) { 1 }).contentEquals(crypto.messageIv(ByteArray(26) { 2 })),
        )
    }

    /** A bare public key is not a certificate, so no context must be invented from one. */
    @Test
    fun aBarePublicKeyCannotBecomeAContext() {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()

        val result = SmartGlassCrypto.create(pair.public.encoded)
        assertNull("a bare public key is not a certificate", result.crypto)
        assertNotNull("and the reason must be stated", result.reason)
    }

    private fun testCrypto(): SmartGlassCrypto {
        val pair = KeyPairGenerator.getInstance("EC").apply {
            initialize(ECGenParameterSpec("secp384r1"))
        }.generateKeyPair()
        return SmartGlassCrypto.forTesting(
            SmartGlassCrypto.deriveKeys(ByteArray(48) { it.toByte() }),
            pair,
        )
    }

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /** A discovery reply's parts must be readable, including the certificate. */
    @Test
    fun aDiscoveryReplyIsReadWithItsCertificate() {
        val name = SmartGlassMessage.string("My Xbox")
        val uuid = SmartGlassMessage.string("01234567-89ab-cdef-0123-456789abcdef")
        val certificate = byteArrayOf(0x30, 0x82.toByte(), 0x01, 0x02, 0x03)

        val payload = java.io.ByteArrayOutputStream().apply {
            write(byteArrayOf(0, 0, 0, 4))          // flags: anonymous allowed
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
        assertEquals("the anonymous flag is bit 0x04", 0x04, reply.flags and 0x04)
        assertArrayEquals("the certificate must survive", certificate, reply.certificate)
    }

    /** A truncated reply must return null rather than a half-read object. */
    @Test
    fun aTruncatedDiscoveryReplyIsRefused() {
        assertNull(SmartGlassMessage.readDiscoveryReply(byteArrayOf(0, 0)))
        assertNull(SmartGlassMessage.readDiscoveryReply(ByteArray(0)))
    }

    /** Power on carries the Live ID as an SGString, which is all a standby console needs. */
    @Test
    fun powerOnCarriesTheLiveId() {
        val payload = SmartGlassMessage.powerOnPayload("FD00000000000000")
        assertEquals("the Live ID length's high byte", 0x00, payload[0].toInt())
        assertEquals("the low byte is 16", 0x10, payload[1].toInt())
        assertEquals("then the id", 'F'.code.toByte(), payload[2])
        assertEquals("and a trailing null", 0, payload[payload.size - 1].toInt())
    }

    // ------------------------------------------------------------------
    // Gamepad
    // ------------------------------------------------------------------

    /**
     * THE GAMEPAD PAYLOAD IS A TIMESTAMP AND EIGHT FIELDS, with no message id and no wrapper.
     *
     * The message type lives in the header flags, so a payload that repeats it is malformed. The
     * sticks are float32 and were missing from the first version of this code entirely.
     */
    @Test
    fun theGamepadPayloadIsATimestampAndEightFields() {
        val payload = SmartGlassMessage.gamepadPayload(
            buttonMask = 0x0010,
            leftTrigger = 0.25f,
            rightTrigger = 0.5f,
            leftStickX = -0.5f,
            leftStickY = 0.75f,
        )

        assertEquals("8 timestamp, 2 buttons, six floats", 34, payload.size)

        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        buffer.long
        assertEquals("the button mask follows the timestamp", 0x0010, buffer.short.toInt())
        assertEquals("then the left trigger", 0.25f, buffer.float, 0.001f)
        assertEquals("then the right trigger", 0.5f, buffer.float, 0.001f)
        assertEquals("then the left stick X", -0.5f, buffer.float, 0.001f)
        assertEquals("then the left stick Y", 0.75f, buffer.float, 0.001f)
        assertEquals("and the right stick, centred", 0f, buffer.float, 0.001f)
    }

    /** A stick value outside the range must be clamped, or the console no-ops it. */
    @Test
    fun aStickValueIsClampedToTheExpectedRange() {
        val buffer = ByteBuffer
            .wrap(SmartGlassMessage.gamepadPayload(0, leftStickX = 9f, leftStickY = -9f))
            .order(ByteOrder.BIG_ENDIAN)
        buffer.long
        buffer.short
        buffer.float
        buffer.float
        assertEquals("a stick beyond 1 is clamped", 1f, buffer.float, 0.001f)
        assertEquals("and below -1 is clamped too", -1f, buffer.float, 0.001f)
    }
}
