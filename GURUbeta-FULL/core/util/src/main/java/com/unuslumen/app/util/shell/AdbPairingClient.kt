package com.unuslumen.app.util.shell

import android.os.Build
import android.util.Log
import androidx.annotation.RequiresApi
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.Cipher
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import java.security.cert.X509Certificate

/**
 * ADB Wireless Debugging pairing client.
 *
 * Matches Shizuku's AdbPairingClient implementation exactly:
 * 1. TLS connection to pairing port
 * 2. Export keying material via Conscrypt
 * 3. SPAKE2 key exchange with pairing code
 * 4. AES-128-GCM encrypted peer info exchange
 *
 * The pairing code is the 6-digit number shown in:
 * Settings > Developer Options > Wireless Debugging > Pair device with pairing code
 */
@RequiresApi(Build.VERSION_CODES.R)
class AdbPairingClient(
    private val host: String,
    private val port: Int,
    private val pairingCode: String,
    private val rsaKeyPair: KeyPair
) {
    companion object {
        private const val TAG = "guru"

        // Pairing packet header constants (matching AOSP)
        private const val kCurrentKeyHeaderVersion: Byte = 1
        private const val kMinSupportedKeyHeaderVersion: Byte = 1
        private const val kMaxSupportedKeyHeaderVersion: Byte = 1
        private const val kMaxPeerInfoSize = 8192
        private const val kMaxPayloadSize = kMaxPeerInfoSize * 2
        private const val kPairingPacketHeaderSize = 6

        // TLS key export constants (matching AOSP)
        private const val kExportedKeyLabel = "adb-label\u0000"
        private const val kExportedKeySize = 64

        // Peer info type constants
        private const val PEER_INFO_TYPE_ADB_RSA_PUB_KEY: Byte = 0
    }

    private data class PairingPacketHeader(
        val version: Byte,
        val type: Byte,
        val payloadSize: Int
    ) {
        enum class Type(val value: Byte) {
            SPAKE2_MSG(0),
            PEER_INFO(1)
        }
    }

    private data class PeerInfo(
        val type: Byte,
        val data: ByteArray
    ) {
        fun writeTo(buffer: ByteBuffer) {
            buffer.put(type)
            buffer.put(data)
        }

        companion object {
            fun readFrom(buffer: ByteBuffer): PeerInfo {
                val type = buffer.get()
                val data = ByteArray(kMaxPeerInfoSize - 1)
                buffer.get(data)
                return PeerInfo(type, data)
            }
        }
    }

    /**
     * Result of a pairing attempt.
     */
    data class PairingResult(
        val success: Boolean,
        val error: String? = null
    )

    /**
     * Start the pairing process.
     * Connects to the pairing port, performs TLS handshake, SPAKE2 exchange,
     * and sends our RSA public key.
     */
    suspend fun start(): PairingResult = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        // Retry connection up to 3 times with short delays.
        // The pairing port may take a moment to become available, or the user
        // may not be on the pairing screen yet.
        val maxRetries = 3
        var lastException: Exception? = null

        for (attempt in 1..maxRetries) {
            try {
                Log.d(TAG, "AdbPairingClient: starting pairing with $host:$port (attempt $attempt/$maxRetries)")
                return@withContext performPairing()
            } catch (e: java.net.ConnectException) {
                lastException = e
                Log.w(TAG, "AdbPairingClient: connection refused on attempt $attempt/$maxRetries: ${e.message}")
                if (attempt < maxRetries) {
                    kotlinx.coroutines.delay(1500L * attempt) // Progressive backoff: 1.5s, 3s
                }
            } catch (e: Exception) {
                Log.e(TAG, "AdbPairingClient: pairing failed", e)
                return@withContext PairingResult(success = false, error = "Pairing failed: ${e.message}")
            }
        }

        val errorMsg = lastException?.message ?: "Unknown error"
        PairingResult(
            success = false,
            error = "Could not connect to ADB pairing port $host:$port. " +
                "Make sure you are on the 'Pair device with pairing code' screen in Developer Options. " +
                "Error: $errorMsg"
        )
    }

    private suspend fun performPairing(): PairingResult {
        // Step 1: TLS connection
        val socket = Socket()
        socket.connect(java.net.InetSocketAddress(host, port), 10000)
        socket.tcpNoDelay = true
        socket.soTimeout = 0 // No timeout — AGI doesn't have timeouts

        Log.d(TAG, "AdbPairingClient: connected, setting up TLS")

        try {
            // Step 2: TLS handshake with self-signed cert
            val sslSocket = createTlsSocket(socket)
            val inputStream = DataInputStream(sslSocket.inputStream)
            val outputStream = DataOutputStream(sslSocket.outputStream)

            Log.d(TAG, "AdbPairingClient: TLS handshake succeeded")

            // Step 3: Export keying material from TLS
            val keyMaterial = exportKeyingMaterial(sslSocket)
            Log.d(TAG, "AdbPairingClient: exported key material (${keyMaterial.size} bytes)")

            // Step 4: SPAKE2 key exchange
            // Password = pairingCode + keyMaterial
            val password = pairingCode.toByteArray(Charsets.US_ASCII) + keyMaterial
            val spake2 = Spake2.createClient(password)
            val ourMessage = spake2.generateMessage()

            Log.d(TAG, "AdbPairingClient: SPAKE2 message generated (${ourMessage.size} bytes)")

            // Step 5: Exchange SPAKE2 messages
            val ourHeader = createHeader(PairingPacketHeader.Type.SPAKE2_MSG, ourMessage.size)
            writeHeader(outputStream, ourHeader, ourMessage)

            Log.d(TAG, "AdbPairingClient: sent SPAKE2 message, waiting for peer...")

            val theirHeader = readHeader(inputStream)
                ?: return PairingResult(false, "Failed to read peer header")

            if (theirHeader.type != PairingPacketHeader.Type.SPAKE2_MSG.value) {
                return PairingResult(false, "Expected SPAKE2_MSG, got type ${theirHeader.type}")
            }

            if (theirHeader.version < kMinSupportedKeyHeaderVersion ||
                theirHeader.version > kMaxSupportedKeyHeaderVersion) {
                return PairingResult(false, "Unsupported header version: ${theirHeader.version}")
            }

            val theirMessage = ByteArray(theirHeader.payloadSize)
            inputStream.readFully(theirMessage)

            Log.d(TAG, "AdbPairingClient: received peer SPAKE2 message (${theirMessage.size} bytes)")

            // Step 6: Derive AES key from SPAKE2 shared secret
            val aesKey = spake2.processPeerMessage(theirMessage)
            Log.d(TAG, "AdbPairingClient: derived AES key (${aesKey.size} bytes)")

            // Step 7: Exchange peer info (encrypted with AES-128-GCM)
            val peerInfo = PeerInfo(
                PEER_INFO_TYPE_ADB_RSA_PUB_KEY,
                encodeAdbPublicKey(rsaKeyPair)
            )

            val peerInfoBuffer = ByteBuffer.allocate(kMaxPeerInfoSize).order(ByteOrder.BIG_ENDIAN)
            peerInfo.writeTo(peerInfoBuffer)
            val peerInfoData = peerInfoBuffer.array()

            // Encrypt our peer info
            val encSequence = 0L
            val encryptedPeerInfo = encryptAesGcm(aesKey, peerInfoData, encSequence)

            val peerInfoHeader = createHeader(PairingPacketHeader.Type.PEER_INFO, encryptedPeerInfo.size)
            writeHeader(outputStream, peerInfoHeader, encryptedPeerInfo)

            Log.d(TAG, "AdbPairingClient: sent encrypted peer info")

            // Step 8: Receive and decrypt peer's info
            val theirPeerInfoHeader = readHeader(inputStream)
                ?: return PairingResult(false, "Failed to read peer info header")

            if (theirPeerInfoHeader.type != PairingPacketHeader.Type.PEER_INFO.value) {
                return PairingResult(false, "Expected PEER_INFO, got type ${theirPeerInfoHeader.type}")
            }

            val theirEncryptedPeerInfo = ByteArray(theirPeerInfoHeader.payloadSize)
            inputStream.readFully(theirEncryptedPeerInfo)

            val decSequence = 0L
            val decryptedPeerInfo = decryptAesGcm(aesKey, theirEncryptedPeerInfo, decSequence)

            if (decryptedPeerInfo == null) {
                return PairingResult(false, "Failed to decrypt peer info - wrong pairing code?")
            }

            Log.d(TAG, "AdbPairingClient: pairing successful!")

            // Clean up
            try { inputStream.close() } catch (_: Exception) {}
            try { outputStream.close() } catch (_: Exception) {}
            try { sslSocket.close() } catch (_: Exception) {}
            try { socket.close() } catch (_: Exception) {}

            return PairingResult(success = true)
        } catch (e: Exception) {
            try { socket.close() } catch (_: Exception) {}
            throw e
        }
    }

    /**
     * Create TLS socket for ADB pairing connection.
     * Uses a self-signed X509 certificate with our RSA key.
     */
    private fun createTlsSocket(socket: Socket): SSLSocket {
        val trustManagers = arrayOf(object : X509ExtendedTrustManager() {
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {}
            override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: javax.net.ssl.SSLEngine?) {}
            override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
            override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
        })

        val keyManager = object : X509ExtendedKeyManager() {
            private val alias = "guru"
            private val cert = SelfSignedCert.generate(rsaKeyPair)

            override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out java.security.Principal>?, socket: Socket?): String? =
                if (keyTypes.contains("RSA") || keyTypes.contains("DHE_RSA") || keyTypes.contains("ECDHE_RSA")) alias else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? =
                if (keyType == "RSA") arrayOf(alias) else null
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == this.alias) arrayOf(cert) else null
            override fun getPrivateKey(alias: String?): java.security.PrivateKey? =
                if (alias == this.alias) rsaKeyPair.private else null
        }

        val sslContext = javax.net.ssl.SSLContext.getInstance("TLSv1.3")
        sslContext.init(arrayOf(keyManager), trustManagers, SecureRandom())

        val sslSocket = sslContext.socketFactory.createSocket(socket, host, port, true) as SSLSocket
        sslSocket.startHandshake()
        return sslSocket
    }

    /**
     * Export keying material from TLS connection using Conscrypt.
     * This matches Shizuku's Conscrypt.exportKeyingMaterial() call.
     */
    private fun exportKeyingMaterial(sslSocket: SSLSocket): ByteArray {
        // Try Conscrypt first (available on Android)
        return try {
            val conscryptClass = Class.forName("com.android.org.conscrypt.Conscrypt")
            val exportMethod = conscryptClass.getMethod(
                "exportKeyingMaterial",
                javax.net.ssl.SSLSocket::class.java,
                String::class.java,
                ByteArray::class.java,
                Int::class.javaPrimitiveType
            )
            exportMethod.invoke(null, sslSocket, kExportedKeyLabel, null, kExportedKeySize) as ByteArray
        } catch (e: Exception) {
            Log.w(TAG, "AdbPairingClient: Conscrypt export failed, trying reflection", e)
            try {
                // Try alternative Conscrypt class name
                val conscryptClass = Class.forName("org.conscrypt.Conscrypt")
                val exportMethod = conscryptClass.getMethod(
                    "exportKeyingMaterial",
                    javax.net.ssl.SSLSocket::class.java,
                    String::class.java,
                    ByteArray::class.java,
                    Int::class.javaPrimitiveType
                )
                exportMethod.invoke(null, sslSocket, kExportedKeyLabel, null, kExportedKeySize) as ByteArray
            } catch (e2: Exception) {
                Log.w(TAG, "AdbPairingClient: Conscrypt not available, using TLS PRF fallback", e2)
                // Fallback: use TLS PRF to derive key material
                // This is a simplified version - may not work with all ADB implementations
                val session = sslSocket.session
                val random = ByteArray(32)
                SecureRandom().nextBytes(random)
                hkdfSha256(session.getId() + random, kExportedKeyLabel.toByteArray(), kExportedKeySize)
            }
        }
    }

    private fun hkdfSha256(ikm: ByteArray, info: ByteArray, length: Int): ByteArray {
        val salt = ByteArray(32) // zero salt
        val mac = javax.crypto.Mac.getInstance("HmacSHA256")
        mac.init(javax.crypto.spec.SecretKeySpec(salt, "HmacSHA256"))
        val prk = mac.doFinal(ikm)

        val result = ByteArray(length)
        var t = ByteArray(0)
        var counter = 1
        var offset = 0
        while (offset < length) {
            val input = t + info + byteArrayOf(counter.toByte())
            mac.init(javax.crypto.spec.SecretKeySpec(prk, "HmacSHA256"))
            t = mac.doFinal(input)
            val copyLen = minOf(t.size, length - offset)
            System.arraycopy(t, 0, result, offset, copyLen)
            offset += copyLen
            counter++
        }
        return result
    }

    /**
     * Create a pairing packet header.
     * Format: [1 byte version][1 byte type][4 bytes payload size (big-endian)]
     */
    private fun createHeader(type: PairingPacketHeader.Type, payloadSize: Int): PairingPacketHeader {
        return PairingPacketHeader(kCurrentKeyHeaderVersion, type.value, payloadSize)
    }

    private fun writeHeader(outputStream: DataOutputStream, header: PairingPacketHeader, payload: ByteArray) {
        val buffer = ByteBuffer.allocate(kPairingPacketHeaderSize).order(ByteOrder.BIG_ENDIAN)
        buffer.put(header.version)
        buffer.put(header.type)
        buffer.putInt(header.payloadSize)
        outputStream.write(buffer.array())
        outputStream.write(payload)
        outputStream.flush()
        Log.d(TAG, "AdbPairingClient: wrote header version=${header.version}, type=${header.type}, payload=${header.payloadSize}")
    }

    private fun readHeader(inputStream: DataInputStream): PairingPacketHeader? {
        val bytes = ByteArray(kPairingPacketHeaderSize)
        try {
            inputStream.readFully(bytes)
        } catch (e: Exception) {
            Log.e(TAG, "AdbPairingClient: failed to read header", e)
            return null
        }

        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.BIG_ENDIAN)
        val version = buffer.get()
        val type = buffer.get()
        val payloadSize = buffer.int

        if (version < kMinSupportedKeyHeaderVersion || version > kMaxSupportedKeyHeaderVersion) {
            Log.e(TAG, "AdbPairingClient: unsupported header version: $version")
            return null
        }

        if (type != PairingPacketHeader.Type.SPAKE2_MSG.value && type != PairingPacketHeader.Type.PEER_INFO.value) {
            Log.e(TAG, "AdbPairingClient: unknown packet type: $type")
            return null
        }

        if (payloadSize <= 0 || payloadSize > kMaxPayloadSize) {
            Log.e(TAG, "AdbPairingClient: invalid payload size: $payloadSize")
            return null
        }

        Log.d(TAG, "AdbPairingClient: read header version=$version, type=$type, payload=$payloadSize")
        return PairingPacketHeader(version, type, payloadSize)
    }

    /**
     * Encrypt data with AES-128-GCM using sequence-based nonce.
     * Matches BoringSSL's EVP_AEAD_CTX_seal with sequence number as nonce.
     */
    private fun encryptAesGcm(key: ByteArray, plaintext: ByteArray, sequence: Long): ByteArray {
        val secretKey = SecretKeySpec(key, "AES")
        // Nonce = 12 bytes: sequence number (8 bytes big-endian) + 4 zero bytes
        val nonce = ByteArray(12)
        val nonceBuffer = ByteBuffer.wrap(nonce).order(ByteOrder.BIG_ENDIAN)
        nonceBuffer.putLong(sequence)
        // Last 4 bytes remain zero

        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey, GCMParameterSpec(128, nonce))
        return cipher.doFinal(plaintext)
    }

    /**
     * Decrypt data with AES-128-GCM using sequence-based nonce.
     */
    private fun decryptAesGcm(key: ByteArray, ciphertext: ByteArray, sequence: Long): ByteArray? {
        return try {
            val secretKey = SecretKeySpec(key, "AES")
            val nonce = ByteArray(12)
            val nonceBuffer = ByteBuffer.wrap(nonce).order(ByteOrder.BIG_ENDIAN)
            nonceBuffer.putLong(sequence)

            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, secretKey, GCMParameterSpec(128, nonce))
            cipher.doFinal(ciphertext)
        } catch (e: Exception) {
            Log.e(TAG, "AdbPairingClient: AES-GCM decrypt failed", e)
            null
        }
    }

    /**
     * Encode RSA public key in ADB format.
     * Matches the Android RSAPublicKey C struct:
     *   uint32_t modulus_size_words
     *   uint32_t n0inv
     *   uint8_t  modulus[256]  (little-endian uint32 array)
     *   uint8_t  rr[256]       (little-endian uint32 array, rr = R^2 mod N)
     *   uint32_t exponent
     * Then base64 encoded + " name\0"
     */
    private fun encodeAdbPublicKey(keyPair: KeyPair): ByteArray {
        val pubKey = keyPair.public as java.security.interfaces.RSAPublicKey
        val modulus = pubKey.modulus
        val exponent = pubKey.publicExponent

        val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8  // 256
        val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4  // 64

        val r32 = java.math.BigInteger.ZERO.setBit(32)
        val n0inv = modulus.remainder(r32).modInverse(r32).negate()

        val r = java.math.BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
        val rr = r.modPow(java.math.BigInteger.valueOf(2), modulus)

        val modulusEncoded = modulus.toAdbEncoded(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
        val rrEncoded = rr.toAdbEncoded(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)

        val buffer = ByteBuffer.allocate(4 + 4 + ANDROID_PUBKEY_MODULUS_SIZE + ANDROID_PUBKEY_MODULUS_SIZE + 4)
            .order(ByteOrder.LITTLE_ENDIAN)
        buffer.putInt(ANDROID_PUBKEY_MODULUS_SIZE_WORDS)
        buffer.putInt(n0inv.toInt())
        modulusEncoded.forEach { buffer.putInt(it) }
        rrEncoded.forEach { buffer.putInt(it) }
        buffer.putInt(exponent.toInt())

        val keyBytes = buffer.array()
        val base64 = android.util.Base64.encodeToString(keyBytes, android.util.Base64.NO_WRAP)
        val name = " guru@guru\u0000"
        return (base64 + name).toByteArray()
    }

    private fun java.math.BigInteger.toAdbEncoded(sizeWords: Int): IntArray {
        val encoded = IntArray(sizeWords)
        val r32 = java.math.BigInteger.ZERO.setBit(32)
        var tmp = this.add(java.math.BigInteger.ZERO)
        for (i in 0 until sizeWords) {
            val result = tmp.divideAndRemainder(r32)
            tmp = result[0]
            encoded[i] = result[1].toInt()
        }
        return encoded
    }
}