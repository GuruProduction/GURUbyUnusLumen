// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import java.security.MessageDigest

/**
 * The PKCS#1 v1.5 block an ADB AUTH signature carries (issue 293).
 *
 * WHY THIS IS ITS OWN OBJECT. The signing function was private, so the one thing that went
 * wrong — which bytes go inside the padding — could not be tested without a socket and a
 * daemon. Pulling the encoding out makes the decision provable on the JVM, and leaves the
 * signing code with nothing to get wrong but the call to the cipher.
 *
 * THE TWO SCHEMES AOSP ACCEPTS, and what each puts in the block:
 *
 *   legacy  `adb_auth_client.c`, `RSA_private_encrypt`:
 *             00 01 FF.. 00 || token
 *
 *   modern  `adb_auth_client.cpp`, `RSA_sign(NID_sha1, ...)`:
 *             00 01 FF.. 00 || DigestInfo(SHA-1) || SHA1(token)
 *
 * THE THIRD THING THE CODE USED TO EMIT was `DigestInfo || token`: the ASN.1 header of a
 * SHA-1 signature followed by the UNHASHED token. It would only be correct where
 * `token == SHA1(token)`, which is false for every 20-byte value a daemon could send. So the
 * signature never verified, the daemon kept asking, and the prompt appeared on every connect
 * even after the key had been trusted.
 */
object AdbAuthEncoding {

    /** The ASN.1 DigestInfo header for SHA-1: OID 1.3.14.3.2.26, NULL params, 20-byte digest. */
    private val SHA1_DIGEST_INFO_PREFIX = byteArrayOf(
        0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
        0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14,
    )

    /** SHA-1's output length, and the length of the token a daemon sends. */
    const val SHA1_LENGTH = 20

    /**
     * The complete block to encrypt, for the modern `RSA_sign` scheme.
     *
     * @param token the 20 bytes the daemon sent in AUTH_TOKEN.
     * @param keySizeBytes the modulus length in bytes, 256 for a 2048-bit key.
     */
    fun signatureBlock(token: ByteArray, keySizeBytes: Int): ByteArray {
        require(token.isNotEmpty()) { "the AUTH token is empty, so there is nothing to sign" }
        val digest = MessageDigest.getInstance("SHA-1").digest(token)
        /*
         * THE BODY IS DigestInfo || DIGEST, NOT THE DIGEST ALONE.
         *
         * Caught by a test, and worth recording because it is the same mistake one layer down
         * from the one this issue is about: passing only the digest produces
         * `00 01 FF.. 00 || SHA1(token)`, which is a FOURTH scheme matching neither AOSP form.
         * The padding function takes the whole body, so the prefix belongs here.
         */
        val body = SHA1_DIGEST_INFO_PREFIX + digest
        return emsaPkcs1(body, keySizeBytes)
    }

    /**
     * The block the LEGACY daemon expects: the token itself, with no DigestInfo at all.
     *
     * Kept because it is the only way a test can demonstrate what the difference between the
     * two schemes actually is, and because a future build may need to fall back to it for an
     * old daemon.
     */
    fun legacyBlock(token: ByteArray, keySizeBytes: Int): ByteArray = emsaPkcs1(token, keySizeBytes)

    /**
     * EMSA-PKCS1-v1_5: `00 01 FF..FF 00 || body`.
     *
     * The FF run must be at least eight bytes, which is a requirement of the padding scheme
     * rather than of ADB. A key too small for the body is refused here rather than producing a
     * malformed block that fails on the wire with no explanation.
     */
    private fun emsaPkcs1(body: ByteArray, keySizeBytes: Int): ByteArray {
        val paddingLength = keySizeBytes - 3 - body.size
        require(paddingLength >= 8) {
            "a $keySizeBytes-byte key cannot carry a ${body.size}-byte body with valid " +
                "PKCS#1 v1.5 padding, which needs at least 8 bytes of 0xFF"
        }

        val block = ByteArray(keySizeBytes)
        var offset = 0
        block[offset++] = 0x00
        block[offset++] = 0x01
        repeat(paddingLength) { block[offset++] = 0xFF.toByte() }
        block[offset++] = 0x00
        System.arraycopy(body, 0, block, offset, body.size)
        return block
    }

    /**
     * The DigestInfo prefix, exposed so a test can prove the header names SHA-1.
     *
     * A copy is returned: the constant itself must not be mutated by a caller.
     */
    fun sha1DigestInfoPrefix(): ByteArray = SHA1_DIGEST_INFO_PREFIX.copyOf()
}
