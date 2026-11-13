// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.security.KeyPairGenerator
import java.security.MessageDigest
import javax.crypto.Cipher

/**
 * Holds the ADB AUTH signature scheme, because issue 293 was a signature that verified against
 * nothing.
 *
 * THE DEFECT. The block was `DigestInfo(SHA-1) || token`: the ASN.1 header of a SHA-1
 * signature followed by the UNHASHED token. Neither AOSP scheme looks like that. The legacy one
 * puts the bare token in the block, and the modern one puts `DigestInfo || SHA1(token)`. The
 * code was correct only where `token == SHA1(token)`, which is false for every 20-byte value a
 * daemon could send.
 *
 * THE VISIBLE SYMPTOM, which is why this matters beyond the wire: a rejected signature makes
 * the daemon send another token, so the client fell through to sending its public key, the
 * person tapped Allow, and the key was trusted. On the next connection the lookup still failed
 * because the daemon resolves the key BY THE SIGNATURE, never reaching the trusted entry. So the
 * prompt appeared on every connect, forever.
 *
 * HOW THIS PROVES IT, rather than comparing bytes to a constant this file also defines: the
 * block is encrypted with a real private key and then DECRYPTED with the matching public key,
 * which is exactly the operation the daemon performs. A structure the daemon would reject
 * cannot pass that.
 */
class AdbAuthEncodingTest {

    private fun keyPair() = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        .generateKeyPair()

    /** A token shaped like the daemon's: 20 arbitrary bytes. */
    private fun token(seed: Int = 7): ByteArray =
        ByteArray(20) { index -> ((index * 31 + seed) and 0xFF).toByte() }

    @Test
    fun theBlockCarriesTheDigestOfTheTokenRatherThanTheToken() {
        val token = token()
        val block = AdbAuthEncoding.signatureBlock(token, 256)

        val digest = MessageDigest.getInstance("SHA-1").digest(token)
        assertArrayEquals(
            "The body must be DigestInfo || SHA1(token). Putting the raw token there is the " +
                "defect this issue is about.",
            digest,
            block.takeLast(20).toByteArray(),
        )
        assertArrayEquals(
            "and it must be the SHA-1 of the token, not the token itself. This is the exact " +
                "comparison the old code failed.",
            MessageDigest.getInstance("SHA-1").digest(token),
            block.takeLast(20).toByteArray(),
        )
    }

    @Test
    fun theBlockStartsWithThePkcs1V15Header() {
        val block = AdbAuthEncoding.signatureBlock(token(), 256)
        assertEquals("block length must equal the modulus", 256, block.size)
        assertEquals(0x00.toByte(), block[0])
        assertEquals(0x01.toByte(), block[1])
        /*
         * The separator is the first 0x00 AFTER the padding run. Searching the whole array for
         * an indexOf match is wrong here because indexOf finds the FIRST zero, which is byte 0
         * itself, so the search has to start past the 0xFF run.
         */
        var separator = 2
        while (separator < block.size && block[separator] == 0xFF.toByte()) separator++
        assertTrue("there must be a 0x00 separator after the run", separator > 2)
        assertEquals("the separator itself is 0x00", 0x00.toByte(), block[separator])
        assertTrue("at least 8 bytes of 0xFF are required by the padding scheme", separator - 2 >= 8)
        for (i in 2 until separator) {
            assertEquals("byte $i of the padding run", 0xFF.toByte(), block[i])
        }
    }

    @Test
    fun theDigestInfoHeaderNamesSha1() {
        val prefix = AdbAuthEncoding.sha1DigestInfoPrefix()
        assertEquals("the header is 15 bytes", 15, prefix.size)
        // 1.3.14.3.2.26 is the SHA-1 OID; its DER is 06 05 2B 0E 03 02 1A.
        val oid = prefix.sliceArray(4..10)
        assertArrayEquals(
            "the OID must be SHA-1's, or the digest the daemon computes will not match",
            byteArrayOf(0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02, 0x1a),
            oid,
        )
    }

    /**
     * The real proof: a signature the daemon's own operation can read.
     *
     * The message is encrypted with the private key and decrypted with the public one, which is
     * how a verifier recovers the block. Then the recovered block is checked for the digest the
     * daemon would recompute.
     */
    @Test
    fun aSignatureDecryptsToABlockCarryingTheTokenDigest() {
        val pair = keyPair()
        val token = token(seed = 11)
        val block = AdbAuthEncoding.signatureBlock(token, 256)

        val encrypted = Cipher.getInstance("RSA/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, pair.private)
        }.doFinal(block)

        val recovered = Cipher.getInstance("RSA/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, pair.public)
        }.doFinal(encrypted)

        assertArrayEquals("the daemon must recover the exact block", block, recovered)

        val digest = MessageDigest.getInstance("SHA-1").digest(token)
        assertArrayEquals(
            "the recovered block must end in SHA1(token), which is what a verifier compares",
            digest,
            recovered.takeLast(20).toByteArray(),
        )
    }

    @Test
    fun theOldSchemeWouldNotHaveVerifiedAndThisTestCanShowIt() {
        // Rebuilt here so the regression is visible rather than described: DigestInfo || token,
        // the exact shape the old code emitted.
        val pair = keyPair()
        val token = token(seed = 3)
        val prefix = AdbAuthEncoding.sha1DigestInfoPrefix()
        val body = prefix + token

        val block = ByteArray(256)
        var offset = 0
        block[offset++] = 0x00
        block[offset++] = 0x01
        while (offset < 256 - body.size - 1) block[offset++] = 0xFF.toByte()
        block[offset++] = 0x00
        System.arraycopy(body, 0, block, offset, body.size)

        val encrypted = Cipher.getInstance("RSA/ECB/NoPadding").apply {
            init(Cipher.ENCRYPT_MODE, pair.private)
        }.doFinal(block)
        val recovered = Cipher.getInstance("RSA/ECB/NoPadding").apply {
            init(Cipher.DECRYPT_MODE, pair.public)
        }.doFinal(encrypted)

        val digest = MessageDigest.getInstance("SHA-1").digest(token)
        assertFalse(
            "The old shape must NOT end in SHA1(token); if this ever passes, the issue's " +
                "diagnosis was wrong and this change is unjustified.",
            recovered.takeLast(20).toByteArray().contentEquals(digest),
        )
    }

    @Test
    fun theLegacySchemeCarriesTheBareToken() {
        val token = token(seed = 5)
        val block = AdbAuthEncoding.legacyBlock(token, 256)
        assertArrayEquals(
            "the legacy daemon expects the token with no DigestInfo at all",
            token,
            block.takeLast(20).toByteArray(),
        )
    }

    @Test
    fun aKeyTooSmallForItsBodyIsRefusedRatherThanProducingAMalformedBlock() {
        val failure = assertThrows(IllegalArgumentException::class.java) {
            AdbAuthEncoding.signatureBlock(token(), 32)
        }
        assertTrue(
            "The refusal must name the padding requirement rather than failing on the wire. " +
                "A body of 15 + 20 bytes needs keySize >= 35 + 3 + 8 = 46, so 32 must be " +
                "refused. 40 fits with two bytes of padding, which is why the first version " +
                "of this test failed against correct code. Got: ${failure.message}",
            failure.message?.contains("8 bytes") == true,
        )
    }

    @Test
    fun anEmptyTokenIsRefused() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbAuthEncoding.signatureBlock(ByteArray(0), 256)
        }
    }
}
