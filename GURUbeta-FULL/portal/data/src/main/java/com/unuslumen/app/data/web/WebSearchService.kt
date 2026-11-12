// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.web

import android.util.Log
import com.unuslumen.app.data.tor.TorManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jsoup.Jsoup
import org.koin.core.annotation.Factory
import java.io.BufferedReader
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL
import java.net.URLEncoder

@Factory
class WebSearchService(
    private val torManager: TorManager
) {

    companion object {
        private const val TAG = "WebSearch"

        /** How long a search waits for a circuit before giving up on it. */
        private const val CIRCUIT_WAIT_MS = 30_000L

        /** How often the wait re-reads Tor's readiness. */
        private const val CIRCUIT_POLL_MS = 100L
    }

    data class SearchResult(
        val title: String,
        val url: String,
        val snippet: String,
        val engine: String
    )

    private val json = Json { ignoreUnknownKeys = true }

    private fun currentEgressMode(): com.unuslumen.app.preferences.domain.model.EgressMode =
        try {
            val policy: com.unuslumen.app.data.tor.EgressPolicy =
                org.koin.java.KoinJavaComponent.getKoin()
                    .get(com.unuslumen.app.data.tor.EgressPolicy::class)
            policy.current()
        } catch (_: Exception) {
            com.unuslumen.app.preferences.domain.model.EgressMode.DEFAULT
        }

    /**
     * The proxy to use, or null for a direct connection, and whether that is an exposure.
     *
     * WHAT WAS WRONG, TWICE. This first returned a SOCKS proxy on port 9050 whenever Tor was
     * not ready, under a log line claiming it was "trying direct". It was not direct: it was a
     * SOCKS connection to a listener that is not running, which fails every time. Then it
     * returned a refusal, which told a person to change a setting because a circuit was missing.
     *
     * Now it WAITS for the circuit, because Tor is infrastructure that recovers on its own. The
     * posture still decides one thing: with clearnet on, an unavailable circuit is not a reason
     * to hold a search, so the override goes direct and says so.
     */
    private fun proxyForSearch(): Pair<Proxy?, Boolean> {
        val mode = currentEgressMode()
        if (awaitCircuit()) return torManager.getSocksProxy() to false
        return when (mode) {
            com.unuslumen.app.preferences.domain.model.EgressMode.CLEARNET -> null to true
            com.unuslumen.app.preferences.domain.model.EgressMode.AUTO,
            com.unuslumen.app.preferences.domain.model.EgressMode.TOR_ONLY -> null to false
        }
    }

    /**
     * Block, bounded, until Tor is ready, and report whether it is.
     *
     * Every caller is already inside `withContext(Dispatchers.IO)`, so blocking costs a worker
     * thread that has nothing else to do. The rebuild is coalesced by the manager, so a burst of
     * search attempts produces one recovery rather than one each.
     */
    private fun awaitCircuit(): Boolean {
        if (torManager.isReady.value) return true
        torManager.requestCircuitRebuildAsync()
        val deadline = System.currentTimeMillis() + CIRCUIT_WAIT_MS
        while (System.currentTimeMillis() < deadline) {
            if (torManager.isReady.value) {
                Log.i(TAG, "A search waited for a Tor circuit and then proceeded")
                return true
            }
            try {
                Thread.sleep(CIRCUIT_POLL_MS)
            } catch (e: InterruptedException) {
                Thread.currentThread().interrupt()
                return torManager.isReady.value
            }
        }
        Log.w(TAG, "A search waited ${CIRCUIT_WAIT_MS}ms for a Tor circuit and did not get one")
        return torManager.isReady.value
    }

    /**
     * Fetch raw response body routed by the posture, using HttpURLConnection.
     * Returns the exact response body as a string — no HTML wrapping, no parsing.
     * Use this for JSON endpoints where Jsoup would corrupt the response.
     */
    private fun fetchRawThroughTor(url: String): String? {
        return try {
            Log.d(TAG, "Raw fetch: $url")
            val (proxy, unproxied) = proxyForSearch()
            if (unproxied) {
                Log.w(TAG, "Clearnet is on, so this search request goes direct rather than via Tor")
            }
            val conn = when (proxy) {
                null -> URL(url).openConnection(Proxy.NO_PROXY) as HttpURLConnection
                else -> URL(url).openConnection(proxy) as HttpURLConnection
            }
            conn.requestMethod = "GET"
            conn.setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            conn.setRequestProperty("Accept", "*/*")
            conn.instanceFollowRedirects = true
            conn.connect()
            val responseCode = conn.responseCode
            Log.d(TAG, "Raw response from $url — HTTP $responseCode")
            val stream = if (responseCode in 200..299) conn.inputStream else conn.errorStream
            val body = BufferedReader(InputStreamReader(stream)).readText()
            conn.disconnect()
            Log.d(TAG, "Raw body from $url — ${body.length} chars")
            body
        } catch (e: Exception) {
            Log.w(TAG, "Raw fetch failed for $url: ${e.message}")
            null
        }
    }

    /**
     * A Jsoup connection routed by the posture.
     *
     * Jsoup REJECTS A NULL PROXY rather than defaulting to direct, so a clearnet request must
     * leave the proxy off the connection entirely rather than be handed null. That distinction
     * is why this builds the request in one place instead of duplicating it in each fetcher.
     */
    private fun jsoupConnection(url: String): org.jsoup.Connection? {
        val (proxy, _) = proxyForSearch()
        val connection = Jsoup.connect(url)
            .userAgent("Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36")
            .ignoreContentType(true)
            .ignoreHttpErrors(true)
            .followRedirects(true)
        if (proxy != null) connection.proxy(proxy)
        return connection
    }

    private fun fetchThroughTor(url: String): String? {
        return try {
            Log.d(TAG, "Fetching: $url")
            val doc = jsoupConnection(url)?.get() ?: return null
            Log.d(TAG, "Got response from $url — ${doc.body().text().length} chars")
            doc.html()
        } catch (e: Exception) {
            Log.w(TAG, "Fetch failed for $url: ${e.message}")
            null
        }
    }

    private fun fetchHtmlThroughTor(url: String): org.jsoup.nodes.Document? {
        return try {
            Log.d(TAG, "Fetching HTML: $url")
            val doc = jsoupConnection(url)?.get() ?: return null
            Log.d(TAG, "Got HTML from $url")
            doc
        } catch (e: Exception) {
            Log.w(TAG, "HTML fetch failed for $url: ${e.message}")
            null
        }
    }

    // SearXNG instances — all routed through Tor, traffic is private
    // Ordered by reliability through Tor. Working instances first, then fallbacks.
    private val searxngInstances = listOf(
        "https://search.sethforprivacy.com",
        "https://searx.be",
        "https://search.ononoki.org",
        "https://searx.tiekoetter.com",
        "https://search.sapti.me",
        "https://searx.si",
        "https://search.inetol.net",
        "https://opnxng.com",
        "https://search.zzls.xyz",
        "https://searx.fmac.xyz",
        "https://searx.work",
        "https://search.mistac.net",
        "https://searx.cat"
    )

    private val braveOnion = "https://search.brave4u7jddbv7cyviptqjc7jusxh72uik7zt6adtckl5f4nwy2v72qd.onion"
    private val ahmiaOnion = "http://juhanurmihxlp77nkq76byazcldy2hlmovfu2epvl5ankdibsot4csyd.onion"
    private val torchOnion = "http://torchdeedp3i2jigzjdmfpn5ttjhthh5wbmda2rr3jvqjg5p77c54dqd.onion"

    suspend fun search(query: String, maxResults: Int = 10): List<SearchResult> {
        Log.d(TAG, "Searching for: $query (Tor ready: ${torManager.isReady.value})")

        val result = withContext(Dispatchers.IO) {
                // SearXNG clearnet instances first — they aggregate real search engines
                // (Google, Bing, DuckDuckGo) and return mainstream results.
                searxngSearch(query, maxResults)?.let {
                    Log.d(TAG, "SearXNG hit — ${it.size} results")
                    return@withContext it
                }

                // Brave onion as fallback — clearnet results via Tor exit node
                braveOnionSearch(query, maxResults)?.let {
                    Log.d(TAG, "Brave onion hit — ${it.size} results")
                    return@withContext it
                }

                // Ahmia and Torch are dark web indexers — last resort only.
                // They surface .onion sites which are rarely relevant to
                // mainstream factual queries but better than no results.
                ahmiaOnionSearch(query, maxResults)?.let {
                    Log.d(TAG, "Ahmia hit — ${it.size} results")
                    return@withContext it
                }

                torchOnionSearch(query, maxResults)?.let {
                    Log.d(TAG, "Torch hit — ${it.size} results")
                    return@withContext it
                }

                if (torManager.isReady.value) {
                    Log.d(TAG, "All engines failed, requesting new Tor circuits")
                    val recovered = torManager.requestNewCircuits()
                    if (recovered) {
                        searxngSearch(query, maxResults)?.let { return@withContext it }
                        braveOnionSearch(query, maxResults)?.let { return@withContext it }
                    }
                }

                emptyList()
        }

        Log.d(TAG, "Search complete — ${result.size} results")
        return result
    }

    private fun searxngSearch(query: String, maxResults: Int): List<SearchResult>? {
        val encodedQuery = URLEncoder.encode(query, "UTF-8")
        for (instance in searxngInstances) {
            try {
                val url = "$instance/search?q=$encodedQuery&format=json&categories=general"
                val raw = fetchRawThroughTor(url)
                if (raw == null) continue

                val results = mutableListOf<SearchResult>()
                try {
                    val jsonObj = json.parseToJsonElement(raw).jsonObject
                    val resultsArray = jsonObj["results"]?.jsonArray
                    if (resultsArray != null) {
                        for (item in resultsArray.take(maxResults)) {
                            val obj = item.jsonObject
                            val title = obj["title"]?.jsonPrimitive?.content ?: ""
                            val resultUrl = obj["url"]?.jsonPrimitive?.content ?: ""
                            val snippet = obj["content"]?.jsonPrimitive?.content
                                ?: obj["snippet"]?.jsonPrimitive?.content ?: ""
                            if (title.isNotBlank() && resultUrl.isNotBlank()) {
                                results.add(SearchResult(title, resultUrl, snippet, "searxng"))
                            }
                        }
                    }
                } catch (e: Exception) {
                    // Not JSON — fall back to HTML parsing via Jsoup
                    Log.d(TAG, "SearXNG $instance returned non-JSON, trying HTML parse")
                    val doc = Jsoup.parse(raw)
                    val elements = doc.select("div.result, article.result, .result")
                    for (element in elements) {
                        if (results.size >= maxResults) break
                        val title = element.selectFirst("h3 a, a.result-url, a")?.text()?.trim() ?: ""
                        val resultUrl = element.selectFirst("h3 a, a.result-url, a")?.attr("href")?.trim() ?: ""
                        val snippet = element.selectFirst("p, .snippet, .content")?.text()?.trim() ?: ""
                        if (title.isNotBlank() && resultUrl.isNotBlank()) {
                            results.add(SearchResult(title, resultUrl, snippet, "searxng-html"))
                        }
                    }
                }

                Log.d(TAG, "SearXNG $instance: ${results.size} results")
                if (results.isNotEmpty()) return results
            } catch (e: Exception) {
                Log.v(TAG, "SearXNG $instance failed: ${e.message}")
            }
        }
        return null
    }

    private fun braveOnionSearch(query: String, maxResults: Int): List<SearchResult>? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val doc = fetchHtmlThroughTor("$braveOnion/search?q=$encodedQuery") ?: return null
            return parseBraveResults(doc, maxResults)
        } catch (e: Exception) {
            return null
        }
    }

    private fun ahmiaOnionSearch(query: String, maxResults: Int): List<SearchResult>? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val doc = fetchHtmlThroughTor("$ahmiaOnion/search/?q=$encodedQuery") ?: return null
            val results = mutableListOf<SearchResult>()
            val elements = doc.select("li.result, div.result")
            for (element in elements) {
                if (results.size >= maxResults) break
                val title = element.select("h4, h3, a").first()?.text()?.trim() ?: ""
                val url = element.select("a").attr("href").trim()
                val snippet = element.select("p, .description").text().trim()
                if (title.isNotBlank() && url.isNotBlank()) {
                    results.add(SearchResult(title, url, snippet, "ahmia"))
                }
            }
            return if (results.isNotEmpty()) results else null
        } catch (e: Exception) {
            return null
        }
    }

    private fun torchOnionSearch(query: String, maxResults: Int): List<SearchResult>? {
        try {
            val encodedQuery = URLEncoder.encode(query, "UTF-8")
            val doc = fetchHtmlThroughTor("$torchOnion/search?query=$encodedQuery") ?: return null
            val results = mutableListOf<SearchResult>()
            val elements = doc.select("dl.result, div.result")
            for (element in elements) {
                if (results.size >= maxResults) break
                val title = element.select("dt a, h5 a, a").first()?.text()?.trim() ?: ""
                val url = element.select("dt a, h5 a, a").attr("href").trim()
                val snippet = element.select("dd, p").text().trim()
                if (title.isNotBlank() && url.isNotBlank()) {
                    results.add(SearchResult(title, url, snippet, "torch"))
                }
            }
            return if (results.isNotEmpty()) results else null
        } catch (e: Exception) {
            return null
        }
    }

    private fun parseBraveResults(doc: org.jsoup.nodes.Document, maxResults: Int): List<SearchResult>? {
        val results = mutableListOf<SearchResult>()
        // Brave Search uses Svelte-rendered HTML with result links having class "l1"
        // <a href="URL" target="_self" class="svelte-14r20fy l1">
        // Titles are in <div class="title search-snippet-title" title="PAGE TITLE">
        val links = doc.select("a.l1")
        for (link in links) {
            if (results.size >= maxResults) break
            val url = link.attr("href").trim()
            if (url.isEmpty() || url.contains("search.brave.com") || url.contains("brave.com/search") ||
                url.contains("cdn.search.brave.com") || url.contains("imgs.search.brave.com")) continue
            // Find the title — look for a sibling or parent with search-snippet-title
            val titleEl = link.parent()?.selectFirst(".search-snippet-title, .title")
            val title = titleEl?.attr("title")?.takeIf { it.isNotBlank() }
                ?: titleEl?.text()?.trim()
                ?: link.text().trim()
            // Look for snippet description nearby
            val snippetEl = link.parent()?.parent()?.selectFirst(".snippet-description, p")
            val snippet = snippetEl?.text()?.trim() ?: ""
            if (title.isNotBlank() && url.isNotBlank()) {
                results.add(SearchResult(title, url, snippet, "brave-onion"))
            }
        }
        return if (results.isNotEmpty()) results else null
    }
}
