// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.android.apksig.ApkVerifier
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import java.security.cert.X509Certificate
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Proves that APK signing really signs, on the JVM, with no JVM binary and no
 * external tool.
 *
 * WHY THIS TEST MATTERS MORE THAN MOST. Issue 200's signing defect was not that
 * signing produced a bad signature — it is that signing could not run at all,
 * because it shelled out to `keytool`, `apksigner` and `jarsigner` and none of
 * them existed. A test that only asserted "no exception thrown" would not catch a
 * regression back to that shape, because the old code also returned a result
 * object without throwing.
 *
 * So these tests verify the OUTPUT with apksig's own verifier: the signed APK must
 * carry a real APK Signature Scheme v2 block that validates against the
 * certificate the keystore holds. That is the same check the platform performs at
 * install time, which is the only claim worth making.
 *
 * They also prove the certificate generator, because it is hand-written DER and a
 * wrong length octet there would produce a certificate that parses sometimes.
 */
class ApkSignerTest {

    private fun tempDir(): File {
        val dir = File.createTempFile("apksigner-test", "")
        dir.delete()
        dir.mkdirs()
        dir.deleteOnExit()
        return dir
    }

    /**
     * A minimal but REAL APK: a zip with an AndroidManifest.xml and a classes.dex.
     *
     * apksig requires the manifest to exist and to be named exactly that, because
     * it signs the archive as an APK rather than as a generic jar. The content is
     * not a valid binary manifest and does not need to be: signing operates on
     * bytes, and verification checks the signature over those bytes.
     */
    private fun fakeApk(dir: File): File {
        val apk = File(dir, "input.apk")
        ZipOutputStream(apk.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("AndroidManifest.xml"))
            out.write(ByteArray(64) { 0x03 })
            out.closeEntry()

            out.putNextEntry(ZipEntry("classes.dex"))
            out.write("dex\n035\u0000".toByteArray() + ByteArray(256) { it.toByte() })
            out.closeEntry()

            out.putNextEntry(ZipEntry("resources.arsc"))
            out.write(ByteArray(32) { 0x11 })
            out.closeEntry()
        }
        return apk
    }

    /**
     * The keystore is created, is a real PKCS12 file, and holds a usable key and
     * certificate.
     */
    @Test
    fun createsAKeystoreWithAKeyAndCertificate() {
        val dir = tempDir()
        val keystore = File(dir, "guru_debug.keystore")

        val created = ApkSigner.ensureKeystore(keystore)

        assertTrue("ensureKeystore must produce the file", created.isFile)
        assertTrue("A real keystore is more than a few bytes", created.length() > 1000)

        val store = KeyStore.getInstance("PKCS12")
        keystore.inputStream().use { store.load(it, "android".toCharArray()) }
        assertTrue("The keystore must contain the guru_debug alias",
            store.containsAlias("guru_debug"))
        assertNotNull("The alias must have a private key",
            store.getKey("guru_debug", "android".toCharArray()))
        assertNotNull("The alias must have a certificate",
            store.getCertificate("guru_debug"))
    }

    /**
     * The hand-written DER certificate must be a valid X.509 v3 carrier.
     *
     * If the length encoding or the OID packing were wrong, CertificateFactory
     * would either throw or produce a certificate with the wrong algorithm; both
     * would then fail in apksig several layers away from the cause.
     */
    @Test
    fun theGeneratedCertificateIsAValidX509() {
        val dir = tempDir()
        val keystore = File(dir, "guru_debug.keystore")
        ApkSigner.ensureKeystore(keystore)

        val store = KeyStore.getInstance("PKCS12")
        keystore.inputStream().use { store.load(it, "android".toCharArray()) }
        val cert = store.getCertificate("guru_debug") as X509Certificate

        assertEquals("The signature algorithm must be SHA256withRSA",
            "SHA256withRSA", cert.sigAlgName)
        assertTrue("The subject must name GURU so a signature is traceable to it",
            cert.subjectX500Principal.name.contains("Guru Debug"))
        assertTrue("The certificate must be currently valid",
            cert.notBefore.before(java.util.Date()) && cert.notAfter.after(java.util.Date()))

        // A self-signed certificate verifies against its own public key. If the
        // signature in the DER did not cover the TBS bytes correctly, this is
        // where that shows up rather than during a signing run.
        cert.verify(cert.publicKey)
    }

    /** Signing twice must reuse the key, or rebuilds cannot install over each other. */
    @Test
    fun theKeystoreIsReusedRatherThanRegenerated() {
        val dir = tempDir()
        val keystore = File(dir, "guru_debug.keystore")
        ApkSigner.ensureKeystore(keystore)
        val first = keystore.readBytes()

        ApkSigner.ensureKeystore(keystore)
        val second = keystore.readBytes()

        assertArrayEquals(
            "ensureKeystore regenerated the keystore. Android refuses an update signed " +
                "with a different key, so every APK built before this point would be " +
                "unable to install over the new one.",
            first,
            second,
        )
    }

    /**
     * THE CENTRAL TEST: sign, then verify with the platform's own algorithm.
     *
     * The verification is done by apksig's ApkVerifier, which is what Android
     * itself runs at install time. A v2 signature block that validates here is a
     * signature that installs.
     */
    @Test
    fun signsAnApkAndTheSignatureVerifies() {
        val dir = tempDir()
        val apk = fakeApk(dir)
        val keystore = File(dir, "guru_debug.keystore")
        val signed = File(dir, "signed.apk")

        val result = ApkSigner.sign(
            inputApk = apk,
            outputApk = signed,
            keystoreFile = ApkSigner.ensureKeystore(keystore),
        )

        assertTrue("Signing failed: ${result.error}", result.success)
        assertEquals("Signing must report the schemes it applied", "v1+v2+v3", result.schemes)
        assertTrue("The signed file must be written", signed.isFile)
        assertTrue("The signed file must not be empty", signed.length() > 0)
        assertTrue(
            "The certificate fingerprint must be reported so one key can be told from another",
            result.certificateSha256.length == 64,
        )

        // The real check: the platform's verification path.
        /*
         * The platform version is stated explicitly rather than read from the
         * manifest. apksig otherwise parses the APK's minSdkVersion out of the
         * binary AndroidManifest, and this fixture's manifest is a placeholder
         * byte array — it exists so the archive IS an APK, not so it describes
         * one. Making the test carry a real binary manifest would prove apksig's
         * XML parser, which is not the claim under test.
         */
        val verification = ApkVerifier.Builder(signed)
            .setMinCheckedPlatformVersion(26)
            .build()
            .verify()
        assertTrue(
            "The signed APK does not verify. Errors: " +
                verification.allErrors.joinToString("; ") { it.toString() },
            verification.isVerified,
        )
        assertTrue(
            "The APK must carry a v2 signature, which is what Android 7+ checks. " +
                "Schemes applied: ${result.schemes}",
            verification.isVerifiedUsingV2Scheme,
        )
        assertTrue(
            "The APK must carry a v3 signature; Android 11+ reports a v2-only " +
                "signature as missing a lineage",
            verification.isVerifiedUsingV3Scheme,
        )

        /*
         * v1 is verified at a platform that actually checks it.
         *
         * apksig mirrors the platform's own behaviour: a v1 (JAR) signature is
         * only consulted below API 24, because from 24 onward v2 supersedes it.
         * Verifying at 26 therefore says nothing about v1 and reporting its
         * absence as a fault would be wrong. Android 6 (23) is where a v1
         * signature is the only thing standing between the APK and an install
         * failure, so that is where it is checked.
         */
        val v1Verification = ApkVerifier.Builder(signed)
            .setMinCheckedPlatformVersion(23)
            .build()
            .verify()
        assertTrue(
            "The signed APK does not verify at API 23, where v1 is the only scheme. " +
                "Errors: " + v1Verification.allErrors.joinToString("; ") { it.toString() },
            v1Verification.isVerified,
        )
        assertTrue(
            "The APK must carry a v1 (JAR) signature that validates, or it cannot " +
                "install on any platform below API 24. The report says v1+v2+v3 were " +
                "applied, so a v1 failure here means that claim is false.",
            v1Verification.isVerifiedUsingV1Scheme,
        )

        // And the certificate must be the one in the keystore, not another.
        val store = KeyStore.getInstance("PKCS12")
        keystore.inputStream().use { store.load(it, "android".toCharArray()) }
        val expected = (store.getCertificate("guru_debug") as X509Certificate).encoded
        val signerCert = verification.signerCertificates.firstOrNull()
        assertNotNull("The verification must name the signer certificate", signerCert)
        assertArrayEquals(
            "The APK was signed with a certificate that is not the keystore's",
            expected,
            signerCert!!.encoded,
        )
    }

    /**
     * A missing input is refused with a named reason.
     *
     * The old code's failure was a named reason of the wrong kind: it reported
     * that no signing tool existed when the real fault was a file it had never
     * looked for. Every refusal here has to say what is actually wrong.
     */
    @Test
    fun refusesAMissingInputWithANamedReason() {
        val dir = tempDir()
        val keystore = ApkSigner.ensureKeystore(File(dir, "guru_debug.keystore"))

        val result = ApkSigner.sign(
            inputApk = File(dir, "does-not-exist.apk"),
            outputApk = File(dir, "out.apk"),
            keystoreFile = keystore,
        )

        assertFalse("Signing a non-existent file must fail", result.success)
        assertTrue(
            "The refusal must name the missing file, not a missing tool. Got: ${result.error}",
            result.error?.contains("does-not-exist.apk") == true,
        )
    }

    @Test
    fun refusesAnEmptyInputWithANamedReason() {
        val dir = tempDir()
        val empty = File(dir, "empty.apk").apply { writeBytes(ByteArray(0)) }
        val keystore = ApkSigner.ensureKeystore(File(dir, "guru_debug.keystore"))

        val result = ApkSigner.sign(empty, File(dir, "out.apk"), keystore)

        assertFalse("Signing an empty file must fail", result.success)
        assertTrue("The refusal must say the file is empty, not that a tool is absent",
            result.error?.contains("empty") == true)
    }

    @Test
    fun refusesAMissingKeystoreWithANamedReason() {
        val dir = tempDir()
        val result = ApkSigner.sign(
            inputApk = fakeApk(dir),
            outputApk = File(dir, "out.apk"),
            keystoreFile = File(dir, "no-such.keystore"),
        )

        assertFalse("Signing without a keystore must fail", result.success)
        assertTrue("The refusal must name the keystore",
            result.error?.contains("keystore") == true)
    }

    /**
     * A non-APK input is refused rather than producing a signed non-APK.
     *
     * This is the case that matters for the rebuild flow: modApk can produce a zip
     * that is not a valid APK, and signing it "successfully" would hand the caller
     * a file that fails at install with a message about signatures rather than
     * about the rebuild.
     */
    @Test
    fun refusesAFileThatIsNotAnApk() {
        val dir = tempDir()
        val notApk = File(dir, "not-an.apk")
        notAnApk(dir, notApk)
        val keystore = ApkSigner.ensureKeystore(File(dir, "guru_debug.keystore"))

        val result = ApkSigner.sign(notApk, File(dir, "out.apk"), keystore)

        assertFalse("Signing a non-Apk must fail", result.success)
        assertNotNull("The failure must carry a reason", result.error)
    }

    private fun notAnApk(dir: File, target: File) {
        ZipOutputStream(target.outputStream()).use { out ->
            out.putNextEntry(ZipEntry("readme.txt"))
            out.write("this is a plain zip with no AndroidManifest".toByteArray())
            out.closeEntry()
        }
    }
}
