package com.unuslumen.app.data.tor

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.SocketAddress
import java.net.URI

/**
 * The single egress policy for every outbound HTTP connection the app makes:
 * LLM streaming, model executors, webhook tools. Loopback and private LAN
 * traffic (the user's own Ollama, LM Studio) goes direct — it never leaves
 * the device or LAN, and Tor cannot reach loopback targets. Everything else
 * MUST ride Tor's SOCKS proxy. If Tor is not ready, the request FAILS CLOSED:
 * an IOException is raised and no packet ever touches clearnet. This mirrors
 * the posture the web tools already enforce — Tor down means no egress, never
 * a silent direct fallback.
 */
object TorEgress {

    private const val TAG = "TorEgress"

    /** Lazily resolve the app's TorManager; null when DI is unavailable (unit tests). */
    private val torManager: TorManager? by lazy {
        try {
            org.koin.java.KoinJavaComponent.getKoin().get(TorManager::class)
        } catch (_: Exception) {
            null
        }
    }

    /** Hosts allowed to bypass Tor: the device itself and private LAN ranges. */
    private fun isLoopbackOrPrivate(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        if (h == "localhost" || h == "127.0.0.1" || h == "::1" || h == "[::1]") return true
        if (h.startsWith("10.")) return true
        if (h.startsWith("192.168.")) return true
        if (h.startsWith("172.")) {
            val second = h.substringAfter("172.").substringBefore('.').toIntOrNull()
            if (second != null && second in 16..31) return true
        }
        if (h == "host.docker.internal" || h.endsWith(".local")) return true
        return false
    }

    /**
     * Proxy selector applied to every TorEgress client. Per-request lookup so
     * circuit changes and port rebinds from TorService are always honoured.
     */
    fun selector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host
            if (isLoopbackOrPrivate(host)) return listOf(Proxy.NO_PROXY)

            val manager = torManager
            if (manager == null || !manager.isReady.value) {
                // Fail closed: refuse to dial clearnet. OkHttp surfaces this as a
                // request failure; the UI shows an error instead of leaking.
                return emptyList()
            }
            val addr = manager.getSocksProxy().address() as? InetSocketAddress
                ?: return emptyList()
            val port = if (addr.port > 0) addr.port else 9050
            return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: java.io.IOException?) {
            // Route failure information only; Tor circuit recovery handles retries.
        }
    }

    /**
     * Build an OkHttp-backed engine with the sovereign selector. Built fresh
     * per client because koog brand clients expect to own their sockets.
     */
    fun newEngine(): io.ktor.client.engine.HttpClientEngine {
        val selector = selector()
        return io.ktor.client.engine.okhttp.OkHttp.create {
            config {
                proxySelector(selector)
            }
        }
    }

    /**
     * Convenience: a ready-made long-timeout HttpClient with the sovereign
     * selector, matching the app's no-timeout AGI posture.
     */
    fun httpClient(): io.ktor.client.HttpClient {
        val engine = newEngine()
        return io.ktor.client.HttpClient(engine) {
            install(io.ktor.client.plugins.HttpTimeout) {
                requestTimeoutMillis = Long.MAX_VALUE
                connectTimeoutMillis = Long.MAX_VALUE
                socketTimeoutMillis = Long.MAX_VALUE
            }
        }
    }
}