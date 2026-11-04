// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPrivateCrtKeySpec
import java.security.spec.RSAPrivateKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/**
 * The RSA key material ADB authentication and pairing are built on.
 *
 * WHY THIS EXISTS, AND WHY IT IS SEPARATE FROM THE STORE ON DISK.
 *
 * Issue 306. `AdbKeyStore` used to persist three hex lines (modulus, public
 * exponent, private exponent) and rebuild the private half with
 * `RSAPrivateKeySpec(modulus, privateExponent)`. That spec carries no public
 * exponent, so the object handed to the TLS layer knew n and d and nothing else.
 *
 * BoringSSL refuses to install a certificate against such a key. In
 * `ssl_compare_public_and_private_key` it compares the certificate's public key
 * with the private key through `EVP_PKEY_eq`, and for RSA that comparison is two
 * `BN_cmp` calls: on n and on e. `BN_cmp` treats a NULL operand as unequal to any
 * real number, so a private half with no e can never match the e embedded in the
 * certificate. The result is `KEY_VALUES_MISMATCH` at `ssl_cert.cc`, raised while
 * the certificate is being configured on the socket and therefore before a single
 * byte of the pairing protocol is sent.
 *
 * It passed every local check because `cert.verify(keyPair.public)` proves only
 * that the TBS bytes were signed by the private key. It never compares the
 * certificate's own SubjectPublicKeyInfo against the private key, so a key whose
 * public half disagrees with its private half verifies happily.
 *
 * The invariant this file exists to enforce: every KeyPair leaving this building
 * carries a COMPLETE RSA key, meaning an `RSAPrivateCrtKey` with n, e, d and the
 * CRT parameters, whose derived public half shares n and e with it. A key that
 * cannot satisfy that is never returned; it is replaced, and the replacement is
 * proven by a real sign/verify round trip.
 *
 * Nothing here touches Android, so a JVM test can prove all of it in seconds
 * rather than booting a device.
 */
object AdbKeyMaterial {

    /**
     * 2048 bits is not a preference. ADB's `AUTH_RSAPUBLICKEY` wire format is a
     * fixed-size C struct of two 256-byte integers, so a larger modulus cannot be
     * expressed on that wire and a smaller one leaves the struct half empty.
     */
    const val KEY_BITS = 2048

    /** The three hex lines the old store wrote: modulus, publicExponent, privateExponent. */
    data class LegacyKey(
        val modulus: BigInteger,
        val publicExponent: BigInteger,
        val privateExponent: BigInteger,
    )

    /** A freshly generated, complete key pair. */
    fun generate(): KeyPair {
        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_BITS)
        return generator.generateKeyPair()
    }

    /** Rebuild a key pair from base64 PKCS#8 DER. */
    fun load(pkcs8Base64: String): KeyPair {
        val der = Base64.getDecoder().decode(pkcs8Base64.trim())
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(der))
        return pairFrom(privateKey)
    }

    /** Serialise the private half as base64 PKCS#8 DER, the form BoringSSL parses itself. */
    fun encode(privateKey: java.security.PrivateKey): String {
        val der = requireNotNull(privateKey.encoded) {
            "ADB key is not exportable. A Keystore-backed key cannot be persisted for ADB, " +
                "and a key built without a public exponent cannot be PKCS#8 encoded at all."
        }
        return Base64.getEncoder().encodeToString(der)
    }

    /**
     * Read the old three-line file.
     *
     * Throws rather than returning null on a short file, because a caller that
     * silently falls back to a fresh key would rotate the key on every launch and
     * the phone would re-prompt forever. The store catches this and decides.
     */
    fun parseLegacy(text: String): LegacyKey {
        val lines = text.split('\n').map { it.trim() }.filter { it.isNotEmpty() }
        require(lines.size >= 3) { "legacy adb_key has ${lines.size} non-empty lines, expected 3" }
        return LegacyKey(
            modulus = BigInteger(lines[0], 16),
            publicExponent = BigInteger(lines[1], 16),
            privateExponent = BigInteger(lines[2], 16),
        )
    }

    /**
     * Rebuild the SAME key identity from the old file, so an authorisation the
     * phone already granted stays valid and the user does not have to re-pair.
     *
     * The old file carries n, e and d, which is everything needed: `e*d - 1` is a
     * multiple of the Carmichael function of n, and the classic "non-trivial
     * square root of one" argument factors n from it.
     */
    fun fromLegacy(text: String): KeyPair {
        val legacy = parseLegacy(text)
        return fromParts(legacy.modulus, legacy.publicExponent, legacy.privateExponent)
    }

    fun fromParts(modulus: BigInteger, publicExponent: BigInteger, privateExponent: BigInteger): KeyPair {
        val (p, q) = factor(modulus, publicExponent, privateExponent)
        val dP = privateExponent.mod(p.subtract(BigInteger.ONE))
        val dQ = privateExponent.mod(q.subtract(BigInteger.ONE))
        val qInv = q.modInverse(p)
        val spec = RSAPrivateCrtKeySpec(
            modulus, publicExponent, privateExponent, p, q, dP, dQ, qInv,
        )
        val privateKey = KeyFactory.getInstance("RSA").generatePrivate(spec)
        return pairFrom(privateKey)
    }

    /**
     * Recover the two prime factors of n from (n, e, d).
     *
     * The textbook shortcut that reads the factors off the quadratic in phi(n) is
     * deliberately not used: it assumes d was computed modulo phi(n), and Android's
     * providers are free to compute it modulo the Carmichael function instead. The
     * square-root argument below holds under either convention, because in both
     * cases `e*d - 1` is a multiple of the Carmichael function.
     *
     * Each candidate base succeeds with probability at least one half, so the scan
     * over small bases terminates for any real key.
     */
    fun factor(n: BigInteger, e: BigInteger, d: BigInteger): Pair<BigInteger, BigInteger> {
        val k = d.multiply(e).subtract(BigInteger.ONE)
        require(k.signum() > 0) { "e*d - 1 is not positive, so d does not belong to e" }
        val s = k.lowestSetBit
        val t = k.shiftRight(s)
        val nMinusOne = n.subtract(BigInteger.ONE)

        for (base in 2L..200L) {
            var x = BigInteger.valueOf(base).modPow(t, n)
            if (x == BigInteger.ONE || x == nMinusOne) continue
            for (round in 0 until s) {
                val y = x.multiply(x).mod(n)
                if (y == BigInteger.ONE) {
                    val p = x.subtract(BigInteger.ONE).gcd(n)
                    if (p > BigInteger.ONE && p < n) {
                        return ordered(p, n.divide(p))
                    }
                    break
                }
                if (y == nMinusOne) break
                x = y
            }
        }
        throw IllegalStateException(
            "Could not recover the prime factors of the stored ADB key. The file is corrupt, " +
                "or its private exponent does not belong to its public exponent.",
        )
    }

    private fun ordered(a: BigInteger, b: BigInteger): Pair<BigInteger, BigInteger> =
        if (a > b) a to b else b to a

    /**
     * Build the pair from any private key, refusing anything that is not a complete
     * RSA key with a matching public half.
     *
     * The public half is DERIVED from the private half's own n and e rather than
     * taken from anywhere else, so the two cannot disagree.
     */
    fun pairFrom(privateKey: java.security.PrivateKey): KeyPair {
        val crt = requireComplete(privateKey)
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(crt.modulus, crt.publicExponent))
        return KeyPair(publicKey, crt)
    }

    /**
     * The single gate that makes issue 306 impossible to reintroduce.
     *
     * A key that is not an `RSAPrivateCrtKey` has no public exponent, and that is
     * precisely the shape BoringSSL rejects. There is no safe way to use one, so
     * this throws rather than letting it reach the TLS layer and produce an error
     * message that names the certificate routines instead of the real cause.
     */
    fun requireComplete(privateKey: java.security.PrivateKey): RSAPrivateCrtKey {
        return privateKey as? RSAPrivateCrtKey ?: throw IllegalStateException(
            "ADB key is incomplete: it has no public exponent or CRT parameters, so BoringSSL " +
                "will refuse it with KEY_VALUES_MISMATCH. Rebuild it from PKCS#8 or from " +
                "(n, e, d). Plain RSAPrivateKeySpec(modulus, privateExponent) produces exactly " +
                "this broken shape and must never be used for an ADB or TLS key.",
        )
    }

    /**
     * Prove the pair before anything depends on it: the halves agree on n and e, and
     * a real signature made by the private half verifies against the public half.
     *
     * The second check is what catches a key whose public exponent is present but
     * belongs to a different modulus, which the n/e comparison alone would miss
     * only if the pair were assembled from mixed sources.
     */
    fun selfCheck(keyPair: KeyPair) {
        val privateKey = requireComplete(keyPair.private)
        val publicKey = keyPair.public as? RSAPublicKey
            ?: throw IllegalStateException("ADB public key is not RSA")

        check(privateKey.modulus == publicKey.modulus) {
            "ADB key halves disagree on the modulus; this pair can never authenticate"
        }
        check(privateKey.publicExponent == publicKey.publicExponent) {
            "ADB key halves disagree on the public exponent; BoringSSL compares exactly this and " +
                "will raise KEY_VALUES_MISMATCH"
        }

        val message = ByteArray(32).also { SecureRandom().nextBytes(it) }
        val signature = Signature.getInstance("SHA256withRSA").run {
            initSign(privateKey)
            update(message)
            sign()
        }
        check(
            Signature.getInstance("SHA256withRSA").run {
                initVerify(publicKey)
                update(message)
                verify(signature)
            },
        ) { "ADB key pair failed a sign/verify round trip" }
    }

    /**
     * The exact invariant BoringSSL checks, at the moment the certificate is built.
     *
     * `cert.verify(keyPair.public)` is not this check and never was. It proves the
     * TBS bytes carry a valid signature under whichever key is passed in. It says
     * nothing about the SubjectPublicKeyInfo written INSIDE the certificate, which
     * is the key BoringSSL compares against the private key.
     *
     * So a certificate can carry a valid self-signature and still be rejected on
     * the socket, which is why this failure surfaced as an OpenSSL message rather
     * than as anything the app could explain.
     */
    fun checkCertificate(certificate: X509Certificate, keyPair: KeyPair) {
        val privateKey = requireComplete(keyPair.private)
        val embedded = certificate.publicKey as? RSAPublicKey
            ?: throw IllegalStateException("Certificate public key is not RSA")

        check(embedded.modulus == privateKey.modulus) {
            "The certificate's SubjectPublicKeyInfo carries a different modulus from the private " +
                "key. BoringSSL compares exactly (n, e) and will raise KEY_VALUES_MISMATCH."
        }
        check(embedded.publicExponent == privateKey.publicExponent) {
            "The certificate's SubjectPublicKeyInfo carries a different public exponent from the " +
                "private key. BoringSSL compares exactly (n, e) and will raise KEY_VALUES_MISMATCH."
        }
        certificate.verify(embedded)
    }

    /**
     * Render the three-line legacy format.
     *
     * Only used to prove the migration in a test. Nothing writes this shape any
     * more, because it is the shape that lost the public exponent.
     */
    fun legacyText(modulus: BigInteger, publicExponent: BigInteger, privateExponent: BigInteger): String =
        buildString {
            append(modulus.toString(16))
            append('\n')
            append(publicExponent.toString(16))
            append('\n')
            append(privateExponent.toString(16))
        }

    /**
     * The broken shape issue 306 was caused by, preserved so a test can prove it is
     * rejected rather than quietly accepted.
     */
    fun incompleteForTest(modulus: BigInteger, privateExponent: BigInteger): java.security.PrivateKey =
        KeyFactory.getInstance("RSA")
            .generatePrivate(RSAPrivateKeySpec(modulus, privateExponent))
}
