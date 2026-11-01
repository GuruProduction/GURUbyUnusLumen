// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tor.EgressClassifier
import com.unuslumen.app.data.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * The named HTTP tools.
 *
 * Issue #215: these opened every URL through Tor's SOCKS proxy unconditionally
 * and rejected the call outright when Tor was not running. Both halves were
 * wrong for a local address. A television at 192.168.0.115 is not routable
 * through Tor at all, so `httpGet` could not read a single device on the LAN,
 * and the failure read as "Tor not running", which points at the wrong thing
 * entirely.
 *
 * The route now comes from [EgressClassifier], which is the same object the
 * Ktor clients ask, so there is one policy rather than one per tool. Failures
 * are reported as the cause they actually were: proxy refusal, unreachable
 * host, closed port, or a name that did not resolve.
 */
class HttpToolExecutor(
    private val torManager: TorManager,
    private val context: Context
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        HttpToolDefinitions.HTTP_GET -> executeRequest("GET", args)
        HttpToolDefinitions.HTTP_POST -> executeRequest("POST", args)
        HttpToolDefinitions.HTTP_PUT -> executeRequest("PUT", args)
        HttpToolDefinitions.HTTP_DELETE -> executeRequest("DELETE", args)
        HttpToolDefinitions.HTTP_PATCH -> executeRequest("PATCH", args)
        HttpToolDefinitions.HTTP_HEAD -> executeRequest("HEAD", args)
        HttpToolDefinitions.HTTP_DOWNLOAD -> downloadFile(args)
        HttpToolDefinitions.RESOLVE_DOWNLOAD_URL -> { val r = ResolveDownloadResult(false, null, null, "resolveDownloadUrl requires WebView implementation on device."); ToolExecutionResult.success(r, json.encodeToString(ResolveDownloadResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    /**
     * The proxy for a host, or null for a direct connection.
     *
     * A direct route never consults Tor, so a local request works with Tor
     * stopped, which is the whole point of the split. A Tor route fails closed
     * when there is no circuit: it returns null with a sentence rather than
     * quietly going direct.
     */
    private fun resolveProxy(host: String): Pair<Proxy?, String?> =
        when (EgressClassifier.routeForHost(host)) {
            EgressClassifier.Route.DIRECT -> null to null
            EgressClassifier.Route.TOR -> {
                if (!torManager.isReady.value) {
                    null to (
                        "Tor is not running, so $host was not contacted. Public addresses ride Tor and " +
                            "never fall back to a direct connection. Local network addresses do not need Tor."
                        )
                    } else {
                        torManager.getSocksProxy() to null
                    }
            }
        }

    private suspend fun executeRequest(method: String, args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args["url"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'url'")
        val body = args["body"] as? String
        val contentType = args["contentType"] as? String
        val headersJson = args["headers"] as? String ?: "{}"

        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            val r = HttpResponseResult(0, "Bad URL", "", "", false, "Not a usable URL: $url (${e.message})")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HttpResponseResult.serializer(), r))
        }
        val host = parsed.host
        val route = EgressClassifier.routeForHost(host)
        val (proxy, refusal) = resolveProxy(host)
        if (refusal != null) {
            val r = HttpResponseResult(0, "No Route", "", "", false, refusal)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HttpResponseResult.serializer(), r))
        }

        var conn: HttpURLConnection? = null
        try {
            val headers = parseHeaders(headersJson)
            conn = (
                if (proxy != null) parsed.openConnection(proxy) as HttpURLConnection
                else parsed.openConnection() as HttpURLConnection
                )
            conn.requestMethod = method
            conn.instanceFollowRedirects = true
            conn.useCaches = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null && method !in setOf("GET", "HEAD", "DELETE")) {
                conn.doOutput = true
                if (contentType != null) conn.setRequestProperty("Content-Type", contentType)
                conn.outputStream.use { it.write(body.toByteArray()) }
            }
            val statusCode = conn.responseCode
            val statusText = conn.responseMessage ?: ""
            val responseHeaders = conn.headerFields.entries
                .filter { it.key != null }
                .joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" }
            val responseBody = try {
                conn.inputStream.bufferedReader().use { it.readText() }
            } catch (_: Exception) {
                try {
                    conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                } catch (_: Exception) {
                    ""
                }
            }
            val r = HttpResponseResult(
                statusCode,
                statusText,
                responseHeaders,
                responseBody.take(10000),
                statusCode in 200..399,
                if (statusCode >= 400) "HTTP $statusCode" else null,
            )
            ToolExecutionResult.success(r, json.encodeToString(HttpResponseResult.serializer(), r))
        } catch (e: Exception) {
            val sentence = EgressClassifier.failureSentence(host, parsed.port, route, e)
            val r = HttpResponseResult(0, "Error", "", "", false, sentence)
            ToolExecutionResult.success(r, json.encodeToString(HttpResponseResult.serializer(), r))
        } finally {
            conn?.disconnect()
        }
    }

    private suspend fun downloadFile(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args["url"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'url'")
        val destPath = args["destinationPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'destinationPath'")

        val parsed = try {
            URL(url)
        } catch (e: Exception) {
            val r = HttpDownloadResult(false, destPath, 0, "", "Not a usable URL: $url (${e.message})")
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HttpDownloadResult.serializer(), r))
        }
        val host = parsed.host
        val route = EgressClassifier.routeForHost(host)
        val (proxy, refusal) = resolveProxy(host)
        if (refusal != null) {
            val r = HttpDownloadResult(false, destPath, 0, "", refusal)
            return@withContext ToolExecutionResult.success(r, json.encodeToString(HttpDownloadResult.serializer(), r))
        }

        val headers = parseHeaders(args["headers"] as? String ?: "{}")
        var conn: HttpURLConnection? = null
        try {
            conn = (
                if (proxy != null) parsed.openConnection(proxy) as HttpURLConnection
                else parsed.openConnection() as HttpURLConnection
                )
            conn.requestMethod = "GET"
            conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val file = File(destPath)
            file.parentFile?.mkdirs()
            conn.inputStream.use { inp -> FileOutputStream(file).use { out -> inp.copyTo(out) } }
            val r = HttpDownloadResult(true, file.absolutePath, file.length(), conn.contentType ?: "unknown", null)
            ToolExecutionResult.success(r, json.encodeToString(HttpDownloadResult.serializer(), r))
        } catch (e: Exception) {
            val sentence = EgressClassifier.failureSentence(host, parsed.port, route, e)
            val r = HttpDownloadResult(false, destPath, 0, "", sentence)
            ToolExecutionResult.success(r, json.encodeToString(HttpDownloadResult.serializer(), r))
        } finally {
            conn?.disconnect()
        }
    }

    private fun parseHeaders(headersJson: String): Map<String, String> {
        if (headersJson.isBlank() || headersJson == "{}") return emptyMap()
        return try { json.decodeFromString<Map<String, String>>(headersJson) } catch (e: Exception) { emptyMap() }
    }
}
