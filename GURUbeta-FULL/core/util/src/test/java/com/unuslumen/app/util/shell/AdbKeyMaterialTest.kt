// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.interfaces.RSAPrivateKey

/**
 * Holds the ADB key material to the invariant BoringSSL enforces.
 *
 * WHY THIS EXISTS. Issue 306: pairing died with `KEY_VALUES_MISMATCH` on every
 * attempt, and nothing in the app could explain it because the message names the
 * certificate routines. The cause was a private key rebuilt from
 * `RSAPrivateKeySpec(modulus, privateExponent)`, which has no public exponent, and
 * BoringSSL's certificate-versus-key comparison is exactly (n, e).
 *
 * These tests pin three separate things:
 *
 * 1. The broken shape is REJECTED rather than accepted, so it cannot be
 *    reintroduced by a future refactor that looks tidy.
 * 2. The old three-line key file migrates while keeping the SAME key identity, so
 *    a phone that already authorised the key does not have to be re-paired.
 * 3. The certificate check catches the mismatch that `cert.verify()` misses.
 *
 * Everything here runs on the JVM with no device, because the subject is key
 * arithmetic rather than anything Android owns. The one fact this cannot prove is
 * that on-device Conscrypt builds the same incomplete key from the same spec, and
 * that is recorded in the PR body rather than pretended here.
 */
class AdbKeyMaterialTest {

    /** A complete CRT key pair, which is what generation must always produce. */
    private fun freshPair(): KeyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }
        .generateKeyPair()

    // ===== 1. The defect itself =====

    /**
     * The exact shape issue 306 was caused by.
     *
     * Plain `RSAPrivateKeySpec(modulus, privateExponent)` produces a key with no
     * public exponent, so BoringSSL can never match it to its certificate. The
     * loader must refuse it rather than hand it to TLS, because the TLS failure is
     * reported as a certificate fault and hides the real cause.
     */
    @Test
    fun aKeyWithNoPublicExponentIsRejected() {
        val complete = freshPair().private as RSAPrivateCrtKey
        val incomplete = AdbKeyMaterial.incompleteForTest(
            complete.modulus,
            complete.privateExponent,
        )

        val thrown = assertThrows(IllegalStateException::class.java) {
            AdbKeyMaterial.requireComplete(incomplete)
        }
        assertTrue(
            "The message must name the real cause so the next reader does not chase BoringSSL. Got: ${thrown.message}",
            thrown.message!!.contains("KEY_VALUES_MISMATCH"),
        )
        assertTrue(
            "The message must name the spec that produces the broken shape. Got: ${thrown.message}",
            thrown.message!!.contains("RSAPrivateKeySpec"),
        )
    }

    /**
     * The regression guard: the shape that used to be accepted is now refused at
     * the point of loading, so a reloaded key can never reach the TLS layer.
     */
    @Test
    fun theOldLoadPathIsRefusedRatherThanSilentlyRepaired() {
        val complete = freshPair().private as RSAPrivateCrtKey
        val rebuiltFromOldSpec = AdbKeyMaterial.incompleteForTest(
            complete.modulus,
            complete.privateExponent,
        )

        // A complete key is accepted, so the gate is not simply refusing everything.
        AdbKeyMaterial.requireComplete(complete)

        assertThrows(IllegalStateException::class.java) {
            AdbKeyMaterial.requireComplete(rebuiltFromOldSpec)
        }
    }

    // ===== 2. Generation and round-tripping =====

    @Test
    fun aGeneratedKeyIsCompleteAndSelfChecks() {
        val pair = AdbKeyMaterial.generate()
        val private = pair.private as RSAPrivateCrtKey

        assertEquals("ADB requires a 2048-bit modulus for the wire struct", 2048, private.modulus.bitLength())
        assertEquals(BigInteger.valueOf(65537), private.publicExponent)
        assertNotEquals("CRT parameters must be present", null, private.primeP)

        AdbKeyMaterial.selfCheck(pair)
    }

    @Test
    fun aKeySurvivesThePkcs8RoundTrip() {
        val original = AdbKeyMaterial.generate()
        val encoded = AdbKeyMaterial.encode(original.private)
        val restored = AdbKeyMaterial.load(encoded)

        val originalPrivate = original.private as RSAPrivateCrtKey
        val restoredPrivate = restored.private as RSAPrivateCrtKey

        assertEquals("The modulus must survive persistence", originalPrivate.modulus, restoredPrivate.modulus)
        assertEquals(
            "The public exponent must survive persistence. Losing it is issue 306.",
            originalPrivate.publicExponent,
            restoredPrivate.publicExponent,
        )
        assertEquals("The private exponent must survive persistence", originalPrivate.privateExponent, restoredPrivate.privateExponent)

        AdbKeyMaterial.selfCheck(restored)
    }

    /**
     * The public half is DERIVED from the private half, never stored beside it.
     *
     * Two independently stored halves can drift, and a drifted pair is exactly the
     * state that produced the original error. This pins that the derivation holds.
     */
    @Test
    fun thePublicHalfIsDerivedFromThePrivateHalf() {
        val original = AdbKeyMaterial.generate()
        val restored = AdbKeyMaterial.load(AdbKeyMaterial.encode(original.private))

        val private = restored.private as RSAPrivateCrtKey
        val public = restored.public as RSAPublicKey

        assertEquals(private.modulus, public.modulus)
        assertEquals(private.publicExponent, public.publicExponent)
    }

    // ===== 3. Legacy migration =====

    /**
     * The migration that saves the user a re-pair.
     *
     * The old file carries n, e and d, which is enough to recover the primes, so
     * the SAME key identity comes back. If this test ever passes while producing a
     * different modulus, the migration has silently rotated the key and every
     * already-authorised phone would start prompting again.
     */
    @Test
    fun theLegacyFileMigratesToTheSameKeyIdentity() {
        val original = AdbKeyMaterial.generate()
        val originalPrivate = original.private as RSAPrivateCrtKey

        val legacyText = AdbKeyMaterial.legacyText(
            originalPrivate.modulus,
            originalPrivate.publicExponent,
            originalPrivate.privateExponent,
        )

        val migrated = AdbKeyMaterial.fromLegacy(legacyText)
        val migratedPrivate = migrated.private as RSAPrivateCrtKey

        assertEquals(
            "Migration must keep the same modulus, or the phone sees a brand new key",
            originalPrivate.modulus,
            migratedPrivate.modulus,
        )
        assertEquals(originalPrivate.publicExponent, migratedPrivate.publicExponent)
        assertEquals(originalPrivate.privateExponent, migratedPrivate.privateExponent)
        assertNotEquals("The migrated key must carry CRT parameters", null, migratedPrivate.primeP)

        AdbKeyMaterial.selfCheck(migrated)
    }

    /**
     * The primes really are the original ones, checked against n rather than
     * trusted from the spec: p * q must reconstruct the modulus.
     */
    @Test
    fun theRecoveredPrimesReconstructTheModulus() {
        val original = AdbKeyMaterial.generate()
        val originalPrivate = original.private as RSAPrivateCrtKey

        val (p, q) = AdbKeyMaterial.factor(
            originalPrivate.modulus,
            originalPrivate.publicExponent,
            originalPrivate.privateExponent,
        )

        assertEquals(
            "The recovered primes must multiply back to n",
            originalPrivate.modulus,
            p.multiply(q),
        )
    }

    /**
     * Recovery must also work when d was computed modulo the Carmichael function
     * rather than phi(n), because Android's providers are free to do either and the
     * textbook shortcut breaks under the second convention.
     */
    @Test
    fun theRecoveryWorksWhenDIsReducedModuloTheCarmichaelFunction() {
        val original = freshPair()
        val private = original.private as RSAPrivateCrtKey
        val p = private.primeP
        val q = private.primeQ

        val pMinusOne = p.subtract(BigInteger.ONE)
        val qMinusOne = q.subtract(BigInteger.ONE)
        val gcd = pMinusOne.gcd(qMinusOne)
        val lcm = pMinusOne.divide(gcd).multiply(qMinusOne)
        val dModLambda = private.publicExponent.modInverse(lcm)

        val (recoveredP, recoveredQ) = AdbKeyMaterial.factor(private.modulus, private.publicExponent, dModLambda)
        assertEquals(private.modulus, recoveredP.multiply(recoveredQ))
    }

    @Test
    fun aCorruptLegacyFileIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            AdbKeyMaterial.parseLegacy("only-one-line")
        }
    }

    @Test
    fun aLegacyFileWhoseExponentDoesNotBelongToItsKeyIsRejected() {
        val original = AdbKeyMaterial.generate()
        val private = original.private as RSAPrivateCrtKey
        val wrongD = private.privateExponent.add(BigInteger.valueOf(4))

        assertThrows(IllegalStateException::class.java) {
            AdbKeyMaterial.fromParts(private.modulus, private.publicExponent, wrongD)
        }
    }

    // ===== 4. The certificate check =====

    /**
     * `cert.verify(keyPair.public)` is not the check BoringSSL performs, and this
     * test exists to stop anyone believing it is.
     *
     * A certificate is generated for one key pair and then handed the OTHER pair's
     * private key. The self-signature still verifies against the key the
     * certificate itself carries, and yet the certificate and private key disagree,
     * which is precisely the state BoringSSL kills the connection over.
     */
    @Test
    fun aCertificateForADifferentKeyIsCaughtEvenThoughItsSignatureIsValid() {
        val certificatePair = AdbKeyMaterial.generate()
        val otherPair = AdbKeyMaterial.generate()

        val certificate = SelfSignedCert.generate(certificatePair)

        // The signature is genuinely valid against the certificate's own key, which
        // is exactly why the old check was useless here.
        certificate.verify(certificate.publicKey)

        val thrown = assertThrows(IllegalStateException::class.java) {
            AdbKeyMaterial.checkCertificate(certificate, otherPair)
        }
        assertTrue(
            "The message must name the invariant BoringSSL checks. Got: ${thrown.message}",
            thrown.message!!.contains("KEY_VALUES_MISMATCH"),
        )
    }

    @Test
    fun aMatchingCertificatePassesTheCheck() {
        val pair = AdbKeyMaterial.generate()
        val certificate = SelfSignedCert.generate(pair)

        // Must not throw. This is the path every successful pairing takes.
        AdbKeyMaterial.checkCertificate(certificate, pair)
    }

    /**
     * The end-to-end shape of the original fault: a certificate built for a
     * complete key, checked against a private key carrying only n and d. This is
     * what reached TLS and produced KEY_VALUES_MISMATCH on the socket.
     */
    @Test
    fun theOriginalFailureShapeIsCaughtBeforeTheHandshake() {
        val complete = AdbKeyMaterial.generate()
        val completePrivate = complete.private as RSAPrivateCrtKey
        val certificate = SelfSignedCert.generate(complete)

        val incomplete: RSAPrivateKey = AdbKeyMaterial.incompleteForTest(
            completePrivate.modulus,
            completePrivate.privateExponent,
        ) as RSAPrivateKey
        val brokenPair = KeyPair(complete.public, incomplete)

        assertThrows(IllegalStateException::class.java) {
            AdbKeyMaterial.checkCertificate(certificate, brokenPair)
        }
    }
}
