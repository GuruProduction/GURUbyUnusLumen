package com.unuslumen.app.util.shell

import android.util.Log
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.XECPublicKeySpec
import java.security.spec.NamedParameterSpec
import javax.crypto.KeyAgreement
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * Pure Kotlin SPAKE2 implementation matching BoringSSL's exact algorithm
 * as used by Android's ADB pairing daemon (adbd).
 *
 * Uses Android's built-in X25519 KeyAgreement for scalar multiplication
 * and Ed25519 extended coordinate arithmetic for point addition/subtraction.
 *
 * Protocol flow (matching Shizuku's AdbPairingClient):
 * 1. Generate random scalar x, compute X = x*B + pw*M (client)
 * 2. Send X to peer, receive Y from peer
 * 3. Compute shared secret: K = (Y - pw*N)^x (client)
 * 4. Derive AES-128-GCM key via HKDF-SHA256
 * 5. Encrypt/decrypt peer info with AES-128-GCM using sequence-based nonces
 *
 * Reference: BoringSSL's spake25519.c, AOSP's pairing_auth.cpp, Shizuku's adb_pairing.cpp
 */
class Spake2 private constructor(
    private val role: Role,
    private val myName: ByteArray,
    private val theirName: ByteArray,
    private val password: ByteArray
) {
    enum class Role { CLIENT, SERVER }

    companion object {
        private const val TAG = "guru"

        // ADB pairing names (from AOSP, with null terminators matching BoringSSL)
        private val CLIENT_NAME = "adb pair client\u0000".toByteArray(Charsets.US_ASCII)
        private val SERVER_NAME = "adb pair server\u0000".toByteArray(Charsets.US_ASCII)

        // Curve25519 prime: 2^255 - 19
        private val P = java.math.BigInteger("72370055773322622139731865630429942408293740416025352524609994995782788967603")

        // Ed25519 parameters
        private val ED25519_D = java.math.BigInteger("37095705934643414759179890542855279545202047564770572494512390516351503850790")
        private val ED25519_L = java.math.BigInteger("72370055773322622139731865630429942408293740416025352524609994995782788967604")
        private val ED25519_SQRT_M1 = java.math.BigInteger("19681161376707505956807079304988542015446066515923890127444721721206850667615")

        // Ed25519 base point
        private val ED25519_BASE_X = java.math.BigInteger("15112221349535807908742529608623025208887383471466464708250871441876532987330")
        private val ED25519_BASE_Y = java.math.BigInteger("46316835694926478169428394003475163141307993866256361573442533685876545002330")

        // SPAKE2 M and N constants (computed from seeds via hash-to-curve)
        private val M_U: ByteArray by lazy { computeConstant("M-Ed25519") }
        private val N_U: ByteArray by lazy { computeConstant("N-Ed25519") }

        // Base point u-coordinate for Curve25519: u = 9
        private val BASE_POINT_U = ByteArray(32).also { it[0] = 9 }

        fun createClient(password: ByteArray): Spake2 {
            return Spake2(Role.CLIENT, CLIENT_NAME, SERVER_NAME, password)
        }

        /**
         * Compute SPAKE2 constant by hashing seed to a curve point.
         * BoringSSL: SHA-512(seed) -> clamp first 32 bytes as scalar -> scalar * base_point -> u-coordinate
         */
        private fun computeConstant(seed: String): ByteArray {
            val digest = MessageDigest.getInstance("SHA-512")
            digest.update(seed.toByteArray(Charsets.US_ASCII))
            val hash = digest.digest()
            val scalar = clampScalar(hash.copyOfRange(0, 32))
            val scalarInt = java.math.BigInteger(1, scalar).mod(ED25519_L)
            val point = ed25519ScalarMult(scalarInt, ED25519_BASE)
            return ed25519ToMontgomeryU(point)
        }

        private fun clampScalar(bytes: ByteArray): ByteArray {
            val result = bytes.copyOf()
            result[0] = (result[0].toInt() and 248).toByte()
            result[31] = (result[31].toInt() and 127).toByte()
            result[31] = (result[31].toInt() or 64).toByte()
            return result
        }

        // ===== Ed25519 extended coordinate arithmetic =====

        private data class Ed25519Point(
            val X: java.math.BigInteger,
            val Y: java.math.BigInteger,
            val Z: java.math.BigInteger,
            val T: java.math.BigInteger
        )

        private val ED25519_IDENTITY = Ed25519Point(
            java.math.BigInteger.ZERO, java.math.BigInteger.ONE,
            java.math.BigInteger.ONE, java.math.BigInteger.ZERO
        )

        private val ED25519_BASE = Ed25519Point(
            ED25519_BASE_X, ED25519_BASE_Y,
            java.math.BigInteger.ONE,
            (ED25519_BASE_X * ED25519_BASE_Y).mod(P)
        )

        private fun ed25519PointAdd(p: Ed25519Point, q: Ed25519Point): Ed25519Point {
            val A = (p.X * q.Y).mod(P)
            val B = (p.Y * q.X).mod(P)
            val C = (p.T * q.T * ED25519_D).mod(P)
            val D = (p.Z * q.Z).mod(P)
            val E = (A + B).mod(P)
            val F = ((D - C + P)).mod(P)
            val G = (D + C).mod(P)
            val H = ((B - A + P)).mod(P)
            return Ed25519Point(
                (E * F).mod(P),
                (G * H).mod(P),
                (F * G).mod(P),
                (E * H).mod(P)
            )
        }

        private fun ed25519PointNegate(p: Ed25519Point): Ed25519Point {
            return Ed25519Point((P - p.X).mod(P), p.Y, p.Z, (P - p.T).mod(P))
        }

        private fun ed25519ScalarMult(scalar: java.math.BigInteger, point: Ed25519Point): Ed25519Point {
            var result = ED25519_IDENTITY
            var addend = point
            var s = scalar.mod(ED25519_L)
            if (s < java.math.BigInteger.ZERO) s = s.add(ED25519_L)

            while (s > java.math.BigInteger.ZERO) {
                if (s.and(java.math.BigInteger.ONE) == java.math.BigInteger.ONE) {
                    result = ed25519PointAdd(result, addend)
                }
                addend = ed25519PointAdd(addend, addend)
                s = s.shiftRight(1)
            }
            return result
        }

        /**
         * Convert Ed25519 point to Montgomery u-coordinate.
         * u = (1 + y) / (1 - y) mod p, where y = Y/Z
         */
        private fun ed25519ToMontgomeryU(point: Ed25519Point): ByteArray {
            val zInv = point.Z.modInverse(P)
            val y = (point.Y * zInv).mod(P)
            val onePlusY = (java.math.BigInteger.ONE + y).mod(P)
            val oneMinusY = ((java.math.BigInteger.ONE - y + P)).mod(P)
            val u = onePlusY.multiply(oneMinusY.modInverse(P)).mod(P)
            return encodeUCoordinate(u)
        }

        /**
         * Convert Montgomery u-coordinate to Ed25519 point.
         * y = (u-1)/(u+1) mod p
         * x = sqrt((y^2-1)/(d*y^2+1)) mod p
         */
        private fun montgomeryUToEd25519(u: java.math.BigInteger): Ed25519Point {
            val uMod = u.mod(P)
            val uPlus1 = (uMod + java.math.BigInteger.ONE).mod(P)
            val uMinus1 = ((uMod - java.math.BigInteger.ONE + P)).mod(P)
            val y = uMinus1.multiply(uPlus1.modInverse(P)).mod(P)

            val y2 = (y * y).mod(P)
            val num = ((y2 - java.math.BigInteger.ONE + P)).mod(P)
            val den = (ED25519_D * y2 + java.math.BigInteger.ONE).mod(P)
            val x2 = num.multiply(den.modInverse(P)).mod(P)

            var x = sqrtModP(x2)
            if ((x * x).mod(P) != x2.mod(P)) {
                x = P.subtract(x)
            }

            val T = (x * y).mod(P)
            return Ed25519Point(x, y, java.math.BigInteger.ONE, T)
        }

        private fun sqrtModP(a: java.math.BigInteger): java.math.BigInteger {
            val exp = (P.add(java.math.BigInteger.valueOf(3))).divide(java.math.BigInteger.valueOf(8))
            var x = a.mod(P).modPow(exp, P)
            if ((x * x).mod(P) != a.mod(P)) {
                x = x.multiply(ED25519_SQRT_M1).mod(P)
            }
            return x
        }

        private fun encodeUCoordinate(u: java.math.BigInteger): ByteArray {
            val result = ByteArray(32)
            val uMod = u.mod(P)
            val bytes = uMod.toByteArray()
            for (i in bytes.indices) {
                val srcIdx = bytes.size - 1 - i
                if (srcIdx >= 0 && i < 32) {
                    result[i] = bytes[srcIdx]
                }
            }
            return result
        }

        private fun decodeUCoordinate(bytes: ByteArray): java.math.BigInteger {
            val result = bytes.copyOf()
            result[31] = (result[31].toInt() and 0x7F).toByte()
            return java.math.BigInteger(1, result.reversedArray())
        }

        /**
         * X25519 scalar multiplication using Android's built-in XDH KeyAgreement.
         */
        private fun x25519(scalar: ByteArray, u: ByteArray): ByteArray {
            return try {
                val keyFactory = KeyFactory.getInstance("XDH")
                val privateKeySpec = PKCS8EncodedKeySpec(encodeX25519PrivateKey(scalar))
                val privateKey = keyFactory.generatePrivate(privateKeySpec)
                val uBigInt = java.math.BigInteger(1, u.reversedArray())
                val publicKeySpec = XECPublicKeySpec(NamedParameterSpec("X25519"), uBigInt)
                val publicKey = keyFactory.generatePublic(publicKeySpec)
                val keyAgreement = KeyAgreement.getInstance("XDH")
                keyAgreement.init(privateKey)
                keyAgreement.doPhase(publicKey, true)
                keyAgreement.generateSecret()
            } catch (e: Exception) {
                Log.e(TAG, "SPAKE2: X25519 failed, using manual fallback", e)
                x25519Manual(scalar, u)
            }
        }

        private fun encodeX25519PrivateKey(scalar: ByteArray): ByteArray {
            val x25519Oid = byteArrayOf(0x06, 0x03, 0x55, 0x6E)
            val algSeq = wrapDer(0x30, x25519Oid)
            val version = byteArrayOf(0x02, 0x01, 0x00)
            val privKeyOctet = wrapDer(0x04, scalar)
            return wrapDer(0x30, version + algSeq + privKeyOctet)
        }

        private fun wrapDer(tag: Int, content: ByteArray): ByteArray {
            val tagByte = tag.toByte()
            return when {
                content.size < 128 -> byteArrayOf(tagByte, content.size.toByte()) + content
                content.size < 256 -> byteArrayOf(tagByte, 0x81.toByte(), content.size.toByte()) + content
                else -> byteArrayOf(tagByte, 0x82.toByte(), (content.size shr 8).toByte(), content.size.toByte()) + content
            }
        }

        /**
         * Manual X25519 fallback using BigInteger Montgomery ladder.
         */
        private fun x25519Manual(scalar: ByteArray, u: ByteArray): ByteArray {
            val a24 = java.math.BigInteger.valueOf(121666)
            val k = clampScalar(scalar)
            val uInt = decodeUCoordinate(u)

            var x1 = java.math.BigInteger.ONE
            var z1 = java.math.BigInteger.ZERO
            var x2 = uInt
            var z2 = java.math.BigInteger.ONE

            for (i in 254 downTo 0) {
                val bit = (k[i / 8].toInt() ushr (i % 8)) and 1
                if (bit == 1) {
                    val tmpX = x1; x1 = x2; x2 = tmpX
                    val tmpZ = z1; z1 = z2; z2 = tmpZ
                }

                val a = (x2 + z2).mod(P)
                val aa = (a * a).mod(P)
                val b = ((x2 - z2 + P)).mod(P)
                val bb = (b * b).mod(P)
                val e = ((aa - bb + P)).mod(P)

                val oldX1 = x1
                val oldZ1 = z1

                val x4 = (aa * bb).mod(P)
                val z4 = (e * ((bb + a24 * e) % P)).mod(P)

                val da = ((oldX1 + oldZ1) * b).mod(P)
                val db = ((oldX1 - oldZ1 + P) * a).mod(P)
                val x3 = ((da + db) * (da + db)).mod(P)
                val z3 = (oldX1 * ((da - db + P) * (da - db + P))).mod(P)

                if (bit == 1) {
                    x2 = x3; z2 = z3; x1 = x4; z1 = z4
                } else {
                    x2 = x4; z2 = z4; x1 = x3; z1 = z3
                }
            }

            val result = (x1 * z1.modInverse(P)).mod(P)
            return encodeUCoordinate(result)
        }

        private fun montgomeryAdd(u1: ByteArray, u2: ByteArray): ByteArray {
            val p1 = montgomeryUToEd25519(decodeUCoordinate(u1))
            val p2 = montgomeryUToEd25519(decodeUCoordinate(u2))
            val sum = ed25519PointAdd(p1, p2)
            return ed25519ToMontgomeryU(sum)
        }

        private fun montgomerySub(u1: ByteArray, u2: ByteArray): ByteArray {
            val p1 = montgomeryUToEd25519(decodeUCoordinate(u1))
            val p2 = montgomeryUToEd25519(decodeUCoordinate(u2))
            val diff = ed25519PointAdd(p1, ed25519PointNegate(p2))
            return ed25519ToMontgomeryU(diff)
        }
    }

    private var secretScalar: ByteArray? = null
    private var ourMessage: ByteArray? = null

    fun generateMessage(): ByteArray {
        val random = ByteArray(32)
        java.security.SecureRandom().nextBytes(random)
        secretScalar = clampScalar(random)

        val xB = x25519(secretScalar!!, BASE_POINT_U)
        val pwScalar = computePasswordScalar()
        val constant = if (role == Role.CLIENT) M_U else N_U
        val pwConstant = x25519(pwScalar, constant)
        ourMessage = montgomeryAdd(xB, pwConstant)

        Log.d(TAG, "SPAKE2: generated message (${ourMessage!!.size} bytes)")
        return ourMessage!!
    }

    fun processPeerMessage(peerMessage: ByteArray): ByteArray {
        if (secretScalar == null || ourMessage == null) {
            throw IllegalStateException("Must call generateMessage() first")
        }

        val pwScalar = computePasswordScalar()
        val constant = if (role == Role.CLIENT) N_U else M_U
        val pwConstant = x25519(pwScalar, constant)
        val peerAdjusted = montgomerySub(peerMessage, pwConstant)
        val sharedSecret = x25519(secretScalar!!, peerAdjusted)
        val keyMaterial = deriveKeyMaterial(sharedSecret, ourMessage!!, peerMessage)
        Log.d(TAG, "SPAKE2: derived key material (${keyMaterial.size} bytes)")
        return keyMaterial
    }

    private fun computePasswordScalar(): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("SPAKE2 pw".toByteArray(Charsets.US_ASCII))
        digest.update(intToLeBytes(myName.size))
        digest.update(intToLeBytes(theirName.size))
        digest.update(myName)
        digest.update(theirName)
        digest.update(password)
        return clampScalar(digest.digest())
    }

    private fun deriveKeyMaterial(sharedSecret: ByteArray, ourMsg: ByteArray, theirMsg: ByteArray): ByteArray {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update("SPAKE2 ks".toByteArray(Charsets.US_ASCII))
        digest.update(intToLeBytes(myName.size))
        digest.update(intToLeBytes(theirName.size))
        digest.update(myName)
        digest.update(theirName)
        digest.update(ourMsg)
        digest.update(theirMsg)
        digest.update(sharedSecret)
        val transcript = digest.digest()

        return hkdfSha256(
            ikm = transcript,
            salt = null,
            info = "adb pairing_auth aes-128-gcm key".toByteArray(Charsets.US_ASCII),
            length = 16
        )
    }

    private fun hkdfSha256(ikm: ByteArray, salt: ByteArray?, info: ByteArray, length: Int): ByteArray {
        val actualSalt = salt ?: ByteArray(32) { 0 }
        val prk = hmacSha256(actualSalt, ikm)
        val result = ByteArray(length)
        var t = ByteArray(0)
        var counter = 1
        var offset = 0
        while (offset < length) {
            val input = t + info + byteArrayOf(counter.toByte())
            t = hmacSha256(prk, input)
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }

    private fun hmacSha256(key: ByteArray, data: ByteArray): ByteArray {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(key, "HmacSHA256"))
        return mac.doFinal(data)
    }

    private fun intToLeBytes(value: Int): ByteArray {
        return byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        )
    }
}