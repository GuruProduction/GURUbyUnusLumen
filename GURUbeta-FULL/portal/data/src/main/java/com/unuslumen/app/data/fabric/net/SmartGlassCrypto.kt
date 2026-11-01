// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.ECPublicKey
import java.security.spec.ECGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * The SmartGlass crypto context: how an Xbox and a client agree on a key and sign what they
 * send. Requirement for Dylan's console.
 *
 * THE PROTOCOL, from the published documentation:
 *
 *  1. The client generates an ephemeral P-384 key pair and sends its public key in the Connect
 *     Request.
 *  2. The console's Discovery Response carried an X.509 certificate holding its own P-384
 *     public key.
 *  3. Both sides derive the same ECDH shared secret from those two keys.
 *  4. The secret is salted and hashed with SHA-512, and the result is split into an
 *     encryption key, an initialisation vector and a hashing key.
 *  5. Payloads are enciphered with AES-128-CBC and each message carries an HMAC-SHA-256
 *     digest made with the hashing key.
 *
 * WHY THIS IS WRITTEN BY HAND RATHER THAN FROM A LIBRARY. The module declares no crypto
 * beyond the JDK, and every piece here is JDK: EC key agreement, SHA-512, AES-128-CBC and
 * HMAC-SHA-256. So the protocol is the work, not the primitives, and the whole derivation is
 * testable against known values.
 *
 * THE ONE THING TO GET RIGHT, stated because it is the trap: the shared secret is NOT used
 * as the key directly. It is salted and hashed first, and a client that skips that step gets
 * a console that accepts the connection and then answers nothing, because its payloads do
 * not authenticate.
 */
class SmartGlassCrypto private constructor(
    /** AES-128-CBC key, sixteen bytes. */
    val encryptionKey: ByteArray,
    /** The initialisation vector for the first message. */
    val initialisationVector: ByteArray,
    /** HMAC-SHA-256 key. */
    val hashingKey: ByteArray,
    /** The client's ephemeral key pair, kept so the public key can travel in the request. */
    val keyPair: KeyPair,
) {

    companion object {

        /** The curve every SmartGlass console uses. */
        private const val CURVE = "secp384r1"

        /**
         * Build a client context, plus the public key to send in the Connect Request.
         *
         * [consoleCertificate] is the DER certificate from the Discovery Response, and its
         * public key is what completes the ECDH exchange.
         */
        fun create(consoleCertificate: ByteArray): Result {
            val keyPair = KeyPairGenerator.getInstance("EC").apply {
                initialize(ECGenParameterSpec(CURVE), SecureRandom())
            }.generateKeyPair()

            val consoleKey = consolePublicKey(consoleCertificate)
                ?: return Result(null, "The console certificate carries no usable P-384 public key.")

            val shared = runCatching { deriveSharedSecret(keyPair, consoleKey) }.getOrNull()
                ?: return Result(
                    null,
                    "The ECDH exchange with the console key failed, so no crypto context exists.",
                )

            // The salt is the client's own public key, which is what the reference
            // implementations use. Both sides can compute it, so it needs no transmission.
            val salt = keyPair.public.encoded
            val derived = splitKeys(shared, salt)

            return Result(
                SmartGlassCrypto(
                    encryptionKey = derived.encryptionKey,
                    initialisationVector = derived.initialisationVector,
                    hashingKey = derived.hashingKey,
                    keyPair = keyPair,
                ),
                null,
            )
        }

        data class Result(val crypto: SmartGlassCrypto?, val reason: String?)

        /** The three keys a SmartGlass context needs. */
        data class DerivedKeys(
            val encryptionKey: ByteArray,
            val initialisationVector: ByteArray,
            val hashingKey: ByteArray,
        ) {
            override fun equals(other: Any?): Boolean {
                if (this === other) return true
                if (other !is DerivedKeys) return false
                return encryptionKey.contentEquals(other.encryptionKey) &&
                    initialisationVector.contentEquals(other.initialisationVector) &&
                    hashingKey.contentEquals(other.hashingKey)
            }

            override fun hashCode(): Int = encryptionKey.contentHashCode()
        }

        /**
         * Salt the shared secret and split the hash into the three keys.
         *
         * The derivation is SHA-512 over the shared secret with the salt prepended, and the
         * first 48 bytes become the three 16-byte keys in order: encryption, IV, hashing.
         * A client that uses the shared secret directly produces messages the console cannot
         * verify, which is why this is a named function with a test rather than two lines
         * inside a connect method.
         */
        fun splitKeys(sharedSecret: ByteArray, salt: ByteArray): DerivedKeys {
            val digest = MessageDigest.getInstance("SHA-512")
            digest.update(salt)
            digest.update(sharedSecret)
            // A counter byte, so a longer key could be derived if the protocol ever needs it.
            digest.update(0x01.toByte())
            val hash = digest.digest()

            return DerivedKeys(
                encryptionKey = hash.copyOfRange(0, 16),
                initialisationVector = hash.copyOfRange(16, 32),
                hashingKey = hash.copyOfRange(32, 48),
            )
        }

        /**
         * A context built from already-derived keys, for a test.
         *
         * Named `forTesting` rather than exposed as a general constructor, because a
         * production path that skipped the ECDH exchange would produce a client that cannot
         * talk to any console while appearing to work.
         */
        internal fun forTesting(keys: DerivedKeys, keyPair: KeyPair): SmartGlassCrypto =
            SmartGlassCrypto(
                encryptionKey = keys.encryptionKey,
                initialisationVector = keys.initialisationVector,
                hashingKey = keys.hashingKey,
                keyPair = keyPair,
            )

        /** The console's public key, from the DER certificate in its discovery reply. */
        fun consolePublicKey(certificate: ByteArray): ECPublicKey? = runCatching {
            val factory = java.security.cert.CertificateFactory.getInstance("X.509")
            val cert = factory.generateCertificate(certificate.inputStream()) as java.security.cert.X509Certificate
            cert.publicKey as? ECPublicKey
        }.getOrNull()

        /** The P-384 shared secret, which is the raw ECDH output. */
        fun deriveSharedSecret(client: KeyPair, consoleKey: ECPublicKey): ByteArray {
            val keyAgreement = javax.crypto.KeyAgreement.getInstance("ECDH")
            keyAgreement.init(client.private)
            keyAgreement.doPhase(consoleKey, true)
            return keyAgreement.generateSecret()
        }

        /**
         * Encode a P-384 public key as the uncompressed point the protocol expects.
         *
         * The Connect Request carries the raw X and Y coordinates with a 0x04 prefix, not the
         * DER SPKI form. Sending DER produces a console that rejects the request without
         * saying why, which is the failure this encoding exists to prevent.
         */
        fun encodePoint(key: ECPublicKey): ByteArray {
            val x = key.w.affineX
            val y = key.w.affineY
            val out = ByteArray(97)   // 1 prefix byte + 48 + 48 for P-384
            out[0] = 0x04
            writeFixed(x, out, 1, 48)
            writeFixed(y, out, 49, 48)
            return out
        }

        /** Write a BigInteger into a fixed-width big-endian slot, left padded with zeros. */
        private fun writeFixed(value: BigInteger, target: ByteArray, offset: Int, width: Int) {
            val bytes = value.toByteArray()
            // toByteArray carries a sign byte that must be dropped when it is a leading zero.
            val source = if (bytes.size > width && bytes[0] == 0.toByte()) {
                bytes.copyOfRange(bytes.size - width, bytes.size)
            } else {
                bytes
            }
            val start = offset + width - source.size
            source.copyInto(target, start)
        }
    }

    /**
     * Encrypt one payload with AES-128-CBC.
     *
     * The protocol works on whole blocks, so a payload that is not a multiple of sixteen is
     * zero padded. A console tolerates trailing zeros and rejects a short block.
     */
    fun encrypt(payload: ByteArray): ByteArray {
        val padded = if (payload.size % 16 == 0) payload else payload + ByteArray(16 - payload.size % 16)
        val cipher = Cipher.getInstance("AES/CBC/NoPadding")
        cipher.init(
            Cipher.ENCRYPT_MODE,
            SecretKeySpec(encryptionKey, "AES"),
            IvParameterSpec(initialisationVector),
        )
        return cipher.doFinal(padded)
    }

    /** Decrypt one payload, returning null when it does not authenticate as AES output. */
    fun decrypt(payload: ByteArray): ByteArray? {
        if (payload.isEmpty() || payload.size % 16 != 0) return null
        return runCatching {
            val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            cipher.init(
                Cipher.DECRYPT_MODE,
                SecretKeySpec(encryptionKey, "AES"),
                IvParameterSpec(initialisationVector),
            )
            cipher.doFinal(payload)
        }.getOrNull()
    }

    /**
     * The HMAC-SHA-256 digest a message carries.
     *
     * The digest covers the message header and the encrypted payload, so a console can verify
     * the whole message rather than only its body.
     */
    fun sign(header: ByteArray, payload: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(hashingKey, "HmacSHA256"))
        mac.update(header)
        mac.update(payload)
        return mac.doFinal()
    }

    /** Verify a message's digest in constant time, so a wrong key cannot be probed. */
    fun verify(header: ByteArray, payload: ByteArray, signature: ByteArray): Boolean {
        val expected = sign(header, payload)
        if (expected.size != signature.size) return false
        var difference = 0
        for (index in expected.indices) {
            difference = difference or (expected[index].toInt() xor signature[index].toInt())
        }
        return difference == 0
    }
}
