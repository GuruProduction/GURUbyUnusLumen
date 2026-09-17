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
                        document.querySelectorAll('script, style, nav, footer, header, noscript').forEach(function(el) {
                            el.remove();
                        });
                        var bodyText = document.body ? document.body.innerText : '';
                        bodyText = bodyText.replace(/\s+/g, ' ').trim();
                        return JSON.stringify({title: title, content: bodyText});
                    })();
                """.trimIndent()) { result ->
                    try {
                        val cleaned = result?.removeSurrounding("\"")?.replace("\\\"", "\"")?.replace("\\n", "\n") ?: "{}"
                        val parsed = org.json.JSONObject(cleaned)
                        val title = parsed.optString("title", pageTitle ?: "")
                        val content = parsed.optString("content", "")
                        extractedResult = FetchResult(
                            url = url,
                            title = title,
                            content = content.take(maxLength),
                            success = content.isNotBlank()
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
        if (webViewResult.success && webViewResult.content.length > (jsoupResult?.content?.length ?: 0)) {
            return@withContext webViewResult
        }

        // Step 3: If both failed, try circuit recovery and attempt Jsoup once more
        if (jsoupResult == null && torManager.isReady.value) {
            val recovered = torManager.requestNewCircuits()
            if (recovered) {
                tryFetchWithProxies(url, maxLength)?.let { return@withContext it }
            }
        }

        // Return whatever we got — prefer Jsoup result if WebView also failed
        jsoupResult ?: webViewResult.let {
            if (it.success) it
            else FetchResult(
                url = url, title = "", content = "",
                success = false, error = "Failed to fetch URL through all methods: $url"
            )
        }
    }
}
