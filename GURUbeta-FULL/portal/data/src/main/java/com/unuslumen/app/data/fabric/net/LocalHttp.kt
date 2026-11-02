// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.UnknownHostException

/**
 * Plain HTTP over a raw socket, for local devices that inspect their own headers.
 * Issue #252.
 *
 * WHY THIS EXISTS AND WHY HttpURLConnection COULD NOT DO THE JOB. `HttpURLConnection`
 * reserves `Origin` as a system header and drops it silently: a probe against a local
 * listener showed `X-Probe` arriving and `Origin` missing entirely from the request the
 * server received. The Roku on this network requires an `Origin` header and answers
 * every POST without one with HTTP 403 and the body "Origin  is not allowed access",
 * where the double space is the empty value it was handed. So the one header the device
 * asked for was the one header the JDK refused to send, and the fault surfaced three
 * layers away as a lie about the set being asleep.
 *
 * THE OTHER HALF OF THE JOB IS TELLING TWO FAILURES APART. A device that answers with an
 * error has told you something real, and its status and body are the answer. A device
 * that never answers is unreachable or asleep. Conflating those is what produced the
 * defect this file fixes, so [Outcome] has a branch for each and there is no way to
 * report a refusal as an absence.
 *
 * WHY A SOCKET IS SAFE HERE. Opening a `Socket` bypasses the injected proxy by
 * construction, which is correct: every host this client is asked for must already have
 * been classified as local by [LocalRoute]. A public host is refused rather than quietly
 * routed around Tor, because reaching the internet from a local-device path is never
 * what the caller asked for.
 */
object LocalHttp {

    /** A response that actually arrived. */
    data class Response(
        val status: Int,
        val reason: String,
        /** Header names lowercased, so a lookup cannot miss on case. */
        val headers: Map<String, String>,
        val body: String,
    ) {
        /** Whether the device accepted the request. */
        val ok: Boolean get() = status in 200..299

        /** One header by name, matched without regard to case. */
        fun header(name: String): String? = headers[name.trim().lowercase()]
    }

    /**
     * What came back: a real answer, or nothing at all.
     *
     * A refusal and an absence are different facts and a caller must handle both, which
     * is why this is a sealed branch rather than a nullable response.
     */
    sealed interface Outcome {
        /** The device answered. [response] carries its status, headers and own words. */
        data class Answered(val response: Response) : Outcome

        /** Nothing answered. [reason] says which way it failed, in plain words. */
        data class NoAnswer(val reason: String) : Outcome
    }

    /** How much of a body to keep, because a device description is small and a NAS is not. */
    private const val MAX_BODY_CHARS = 64 * 1024

    /**
     * The `Origin` a device expects from a client talking to it directly.
     *
     * WHY SAME-ORIGIN. The protection this header feeds exists to stop a page on another
     * host from driving a device on this network, so the value a legitimate direct client
     * sends is the device's own origin. Sending anything else would be inventing a
     * permission, and sending nothing is what the Roku already refused.
     */
    fun sameOrigin(host: String, port: Int): String = "http://$host:$port"

    /**
     * Make one request and report either the answer or the absence of one.
     *
     * [headers] are written verbatim, which is the whole point: a caller that needs a
     * header the platform reserves can send it here. `Host`, `Content-Length` and
     * `Connection` are managed by this method so no caller can write a malformed request.
     */
    fun request(
        host: String,
        port: Int,
        method: String,
        path: String,
        headers: Map<String, String> = emptyMap(),
        body: ByteArray = ByteArray(0),
        timeoutMillis: Int = 4000,
        maxBodyChars: Int = MAX_BODY_CHARS,
    ): Outcome {
        val resolution = LocalRoute.routeFor(host)
        if (resolution.route == LocalRoute.Route.TOR) {
            // A local-device path must never reach the public internet, directly or through
            // Tor. Refusing here keeps a mistyped address from becoming a silent privacy leak.
            return Outcome.NoAnswer(
                "$host is not on this network, so nothing was sent to it",
            )
        }

        val target = if (path.startsWith("/")) path else "/$path"
        val socket = Socket()
        return try {
            socket.connect(InetSocketAddress(host, port), timeoutMillis)
            socket.soTimeout = timeoutMillis
            socket.tcpNoDelay = true

            val request = buildRequest(host, port, method, target, headers, body)
            socket.getOutputStream().apply {
                write(request)
                flush()
            }

            val input = socket.getInputStream()
            val head = readHead(input) ?: return Outcome.NoAnswer(
                "$host:$port closed the connection without answering",
            )
            val response = parseHead(head) ?: return Outcome.NoAnswer(
                "$host:$port answered with something that is not HTTP",
            )
            val responseBody = readBody(input, response.headers, maxBodyChars)
            Outcome.Answered(response.copy(body = responseBody))
        } catch (error: UnknownHostException) {
            Outcome.NoAnswer("$host could not be resolved")
        } catch (error: java.net.SocketTimeoutException) {
            Outcome.NoAnswer("$host:$port did not answer within ${timeoutMillis}ms")
        } catch (error: java.net.ConnectException) {
            Outcome.NoAnswer("nothing is listening on $host:$port")
        } catch (error: Exception) {
            Outcome.NoAnswer(
                "the connection to $host:$port failed: ${error.message ?: error.javaClass.simpleName}",
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    /** The bytes of one HTTP/1.1 request. */
    private fun buildRequest(
        host: String,
        port: Int,
        method: String,
        target: String,
        headers: Map<String, String>,
        body: ByteArray,
    ): ByteArray {
        val head = StringBuilder()
        head.append(method).append(' ').append(target).append(" HTTP/1.1\r\n")
        head.append("Host: ").append(host).append(':').append(port).append("\r\n")
        headers.forEach { (name, value) ->
            // Managed headers are dropped rather than duplicated, so a request cannot carry
            // two Host lines or a Content-Length that disagrees with the body.
            if (name.equals("Host", true) ||
                name.equals("Content-Length", true) ||
                name.equals("Connection", true)
            ) {
                return@forEach
            }
            head.append(name).append(": ").append(value).append("\r\n")
        }
        head.append("Content-Length: ").append(body.size).append("\r\n")
        // Close after the response, which makes reading to the end of the stream a complete
        // read rather than a wait for a keep-alive timeout.
        head.append("Connection: close\r\n")
        head.append("\r\n")

        val headBytes = head.toString().toByteArray(Charsets.ISO_8859_1)
        return headBytes + body
    }

    /**
     * Read the status line and headers, up to the blank line that ends them.
     *
     * THE TERMINATOR IS MATCHED FROM A FOUR-BYTE WINDOW, never by reading ahead. A peek at
     * the byte after a candidate terminator would steal the first byte of the body on a
     * response that is malformed in that one way, and a body that loses its first byte is
     * a body nobody can parse. Byte-at-a-time with a rolling window cannot do that.
     */
    private fun readHead(input: InputStream): String? {
        val collected = ByteArrayOutputStream(512)
        var b0 = -1
        var b1 = -1
        var b2 = -1
        var b3 = -1
        while (true) {
            val current = input.read()
            if (current == -1) {
                // A device that closes immediately after accepting has sent nothing usable.
                return if (collected.size() == 0) null else collected.toString("ISO-8859-1")
            }
            collected.write(current)
            if (collected.size() > 32 * 1024) {
                // Headers this large are not a device description, they are a fault.
                return collected.toString("ISO-8859-1")
            }
            b0 = b1
            b1 = b2
            b2 = b3
            b3 = current
            // CRLF CRLF is the specification; LF LF is accepted because a few embedded
            // servers send it and a lenient read costs nothing here.
            if (b0 == '\r'.code && b1 == '\n'.code && b2 == '\r'.code && b3 == '\n'.code) break
            if (b2 == '\n'.code && b3 == '\n'.code) break
        }
        return collected.toString("ISO-8859-1")
    }

    /** Split a head block into a status line and a header map. */
    private fun parseHead(head: String): Response? {
        val lines = head.split("\r\n", "\n")
        val statusLine = lines.firstOrNull()?.trim() ?: return null
        if (!statusLine.startsWith("HTTP/")) return null

        val parts = statusLine.split(" ", limit = 3)
        val status = parts.getOrNull(1)?.toIntOrNull() ?: return null
        val reason = parts.getOrNull(2)?.trim().orEmpty()

        val headers = mutableMapOf<String, String>()
        for (line in lines.drop(1)) {
            if (line.isBlank()) continue
            val index = line.indexOf(':')
            if (index <= 0) continue
            val name = line.substring(0, index).trim().lowercase()
            val value = line.substring(index + 1).trim()
            // The first occurrence wins, which matches every device that repeats a header
            // meaninglessly and keeps a caller's lookup predictable.
            if (!headers.containsKey(name)) headers[name] = value
        }

        return Response(status = status, reason = reason, headers = headers, body = "")
    }

    /** Read the body, honouring chunked encoding and a declared length. */
    private fun readBody(input: InputStream, headers: Map<String, String>, limit: Int): String {
        val chunked = headers["transfer-encoding"]?.contains("chunked", true) == true
        if (chunked) return readChunked(input, limit)

        val declared = headers["content-length"]?.trim()?.toIntOrNull()
        if (declared != null && declared >= 0) {
            return String(
                readExactly(input, minOf(declared, limit), minOf(declared, limit)),
                Charsets.UTF_8,
            )
        }
        return readToEnd(input, limit)
    }

    /** Read one chunked body, which a few DIAL descriptions use. */
    private fun readChunked(input: InputStream, limit: Int): String {
        val out = ByteArrayOutputStream()
        while (out.size() < limit) {
            val sizeLine = readLine(input) ?: break
            val size = sizeLine.trim().substringBefore(';').trim().toIntOrNull(16) ?: break
            if (size <= 0) break
            out.write(readExactly(input, size, minOf(size, limit - out.size())))
            // Each chunk is followed by its own CRLF, which is not part of the payload.
            readLine(input)
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** Read exactly [count] bytes, or as many as arrive. */
    private fun readExactly(input: InputStream, count: Int, limit: Int): ByteArray {
        val out = ByteArrayOutputStream(minOf(count, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var remaining = minOf(count, limit)
        while (remaining > 0) {
            val want = minOf(buffer.size, remaining)
            val read = input.read(buffer, 0, want)
            if (read <= 0) break
            out.write(buffer, 0, read)
            remaining -= read
        }
        return out.toByteArray()
    }

    /** Read to the end of the stream, keeping at most [limit] bytes. */
    private fun readToEnd(input: InputStream, limit: Int): String {
        val out = ByteArrayOutputStream(minOf(limit, 8 * 1024))
        val buffer = ByteArray(8 * 1024)
        var total = 0
        while (total < limit) {
            val want = minOf(buffer.size, limit - total)
            val read = input.read(buffer, 0, want)
            if (read <= 0) break
            out.write(buffer, 0, read)
            total += read
        }
        return out.toString(Charsets.UTF_8.name())
    }

    /** One CRLF-terminated line of text, for the chunk size headers. */
    private fun readLine(input: InputStream): String? {
        val out = ByteArrayOutputStream(64)
        while (true) {
            val value = input.read()
            if (value == -1) return if (out.size() == 0) null else out.toString("ISO-8859-1")
            if (value == '\n'.code) return out.toString("ISO-8859-1").trimEnd('\r')
            out.write(value)
        }
    }
}
