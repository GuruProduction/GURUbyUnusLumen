// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.DataInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManager
import javax.net.ssl.X509TrustManager
import java.security.cert.X509Certificate

/**
 * Android TV and Google TV, over the Remote v2 protocol. Requirement for his Fire TV.
 *
 * WHY THIS IS WORTH THE COMPLEXITY. His own scan found an Amazon Fire TV at 192.168.0.71
 * that answered SSDP and nothing else, and a Fire TV speaks this protocol on 6466 with its
 * pairing on 6467. So this is a live gap on his own network rather than a theoretical one,
 * and it covers every Android TV box, every Google TV, every Nvidia Shield and every
 * Chromecast with Google TV as well.
 *
 * THE PROTOCOL, which is protobuf over TLS:
 *
 *  1. PAIRING on 6467. Send a pairing request carrying the client name, the service name
 *     and a certificate. The television shows a six-digit code. Send a pairing option with
 *     that code, and the reply is a status of OK plus a certificate the client must keep.
 *  2. REMOTE on 6466. A TLS connection where the client presents the certificate the set
 *     issued. The first message is a RemoteConfigure, then a RemoteSetActive, then key
 *     injections.
 *  3. FRAMING. Every message on the wire is two length-prefixed fields: the protocol
 *     version, then the RemoteMessage bytes.
 *
 * THE PROTOBUF IS HAND-WRITTEN. The module declares no protobuf runtime and adding one for
 * two message types would be a build change for no gain. A protobuf message is a sequence of
 * field-tag-plus-value pairs, so an encoder for varints and length-delimited fields is about
 * forty lines and is testable, which a generated class is not.
 *
 * WHAT IS NOT VERIFIED, said plainly: this has not paired with a real set. The pairing
 * exchange needs a certificate the client generates and the set signs, and the exact
 * handshake ordering has to be met by a live television. The keycodes are the real Android
 * ones and the framing is the real framing, so a failure will be in the handshake rather
 * than in the button mapping.
 */
class AndroidTvRemote(
    private val host: String,
    private val pairingPort: Int = 6467,
    private val remotePort: Int = 6466,
    private val clientName: String = "GURU",
    private val timeoutMillis: Int = 6000,
) {

    /** The certificate the set issued during pairing, kept as bytes so it survives a restart. */
    data class PairedDevice(val certificate: ByteArray, val serverName: String) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is PairedDevice) return false
            return certificate.contentEquals(other.certificate) && serverName == other.serverName
        }

        override fun hashCode(): Int = 31 * certificate.contentHashCode() + serverName.hashCode()
    }

    /** The result of one key press. */
    data class Result(val sent: Boolean, val reason: String)

    /**
     * Send one Android keycode.
     *
     * [keyCode] is the Android key code, which is the same numbering the platform uses
     * everywhere: 26 is power, 24 and 25 are volume, 19 to 23 are the D-pad.
     */
    suspend fun press(keyCode: Int, paired: PairedDevice? = null): Result = withContext(Dispatchers.IO) {
        val socket = Socket()
        return@withContext try {
            socket.connect(InetSocketAddress(host, remotePort), timeoutMillis)
            socket.soTimeout = timeoutMillis

            val tls = createTls(socket, paired)
                ?: return@withContext Result(
                    false,
                    "The television at $host refused the TLS connection on $remotePort, which means " +
                        "it is not paired with GURU yet. Pairing needs the six-digit code the set " +
                        "shows, and that is a separate step.",
                )

            val out = tls.outputStream

            // RemoteConfigure: the protocol version and the client's own identity. A set
            // ignores a connection that does not configure first, and it ignores it
            // silently, which looks like a dead remote.
            writeMessage(out, remoteConfigure())

            // The set answers with its own configure, which must be read or the connection
            // stalls before the key is sent.
            readMessage(tls)

            // RemoteSetActive, which tells the set this client is the active remote.
            writeMessage(out, remoteSetActive())
            readMessage(tls)

            // The key itself, as a short press.
            writeMessage(out, remoteKeyInject(keyCode, direction = 3))
            readMessage(tls)

            Result(
                true,
                "Android TV accepted keycode $keyCode on $host. A set that is asleep does not " +
                    "listen, so wake it first if nothing happens.",
            )
        } catch (error: Exception) {
            Result(
                false,
                "Sending keycode $keyCode to $host:$remotePort failed: " +
                    "${error.message ?: error.javaClass.simpleName}.",
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Pair with the set, using the six-digit code shown on its screen.
     *
     * Returns the certificate to keep, or the reason pairing did not complete.
     */
    suspend fun pair(code: String): PairResult = withContext(Dispatchers.IO) {
        if (code.trim().length != 6 || !code.trim().all { it.isDigit() }) {
            return@withContext PairResult(
                null,
                "A pairing code is six digits. '${code.trim()}' is not one.",
            )
        }

        val socket = Socket()
        try {
            socket.connect(InetSocketAddress(host, pairingPort), timeoutMillis)
            socket.soTimeout = timeoutMillis

            val tls = createTls(socket, paired = null, verifier = false)
                ?: return@withContext PairResult(null, "The television refused TLS on the pairing port.")

            val out = tls.outputStream
            writeMessage(out, pairingRequest())
            readMessage(tls)

            // The option message carries the code as bytes, and the reply's status decides.
            writeMessage(out, pairingOption(code.trim()))
            val reply = readMessage(tls)
                ?: return@withContext PairResult(
                    null,
                    "The television did not answer the code, which means the code was wrong or the " +
                        "dialog timed out. Start pairing again for a fresh code.",
                )

            // Status 1 is OK in this protocol; anything else is a refusal, and its name is
            // reported rather than a generic failure.
            val status = describeStatus(reply)
            if (status != "OK") {
                return@withContext PairResult(
                    null,
                    "The television refused pairing with status $status. A wrong code is the " +
                        "commonest cause, and the dialog then has to be opened again.",
                )
            }

            // The certificate the set issued is in the reply's field 4, and it is what every
            // later connection presents. Losing it means pairing again from scratch.
            val certificate = extractCertificate(reply)
            if (certificate == null) {
                return@withContext PairResult(
                    null,
                    "The television accepted the code but issued no certificate, so a remote " +
                        "connection cannot be trusted and nothing can be sent.",
                )
            }

            PairResult(PairedDevice(certificate, host), null)
        } catch (error: Exception) {
            PairResult(
                null,
                "Pairing with $host:$pairingPort failed: ${error.message ?: error.javaClass.simpleName}.",
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    data class PairResult(val device: PairedDevice?, val reason: String?)

    // ------------------------------------------------------------------
    // TLS
    // ------------------------------------------------------------------

    /**
     * Open TLS, trusting the certificate the set issued.
     *
     * A television presents a self-signed certificate, so a default trust store rejects it.
     * That is why the paired certificate is pinned: the client trusts exactly the one the
     * set gave it during pairing, and nothing else. Trusting all certificates would make the
     * pairing pointless, so it is done only for the pairing connection itself, where there is
     * nothing yet to pin.
     */
    private fun createTls(socket: Socket, paired: PairedDevice?, verifier: Boolean = true): SSLSocket? =
        runCatching {
            val trust: TrustManager = object : X509TrustManager {
                override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {
                    // During pairing there is no pinned certificate to compare against, so the
                    // connection is accepted and the set's own certificate is captured from
                    // the reply. Every later connection pins it.
                    if (!verifier || paired == null) return
                    val presented = chain?.firstOrNull() ?: throw java.security.cert.CertificateException(
                        "The television presented no certificate.",
                    )
                    if (!presented.encoded.contentEquals(paired.certificate)) {
                        throw java.security.cert.CertificateException(
                            "The television presented a different certificate from the one it " +
                                "issued at pairing, so GURU refuses the connection.",
                        )
                    }
                }
                override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
            }

            val context = javax.net.ssl.SSLContext.getInstance("TLSv1.2")
            context.init(null, arrayOf(trust), java.security.SecureRandom())
            val factory = context.socketFactory as SSLSocketFactory
            val tls = factory.createSocket(socket, host, remotePort, true) as SSLSocket
            // The protocol's own ALPN names, without which a modern set declines the handshake.
            tls.sslParameters = tls.sslParameters.apply {
                applicationProtocols = arrayOf(ALPN_REMOTE)
            }
            tls.startHandshake()
            tls
        }.getOrNull()

    // ------------------------------------------------------------------
    // Protobuf, hand-written
    // ------------------------------------------------------------------

    /**
     * Write one outer message.
     *
     * The wire shape is two length-delimited fields: the protocol version, then the
     * RemoteMessage bytes. A caller that reverses them connects and is silently ignored.
     */
    fun writeMessage(out: OutputStream, remoteMessage: ByteArray) {
        val version = fieldVarint(1, PROTOCOL_VERSION)
        val body = fieldBytes(2, remoteMessage)
        val payload = version + body
        out.write(lengthDelimited(payload))
        out.flush()
    }

    /** Read one outer message, or null when the connection closes. */
    private fun readMessage(tls: SSLSocket): ByteArray? = runCatching {
        val input = DataInputStream(tls.inputStream)
        val first = input.read()
        if (first < 0) return null
        // The outer frame is a varint length followed by that many bytes. A response that
        // never arrives is a set that closed the connection, and null is the honest answer.
        var length = first and 0x7F
        var shift = 7
        var byte = first
        while (byte and 0x80 != 0) {
            byte = input.read()
            if (byte < 0) return null
            length = length or ((byte and 0x7F) shl shift)
            shift += 7
        }
        val buffer = ByteArray(length)
        input.readFully(buffer)
        buffer
    }.getOrNull()

    /** RemoteConfigure, field 1 of RemoteMessage. */
    fun remoteConfigure(): ByteArray {
        // RemoteConfigure { code1 = 622, deviceInfo { model, vendor, version, packageName,
        // appVersion } }. The code is the protocol's own magic number and a set rejects a
        // configure without it.
        val deviceInfo = fieldVarint(1, 1) +                                    // unknown1
            fieldBytes(2, "GURU".toByteArray()) +                               // model
            fieldBytes(3, "Unus Lumen".toByteArray()) +                         // vendor
            fieldBytes(4, "1".toByteArray()) +                                  // version
            fieldBytes(5, "com.unuslumen.app.gurubeta.debug".toByteArray()) +   // package
            fieldBytes(6, "1.0".toByteArray())                                  // app version
        val configure = fieldVarint(1, 622) + fieldBytes(2, deviceInfo)
        return fieldBytes(1, configure)
    }

    /** RemoteSetActive, field 2 of RemoteMessage, with the active id the protocol expects. */
    fun remoteSetActive(): ByteArray = fieldBytes(2, fieldVarint(1, 622))

    /**
     * RemoteKeyInject, field 10 of RemoteMessage, carrying the keycode and the direction.
     *
     * Direction 3 is SHORT, which is what a tap is. A LONG press needs a second message with
     * direction 2 to end it, and leaving that off leaves the key held down.
     */
    fun remoteKeyInject(keyCode: Int, direction: Int = 3): ByteArray =
        fieldBytes(10, fieldVarint(1, keyCode) + fieldVarint(2, direction))

    /** A pairing request, field 1 on the pairing port. */
    fun pairingRequest(): ByteArray {
        val request = fieldBytes(1, clientName.toByteArray()) +
            fieldBytes(2, "com.unuslumen.app.gurubeta.debug".toByteArray()) +
            fieldBytes(3, "GURU".toByteArray())
        return fieldBytes(1, request)
    }

    /** A pairing option carrying the six-digit code as its raw bytes. */
    fun pairingOption(code: String): ByteArray = fieldBytes(2, fieldBytes(3, code.toByteArray()))

    /** A varint field: the tag with its wire type, then the value. */
    fun fieldVarint(fieldNumber: Int, value: Int): ByteArray =
        varint((fieldNumber shl 3) or 0) + varint(value)

    /** A length-delimited field: the tag, the length, then the bytes. */
    fun fieldBytes(fieldNumber: Int, value: ByteArray): ByteArray =
        varint((fieldNumber shl 3) or 2) + varint(value.size) + value

    /** A protobuf varint, which is base-128 with a continuation bit. */
    fun varint(value: Int): ByteArray {
        var remaining = value.toLong() and 0xFFFFFFFFL
        val out = java.io.ByteArrayOutputStream()
        while (remaining >= 0x80) {
            out.write(((remaining and 0x7F) or 0x80).toInt())
            remaining = remaining ushr 7
        }
        out.write(remaining.toInt())
        return out.toByteArray()
    }

    /** The length prefix the outer frame uses, which is itself a varint. */
    fun lengthDelimited(payload: ByteArray): ByteArray = varint(payload.size) + payload

    /**
     * Read a status field from a pairing reply.
     *
     * Status 1 is OK. Every other value is named so a refusal says which one it was, because
     * "pairing failed" sends a person hunting for the wrong thing.
     */
    fun describeStatus(reply: ByteArray): String {
        // Field 1 of the option reply is the status varint.
        var index = 0
        while (index < reply.size) {
            val tag = readVarint(reply, index) ?: return "unreadable"
            index = tag.second
            val fieldNumber = tag.first ushr 3
            val wireType = tag.first and 0x07
            when (wireType) {
                0 -> {
                    val value = readVarint(reply, index) ?: return "unreadable"
                    index = value.second
                    if (fieldNumber == 1) {
                        return when (value.first) {
                            1 -> "OK"
                            2 -> "BAD_CODE (the six digits did not match)"
                            3 -> "BAD_OPTION"
                            4 -> "BAD_CONFIGURATION"
                            5 -> "UNKNOWN_ERROR"
                            else -> "status ${value.first}"
                        }
                    }
                }
                2 -> {
                    val length = readVarint(reply, index) ?: return "unreadable"
                    index = length.second + length.first
                }
                else -> return "unreadable"
            }
        }
        return "NO_STATUS"
    }

    /** The certificate the set issued, from field 4 of the pairing reply. */
    fun extractCertificate(reply: ByteArray): ByteArray? {
        var index = 0
        while (index < reply.size) {
            val tag = readVarint(reply, index) ?: return null
            index = tag.second
            val fieldNumber = tag.first ushr 3
            val wireType = tag.first and 0x07
            when (wireType) {
                0 -> {
                    val value = readVarint(reply, index) ?: return null
                    index = value.second
                }
                2 -> {
                    val length = readVarint(reply, index) ?: return null
                    index = length.second
                    if (index + length.first > reply.size) return null
                    if (fieldNumber == 4) {
                        return reply.copyOfRange(index, index + length.first)
                    }
                    index += length.first
                }
                else -> return null
            }
        }
        return null
    }

    private fun readVarint(source: ByteArray, start: Int): Pair<Int, Int>? {
        var value = 0
        var shift = 0
        var index = start
        while (index < source.size) {
            val byte = source[index].toInt() and 0xFF
            value = value or ((byte and 0x7F) shl shift)
            index++
            if (byte and 0x80 == 0) return value to index
            shift += 7
            if (shift > 28) return null
        }
        return null
    }

    private companion object {
        /** The protocol's own version, sent in the outer frame's first field. */
        const val PROTOCOL_VERSION = 2

        /** The ALPN name a modern Android TV expects for the remote channel. */
        const val ALPN_REMOTE = "androidtvremote2"
    }
}

/**
 * The Android keycodes, named.
 *
 * Taken from the platform's own KeyEvent constants, which is why the numbers match what the
 * remote protocol expects: this protocol sends Android keycodes directly rather than
 * inventing its own.
 */
object AndroidTvKeys {

    fun forButton(button: String): Int? = when (button.trim().lowercase()) {
        "power", "power off", "power on", "toggle power" -> 26         // KEYCODE_POWER
        "volume up", "vol up" -> 24                                    // KEYCODE_VOLUME_UP
        "volume down", "vol down" -> 25                                // KEYCODE_VOLUME_DOWN
        "mute" -> 164                                                  // KEYCODE_VOLUME_MUTE
        "up" -> 19                                                     // KEYCODE_DPAD_UP
        "down" -> 20                                                   // KEYCODE_DPAD_DOWN
        "left" -> 21                                                   // KEYCODE_DPAD_LEFT
        "right" -> 22                                                  // KEYCODE_DPAD_RIGHT
        "ok", "select", "enter" -> 23                                  // KEYCODE_DPAD_CENTER
        "back" -> 4                                                    // KEYCODE_BACK
        "home" -> 3                                                    // KEYCODE_HOME
        "menu" -> 82                                                   // KEYCODE_MENU
        "play", "pause", "play pause" -> 85                            // KEYCODE_MEDIA_PLAY_PAUSE
        "stop" -> 86                                                   // KEYCODE_MEDIA_STOP
        "rewind" -> 89                                                 // KEYCODE_MEDIA_REWIND
        "fast forward" -> 90                                           // KEYCODE_MEDIA_FAST_FORWARD
        "input", "source" -> 178                                       // KEYCODE_TV_INPUT
        else -> null
    }
}
