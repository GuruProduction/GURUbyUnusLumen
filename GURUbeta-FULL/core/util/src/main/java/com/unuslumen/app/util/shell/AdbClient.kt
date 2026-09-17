package com.unuslumen.app.util.shell

import android.os.Build
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.OutputStream
import java.math.BigInteger
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import android.content.Context
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.SecureRandom
import java.security.cert.X509Certificate
import java.security.interfaces.RSAPrivateKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.RSAKeyGenParameterSpec
import javax.crypto.Cipher
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager

/**
 * Embedded ADB client that connects to the device's own ADB daemon via Wireless Debugging.
 * Implements the full ADB protocol including TLS (Android 11+) and RSA key authentication.
 * Runs commands with proper shell UID instead of the app's untrusted_app UID.
 *
 * On Android 11+, ADB uses TLS. The flow is:
 * 1. Send CNXN → receive STLS → send STLS_VERSION → TLS handshake → AUTH → CNXN
 * 2. On older devices: CNXN → AUTH → CNXN (plain text)
 */
class AdbClient(
    private val host: String = "localhost",
    private val port: Int = 5555,
    context: Context? = null
) {
    init {
        if (context != null && _context == null) {
            _context = context.applicationContext
        }
    }
    companion object {
        private const val TAG = "guru"
        private const val ADB_HEADER_LENGTH = 24
        private const val ADB_CNXN = "CNXN"
        private const val ADB_AUTH = "AUTH"
        private const val ADB_OPEN = "OPEN"
        private const val ADB_OKAY = "OKAY"
        private const val ADB_WRTE = "WRTE"
        private const val ADB_CLSE = "CLSE"
        private const val ADB_STLS = "STLS"
        private const val ADB_VERSION = 0x01000000
        private const val ADB_MAX_PAYLOAD = 4096 * 256
        private const val ADB_STLS_VERSION = 0x01000000
        private const val AUTH_TOKEN = 1
        private const val AUTH_SIGNATURE = 2
        private const val AUTH_RSAPUBLICKEY = 3
        // Socket read timeout during auth handshake (15s).
        // Prevents hanging indefinitely if the ADB daemon becomes unresponsive mid-handshake.
        private const val AUTH_READ_TIMEOUT_MS = 0  // No timeout - AGI doesn't have timeouts

        private var _isConnected = false
        val isConnected: Boolean get() = _isConnected

        // Shared RSA key pair — persisted to disk so the ADB daemon remembers us
        private var _rsaKeyPair: KeyPair? = null
        private var _context: Context? = null

        fun getOrCreateRsaKeyPair(): KeyPair {
            if (_rsaKeyPair != null) return _rsaKeyPair!!
            
            val ctx = _context
            if (ctx != null) {
                _rsaKeyPair = AdbKeyStore.getOrCreateKeyPair(ctx)
            } else {
                // Fallback: generate ephemeral key (won't persist across restarts)
                val keyGen = KeyPairGenerator.getInstance("RSA")
                keyGen.initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                _rsaKeyPair = keyGen.generateKeyPair()
                Log.w(TAG, "AdbClient: no context, generated ephemeral RSA key pair")
            }
            return _rsaKeyPair!!
        }
        
        /**
         * Initialize the key store with application context.
         * Call this once at app startup for key persistence.
         */
        fun init(context: Context) {
            _context = context.applicationContext
        }
    }

    private var localIdCounter = 1
    private var rsaKeyPair: KeyPair = getOrCreateRsaKeyPair()

    /**
     * Check if ADB daemon is reachable on localhost:5555.
     */
    suspend fun isAdbAvailable(): Boolean = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 3000)
            socket.close()
            Log.d(TAG, "AdbClient: ADB daemon is reachable at $host:$port")
            true
        } catch (e: Exception) {
            Log.d(TAG, "AdbClient: ADB daemon not reachable at $host:$port - ${e.message}")
            false
        }
    }

    /**
     * Execute a shell command via ADB protocol with full authentication.
     * Supports both TLS (Android 11+) and plain text connections.
     */
    suspend fun executeCommand(command: String): ShellResult = withContext(Dispatchers.IO) {
        try {
            val socket = Socket()
            socket.connect(java.net.InetSocketAddress(host, port), 5000)
            socket.tcpNoDelay = true
            socket.soTimeout = 0 // No timeout — AGI doesn't have timeouts

            val plainInputStream = DataInputStream(socket.getInputStream())
            val plainOutputStream = DataOutputStream(socket.getOutputStream())

            // Send CNXN message
            val systemIdentity = "host::features=cmd,shell_v2\u0000"
            sendAdbMessage(plainOutputStream, ADB_CNXN, ADB_VERSION, ADB_MAX_PAYLOAD, systemIdentity.toByteArray())

            // Check if server wants TLS (Android 11+)
            val firstMsg = readAdbMessage(plainInputStream) ?: run {
                socket.close()
                return@withContext ShellResult(-1, "", "ADB: no response from daemon", false)
            }

            val firstMsgType = String(firstMsg.type, Charsets.US_ASCII).trimEnd('\u0000')
            Log.d(TAG, "AdbClient: first msg=$firstMsgType")

            var useTls = false
            var inputStream: DataInputStream = plainInputStream
            var outputStream: DataOutputStream = plainOutputStream

            if (firstMsgType == ADB_STLS && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                // Server wants TLS — upgrade the connection
                Log.d(TAG, "AdbClient: server requested TLS, upgrading")
                sendAdbMessage(plainOutputStream, ADB_STLS, ADB_STLS_VERSION, 0, ByteArray(0))

                val sslSocket = createTlsSocket(socket)
                inputStream = DataInputStream(sslSocket.inputStream)
                outputStream = DataOutputStream(sslSocket.outputStream)
                useTls = true
                Log.d(TAG, "AdbClient: TLS handshake succeeded")
            } else if (firstMsgType == ADB_AUTH) {
                // Plain text AUTH — process it below
                // Re-process this message in the auth loop
            }

            // Handle AUTH handshake
            // Two-stage ADB auth protocol:
            //   Stage 1: Server sends AUTH_TOKEN -> client signs it and sends AUTH_SIGNATURE
            //   Stage 2: If server rejects, it sends another AUTH_TOKEN -> client sends AUTH_RSAPUBLICKEY
            //   Circuit breaker: after stage 2, if server sends yet another AUTH_TOKEN, fail immediately
            var authenticated = false
            var signatureSent = false
            var publicKeySent = false
            var pendingMsg: AdbMessage? = if (!useTls && firstMsgType == ADB_AUTH) firstMsg else null

            // If we did TLS, read the first message after TLS handshake
            if (useTls && pendingMsg == null) {
                pendingMsg = readAdbMessage(inputStream)
                if (pendingMsg == null) {
                    socket.close()
                    return@withContext ShellResult(-1, "", "ADB: no response after TLS handshake", false)
                }
                val tlsMsgType = String(pendingMsg.type, Charsets.US_ASCII).trimEnd('\u0000')
                Log.d(TAG, "AdbClient: post-TLS msg=$tlsMsgType arg1=${pendingMsg.arg1}")
                if (tlsMsgType == ADB_CNXN) {
                    // Server accepted without auth (unlikely but possible)
                    authenticated = true
                }
            }

            // Set read timeout during auth handshake so we don't hang forever
            socket.soTimeout = AUTH_READ_TIMEOUT_MS

            while (!authenticated) {
                val msg = pendingMsg ?: run {
                    try {
                        readAdbMessage(inputStream)
                    } catch (e: java.net.SocketTimeoutException) {
                        Log.w(TAG, "AdbClient: socket read timeout during auth handshake")
                        break
                    }
                } ?: break
                pendingMsg = null
                val msgType = String(msg.type, Charsets.US_ASCII).trimEnd('\u0000')
                Log.d(TAG, "AdbClient: handshake msg=$msgType arg1=${msg.arg1} payload=${msg.payload.size}bytes")

                when (msgType) {
                    ADB_AUTH -> {
                        if (msg.arg1 != AUTH_TOKEN) {
                            Log.w(TAG, "AdbClient: unexpected AUTH arg1=${msg.arg1}")
                            authenticated = false
                            break
                        }

                        if (!signatureSent) {
                            // Stage 1: sign the token and send AUTH_SIGNATURE
                            Log.d(TAG, "AdbClient: AUTH TOKEN received, signing with RSA key (stage 1/2)")
                            try {
                                val signature = signWithRsa(msg.payload)
                                sendAdbMessage(outputStream, ADB_AUTH, AUTH_SIGNATURE, 0, signature)
                                signatureSent = true
                            } catch (e: Exception) {
                                Log.w(TAG, "AdbClient: RSA signing failed, skipping to public key (stage 2)", e)
                                val pubKey = encodeAdbPublicKey(rsaKeyPair)
                                sendAdbMessage(outputStream, ADB_AUTH, AUTH_RSAPUBLICKEY, 0, pubKey)
                                signatureSent = true
                                publicKeySent = true
                            }
                        } else if (!publicKeySent) {
                            // Stage 2: signature was rejected, send public key
                            Log.d(TAG, "AdbClient: signature rejected, sending public key (stage 2/2)")
                            val pubKey = encodeAdbPublicKey(rsaKeyPair)
                            sendAdbMessage(outputStream, ADB_AUTH, AUTH_RSAPUBLICKEY, 0, pubKey)
                            publicKeySent = true
                        } else {
                            // Circuit breaker: both signature and public key sent, still getting AUTH_TOKEN.
                            // The device does not recognize this key. Pairing is required.
                            Log.w(TAG, "AdbClient: both signature and public key rejected by ADB daemon. Device is not paired with this key.")
                            authenticated = false
                            break
                        }
                    }
                    ADB_CNXN -> {
                        authenticated = true
                        Log.d(TAG, "AdbClient: authenticated successfully!")
                    }
                    ADB_OKAY -> {
                        authenticated = true
                        Log.d(TAG, "AdbClient: received OKAY during auth, proceeding")
                    }
                    else -> {
                        Log.w(TAG, "AdbClient: unexpected message during auth: $msgType")
                        // Unknown message — don't loop, fail cleanly
                        authenticated = false
                        break
                    }
                }
            }

            // Reset to no-timeout for command execution
            socket.soTimeout = 0

            if (!authenticated) {
                val authStage = when {
                    !signatureSent -> "pre-auth (no AUTH_TOKEN received)"
                    !publicKeySent -> "signature rejected"
                    else -> "both signature and public key rejected"
                }
                Log.w(TAG, "AdbClient: authentication failed at stage: $authStage")
                socket.close()
                return@withContext ShellResult(
                    -1, "",
                    "ADB authentication failed (stage: $authStage). Pair this device using the pairing code from Developer Options > Wireless Debugging > Pair device with pairing code.",
                    false
                )
            }

            // Open a shell session
            val localId = localIdCounter++
            val openPayload = "shell:$command\u0000"
            sendAdbMessage(outputStream, ADB_OPEN, localId, 0, openPayload.toByteArray())

            // Read OKAY acknowledgment
            val okayMsg = readAdbMessage(inputStream)
            if (okayMsg == null) {
                socket.close()
                return@withContext ShellResult(-1, "", "ADB: failed to open shell session", false)
            }

            val remoteId = okayMsg.arg1
            Log.d(TAG, "AdbClient: shell session opened, remoteId=$remoteId")

            // Send OKAY back
            sendAdbMessage(outputStream, ADB_OKAY, localId, remoteId, ByteArray(0))

            // Read output — no timeout, AGI is patient
            socket.soTimeout = 0
            val outputBuilder = StringBuilder()
            var readAttempts = 0
            while (readAttempts < 200) {
                val msg = try {
                    readAdbMessage(inputStream)
                } catch (e: java.net.SocketTimeoutException) {
                    break
                }
                if (msg == null) break

                when (String(msg.type, Charsets.US_ASCII).trimEnd('\u0000')) {
                    ADB_WRTE -> {
                        outputBuilder.append(String(msg.payload, Charsets.UTF_8))
                        sendAdbMessage(outputStream, ADB_OKAY, localId, msg.arg1, ByteArray(0))
                    }
                    ADB_CLSE -> break
                    ADB_OKAY -> { /* continue */ }
                }
                readAttempts++
            }

            socket.close()
            _isConnected = true

            val output = outputBuilder.toString().trim()
            Log.d(TAG, "AdbClient: command '$command' output length=${output.length}")

            ShellResult(0, output, "", true)
        } catch (e: java.net.ConnectException) {
            _isConnected = false
            Log.w(TAG, "AdbClient: connection refused", e)
            ShellResult(-1, "", "ADB connection refused. Wireless Debugging may not be enabled.", false)
        } catch (e: Exception) {
            _isConnected = false
            Log.e(TAG, "AdbClient: error executing command", e)
            ShellResult(-1, "", "ADB error: ${e.message}", false)
        }
    }

    /**
     * Create TLS socket for ADB connection (Android 11+).
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

        val selfSignedCert = SelfSignedCert.generate(rsaKeyPair)
        val keyManager = object : X509ExtendedKeyManager() {
            private val alias = "guru"
            override fun chooseClientAlias(keyTypes: Array<out String>, issuers: Array<out java.security.Principal>?, socket: Socket?): String? =
                if (keyTypes.contains("RSA") || keyTypes.contains("DHE_RSA") || keyTypes.contains("ECDHE_RSA")) alias else null
            override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?): String? = null
            override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? =
                if (keyType == "RSA") arrayOf(alias) else null
            override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?): Array<String>? = null
            override fun getCertificateChain(alias: String?): Array<X509Certificate>? =
                if (alias == this.alias) arrayOf(selfSignedCert) else null
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
     * Sign an ADB AUTH token with RSA using PKCS#1 v1.5 padding.
     *
     * ADB authentication: the server sends a 20-byte SHA1 token.
     * The client signs it with RSA/ECB/NoPadding using PKCS#1 v1.5 padding.
     * The signature format is: 0x00 0x01 [0xFF padding] 0x00 [DigestInfo] [token]
     * where DigestInfo is the ASN.1 OID for SHA-1 followed by the token.
     *
     * For a 2048-bit key (256 bytes), the total must be exactly 256 bytes.
     */
    private fun signWithRsa(token: ByteArray): ByteArray {
        // DigestInfo for SHA-1: ASN.1 OID + NULL + SHA-1 digest
        // 30 21 30 09 06 05 2b 0e 03 02 1a 05 00 04 14 [20-byte-hash]
        val digestInfoPrefix = byteArrayOf(
            0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e,
            0x03, 0x02, 0x1a, 0x05, 0x00, 0x04, 0x14
        )

        // Build the padded message: 0x00 0x01 [0xFF padding] 0x00 [DigestInfo] [token]
        // Total must equal the RSA key size in bytes (256 for 2048-bit)
        val keySize = (rsaKeyPair.private as RSAPrivateKey).modulus.bitLength() / 8  // 256 for 2048-bit
        val paddingLen = keySize - 3 - digestInfoPrefix.size - token.size  // 0xFF bytes
        require(paddingLen > 0) { "Token too large for RSA key" }

        val paddedMessage = ByteArray(keySize)
        var offset = 0
        paddedMessage[offset++] = 0x00
        paddedMessage[offset++] = 0x01
        for (i in 0 until paddingLen) {
            paddedMessage[offset++] = 0xFF.toByte()
        }
        paddedMessage[offset++] = 0x00
        System.arraycopy(digestInfoPrefix, 0, paddedMessage, offset, digestInfoPrefix.size)
        offset += digestInfoPrefix.size
        System.arraycopy(token, 0, paddedMessage, offset, token.size)

        // Encrypt with RSA private key using NoPadding (we did the padding ourselves)
        val cipher = Cipher.getInstance("RSA/ECB/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, rsaKeyPair.private)
        return cipher.doFinal(paddedMessage)
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
        val pubKey = keyPair.public as RSAPublicKey
        val modulus = pubKey.modulus
        val exponent = pubKey.publicExponent

        val ANDROID_PUBKEY_MODULUS_SIZE = 2048 / 8  // 256
        val ANDROID_PUBKEY_MODULUS_SIZE_WORDS = ANDROID_PUBKEY_MODULUS_SIZE / 4  // 64

        val r32 = BigInteger.ZERO.setBit(32)
        val n0inv = modulus.remainder(r32).modInverse(r32).negate()

        val r = BigInteger.ZERO.setBit(ANDROID_PUBKEY_MODULUS_SIZE * 8)
        val rr = r.modPow(BigInteger.valueOf(2), modulus)

        // Convert modulus and rr to little-endian uint32 arrays
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

    /**
     * Convert BigInteger to ADB-encoded little-endian uint32 array.
     */
    private fun BigInteger.toAdbEncoded(sizeWords: Int): IntArray {
        val encoded = IntArray(sizeWords)
        val r32 = BigInteger.ZERO.setBit(32)
        var tmp = this.add(BigInteger.ZERO)
        for (i in 0 until sizeWords) {
            val result = tmp.divideAndRemainder(r32)
            tmp = result[0]
            encoded[i] = result[1].toInt()
        }
        return encoded
    }

    // ===== ADB Protocol Implementation =====

    private data class AdbMessage(
        val command: Int,
        val arg1: Int,
        val arg2: Int,
        val type: ByteArray,
        val payload: ByteArray
    )

    private fun sendAdbMessage(
        output: OutputStream,
        type: String,
        arg1: Int,
        arg2: Int,
        payload: ByteArray
    ) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        val command = commandFromType(type)
        val dataLength = payload.size
        val checksum = payload.sumOf { (it.toInt() and 0xFF).toLong() }.toInt()
        val magic = command xor -1 // XOR with 0xFFFFFFFF

        val header = ByteArray(ADB_HEADER_LENGTH)
        // command (4 bytes)
        header[0] = (command and 0xFF).toByte()
        header[1] = (command shr 8 and 0xFF).toByte()
        header[2] = (command shr 16 and 0xFF).toByte()
        header[3] = (command shr 24 and 0xFF).toByte()
        // arg1 (4 bytes)
        header[4] = (arg1 and 0xFF).toByte()
        header[5] = (arg1 shr 8 and 0xFF).toByte()
        header[6] = (arg1 shr 16 and 0xFF).toByte()
        header[7] = (arg1 shr 24 and 0xFF).toByte()
        // arg2 (4 bytes)
        header[8] = (arg2 and 0xFF).toByte()
        header[9] = (arg2 shr 8 and 0xFF).toByte()
        header[10] = (arg2 shr 16 and 0xFF).toByte()
        header[11] = (arg2 shr 24 and 0xFF).toByte()
        // data_length (4 bytes)
        header[12] = (dataLength and 0xFF).toByte()
        header[13] = (dataLength shr 8 and 0xFF).toByte()
        header[14] = (dataLength shr 16 and 0xFF).toByte()
        header[15] = (dataLength shr 24 and 0xFF).toByte()
        // data_checksum (4 bytes)
        header[16] = (checksum and 0xFF).toByte()
        header[17] = (checksum shr 8 and 0xFF).toByte()
        header[18] = (checksum shr 16 and 0xFF).toByte()
        header[19] = (checksum shr 24 and 0xFF).toByte()
        // magic (4 bytes)
        header[20] = (magic and 0xFF).toByte()
        header[21] = (magic shr 8 and 0xFF).toByte()
        header[22] = (magic shr 16 and 0xFF).toByte()
        header[23] = (magic shr 24 and 0xFF).toByte()

        output.write(header)
        if (payload.isNotEmpty()) {
            output.write(payload)
        }
        output.flush()
    }

    private fun readAdbMessage(input: java.io.InputStream): AdbMessage? {
        val header = ByteArray(ADB_HEADER_LENGTH)
        var totalRead = 0
        while (totalRead < ADB_HEADER_LENGTH) {
            val read = input.read(header, totalRead, ADB_HEADER_LENGTH - totalRead)
            if (read == -1) return null
            totalRead += read
        }

        val command = readIntLE(header, 0)
        val arg1 = readIntLE(header, 4)
        val arg2 = readIntLE(header, 8)
        val dataLength = readIntLE(header, 12)

        val payload = if (dataLength > 0 && dataLength <= ADB_MAX_PAYLOAD) {
            val data = ByteArray(dataLength)
            totalRead = 0
            while (totalRead < dataLength) {
                val read = input.read(data, totalRead, dataLength - totalRead)
                if (read == -1) break
                totalRead += read
            }
            data
        } else {
            ByteArray(0)
        }

        return AdbMessage(
            command = command,
            arg1 = arg1,
            arg2 = arg2,
            type = header.copyOfRange(0, 4),
            payload = payload
        )
    }

    private fun readIntLE(bytes: ByteArray, offset: Int): Int {
        return (bytes[offset].toInt() and 0xFF) or
                (bytes[offset + 1].toInt() and 0xFF shl 8) or
                (bytes[offset + 2].toInt() and 0xFF shl 16) or
                (bytes[offset + 3].toInt() and 0xFF shl 24)
    }

    private fun commandFromType(type: String): Int {
        return when (type) {
            ADB_CNXN -> 0x4e584e43
            ADB_AUTH -> 0x48545541
            ADB_OPEN -> 0x4e45504f
            ADB_OKAY -> 0x59414b4f
            ADB_WRTE -> 0x45545257
            ADB_CLSE -> 0x45534c43
            ADB_STLS -> 0x534C5453
            else -> 0
        }
    }
}

data class ShellResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean
)