// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
import android.view.View
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
            // A WebView built in code and never attached to a window has no
            // size. Lay it out before the first load so the page renders at a
            // real viewport and the view can be drawn later.
            layoutForCapture(webView)
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
            // Read-only now, for the same reason as content(): removing nodes
            // from the document mangles the page this session keeps for later
            // screenshots, clicks and inputs. The HTML is captured before
            // anything is touched, and the text is gathered by walking text
            // nodes so script and style bodies are skipped without deleting
            // them.
            val contentJs = """(function(){var d=document;var html=d.documentElement?d.documentElement.outerHTML:'';var b=d.body;var t='';if(b){var w=d.createTreeWalker(b,NodeFilter.SHOW_TEXT,null);var out=[];var n;while(n=w.nextNode()){var p=n.parentNode;if(!p)continue;var tag=p.nodeName;if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT')continue;var s=n.nodeValue;if(!s)continue;s=s.replace(/\s+/g,' ').trim();if(s)out.push(s);}t=out.join(' ').trim();}return JSON.stringify({title:d.title||'',content:t,html:html})})();"""
            val rawResult = evaluateJs(webView, contentJs, 10_000)
            val parsed = parseContentResult(rawResult)
            sessionReady = true
            val r = WebBrowserLoadResult(url, parsed.optString("title", pageTitle ?: ""), parsed.optString("content", "").take(15000), parsed.optString("html", "").take(30000), true, null)
            ToolExecutionResult.success(r, json.encodeToString(WebBrowserLoadResult.serializer(), r))
        } catch (e: Exception) { sessionReady = false; val r = WebBrowserLoadResult(url, "", "", "", false, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(WebBrowserLoadResult.serializer(), r)) }
    }

    private fun content(args: Map<String, Any?>): ToolExecutionResult {
        val webView = sessionWebView ?: return ToolExecutionResult.error("No page loaded")
        if (!sessionReady) return ToolExecutionResult.error("No page loaded")
        val selector = args["selector"] as? String ?: ""
        val js = if (selector.isBlank()) {
            // Read the visible text WITHOUT touching the DOM. The previous
            // version removed every script, style and noscript element before
            // reading, which permanently mutilated the live page: a screenshot
            // taken afterwards was unstyled and any later click or input landed
            // on a document the site no longer recognised.
            "(function(){var b=document.body;if(!b)return '';var w=document.createTreeWalker(b,NodeFilter.SHOW_TEXT,null);var " +
                "out=[];var n;while(n=w.nextNode()){var p=n.parentNode;if(!p)continue;var tag=p.nodeName;" +
                "if(tag==='SCRIPT'||tag==='STYLE'||tag==='NOSCRIPT')continue;var s=n.nodeValue;if(!s)continue;" +
                "s=s.replace(/\\s+/g,' ').trim();if(s)out.push(s);}return out.join(' ').trim();})();"
        } else {
            "(function(){var e=document.querySelector('$selector');return e?(e.innerText||e.textContent||''):'ERROR: Element not found: $selector';})();"
        }
        val text = decodeJsString(evaluateJsSync(webView, js, 10_000) ?: "")
        val r = if (text.startsWith("ERROR:")) WebBrowserContentResult("", false, text) else WebBrowserContentResult(text, true, null)
        return ToolExecutionResult.success(r, json.encodeToString(WebBrowserContentResult.serializer(), r))
    }

    private suspend fun screenshot(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.Main) {
        val webView = sessionWebView ?: return@withContext ToolExecutionResult.error("No page loaded")
        if (!sessionReady) return@withContext ToolExecutionResult.error("No page loaded")
        val savePath = args["savePath"] as? String ?: ""
        try {
            // The zero-size failure came from the WebView never being measured:
            // width and height were both 0, and only height was guarded, so
            // Bitmap.createBitmap threw "width and height must be > 0" while
            // the height guard hid the real number. Ensure a layout first, then
            // refuse honestly if the view still has no area.
            if (webView.width <= 0 || webView.height <= 0) layoutForCapture(webView)
            if (webView.width <= 0 || webView.height <= 0) {
                val r = WebBrowserScreenshotResult("", false, "Failed: the browser has no drawable area (${webView.width}x${webView.height}) after layout")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(WebBrowserScreenshotResult.serializer(), r))
            }
            val bitmap = Bitmap.createBitmap(webView.width, webView.height, Bitmap.Config.ARGB_8888)
            val canvas = Canvas(bitmap)
            webView.draw(canvas)
            val stream = ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream)
            val bytes = stream.toByteArray()
            val b64 = Base64.encodeToString(bytes, Base64.NO_WRAP)
            if (savePath.isNotBlank()) {
                try { java.io.File(savePath).apply { parentFile?.mkdirs() }.writeBytes(bytes) } catch (_: Exception) {}
            }
            bitmap.recycle()
            val r = WebBrowserScreenshotResult(b64, true, null)
            ToolExecutionResult.success(r, json.encodeToString(WebBrowserScreenshotResult.serializer(), r))
        } catch (e: Exception) {
            val r = WebBrowserScreenshotResult("", false, "Failed: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(WebBrowserScreenshotResult.serializer(), r))
        }
    }

    /**
     * Give an unattached WebView a real viewport.
     *
     * A view constructed in code and never added to a window never receives a
     * measure or layout pass, so width and height stay 0. Measuring and laying
     * out by hand fixes both halves of issue #2: the page now renders into a
     * real viewport, and the view has pixels for Canvas to draw.
     *
     * The size is the window's usable area when one is available, so a
     * screenshot matches what the user would see, falling back to a sensible
     * phone-sized viewport when there is no window to ask.
     */
    private fun layoutForCapture(webView: WebView) {
        try {
            val metrics = context.resources.displayMetrics
            val widthPx = if (metrics.widthPixels > 0) metrics.widthPixels else 1080
            val heightPx = if (metrics.heightPixels > 0) metrics.heightPixels else 1920
            webView.measure(
                View.MeasureSpec.makeMeasureSpec(widthPx, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(heightPx, View.MeasureSpec.EXACTLY)
            )
            webView.layout(0, 0, webView.measuredWidth, webView.measuredHeight)
        } catch (_: Exception) {
            // A layout failure is not fatal here: the caller checks the size and
            // reports an honest error instead of guessing at a bitmap.
        }
    }

    /**
     * Parse the JSON string returned by evaluateJavascript.
     *
     * evaluateJavascript hands back a JSON literal, so a script that returns
     * JSON.stringify(...) arrives as a quoted string with every inner quote and
     * newline escaped. The previous code unstripped that by hand with three
     * chained string replaces, which corrupts as soon as page HTML contains a
     * quote or a literal backslash sequence, and the failed parse was swallowed
     * into an empty object while the title fell back to the WebView's own, so
     * the call reported success with empty content. Decoding through a JSON
     * parser is exact and cannot be fooled by page content.
     */
    private fun parseContentResult(rawResult: String?): org.json.JSONObject {
        if (rawResult.isNullOrBlank() || rawResult == "null") return org.json.JSONObject()
        val decoded = decodeJsString(rawResult)
        if (decoded.isBlank()) return org.json.JSONObject()
        return try {
            org.json.JSONObject(decoded)
        } catch (_: Exception) {
            org.json.JSONObject()
        }
    }

    /**
     * Turn an evaluateJavascript return value into the plain string it holds.
     *
     * Returns the input unchanged when it is not a quoted JSON string, so a
     * plain 'OK' or an error sentence passes through untouched.
     */
    private fun decodeJsString(raw: String): String {
        val trimmed = raw.trim()
        if (!trimmed.startsWith("\"")) return trimmed
        return try {
            org.json.JSONTokener(trimmed).nextValue() as? String ?: trimmed
        } catch (_: Exception) {
            trimmed
        }
    }

    private fun click(args: Map<String, Any?>): ToolExecutionResult {
        val webView = sessionWebView ?: return ToolExecutionResult.error("No page loaded")
        if (!sessionReady) return ToolExecutionResult.error("No page loaded")
        val selector = args["selector"] as? String ?: return ToolExecutionResult.error("Missing 'selector'")
        val js = "(function(){var e=document.querySelector('$selector');if(!e)return 'ERROR: Element not found: $selector';e.click();return 'OK';})();"
        // evaluateJavascript returns a JSON literal, so 'OK' arrives as "OK".
        // Comparing against a bare OK reported a false failure on every
        // successful click, which is worse than no answer: the model would
        // retry a click that had already landed.
        val result = decodeJsString(evaluateJsSync(webView, js, 10_000) ?: "")
        val r = if (result == "OK") WebBrowserActionResult(true, null) else WebBrowserActionResult(false, result)
        return ToolExecutionResult.success(r, json.encodeToString(WebBrowserActionResult.serializer(), r))
    }

    private fun input(args: Map<String, Any?>): ToolExecutionResult {
        val webView = sessionWebView ?: return ToolExecutionResult.error("No page loaded")
        if (!sessionReady) return ToolExecutionResult.error("No page loaded")
        val selector = args["selector"] as? String ?: return ToolExecutionResult.error("Missing 'selector'")
        val text = (args["text"] as? String ?: return ToolExecutionResult.error("Missing 'text'")).replace("\\", "\\\\").replace("'", "\\'").replace("\n", "\\n")
        // Assigning e.value directly is ignored by React and other frameworks
        // that track their own state, so the field looked filled on screen while
        // the page never learned about it. The native setter plus a bubbling
        // input event is what a real keystroke does. The read-back is honest:
        // it reports the element's value after the write rather than assuming
        // the write worked.
        val js = "(function(){var e=document.querySelector('$selector');if(!e)return 'ERROR: Element not found: $selector';" +
            "e.focus();var proto=e.tagName==='TEXTAREA'?window.HTMLTextAreaElement.prototype:window.HTMLInputElement.prototype;" +
            "var setter=Object.getOwnPropertyDescriptor(proto,'value');if(setter&&setter.set){setter.set.call(e,'$text');}else{e.value='$text';}" +
            "e.dispatchEvent(new Event('input',{bubbles:true}));e.dispatchEvent(new Event('change',{bubbles:true}));" +
            "return 'OK';})();"
        val result = decodeJsString(evaluateJsSync(webView, js, 10_000) ?: "")
        val r = if (result == "OK") WebBrowserActionResult(true, null) else WebBrowserActionResult(false, result)
        return ToolExecutionResult.success(r, json.encodeToString(WebBrowserActionResult.serializer(), r))
    }

    private fun scroll(args: Map<String, Any?>): ToolExecutionResult {
        val webView = sessionWebView ?: return ToolExecutionResult.error("No page loaded")
        if (!sessionReady) return ToolExecutionResult.error("No page loaded")
        val direction = (args["direction"] as? String ?: "down").lowercase()
        val js = when (direction) { "down" -> "window.scrollBy(0,500);'OK';"; "up" -> "window.scrollBy(0,-500);'OK';"; "bottom" -> "window.scrollTo(0,document.body.scrollHeight);'OK';"; "top" -> "window.scrollTo(0,0);'OK';"; else -> "'ERROR: Invalid direction';" }
        val result = decodeJsString(evaluateJsSync(webView, js, 10_000) ?: "")
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