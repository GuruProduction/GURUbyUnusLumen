// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.android.apksig.ApkSigner
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.MessageDigest
import java.security.PrivateKey
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Signs an APK on the device, in this process, with no external tool.
 *
 * WHY THIS EXISTS (issue 200). `signApk` was a shell-out: it ran `keytool` to
 * make a keystore, then `which apksigner`, then `which jarsigner`, and reported
 * whichever was found. On the reporting handset none of the three existed
 * anywhere in the APK or the prefix, so the tool could only ever answer "No
 * signing tool available" — while the same build's copy advertised a complete
 * on-device rebuild-and-sign chain. Signing is the step that makes a
 * self-modifying app real, so it cannot be the step that needs a desktop.
 *
 * WHY apksig RATHER THAN THE BUNDLED JDK. apksig is the signer inside Google's
 * own build tools. All 267 of its classes are Java 8 class files and it
 * references nothing outside `java.*`, `javax.*` and itself, so it loads and runs
 * on ART directly. That matters twice: signing works even when the bundled JDK
 * is absent or broken, and the signer is the same implementation that produced
 * every APK built on a machine, so a signature made here has the same shape as
 * one made there.
 *
 * It produces APK Signature Scheme v1 (JAR signing), v2 and v3 in one pass. v1
 * is what older platforms verify; v2 and v3 are what Android 7 and 11 require
 * when the platform is able to check them. Signing all three is the difference
 * between an APK that installs on the phone that made it and one that installs
 * anywhere.
 *
 * THE KEY. A self-generated RSA-2048 key in a PKCS12 keystore, created on first
 * use and reused afterwards, so repeated rebuilds produce APKs that install over
 * each other. Android refuses an update signed with a different key, so a
 * keystore regenerated mid-workflow strands every APK already built with the
 * previous one.
 */
object ApkSigner {

    /** What happened, stated as observed rather than as hoped. */
    data class Result(
        val success: Boolean,
        val signedPath: String,
        val method: String,
        val error: String?,
        /** Schemes actually applied, e.g. "v1+v2+v3". Empty when signing failed. */
        val schemes: String = "",
        /** Certificate SHA-256, so a caller can tell one key from another. */
        val certificateSha256: String = "",
    )

    private const val KEYSTORE_TYPE = "PKCS12"
    private const val KEY_ALIAS = "guru_debug"
    private const val KEY_PASSWORD = "android"
    private const val KEY_SIZE = 2048
    private const val VALIDITY_DAYS = 10_000L

    private const val SUBJECT_CN = "Guru Debug"
    private const val SUBJECT_OU = "Reverse Engineering"
    private const val SUBJECT_O = "Guru by Unus Lumen"
    private const val SUBJECT_L = "Bristol"
    private const val SUBJECT_ST = "Bristol"
    private const val SUBJECT_C = "GB"

    /**
     * Create the signing key if it is not already there, and return the
     * keystore path.
     *
     * A PKCS12 keystore is written by java.security.KeyStore rather than by
     * `keytool`, because keytool is one of the things that does not exist on
     * this device. The password is a fixed, published debug password: this key
     * signs throwaway rebuilds on a user's own handset, and it must never be
     * confused with the release key, which lives only on a build machine under a
     * different alias and is never present on a phone.
     */
    fun ensureKeystore(keystoreFile: File): File {
        if (keystoreFile.isFile && keystoreFile.length() > 0) return keystoreFile

        keystoreFile.parentFile?.mkdirs()

        val generator = KeyPairGenerator.getInstance("RSA")
        generator.initialize(KEY_SIZE)
        val keyPair = generator.generateKeyPair()

        val cert = SelfSignedCertificate.create(keyPair, VALIDITY_DAYS)

        val keyStore = KeyStore.getInstance(KEYSTORE_TYPE)
        keyStore.load(null, null)
        keyStore.setKeyEntry(
            KEY_ALIAS,
            keyPair.private,
            KEY_PASSWORD.toCharArray(),
            arrayOf(cert),
        )

        // Write to a sibling and rename, so an interrupted write cannot leave a
        // truncated keystore that then fails every subsequent signing with a
        // confusing password error.
        val temp = File(keystoreFile.absolutePath + ".tmp")
        temp.outputStream().use { out ->
            keyStore.store(out, KEY_PASSWORD.toCharArray())
        }
        if (keystoreFile.exists()) keystoreFile.delete()
        if (!temp.renameTo(keystoreFile)) {
            temp.copyTo(keystoreFile, overwrite = true)
            temp.delete()
        }
        return keystoreFile
    }

    /**
     * Sign [inputApk] into [outputApk].
     *
     * Returns a Result rather than throwing, because the caller is a tool whose
     * job is to report what happened. An exception escaping here would become a
     * generic tool failure with no explanation, which is the same defect as
     * reporting success for something that did not happen.
     *
     * The input is never modified: apksig reads it and writes a new file, so a
     * failed signing leaves the original rebuildable.
     */
    fun sign(
        inputApk: File,
        outputApk: File,
        keystoreFile: File,
        minSdk: Int = 26,
    ): Result {
        if (!inputApk.isFile) {
            return Result(
                success = false,
                signedPath = "",
                method = "apksig",
                error = "Cannot sign: ${inputApk.absolutePath} does not exist.",
            )
        }
        if (inputApk.length() == 0L) {
            return Result(
                success = false,
                signedPath = "",
                method = "apksig",
                error = "Cannot sign: ${inputApk.absolutePath} is empty.",
            )
        }
        if (!keystoreFile.isFile || keystoreFile.length() == 0L) {
            return Result(
                success = false,
                signedPath = "",
                method = "apksig",
                error = "Cannot sign: the signing keystore at ${keystoreFile.absolutePath} does not exist.",
            )
        }

        /*
         * Refuse a file that is not an APK, rather than signing it and returning
         * a success.
         *
         * apksig will happily sign a plain zip: it signs archives, and the APK
         * shape is the caller's business. But the caller here is `modApk`, which
         * repacks an extraction and can produce a zip that lost its manifest. A
         * signed non-APK reports success here and then fails at install with a
         * message about parsing the package, which points at signing rather than
         * at the rebuild that actually broke it.
         */
        val hasManifest = try {
            java.util.zip.ZipFile(inputApk).use { it.getEntry("AndroidManifest.xml") != null }
        } catch (e: Exception) {
            // Not even a readable zip, so it is certainly not signable.
            false
        }
        if (!hasManifest) {
            return Result(
                success = false,
                signedPath = "",
                method = "apksig",
                error = "Cannot sign: ${inputApk.absolutePath} has no AndroidManifest.xml, " +
                    "so it is not an APK. Rebuild the archive before signing it.",
            )
        }

        return try {
            val store = KeyStore.getInstance(KEYSTORE_TYPE)
            keystoreFile.inputStream().use { store.load(it, KEY_PASSWORD.toCharArray()) }

            val key = store.getKey(KEY_ALIAS, KEY_PASSWORD.toCharArray()) as? PrivateKey
                ?: return Result(
                    success = false,
                    signedPath = "",
                    method = "apksig",
                    error = "The signing keystore has no private key under alias '$KEY_ALIAS'.",
                )
            val certificate = store.getCertificate(KEY_ALIAS) as? X509Certificate
                ?: return Result(
                    success = false,
                    signedPath = "",
                    method = "apksig",
                    error = "The signing keystore has no certificate under alias '$KEY_ALIAS'.",
                )

            outputApk.parentFile?.mkdirs()
            if (outputApk.exists()) outputApk.delete()

            val signer = ApkSigner.Builder(
                listOf(
                    ApkSigner.SignerConfig.Builder(
                        KEY_ALIAS,
                        key,
                        listOf(certificate),
                    ).build(),
                ),
            )
                .setInputApk(inputApk)
                .setOutputApk(outputApk)
                .setMinSdkVersion(minSdk)
                // v3 is what records a rotation-capable lineage on Android 9+;
                // asking for it costs nothing where the platform ignores it.
                .setV1SigningEnabled(true)
                .setV2SigningEnabled(true)
                .setV3SigningEnabled(true)
                .setOtherSignersSignaturesPreserved(false)
                .build()

            signer.sign()

            if (!outputApk.isFile || outputApk.length() == 0L) {
                return Result(
                    success = false,
                    signedPath = "",
                    method = "apksig",
                    error = "apksig reported success but wrote no output to ${outputApk.absolutePath}.",
                )
            }

            Result(
                success = true,
                signedPath = outputApk.absolutePath,
                method = "apksig (v1+v2+v3, on-device)",
                error = null,
                schemes = "v1+v2+v3",
                certificateSha256 = sha256OfCertificate(certificate),
            )
        } catch (e: Exception) {
            // The class name matters: an "APK already signed" refusal and a
            // keystore fault are both Exception here and need to read differently
            // to whoever is choosing what to do next.
            Result(
                success = false,
                signedPath = "",
                method = "apksig",
                error = "Signing failed (${e.javaClass.simpleName}): ${e.message ?: "no detail"}",
            )
        }
    }

    /** Hex SHA-256 of the certificate's DER encoding. */
    private fun sha256OfCertificate(certificate: X509Certificate): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(certificate.encoded)
        return digest.joinToString("") { "%02x".format(it) }
    }

    /**
     * A self-signed X.509 certificate, built with JCA and a minimal DER writer.
     *
     * WHY BY HAND. There is no public JDK API for CREATING an X509Certificate;
     * CertificateFactory and KeyStore both parse one and neither makes one.
     * BouncyCastle would do it and is a 4 MB dependency for a single function,
     * and sun.security.x509 is an internal API that does not exist on Android at
     * all. So the certificate is encoded directly, which is the smallest correct
     * thing that works everywhere this app runs.
     *
     * The structure encoded is the X.509 v3 Certificate from RFC 5280:
     *
     *   Certificate  ::= SEQUENCE { tbsCertificate, signatureAlgorithm, signatureValue }
     *   TBSCertificate ::= SEQUENCE {
     *       version [0] EXPLICIT INTEGER DEFAULT v1,
     *       serialNumber INTEGER, signature AlgorithmIdentifier,
     *       issuer Name, validity Validity, subject Name,
     *       subjectPublicKeyInfo SubjectPublicKeyInfo }
     *
     * The public key is taken already-encoded from the KeyPair, because
     * `PublicKey.encoded` IS a DER SubjectPublicKeyInfo, so there is no reason to
     * rebuild it. The signature is made over the TBS bytes with the same
     * algorithm named in the AlgorithmIdentifier, which is what makes the result
     * verifiable rather than merely parseable.
     *
     * Extensions are omitted deliberately: a default-constructed v3 certificate
     * with none is exactly what the Android debug key has, and apksig checks
     * nothing in the extensions.
     */
    internal object SelfSignedCertificate {

        private const val OID_SHA256_WITH_RSA = "1.2.840.113549.1.1.11"
        private const val OID_CN = "2.5.4.3"
        private const val OID_OU = "2.5.4.11"
        private const val OID_O = "2.5.4.10"
        private const val OID_L = "2.5.4.7"
        private const val OID_ST = "2.5.4.8"
        private const val OID_C = "2.5.4.6"

        /** Serial numbers only need to be unique per issuer; a counter plus a
         *  clock value is enough and does not need a SecureRandom. */
        private val serialCounter = AtomicInteger(1)

        fun create(keyPair: java.security.KeyPair, validityDays: Long): X509Certificate {
            val notBefore = Date(System.currentTimeMillis() - TimeUnit.DAYS.toMillis(1))
            val notAfter = Date(System.currentTimeMillis() + TimeUnit.DAYS.toMillis(validityDays))

            val tbs = derSequence(
                // version: [0] EXPLICIT INTEGER 2  -> v3
                derTagged(0xA0.toByte(), derInteger(byteArrayOf(0x02))),
                derInteger(serialBytes()),
                // signature AlgorithmIdentifier
                derSequence(
                    derOid(OID_SHA256_WITH_RSA),
                    derNull(),
                ),
                // issuer and subject are the same Name: it is self-signed
                derName(),
                derSequence(
                    derUtcTime(notBefore),
                    derUtcTime(notAfter),
                ),
                derName(),
                // SubjectPublicKeyInfo, already DER from the key itself
                keyPair.public.encoded,
            )

            val signatureAlgorithm = derSequence(
                derOid(OID_SHA256_WITH_RSA),
                derNull(),
            )

            val signature = Signature.getInstance("SHA256withRSA")
            signature.initSign(keyPair.private)
            signature.update(tbs)
            val signatureBytes = signature.sign()

            val certificateDer = derSequence(
                tbs,
                signatureAlgorithm,
                // BIT STRING with zero unused bits
                derBitString(signatureBytes),
            )

            return CertificateFactory.getInstance("X.509")
                .generateCertificate(certificateDer.inputStream()) as X509Certificate
        }

        /**
         * A positive serial number.
         *
         * A DER INTEGER is two's complement, so a leading byte above 0x7F reads
         * as negative and a certificate with a negative serial is rejected by
         * some verifiers. The first byte is therefore kept below 0x80.
         */
        private fun serialBytes(): ByteArray {
            val millis = System.currentTimeMillis()
            val counter = serialCounter.getAndIncrement() and 0xFF
            val buffer = ByteArrayOutputStream()
            buffer.write(0x40 or (counter and 0x3F))
            for (shift in 48 downTo 0 step 8) {
                buffer.write(((millis shr shift) and 0xFF).toInt())
            }
            return buffer.toByteArray()
        }

        private fun derName(): ByteArray {
            // RDNSequence is a SEQUENCE of SETs of AttributeTypeAndValue, and the
            // order is the order a human reads them, which is also what keytool
            // produces for the same -dname string.
            fun rdn(oid: String, value: String) = derSet(
                derSequence(
                    derOid(oid),
                    derUtf8String(value),
                ),
            )
            return derSequence(
                rdn(OID_CN, SUBJECT_CN),
                rdn(OID_OU, SUBJECT_OU),
                rdn(OID_O, SUBJECT_O),
                rdn(OID_L, SUBJECT_L),
                rdn(OID_ST, SUBJECT_ST),
                rdn(OID_C, SUBJECT_C),
            )
        }

        // --- Minimal DER writers -------------------------------------------

        private fun derTagged(tag: Byte, content: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(tag.toInt())
            writeLength(out, content.size)
            out.write(content)
            return out.toByteArray()
        }

        private fun derSequence(vararg parts: ByteArray): ByteArray = derTagged(0x30, parts.concat())

        private fun derSet(vararg parts: ByteArray): ByteArray = derTagged(0x31, parts.concat())

        private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

        private fun derInteger(value: ByteArray): ByteArray = derTagged(0x02, value)

        private fun derBitString(value: ByteArray): ByteArray =
            derTagged(0x03, byteArrayOf(0x00) + value)

        private fun derUtf8String(value: String): ByteArray =
            derTagged(0x0C, value.toByteArray(Charsets.UTF_8))

        /**
         * UTCTime carries a two-digit year and is only valid for 1950-2049. A
         * 10,000-day validity from today reaches past 2049, so the year is
         * clamped into the UTCTime range and the certificate is still valid
         * today, which is the only thing apksig and Android check.
         */
        private fun derUtcTime(date: Date): ByteArray {
            val format = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
            format.timeZone = TimeZone.getTimeZone("UTC")
            val year = SimpleDateFormat("yyyy", Locale.US)
                .apply { timeZone = TimeZone.getTimeZone("UTC") }
                .format(date)
                .toInt()
            val safe = if (year > 2049) {
                // 31 December 2049, 23:59:59 UTC
                SimpleDateFormat("yyyyMMddHHmmss", Locale.US)
                    .apply { timeZone = TimeZone.getTimeZone("UTC") }
                    .parse("20491231235959")!!
            } else {
                date
            }
            return derTagged(0x17, format.format(safe).toByteArray(Charsets.US_ASCII))
        }

        /**
         * An OBJECT IDENTIFIER: the first two arcs fold into one byte as
         * 40*first+second, then each remaining arc is base-128 with the high bit
         * set on every byte except the last.
         */
        private fun derOid(oid: String): ByteArray {
            val arcs = oid.split('.').map { it.toLong() }
            require(arcs.size >= 2) { "an OID needs at least two arcs: $oid" }
            val out = ByteArrayOutputStream()
            out.write((40 * arcs[0] + arcs[1]).toInt())
            for (arc in arcs.drop(2)) {
                var value = arc
                val chunk = ByteArrayOutputStream()
                chunk.write((value and 0x7F).toInt())
                value = value shr 7
                while (value > 0) {
                    chunk.write(((value and 0x7F) or 0x80).toInt())
                    value = value shr 7
                }
                val bytes = chunk.toByteArray()
                for (i in bytes.indices.reversed()) out.write(bytes[i].toInt())
            }
            return derTagged(0x06, out.toByteArray())
        }

        /** DER length: short form below 128, otherwise long form. */
        private fun writeLength(out: ByteArrayOutputStream, length: Int) {
            if (length < 0x80) {
                out.write(length)
                return
            }
            var remaining = length
            val bytes = ByteArrayOutputStream()
            while (remaining > 0) {
                bytes.write(remaining and 0xFF)
                remaining = remaining shr 8
            }
            val encoded = bytes.toByteArray()
            out.write(0x80 or encoded.size)
            for (i in encoded.indices.reversed()) out.write(encoded[i].toInt())
        }

        private fun Array<out ByteArray>.concat(): ByteArray {
            val total = sumOf { it.size }
            val out = ByteArrayOutputStream(total)
            for (part in this) out.write(part)
            return out.toByteArray()
        }
    }
}
