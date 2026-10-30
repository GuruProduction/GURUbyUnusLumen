// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.web

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.webkit.ProxyConfig
import androidx.webkit.ProxyController
import androidx.webkit.WebViewFeature
import com.unuslumen.app.data.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.jsoup.Jsoup
import org.jsoup.safety.Safelist
import org.koin.core.annotation.Factory
import java.net.Proxy

@Factory
class WebFetchService(
    private val torManager: TorManager,
    private val context: Context
) {

    data class FetchResult(
        val url: String,
        val title: String,
        val content: String,
        val success: Boolean,
        val error: String? = null
    )

    companion object {
        private const val TAG = "WebFetch"
        // If Jsoup returns fewer than this many chars of body text, the page
        // is almost certainly JS-rendered and we need to fall back to WebView.
        private const val MIN_CONTENT_LENGTH = 200

    }

    /** Delegates to the pure classifier so the decision is unit testable. */
    private fun hollownessOf(rawContent: String): String? =
        WebFetchContentQuality.hollownessOf(rawContent)

    private fun getProxies(): List<Proxy> {
        if (!torManager.isReady.value) return emptyList()
        return listOf(
            torManager.getProxy(),       // HTTP tunnel port 8118
            torManager.getSocksProxy(),  // SOCKS port 9050
        )
    }

    private fun tryFetchWithProxies(url: String, maxLength: Int): FetchResult? {
        for (proxy in getProxies()) {
            try {
                val doc = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .proxy(proxy)
                    .ignoreContentType(true)
                    .maxBodySize(1024 * 1024)
                    .get()

                val title = doc.title().trim()
                doc.select("script, style, nav, footer, header").remove()
                val content = Jsoup.clean(doc.body().html(), Safelist.relaxed())
                    .replace(Regex("\\s+"), " ")
                    .trim()
                    .take(maxLength)

                // A response that arrived is not the same as a page that
                // contains anything. This used to report success for every
                // non-exception response, so a redirect shell or a consent
                // gate was indistinguishable from a real fetch and downstream
                // work built on nothing. Hollow bodies are now reported as
                // failures so the caller can tell the difference.
                val hollowness = hollownessOf(content)
                if (hollowness != null) {
                    return FetchResult(
                        url = url,
                        title = title,
                        content = content,
                        success = false,
                        error = "Fetched $url but the page returned no usable content ($hollowness). " +
                            "This usually means a consent wall, a JavaScript-required shell, or a bot challenge rather than a real page."
                    )
                }

                return FetchResult(url = url, title = title, content = content, success = true)
            } catch (_: Exception) {
                // try next proxy
            }
        }
        return null
    }

    /**
     * Fetch a JS-rendered page using a headless WebView routed through Tor.
     *
     * Uses androidx.webkit ProxyController to route every WebView request
     * through Tor's SOCKS proxy on 127.0.0.1:9050. No clearnet leak. Every
     * byte the WebView touches, including the main page load, JavaScript
     * fetches, XHR, CSS, scripts, and any Chromium telemetry attempts, all
     * go through Tor.
     *
     * This handles SPA frameworks (React, Vue, Angular, Svelte) that render
     * content client-side. Jsoup only sees the empty HTML shell; WebView
     * actually runs the JavaScript and produces the rendered DOM.
     *
     * The proxy override is cleared after extraction so the portal canvas
     * WebView is not affected.
     */
    @SuppressLint("SetJavaScriptEnabled")
    private suspend fun fetchWithWebView(url: String, maxLength: Int): FetchResult = withContext(Dispatchers.Main) {
        var pageLoaded = false
        var pageTitle: String? = null
        var loadError: String? = null

        // Route WebView through Tor using ProxyController
        val proxyController = if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
            ProxyController.getInstance()
        } else {
            Log.w(TAG, "ProxyController not supported on this device, WebView will NOT go through Tor")
            null
        }

        val socksPort = if (torManager.isReady.value) {
            val addr = torManager.getSocksProxy().address() as? java.net.InetSocketAddress
            addr?.port ?: 9050
        } else {
            9050
        }

        // Set up the Tor proxy override before loading any URL
        proxyController?.let { controller ->
            try {
                val proxyConfig = ProxyConfig.Builder()
                    .addProxyRule("socks://127.0.0.1:$socksPort")
                    .addDirect()
                    .build()

                val proxyLatch = java.util.concurrent.CountDownLatch(1)
                controller.setProxyOverride(proxyConfig, java.util.concurrent.Executors.newSingleThreadExecutor()) {
                    Log.d(TAG, "Proxy override set to Tor SOCKS 127.0.0.1:$socksPort")
                    proxyLatch.countDown()
                }
                proxyLatch.await(5, java.util.concurrent.TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to set proxy override: ${e.message}")
            }
        }

        try {
            val webView = WebView(context)

            webView.settings.javaScriptEnabled = true
            webView.settings.domStorageEnabled = true
            webView.settings.userAgentString = "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
            webView.settings.blockNetworkImage = true
            webView.settings.javaScriptCanOpenWindowsAutomatically = false
            webView.settings.safeBrowsingEnabled = false

            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView?, url: String?) {
                    pageLoaded = true
                    pageTitle = view?.title
                    Log.d(TAG, "WebView page finished: $url")
                }

                override fun onReceivedError(
                    view: WebView?,
                    request: android.webkit.WebResourceRequest?,
                    error: android.webkit.WebResourceError?
                ) {
                    if (request?.isForMainFrame == true) {
                        loadError = error?.description?.toString()
                        Log.w(TAG, "WebView error: $loadError")
                    }
                }
            }

            webView.loadUrl(url)

            // Wait for the page to load through Tor
            kotlinx.coroutines.delay(5000)

            if (pageLoaded && loadError == null) {
                // Give SPA frameworks time to render content into the DOM
                kotlinx.coroutines.delay(3000)

                // Extract the rendered content from the WebView
                var extractedResult: FetchResult? = null
                val latch = java.util.concurrent.CountDownLatch(1)

                webView.evaluateJavascript("""
                    (function() {
                        var title = document.title || '';
                        // Read the rendered text without removing nodes. This
                        // WebView is thrown away immediately afterwards, but
                        // deleting script/style/nav/header/footer first also
                        // deleted content a reader would legitimately count as
                        // page text, and it made the length check depend on
                        // what the site happened to wrap in a <header>.
                        var bodyText = document.body ? document.body.innerText : '';
                        bodyText = bodyText.replace(/\s+/g, ' ').trim();
                        return JSON.stringify({title: title, content: bodyText});
                    })();
                """.trimIndent()) { result ->
                    try {
                        // evaluateJavascript returns a JSON literal, so the
                        // stringified object arrives quoted and escaped.
                        // Hand-unwrapping it with chained replaces corrupted
                        // any page containing a quote or a backslash, and the
                        // parse failure then surfaced as "empty content" rather
                        // than as a parse error. Decode through a parser.
                        val decoded = decodeJsString(result) ?: "{}"
                        val parsed = org.json.JSONObject(decoded)
                        val title = parsed.optString("title", pageTitle ?: "")
                        val content = parsed.optString("content", "")
                        val hollow = hollownessOf(content)
                        extractedResult = FetchResult(
                            url = url,
                            title = title,
                            content = content.take(maxLength),
                            success = hollow == null,
                            error = hollow?.let {
                                "Rendered $url but the page returned no usable content ($it)."
                            }
                        )
                    } catch (e: Exception) {
                        extractedResult = FetchResult(
                            url = url,
                            title = pageTitle ?: "",
                            content = "",
                            success = false,
                            error = "Failed to parse WebView content: ${e.message}"
                        )
                    }
                    latch.countDown()
                }

                // Wait for the JS evaluation callback (max 10 seconds)
                withContext(Dispatchers.IO) {
                    latch.await(10, java.util.concurrent.TimeUnit.SECONDS)
                }

                webView.destroy()

                extractedResult ?: FetchResult(
                    url = url,
                    title = pageTitle ?: "",
                    content = "",
                    success = false,
                    error = "WebView JS evaluation timed out"
                )
            } else {
                webView.destroy()
                FetchResult(
                    url = url,
                    title = pageTitle ?: "",
                    content = "",
                    success = false,
                    error = loadError ?: "WebView failed to load page"
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "WebView fetch error: ${e.message}")
            FetchResult(
                url = url,
                title = "",
                content = "",
                success = false,
                error = "WebView fetch failed: ${e.message}"
            )
        } finally {
            // Always clear the proxy override so the portal canvas WebView
            // and any other WebViews in the app are not affected.
            proxyController?.clearProxyOverride(java.util.concurrent.Executors.newSingleThreadExecutor()) {
                Log.d(TAG, "Proxy override cleared")
            }
        }
    }

    suspend fun fetch(url: String, maxLength: Int = 10000): FetchResult = withContext(Dispatchers.IO) {
        // Step 1: Try Jsoup first — fast, works for server-rendered pages
        val jsoupResult = tryFetchWithProxies(url, maxLength)
        if (jsoupResult != null && jsoupResult.success && jsoupResult.content.length >= MIN_CONTENT_LENGTH) {
            return@withContext jsoupResult
        }

        // Step 2: If Jsoup returned empty or very little content, the page is
        // likely JS-rendered. Fall back to WebView which executes JavaScript.
        Log.d(TAG, "Jsoup returned thin content (${jsoupResult?.content?.length ?: 0} chars), falling back to WebView for: $url")
        val webViewResult = fetchWithWebView(url, maxLength)

        // Step 3: If Jsoup could not connect at all, try circuit recovery once.
        // Only when there was NO response: a hollow response already came back
        // from the network, so a new circuit would fetch the same wall again.
        val recovered = if (jsoupResult == null && torManager.isReady.value) {
            if (torManager.requestNewCircuits()) tryFetchWithProxies(url, maxLength) else null
        } else {
            null
        }

        // Step 4: Choose by CONTENT, not by method order.
        //
        // This is where issue #5 actually escaped. The old code ended with
        // `jsoupResult ?: ...`, so whenever Jsoup got any response at all it was
        // returned regardless of quality, and the caller was told success. A
        // redirect shell therefore beat a WebView that had rendered the real
        // page. Success now means usable content, from whichever method produced
        // more of it.
        val candidates = listOfNotNull(recovered, jsoupResult, webViewResult)
        val best = candidates
            .filter { it.success && it.content.isNotBlank() }
            .maxByOrNull { it.content.length }

        if (best != null) return@withContext best

        // Nothing produced usable content. Report the most specific reason we
        // have rather than a generic failure, so the model knows whether the
        // network failed or the page was a wall.
        val reasons = candidates.mapNotNull { it.error }.filter { it.isNotBlank() }.distinct()
        if (reasons.isNotEmpty()) {
            return@withContext FetchResult(
                url = url,
                title = candidates.firstOrNull { it.title.isNotBlank() }?.title ?: "",
                content = "",
                success = false,
                error = reasons.joinToString(" | ")
            )
        }

        FetchResult(
            url = url, title = "", content = "",
            success = false, error = "Failed to fetch URL through all methods: $url"
        )
    }

    /**
     * Turn an evaluateJavascript return value into the string it holds.
     *
     * Returns null when the value is literally null, and the input unchanged
     * when it is not a quoted JSON string.
     */
    private fun decodeJsString(raw: String?): String? {
        if (raw == null || raw == "null") return null
        val trimmed = raw.trim()
        if (!trimmed.startsWith("\"")) return trimmed
        return try {
            org.json.JSONTokener(trimmed).nextValue() as? String ?: trimmed
        } catch (_: Exception) {
            trimmed
        }
    }
}
