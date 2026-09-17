package com.unuslumen.app.data.tools

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.webkit.ConsoleMessage
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.os.Looper
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class WebViewBrowserToolExecutor(
    private val torManager: TorManager,
    private val context: Context
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private var sessionWebView: WebView? = null
    private var sessionReady = false
    private val consoleMessages = ConcurrentLinkedQueue<String>()
    private val networkRequests = ConcurrentLinkedQueue<String>()

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        WebViewBrowserToolDefinitions.WEB_BROWSER_LOAD -> loadPage(args)
        WebViewBrowserToolDefinitions.WEB_BROWSER_CONTENT -> content(args)
        WebViewBrowserToolDefinitions.WEB_BROWSER_SCREENSHOT -> screenshot(args)
        WebViewBrowserToolDefinitions.WEB_BROWSER_CLICK -> click(args)
        WebViewBrowserToolDefinitions.WEB_BROWSER_INPUT -> input(args)
        WebViewBrowserToolDefinitions.WEB_BROWSER_SCROLL -> scroll(args)
        WebViewBrowserToolDefinitions.WEB_BROWSER_CONSOLE -> { val r = WebBrowserConsoleResult(consoleMessages.toList(), consoleMessages.size, true, null); ToolExecutionResult.success(r, json.encodeToString(WebBrowserConsoleResult.serializer(), r)) }
        WebViewBrowserToolDefinitions.WEB_BROWSER_NETWORK -> { val r = WebBrowserNetworkResult(networkRequests.toList(), networkRequests.size, true, null); ToolExecutionResult.success(r, json.encodeToString(WebBrowserNetworkResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun loadPage(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        consoleMessages.clear(); networkRequests.clear()
        val url = args["url"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'url'")
        val renderWait = (args["renderWaitSeconds"] as? Number)?.toInt() ?: 5
        var pageLoaded = false; var pageTitle: String? = null; var loadError: String? = null
        try {
            sessionWebView?.destroy()
            val webView = WebView(context); sessionWebView = webView
            webView.settings.javaScriptEnabled = true; webView.settings.domStorageEnabled = true
            webView.settings.safeBrowsingEnabled = false; webView.settings.cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
            webView.webChromeClient = object : WebChromeClient() { override fun onConsoleMessage(cm: ConsoleMessage): Boolean { consoleMessages.add("[${cm.messageLevel()}] ${cm.message()}"); return true } }
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(v: WebView?, u: String?) { pageLoaded = true; pageTitle = v?.title }
                override fun onReceivedError(v: WebView?, r: WebResourceRequest?, e: android.webkit.WebResourceError?) { if (r?.isForMainFrame == true) loadError = e?.description?.toString() }
                override fun shouldInterceptRequest(v: WebView?, r: WebResourceRequest?): WebResourceResponse? { val u = r?.url?.toString() ?: return null; networkRequests.add("${r.method} $u"); return null }
            }
            webView.loadUrl(url)
            withTimeoutOrNull(30_000L) { while (!pageLoaded && loadError == null) kotlinx.coroutines.delay(200) }
            if (!pageLoaded || loadError != null) { sessionReady = false; val r = WebBrowserLoadResult(url, pageTitle ?: "", "", "", false, loadError ?: "Page failed to load"); return@withContext ToolExecutionResult.success(r, json.encodeToString(WebBrowserLoadResult.serializer(), r)) }
            kotlinx.coroutines.delay(renderWait * 1000L)
            val contentJs = """(function(){document.querySelectorAll('script,style,noscript').forEach(function(e){e.remove()});var t=document.body?document.body.innerText:'';t=t.replace(/\s+/g,' ').trim();return JSON.stringify({title:document.title||'',content:t,html:document.documentElement?document.documentElement.outerHTML:''})})();"""
            val rawResult = evaluateJs(webView, contentJs, 10_000) ?: "{}"
            val cleaned = rawResult.removeSurrounding("\"").replace("\\\"", "\"").replace("\\n", "\n").replace("\\/", "/")
            val parsed = try { org.json.JSONObject(cleaned) } catch (e: Exception) { org.json.JSONObject() }
            sessionReady = true
            val r = WebBrowserLoadResult(url, parsed.optString("title", pageTitle ?: ""), parsed.optString("content", "").take(15000), parsed.optString("html", "").take(30000), true, null)
            ToolExecutionResult.success(r, json.encodeToString(WebBrowserLoadResult.serializer(), r))
        } catch (e: Exception) { sessionReady = false; val r = WebBrowserLoadResult(url, "", "", "", false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(WebBrowserLoadResult.serializer(), r)) }
    }

    private fun content(args: Map<String, Any?>): ToolExecutionResult {
        val webView = sessionWebView ?: return ToolExecutionResult.error("No page loaded")
        if (!sessionReady) return ToolExecutionResult.error("No page loaded")
        val selector = args["selector"] as? String ?: ""
        val js = if (selector.isBlank()) "(function(){document.querySelectorAll('script,style,noscript').forEach(function(e){e.remove()});return document.body?document.body.innerText.replace(/\\s+/g,' ').trim():'';})();" else "(function(){var e=document.querySelector('$selector');return e?e.innerText||'':'ERROR: Element not found: $selector';})();"
        val text = evaluateJsSync(webView, js, 10_000) ?: ""
        val r = if (text.startsWith("ERROR:")) WebBrowserContentResult("", false, text) else WebBrowserContentResult(text, true, null)
        return ToolExecutionResult.success(r, json.encodeToString(WebBrowserContentResult.serializer(), r))
    }

    private suspend fun screenshot(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val webView = sessionWebView ?: return@withContext ToolExecutionResult.error("No page loaded")
        if (!sessionReady) return@withContext ToolExecutionResult.error("No page loaded")
        val savePath = args["savePath"] as? String ?: ""
        try { val bitmap = Bitmap.createBitmap(webView.width, webView.height.coerceAtLeast(1), Bitmap.Config.ARGB_8888); val canvas = Canvas(bitmap); webView.draw(canvas); val stream = ByteArrayOutputStream(); bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream); val bytes = stream.toByteArray(); val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP); if (savePath.isNotBlank()) { try { java.io.File(savePath).apply { parentFile?.mkdirs() }.writeBytes(bytes) } catch (_: Exception) {} }; bitmap.recycle(); val r = WebBrowserScreenshotResult(b64, true, null); ToolExecutionResult.success(r, json.encodeToString(WebBrowserScreenshotResult.serializer(), r)) } catch (e: Exception) { val r = WebBrowserScreenshotResult("", false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(WebBrowserScreenshotResult.serializer(), r)) }
    }

    private fun click(args: Map<String, Any?>): ToolExecutionResult {
        val webView = sessionWebView ?: return ToolExecutionResult.error("No page loaded")
        if (!sessionReady) return ToolExecutionResult.error("No page loaded")
        val selector = args["selector"] as? String ?: return ToolExecutionResult.error("Missing 'selector'")
        val js = "(function(){var e=document.querySelector('$selector');if(!e)return 'ERROR: Element not found: $selector';e.click();return 'OK';})();"
        val result = evaluateJsSync(webView, js, 10_000) ?: ""
        val r = if (result == "OK") WebBrowserActionResult(true, null) else WebBrowserActionResult(false, result)
        return ToolExecutionResult.success(r, json.encodeToString(WebBrowserActionResult.serializer(), r))
    }

    private fun input(args: Map<String, Any?>): ToolExecutionResult {
        val webView = sessionWebView ?: return ToolExecutionResult.error("No page loaded")
        if (!sessionReady) return ToolExecutionResult.error("No page loaded")
        val selector = args["selector"] as? String ?: return ToolExecutionResult.error("Missing 'selector'")
        val text = (args["text"] as? String ?: return ToolExecutionResult.error("Missing 'text'")).replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        val js = "(function(){var e=document.querySelector('$selector');if(!e)return 'ERROR: Element not found: $selector';e.focus();e.value='$text';e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));return 'OK';})();"
        val result = evaluateJsSync(webView, js, 10_000) ?: ""
        val r = if (result == "OK") WebBrowserActionResult(true, null) else WebBrowserActionResult(false, result)
        return ToolExecutionResult.success(r, json.encodeToString(WebBrowserActionResult.serializer(), r))
    }

    private fun scroll(args: Map<String, Any?>): ToolExecutionResult {
        val webView = sessionWebView ?: return ToolExecutionResult.error("No page loaded")
        if (!sessionReady) return ToolExecutionResult.error("No page loaded")
        val direction = (args["direction"] as? String ?: "down").lowercase()
        val js = when (direction) { "down" -> "window.scrollBy(0,500);'OK';"; "up" -> "window.scrollBy(0,-500);'OK';"; "bottom" -> "window.scrollTo(0,document.body.scrollHeight);'OK';"; "top" -> "window.scrollTo(0,0);'OK';"; else -> "'ERROR: Invalid direction';" }
        val result = evaluateJsSync(webView, js, 10_000) ?: ""
        val r = if (result == "OK") WebBrowserActionResult(true, null) else WebBrowserActionResult(false, result)
        return ToolExecutionResult.success(r, json.encodeToString(WebBrowserActionResult.serializer(), r))
    }

    private suspend fun evaluateJs(webView: WebView, js: String, timeoutMs: Long): String? {
        var result: String? = null; val latch = CountDownLatch(1)
        withContext(Dispatchers.Main) { webView.evaluateJavascript(js) { value -> result = value; latch.countDown() } }
        withContext(Dispatchers.IO) { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
        return result
    }

    private fun evaluateJsSync(webView: WebView, js: String, timeoutMs: Long): String? {
        var result: String? = null; val latch = CountDownLatch(1)
        android.os.Handler(Looper.getMainLooper()).post { webView.evaluateJavascript(js) { value -> result = value; latch.countDown() } }
        latch.await(timeoutMs, TimeUnit.MILLISECONDS)
        return result
    }
}