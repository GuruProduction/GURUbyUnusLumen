// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.components

import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

/**
 * A loopback HTTP origin for portal documents (issue 475).
 *
 * WHY THIS EXISTS. A WebView loaded with `loadDataWithBaseURL("file:///android_asset/portal/", ...)`
 * gives its document an OPAQUE origin. `window.location.origin` is the string
 * `"null"`, and every iframe the document creates sends either no Referer or one
 * naming a `file://` path. YouTube's embed API validates the referrer and answers
 * `error 153`; every provider that checks origin does the same. No query parameter
 * fixes it, because the check happens before the player is constructed.
 *
 * The measured evidence from the ticket: four portal renders on build 3.78, three
 * of them YouTube embeds returning error 153 with and without the nocookie host
 * and with an explicit origin parameter, and the fourth a direct MP4 in a video
 * tag that played. So the WebView, its JavaScript and its media stack are all
 * healthy. The only missing thing is an origin a provider will accept.
 *
 * THE SHAPE. One ServerSocket bound to 127.0.0.1 on an ephemeral port, held for
 * the life of the process. Documents are registered under a path and served with
 * `Content-Type: text/html`, so `http://127.0.0.1:<port>/portal/<id>` is a real
 * HTTP origin and the Referer header is a real URL.
 *
 * THE PORT IS STABLE FOR THE SESSION, deliberately. An ephemeral port that
 * changed per render would present a different origin on each request, which
 * fails the same way a file origin does: a provider that whitelists the origin it
 * saw once will not recognise the next one.
 *
 * LOOPBACK IS ALREADY DIRECT. `EgressClassifier.routeForAddress` returns DIRECT
 * for every loopback address, so nothing routes this through Tor and relative
 * asset loads inside the document stay on the device. That is not an assumption;
 * it is the same classifier every outbound tool uses, checked here because a
 * portal document served through an exit node would break every relative load.
 *
 * WHAT THIS IS NOT. It is not reachable off the device: the socket binds 127.0.0.1
 * and nothing else, so there is no interface a remote party could connect to.
 * And it serves only documents the app itself registered, from memory, with no
 * filesystem path in the request.
 */
object PortalOriginServer {

    /** The path prefix every portal document lives under. */
    const val PATH_PREFIX = "/portal/"

    /**
     * The sub-prefix the bundled libraries are served under.
     *
     * WHY THE ASSETS HAVE TO BE SERVED TOO. The template loads KaTeX, Mermaid,
     * Chart.js, highlight.js and marked from `file:///android_asset/portal/`.
     * That works while the document ITSELF is a file document. The moment the
     * document is served from `http://127.0.0.1:<port>` it may not load
     * subresources from `file://`, so every one of those libraries fails to load
     * and every portal arrives with no math, no diagrams and no charts.
     *
     * So the server serves them. They are read from the APK's own asset manager,
     * so nothing is copied to disk and nothing is fetched from anywhere.
     */
    const val ASSET_PREFIX = "/portal/assets/"

    private val documents = ConcurrentHashMap<String, String>()
    private val nextId = AtomicInteger(1)

    /**
     * Where bundled assets are read from.
     *
     * Nullable because a unit test can exercise [responseFor] for document paths
     * without an Android Context; an asset request with no manager is a 404,
     * which is an honest answer rather than a crash.
     */
    @Volatile
    private var assetReader: ((String) -> ByteArray?)? = null

    @Volatile
    private var serverSocket: ServerSocket? = null

    @Volatile
    private var acceptThread: Thread? = null

    /**
     * The base URL, or null before [ensureStarted] has run.
     *
     * A caller that gets null falls back to the file origin, which still renders
     * a document; it simply cannot host a third-party embed. That is the honest
     * degradation, and it is why the return type is nullable rather than throwing.
     */
    @Volatile
    var originBase: String? = null
        private set

    /**
     * Start the server if it is not already running.
     *
     * Idempotent, and safe to call from every composition: the first caller binds
     * the socket and later callers get the same origin back.
     */
    @Synchronized
    fun ensureStarted(readAsset: ((String) -> ByteArray?)? = null): String? {
        if (readAsset != null) assetReader = readAsset
        originBase?.let { return it }
        return try {
            // Bound to the loopback address explicitly rather than to a wildcard
            // on port 0. A wildcard bind would accept connections from the LAN.
            val socket = ServerSocket(0, BACKLOG, InetAddress.getByName("127.0.0.1"))
            serverSocket = socket
            val base = "http://127.0.0.1:${socket.localPort}"
            originBase = base
            acceptThread = thread(isDaemon = true, name = "portal-origin") {
                acceptLoop(socket)
            }
            base
        } catch (_: Throwable) {
            // A device that refuses to bind loopback still renders portals, just
            // without an origin an embed provider will accept. Returning null
            // lets the caller say so rather than break the render.
            null
        }
    }

    /** Register a document and return its absolute URL, or null when not started. */
    fun register(html: String): String? {
        val base = ensureStarted() ?: return null
        val id = nextId.getAndIncrement().toString()
        documents[id] = html
        // A portal conversation can be long-lived; the map would otherwise keep
        // every document it has ever rendered. The newest are the ones a live
        // WebView can still ask for.
        if (documents.size > MAX_RETAINED_DOCUMENTS) {
            val oldest = documents.keys.minByOrNull { it.toIntOrNull() ?: Int.MAX_VALUE }
            if (oldest != null) documents.remove(oldest)
        }
        return "$base$PATH_PREFIX$id"
    }

    /**
     * The response for one request path.
     *
     * Split out from the socket so the routing rule is provable on the JVM: a
     * test can ask for a registered path and an unregistered one without opening
     * a port.
     */
    fun responseFor(path: String): PortalHttpResponse {
        val cleanPath = path.substringBefore('?').substringBefore('#')

        if (cleanPath.startsWith(ASSET_PREFIX)) {
            val name = cleanPath.removePrefix(ASSET_PREFIX)
            // A name with a separator in it is a traversal attempt, not an asset.
            // The assets all sit flat beside each other, so there is no legitimate
            // subdirectory to allow.
            if (name.isEmpty() || name.contains('/') || name.contains("..")) {
                return PortalHttpResponse.notFound()
            }
            val bytes = assetReader?.invoke(name) ?: return PortalHttpResponse.notFound()
            return PortalHttpResponse(
                status = "200 OK",
                headers = listOf(
                    "Content-Type" to contentTypeFor(name),
                    // The libraries never change within a build, and the build
                    // changes the APK, so an hour is safe and keeps a conversation
                    // of many portals down to one copy of each library.
                    "Cache-Control" to "max-age=3600",
                    "X-Content-Type-Options" to "nosniff",
                ),
                body = "",
                bodyBytes = bytes,
            )
        }

        if (!cleanPath.startsWith(PATH_PREFIX)) return PortalHttpResponse.notFound()
        val id = cleanPath.removePrefix(PATH_PREFIX)
        val body = documents[id] ?: return PortalHttpResponse.notFound()
        return PortalHttpResponse(
            status = "200 OK",
            headers = listOf(
                "Content-Type" to "text/html; charset=utf-8",
                // Never cached: a re-render is a new document id, and a stale one
                // served under an old id is a portal showing yesterday's content.
                "Cache-Control" to "no-store",
                "X-Content-Type-Options" to "nosniff",
            ),
            body = body,
        )
    }

    /** The MIME type for a bundled asset, by extension. */
    private fun contentTypeFor(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "js", "mjs" -> "application/javascript; charset=utf-8"
        "css" -> "text/css; charset=utf-8"
        "json" -> "application/json; charset=utf-8"
        "svg" -> "image/svg+xml"
        "png" -> "image/png"
        "jpg", "jpeg" -> "image/jpeg"
        "woff" -> "font/woff"
        "woff2" -> "font/woff2"
        "ttf" -> "font/ttf"
        "html" -> "text/html; charset=utf-8"
        else -> "application/octet-stream"
    }

    private fun acceptLoop(socket: ServerSocket) {
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Throwable) {
                // The socket was closed under us, which is shutdown, not an error.
                return
            }
            thread(isDaemon = true) { serve(client) }
        }
    }

    private fun serve(client: Socket) {
        try {
            client.soTimeout = SOCKET_TIMEOUT_MS
            val requestLine = readRequestLine(client) ?: return
            // "GET /portal/12 HTTP/1.1"
            val path = requestLine.split(' ').getOrNull(1) ?: "/"
            val response = responseFor(path)
            writeResponse(client.getOutputStream(), response)
        } catch (_: Throwable) {
            // A malformed request from something else on loopback is not the
            // portal's problem. Drop the connection and keep serving.
        } finally {
            runCatching { client.close() }
        }
    }

    private fun readRequestLine(client: Socket): String? {
        val input = BufferedInputStream(client.getInputStream())
        val line = StringBuilder()
        while (true) {
            val b = input.read()
            if (b == -1) return line.toString().ifBlank { null }
            if (b == '\n'.code) break
            if (b != '\r'.code) line.append(b.toChar())
            // A request line longer than this is not a browser asking for a
            // portal document.
            if (line.length > MAX_REQUEST_LINE) return null
        }
        return line.toString().ifBlank { null }
    }

    private fun writeResponse(out: OutputStream, response: PortalHttpResponse) {
        val bodyBytes = response.bodyBytes ?: response.body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("HTTP/1.1 ").append(response.status).append("\r\n")
            for ((name, value) in response.headers) {
                append(name).append(": ").append(value).append("\r\n")
            }
            append("Content-Length: ").append(bodyBytes.size).append("\r\n")
            append("Connection: close\r\n")
            append("\r\n")
        }
        out.write(head.toByteArray(Charsets.US_ASCII))
        out.write(bodyBytes)
        out.flush()
    }

    /** Stop the server. Called from a test teardown; the app holds it for its life. */
    @Synchronized
    fun stop() {
        runCatching { serverSocket?.close() }
        serverSocket = null
        acceptThread = null
        originBase = null
        documents.clear()
    }

    private const val BACKLOG = 8
    private const val SOCKET_TIMEOUT_MS = 5000
    private const val MAX_REQUEST_LINE = 8192

    /**
     * How many documents to keep addressable.
     *
     * A live WebView may still ask for any document it has loaded, so the map
     * cannot be cleared on each render. It also must not grow without bound over
     * a long conversation, so the oldest are dropped.
     */
    private const val MAX_RETAINED_DOCUMENTS = 64
}

/** One HTTP response, as data, so the routing rule can be tested without a socket. */
data class PortalHttpResponse(
    val status: String,
    val headers: List<Pair<String, String>>,
    val body: String,
    /**
     * The body when it is binary (a bundled library), in which case [body] is
     * empty. Kept as a separate field rather than encoding the bytes into a
     * String, because a `.woff2` decoded as UTF-8 is not the same file.
     */
    val bodyBytes: ByteArray? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PortalHttpResponse) return false
        return status == other.status &&
            headers == other.headers &&
            body == other.body &&
            (bodyBytes?.contentEquals(other.bodyBytes ?: ByteArray(0))
                ?: (other.bodyBytes == null))
    }

    override fun hashCode(): Int {
        var result = status.hashCode()
        result = 31 * result + headers.hashCode()
        result = 31 * result + body.hashCode()
        result = 31 * result + (bodyBytes?.contentHashCode() ?: 0)
        return result
    }

    companion object {
        fun notFound(): PortalHttpResponse = PortalHttpResponse(
            status = "404 Not Found",
            headers = listOf("Content-Type" to "text/plain; charset=utf-8", "Cache-Control" to "no-store"),
            body = "Not found",
        )
    }
}
