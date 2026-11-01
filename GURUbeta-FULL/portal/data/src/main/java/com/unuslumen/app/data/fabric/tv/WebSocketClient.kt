// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

/**
 * A minimal WebSocket client, because two of the television vendors speak nothing
 * else and this module declares no WebSocket library.
 *
 * WHY HAND-WRITTEN RATHER THAN A DEPENDENCY. The module's dependency set is fixed and
 * adding a WebSocket library for two protocols would be a build change for a feature
 * that needs exactly one thing: send a text frame, read a text frame, close. RFC 6455
 * is small enough that the client half is about a hundred lines, and writing it here
 * means the handshake and the masking are visible and testable rather than hidden
 * behind somebody else's API.
 *
 * WHAT IT DOES NOT DO: no permessage-deflate, no binary frames, no fragmentation, no
 * server role. A television's remote channel uses none of those, and pretending to
 * support them with untested code would be worse than refusing.
 */
class WebSocketClient private constructor(
    private val socket: Socket,
    private val input: InputStream,
    private val output: OutputStream,
) {

    companion object {
        /**
         * Open a connection to `ws://host:port/path`.
         *
         * Returns null with the reason when the handshake fails, because a television
         * that is asleep, on a different network, or simply not a WebSocket endpoint
         * all produce the same symptom and the caller needs to tell them apart.
         */
        fun connect(
            host: String,
            port: Int,
            path: String = "/",
            timeoutMillis: Int = 5000,
        ): ConnectResult {
            // The path may carry a query string, which Samsung uses for its token. A
            // caller that passed a full URL path is not made to strip it, because losing
            // the token is losing the pairing.
            val requestPath = if (path.startsWith("/")) path else "/$path"
            val socket = Socket()
            return try {
                socket.connect(InetSocketAddress(host, port), timeoutMillis)
                socket.soTimeout = timeoutMillis

                // The key is 16 random bytes in base64 and exists only so the server can
                // prove it understood the protocol by answering with the right digest.
                val keyBytes = ByteArray(16).also { SecureRandom().nextBytes(it) }
                val key = Base64.getEncoder().encodeToString(keyBytes)

                val handshake = buildString {
                    append("GET $requestPath HTTP/1.1\r\n")
                    append("Host: $host:$port\r\n")
                    append("Upgrade: websocket\r\n")
                    append("Connection: Upgrade\r\n")
                    append("Sec-WebSocket-Key: $key\r\n")
                    append("Sec-WebSocket-Version: 13\r\n")
                    append("\r\n")
                }
                socket.getOutputStream().apply {
                    write(handshake.toByteArray(Charsets.ISO_8859_1))
                    flush()
                }

                val reader = socket.getInputStream()
                val statusLine = readHttpHeaders(reader)
                if (statusLine == null) {
                    socket.close()
                    return ConnectResult(null, "$host:$port did not answer the WebSocket handshake.")
                }
                if (!statusLine.contains("101")) {
                    socket.close()
                    return ConnectResult(
                        null,
                        "$host:$port refused the WebSocket upgrade: ${statusLine.trim()}.",
                    )
                }

                ConnectResult(
                    WebSocketClient(socket, reader, socket.getOutputStream()),
                    null,
                )
            } catch (error: Exception) {
                runCatching { socket.close() }
                ConnectResult(null, "Could not open a WebSocket to $host:$port: ${error.message}.")
            }
        }

        /**
         * The accepted key the server must answer with, exposed so the handshake can
         * be checked rather than trusted.
         *
         * The server's `Sec-WebSocket-Accept` is the SHA-1 of the client key plus this
         * fixed string, base64 encoded. A server that does not produce it is not
         * speaking WebSocket and the connection must not be used.
         */
        fun expectedAccept(key: String): String {
            val magic = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
            val digest = java.security.MessageDigest.getInstance("SHA-1")
                .digest((key + magic).toByteArray(Charsets.ISO_8859_1))
            return Base64.getEncoder().encodeToString(digest)
        }

        /** Read the HTTP response headers up to the blank line, returning the status line. */
        private fun readHttpHeaders(input: InputStream): String? {
            val builder = StringBuilder()
            var previous = -1
            while (true) {
                val byte = input.read()
                if (byte < 0) return if (builder.isEmpty()) null else builder.toString()
                builder.append(byte.toChar())
                // An empty line ends the headers, which is two consecutive CRLF pairs.
                if (previous == '\n'.code && byte == '\n'.code) break
                if (previous == '\r'.code && byte == '\r'.code) continue
                previous = byte
            }
            return builder.toString()
        }
    }

    data class ConnectResult(val client: WebSocketClient?, val reason: String?)

    /**
     * Send one text frame.
     *
     * CLIENT FRAMES MUST BE MASKED, per RFC 6455. A server closes the connection when
     * it receives an unmasked frame, and the close arrives with no error, which looks
     * exactly like a wrong command.
     */
    fun sendText(text: String) {
        val payload = text.toByteArray(Charsets.UTF_8)
        val frame = buildFrame(opcode = 0x1, payload = payload)
        output.write(frame)
        output.flush()
    }

    /**
     * Read one text frame, blocking until one arrives or the socket times out.
     *
     * Returns null on a close frame, a timeout, or a frame type this client does not
     * handle, so a caller's read loop ends rather than spinning.
     */
    fun readText(): String? {
        val first = input.read()
        if (first < 0) return null
        val opcode = first and 0x0F

        val second = input.read()
        if (second < 0) return null
        val masked = (second and 0x80) != 0
        var length = (second and 0x7F).toLong()

        if (length == 126L) {
            length = ((input.read().toLong() shl 8) or input.read().toLong())
        } else if (length == 127L) {
            length = 0
            repeat(8) { length = (length shl 8) or input.read().toLong() }
        }

        val mask = if (masked) ByteArray(4).also { input.readFully(it) } else null
        val payload = ByteArray(length.toInt())
        input.readFully(payload)

        if (mask != null) {
            for (index in payload.indices) {
                payload[index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
            }
        }

        return when (opcode) {
            // Continuation frames are not used by these protocols, so a fragment is a
            // protocol this client does not speak rather than something to reassemble.
            0x8 -> null
            0x9 -> {
                // A ping must be answered with a pong or the server drops the channel.
                output.write(buildFrame(opcode = 0xA, payload = payload))
                output.flush()
                readText()
            }
            0x1, 0x2, 0x0 -> String(payload, Charsets.UTF_8)
            else -> null
        }
    }

    fun close() {
        runCatching { output.write(buildFrame(opcode = 0x8, payload = ByteArray(0))); output.flush() }
        runCatching { socket.close() }
    }

    /** Build one client frame with the mask bit set and a random mask key. */
    private fun buildFrame(opcode: Int, payload: ByteArray): ByteArray {
        val header = ArrayList<Byte>()
        header.add((0x80 or opcode).toByte())      // FIN set, plus the opcode

        val length = payload.size
        when {
            length < 126 -> header.add((0x80 or length).toByte())
            length <= 0xFFFF -> {
                header.add((0x80 or 126).toByte())
                header.add(((length ushr 8) and 0xFF).toByte())
                header.add((length and 0xFF).toByte())
            }
            else -> {
                header.add((0x80 or 127).toByte())
                for (shift in 56 downTo 0 step 8) {
                    header.add(((length.toLong() ushr shift) and 0xFF).toByte())
                }
            }
        }

        val mask = ByteArray(4).also { SecureRandom().nextBytes(it) }
        mask.forEach { header.add(it) }

        val out = ByteArray(header.size + length)
        header.forEachIndexed { index, byte -> out[index] = byte }
        for (index in payload.indices) {
            out[header.size + index] = (payload[index].toInt() xor mask[index % 4].toInt()).toByte()
        }
        return out
    }

    private fun InputStream.readFully(target: ByteArray) {
        var offset = 0
        while (offset < target.size) {
            val read = read(target, offset, target.size - offset)
            if (read < 0) throw IllegalStateException("the WebSocket closed mid-frame")
            offset += read
        }
    }
}
