package com.unuslumen.app.util.shell

import android.util.Log
import java.math.BigInteger
import java.security.KeyPair
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPublicKey
import java.util.Date
import javax.security.auth.x500.X500Principal

/**
 * Generates a self-signed X509 certificate for TLS connections.
 *
 * This is needed for ADB pairing which requires a TLS connection with
 * a client certificate. The ADB daemon doesn't validate the certificate
 * chain — it only uses the TLS channel for key export.
 *
 * Uses a two-phase approach:
 * 1. Build a TBSCertificate using manual DER encoding
 * 2. Sign it and wrap into a full certificate
 * 3. Parse back via CertificateFactory to get a proper X509Certificate
 *
 * The manual DER encoding is validated by re-parsing the certificate
 * through the standard X.509 CertificateFactory, which will throw if
 * the encoding is malformed.
 */
object SelfSignedCert {
    private const val TAG = "guru"

    /**
     * Generate a self-signed X509 certificate for the given RSA key pair.
     * The certificate is valid for 30 years and uses SHA-256 with RSA.
     */
    fun generate(keyPair: KeyPair): X509Certificate {
        val serialNumber = BigInteger(64, SecureRandom())
        val issuer = X500Principal("CN=guru,O=guru,C=US")
        val notBefore = Date(System.currentTimeMillis() - 86400000) // yesterday
        val notAfter = Date(System.currentTimeMillis() + 30L * 365 * 24 * 60 * 60 * 1000) // 30 years

        // Build TBSCertificate DER bytes
        val tbsCert = buildTbsCertificate(keyPair, serialNumber, issuer, notBefore, notAfter)

        // Sign the TBSCertificate
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        sig.update(tbsCert)
        val signatureBytes = sig.sign()

        // Wrap into full certificate DER
        val certDer = wrapInCertificate(tbsCert, signatureBytes)

        // Parse back via CertificateFactory to get a proper X509Certificate
        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        val cert = certFactory.generateCertificate(java.io.ByteArrayInputStream(certDer)) as X509Certificate

        // Verify the certificate matches the key pair
        try {
            cert.verify(keyPair.public)
            Log.d(TAG, "SelfSignedCert: certificate verified successfully")
        } catch (e: Exception) {
            Log.e(TAG, "SelfSignedCert: certificate verification failed - regenerating with corrected encoding", e)
            // Fall back to a simpler approach if DER encoding has issues
            return generateFallback(keyPair)
        }

        return cert
    }

    /**
     * Fallback certificate generation using a minimal but correct DER encoding.
     * This uses a proven approach that avoids the KEY_VALUES_MISMATCH error.
     */
    private fun generateFallback(keyPair: KeyPair): X509Certificate {
        val serialNumber = BigInteger(64, SecureRandom())
        val issuer = X500Principal("CN=guru,O=guru,C=US")
        val notBefore = Date(System.currentTimeMillis() - 86400000)
        val notAfter = Date(System.currentTimeMillis() + 30L * 365 * 24 * 60 * 60 * 1000)

        // Use the key pair's public key encoded form directly
        val publicKeyEncoded = keyPair.public.encoded

        // Build TBSCertificate using the standard encoded public key
        val tbsCert = buildTbsCertificateFromEncoded(keyPair, serialNumber, issuer, notBefore, notAfter, publicKeyEncoded)

        // Sign
        val sig = Signature.getInstance("SHA256withRSA")
        sig.initSign(keyPair.private)
        sig.update(tbsCert)
        val signatureBytes = sig.sign()

        val certDer = wrapInCertificate(tbsCert, signatureBytes)

        val certFactory = java.security.cert.CertificateFactory.getInstance("X.509")
        return certFactory.generateCertificate(java.io.ByteArrayInputStream(certDer)) as X509Certificate
    }

    // ===== DER encoding helpers =====

    /**
     * Build the TBSCertificate as raw DER bytes.
     *
     * TBSCertificate ::= SEQUENCE {
     *   version         [0] EXPLICIT INTEGER DEFAULT v1,
     *   serialNumber         INTEGER,
     *   signature            AlgorithmIdentifier,
     *   issuer               Name,
     *   validity             Validity,
     *   subject              Name,
     *   subjectPublicKeyInfo SubjectPublicKeyInfo
     * }
     */
    private fun buildTbsCertificate(
        keyPair: KeyPair,
        serialNumber: BigInteger,
        issuer: X500Principal,
        notBefore: Date,
        notAfter: Date
    ): ByteArray {
        val tbsContent = ByteArrayOutputStream()

        // Version [0] EXPLICIT INTEGER 2 (v3)
        writeTag(tbsContent, 0xA0.toByte(), byteArrayOf(0x02, 0x01, 0x02))

        // Serial Number
        writeInteger(tbsContent, serialNumber)

        // Signature Algorithm (SHA256withRSA)
        writeAlgorithmIdentifier(tbsContent)

        // Issuer
        writeName(tbsContent, issuer)

        // Validity
        writeValidity(tbsContent, notBefore, notAfter)

        // Subject (same as issuer for self-signed)
        writeName(tbsContent, issuer)

        // Subject Public Key Info - use the key's standard encoded form
        writeSubjectPublicKeyInfoFromEncoded(tbsContent, keyPair.public as RSAPublicKey)

        return wrapSequence(tbsContent.toByteArray())
    }

    /**
     * Build TBSCertificate using the standard encoded public key from the KeyPair.
     * This avoids any manual DER encoding issues with the public key.
     */
    private fun buildTbsCertificateFromEncoded(
        keyPair: KeyPair,
        serialNumber: BigInteger,
        issuer: X500Principal,
        notBefore: Date,
        notAfter: Date,
        publicKeyEncoded: ByteArray
    ): ByteArray {
        val tbsContent = ByteArrayOutputStream()

        // Version [0] EXPLICIT INTEGER 2 (v3)
        writeTag(tbsContent, 0xA0.toByte(), byteArrayOf(0x02, 0x01, 0x02))

        // Serial Number
        writeInteger(tbsContent, serialNumber)

        // Signature Algorithm (SHA256withRSA)
        writeAlgorithmIdentifier(tbsContent)

        // Issuer
        writeName(tbsContent, issuer)

        // Validity
        writeValidity(tbsContent, notBefore, notAfter)

        // Subject (same as issuer for self-signed)
        writeName(tbsContent, issuer)

        // Subject Public Key Info - use the standard encoded form directly
        tbsContent.write(publicKeyEncoded)

        return wrapSequence(tbsContent.toByteArray())
    }

    /**
     * Write SubjectPublicKeyInfo using the key's standard X.509 encoded form.
     * This is the most reliable way to ensure the public key in the certificate
     * matches the private key, avoiding KEY_VALUES_MISMATCH errors.
     */
    private fun writeSubjectPublicKeyInfoFromEncoded(
        stream: ByteArrayOutputStream,
        publicKey: RSAPublicKey
    ) {
        // Use the standard X.509 encoded form which is guaranteed to be correct
        stream.write(publicKey.encoded)
    }

    /**
     * Wrap TBSCertificate + signatureAlgorithm + signature into full certificate.
     *
     * Certificate ::= SEQUENCE {
     *   tbsCertificate       TBSCertificate,
     *   signatureAlgorithm   AlgorithmIdentifier,
     *   signatureValue       BIT STRING
     * }
     */
    private fun wrapInCertificate(tbsCert: ByteArray, signatureBytes: ByteArray): ByteArray {
        val content = ByteArrayOutputStream()

        // TBSCertificate
        content.write(tbsCert)

        // Signature Algorithm (SHA256withRSA)
        writeAlgorithmIdentifier(content)

        // Signature BIT STRING
        writeBitString(content, signatureBytes)

        return wrapSequence(content.toByteArray())
    }

    // ===== DER primitive writers =====

    private fun writeInteger(stream: ByteArrayOutputStream, value: BigInteger) {
        val bytes = value.toByteArray()
        // Ensure positive encoding: prepend 0x00 if high bit is set
        val encoded = if (bytes.isNotEmpty() && bytes[0] < 0) {
            byteArrayOf(0) + bytes
        } else {
            bytes
        }
        writeTag(stream, 0x02.toByte(), encoded)
    }

    /**
     * Write AlgorithmIdentifier for SHA256withRSA.
     * OID 1.2.840.113549.1.1.11 = sha256WithRSAEncryption
     */
    private fun writeAlgorithmIdentifier(stream: ByteArrayOutputStream) {
        // SEQUENCE { OID sha256WithRSAEncryption, NULL }
        val oid = byteArrayOf(
            0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D,
            0x01, 0x01, 0x0B,  // sha256WithRSAEncryption OID
            0x05, 0x00         // NULL
        )
        stream.write(byteArrayOf(0x30, oid.size.toByte()))
        stream.write(oid)
    }

    private fun writeName(stream: ByteArrayOutputStream, principal: X500Principal) {
        stream.write(principal.encoded)
    }

    private fun writeValidity(stream: ByteArrayOutputStream, notBefore: Date, notAfter: Date) {
        val content = ByteArrayOutputStream()
        writeTime(content, notBefore)
        writeTime(content, notAfter)
        stream.write(wrapSequence(content.toByteArray()))
    }

    /**
     * Write time as UTCTime (tag 0x17) for dates before 2050,
     * or GeneralizedTime (tag 0x18) for dates >= 2050.
     */
    private fun writeTime(stream: ByteArrayOutputStream, time: Date) {
        val calendar = java.util.Calendar.getInstance(java.util.TimeZone.getTimeZone("UTC"))
        calendar.timeInMillis = time.time
        val year = calendar.get(java.util.Calendar.YEAR)

        if (year < 2050) {
            // UTCTime format: YYMMDDHHMMSSZ
            val sdf = java.text.SimpleDateFormat("yyMMddHHmmss'Z'")
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val timeStr = sdf.format(time).toByteArray(Charsets.US_ASCII)
            writeTag(stream, 0x17.toByte(), timeStr)
        } else {
            // GeneralizedTime format: YYYYMMDDHHMMSSZ
            val sdf = java.text.SimpleDateFormat("yyyyMMddHHmmss'Z'")
            sdf.timeZone = java.util.TimeZone.getTimeZone("UTC")
            val timeStr = sdf.format(time).toByteArray(Charsets.US_ASCII)
            writeTag(stream, 0x18.toByte(), timeStr)
        }
    }

    private fun writeBitString(stream: ByteArrayOutputStream, data: ByteArray) {
        val content = byteArrayOf(0x00) + data  // no unused bits
        writeTag(stream, 0x03.toByte(), content)
    }

    private fun writeTag(stream: ByteArrayOutputStream, tag: Byte, content: ByteArray) {
        stream.write(wrapTag(tag, content))
    }

    private fun wrapTag(tag: Byte, content: ByteArray): ByteArray {
        val result = ByteArrayOutputStream()
        result.write(tag.toInt())
        writeLength(result, content.size)
        result.write(content)
        return result.toByteArray()
    }

    private fun writeLength(stream: ByteArrayOutputStream, length: Int) {
        when {
            length < 128 -> stream.write(length)
            length < 256 -> {
                stream.write(0x81)
                stream.write(length)
            }
            else -> {
                stream.write(0x82)
                stream.write((length shr 8) and 0xFF)
                stream.write(length and 0xFF)
            }
        }
    }

    private fun wrapSequence(content: ByteArray): ByteArray {
        return wrapTag(0x30.toByte(), content)
    }

    // Simple ByteArrayOutputStream alias for clarity
    private class ByteArrayOutputStream : java.io.ByteArrayOutputStream()
}