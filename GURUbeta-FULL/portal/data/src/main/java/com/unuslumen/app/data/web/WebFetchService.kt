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
import com.unuslumen.app.data.tor.EgressClassifier
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

        /** How long a public fetch waits for a circuit before reporting the wait. */
        private const val CIRCUIT_WAIT_MS = 30_000L

        /** How often the wait re-reads Tor's readiness. */
        private const val CIRCUIT_POLL_MS = 100L
    }

    /** Delegates to the pure classifier so the decision is unit testable. */
    private fun hollownessOf(rawContent: String): String? =
        WebFetchContentQuality.hollownessOf(rawContent)

    /**
     * The proxies to attempt for a host.
     *
     * Issue #215: this used to return the Tor proxies for every URL and an
     * empty list whenever Tor was down. Both were wrong for a local address,
     * and the empty list meant a LAN fetch was never attempted at all. A local
     * host gets a single direct attempt.
     *
     * A PUBLIC HOST NOW WAITS FOR A CIRCUIT instead of returning an empty list. Returning
     * nothing made a stopped Tor indistinguishable from an unreachable site, which is the
     * behaviour the owner rejected: Tor is infrastructure and a fetch should survive a missing
     * circuit rather than being turned into an error. He only ever sees the wait when the window
     * actually runs out.
     */
    private fun getProxies(host: String): List<Proxy?> {
        val ready = awaitCircuitIfNeeded(host)
        val decision = EgressClassifier.decide(
            host = host,
            mode = currentEgressMode(),
            torReady = ready,
        )
        return when (decision.route) {
            // A refused decision yields no proxy, and the caller below reports the decision's
            // own sentence rather than inventing one.
            EgressClassifier.Route.DIRECT ->
                if (decision.refused) emptyList() else listOf(null)
            EgressClassifier.Route.TOR -> {
                if (ready) {
                    listOf(
                        torManager.getProxy(),       // HTTP tunnel port 8118
                        torManager.getSocksProxy(),  // SOCKS port 9050
                    )
                } else {
                    emptyList()
                }
            }
        }
    }

    /**
     * Block, bounded, until Tor can carry this host, and report whether it can.
     *
     * A LOCAL HOST RETURNS IMMEDIATELY, because a LAN fetch never rides Tor and waiting for a
     * circuit to reach the router would be the #215 bug wearing a different hat.
     *
     * This runs on a background dispatcher (every caller is inside `withContext(Dispatchers.IO)`
     * or a Jsoup call), so blocking here costs a worker thread that has nothing else to do.
     */
    private fun awaitCircuitIfNeeded(host: String): Boolean {
        if (torManager.isReady.value) return true
        if (!EgressClassifier.routeForHost(host).equals(EgressClassifier.Route.TOR)) return true

        torManager.requestCircuitRebuildAsync()
        val deadline = System.currentTimeMillis() + CIRCUIT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (torManager.isReady.value) {
                Log.i(TAG, "A fetch waited for a Tor circuit and then proceeded")
                return true
            }
            try {
                Thread.sleep(CIRCUIT_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return torManager.isReady.value
            }
        }
        Log.w(TAG, "A fetch waited ${CIRCUIT_WAIT_MS}ms for a Tor circuit and did not get one")
        return torManager.isReady.value
    }

    /** The live posture, resolved from the container, falling back to the fail-closed one. */
    private fun currentEgressMode(): com.unuslumen.app.preferences.domain.model.EgressMode =
        try {
            val policy: com.unuslumen.app.data.tor.EgressPolicy =
                org.koin.java.KoinJavaComponent.getKoin()
                    .get(com.unuslumen.app.data.tor.EgressPolicy::class)
            policy.current()
        } catch (_: Exception) {
            com.unuslumen.app.preferences.domain.model.EgressMode.DEFAULT
        }

    private fun hostOf(url: String): String? = try {
        java.net.URI(url).host
    } catch (_: Exception) {
        null
    }

    /**
     * Attempt a Jsoup fetch and always answer with a result.
     *
     * This used to return null for every failure, which threw away the cause
     * and forced the caller to invent a generic sentence. It now always says
     * what happened, including which of the four network failures it was.
     */
    private fun tryFetchWithProxies(url: String, maxLength: Int): FetchResult {
        val host = hostOf(url)
        val route = EgressClassifier.routeForHost(host)
        val decision = EgressClassifier.decide(
            host = host,
            mode = currentEgressMode(),
            torReady = torManager.isReady.value,
        )
        val proxies = host?.let { getProxies(it) } ?: emptyList()

        // A refusal is a posture decision, not a network fact, and it carries the policy's own
        // sentence. Reporting it as a network failure used to make a stopped Tor look like a
        // dead site.
        decision.refusal?.let { sentence ->
            return FetchResult(
                url = url,
                title = "",
                content = "",
                success = false,
                error = sentence,
            )
        }

        if (proxies.isEmpty()) {
            return FetchResult(
                url = url,
                title = "",
                content = "",
                success = false,
                error = "$url was not fetched because no route to it was available.",
            )
        }

        var lastError: Exception? = null
        for (proxy in proxies) {
            try {
                val request = Jsoup.connect(url)
                    .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36")
                    .ignoreContentType(true)
                    .maxBodySize(1024 * 1024)

                // Jsoup rejects a null proxy rather than defaulting to direct,
                // so a local route leaves the proxy off the connection entirely.
                if (proxy != null) request.proxy(proxy)

                val doc = request.get()

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
            } catch (e: Exception) {
                // Keep the cause so the caller can be told which of the four
                // failures this was. Swallowing it is what produced a generic
                // "failed through all methods" sentence for every outcome.
                lastError = e
            }
        }

        // Every attempt failed. Say which failure it was rather than returning
        // null and letting the caller invent a reason.
        return FetchResult(
            url = url,
            title = "",
            content = "",
            success = false,
            error = EgressClassifier.failureSentence(
                host ?: url,
                null,
                route,
                lastError,
            ),
        )
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

        // Whether this URL needs Tor at all. A local page — a router admin
        // page, a NAS, a device's own web UI — must load DIRECT, because the
        // old code forced every URL through the SOCKS listener and a LAN host
        // cannot be reached that way. This returned a black page for a device
        // that was answering perfectly well.
        val webViewDecision = EgressClassifier.decide(
            host = hostOf(url),
            mode = currentEgressMode(),
            torReady = torManager.isReady.value,
        )
        webViewDecision.refusal?.let { sentence ->
            return@withContext FetchResult(
                url = url,
                title = "",
                content = "",
                success = false,
                error = sentence,
            )
        }

        // THE DECISION, NOT THE STATIC ROUTE. Keying this on routeForHost meant a public page
        // under the clearnet posture still had a Tor proxy override installed, so the override
        // did nothing for the WebView fallback even though it worked for the Jsoup path.
        val proxyController = if (webViewDecision.route == EgressClassifier.Route.TOR) {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.PROXY_OVERRIDE)) {
                ProxyController.getInstance()
            } else {
                // Without PROXY_OVERRIDE the WebView would load the public page
                // direct, which is the leak this whole object exists to stop.
                Log.w(TAG, "ProxyController not supported, refusing to load a public URL unfiltered")
                return@withContext FetchResult(
                    url = url,
                    title = "",
                    content = "",
                    success = false,
                    error = "This device's WebView cannot be routed through Tor " +
                        "(PROXY_OVERRIDE is unsupported), so $url was not loaded. Loading it " +
                        "would have gone direct.",
                )
            }
        } else {
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
        if (jsoupResult.success && jsoupResult.content.length >= MIN_CONTENT_LENGTH) {
            return@withContext jsoupResult
        }

        // Step 2: If Jsoup returned empty or very little content, the page is
        // likely JS-rendered. Fall back to WebView which executes JavaScript.
        Log.d(TAG, "Jsoup returned thin content (${jsoupResult.content.length} chars), falling back to WebView for: $url")
        val webViewResult = fetchWithWebView(url, maxLength)

        // Step 3: If Jsoup could not reach the host at all, try circuit recovery
        // once — a new circuit fixes a bad exit, not a device that is off.
        //
        // A LOCAL address is excluded deliberately: recovering a Tor circuit
        // cannot help a direct connection, and doing it wasted a minute of
        // NEWNYM retries before reporting a LAN host as unreachable.
        val host = hostOf(url)
        val route = EgressClassifier.routeForHost(host)
        val recovered = if (
            !jsoupResult.success &&
            route == EgressClassifier.Route.TOR &&
            torManager.isReady.value
        ) {
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
