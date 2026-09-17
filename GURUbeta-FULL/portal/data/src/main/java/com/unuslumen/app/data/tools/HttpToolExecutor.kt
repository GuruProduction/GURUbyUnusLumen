package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

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

    private suspend fun executeRequest(method: String, args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args["url"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'url'")
        if (!torManager.isReady.value) return@withContext run { val r = HttpResponseResult(0, "Tor Not Ready", "", "", false, "Tor not running"); ToolExecutionResult.success(r, json.encodeToString(HttpResponseResult.serializer(), r)) }
        val body = args["body"] as? String; val contentType = args["contentType"] as? String; val headersJson = args["headers"] as? String ?: "{}"
        var conn: HttpURLConnection? = null
        try {
            val headers = parseHeaders(headersJson); val proxy = torManager.getSocksProxy()
            conn = (URL(url).openConnection(proxy) as HttpURLConnection); conn.requestMethod = method; conn.instanceFollowRedirects = true; conn.useCaches = false
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (body != null && method !in setOf("GET", "HEAD", "DELETE")) { conn.doOutput = true; if (contentType != null) conn.setRequestProperty("Content-Type", contentType); conn.outputStream.use { it.write(body.toByteArray()) } }
            val statusCode = conn.responseCode; val statusText = conn.responseMessage ?: ""
            val responseHeaders = conn.headerFields.entries.filter { it.key != null }.joinToString("\n") { "${it.key}: ${it.value.joinToString(", ")}" }
            val responseBody = try { conn.inputStream.bufferedReader().use { it.readText() } } catch (_: Exception) { try { conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "" } catch (_: Exception) { "" } }
            val r = HttpResponseResult(statusCode, statusText, responseHeaders, responseBody.take(10000), statusCode in 200..399, if (statusCode >= 400) "HTTP $statusCode" else null)
            ToolExecutionResult.success(r, json.encodeToString(HttpResponseResult.serializer(), r))
        } catch (e: Exception) { val r = HttpResponseResult(0, "Error", "", "", false, "Request failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(HttpResponseResult.serializer(), r)) }
        finally { conn?.disconnect() }
    }

    private suspend fun downloadFile(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val url = args["url"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'url'")
        val destPath = args["destinationPath"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'destinationPath'")
        if (!torManager.isReady.value) return@withContext run { val r = HttpDownloadResult(false, destPath, 0, "", "Tor not running"); ToolExecutionResult.success(r, json.encodeToString(HttpDownloadResult.serializer(), r)) }
        val headers = parseHeaders(args["headers"] as? String ?: "{}")
        var conn: HttpURLConnection? = null
        try {
            conn = (URL(url).openConnection(torManager.getSocksProxy()) as HttpURLConnection); conn.requestMethod = "GET"; conn.instanceFollowRedirects = true
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            val file = File(destPath); file.parentFile?.mkdirs()
            conn.inputStream.use { inp -> FileOutputStream(file).use { out -> inp.copyTo(out) } }
            val r = HttpDownloadResult(true, file.absolutePath, file.length(), conn.contentType ?: "unknown", null)
            ToolExecutionResult.success(r, json.encodeToString(HttpDownloadResult.serializer(), r))
        } catch (e: Exception) { val r = HttpDownloadResult(false, destPath, 0, "", "Download failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(HttpDownloadResult.serializer(), r)) }
        finally { conn?.disconnect() }
    }

    private fun parseHeaders(headersJson: String): Map<String, String> {
        if (headersJson.isBlank() || headersJson == "{}") return emptyMap()
        return try { json.decodeFromString<Map<String, String>>(headersJson) } catch (e: Exception) { emptyMap() }
    }
}