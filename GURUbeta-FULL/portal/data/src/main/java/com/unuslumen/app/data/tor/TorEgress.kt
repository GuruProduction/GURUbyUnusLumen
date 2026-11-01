// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tor

import java.io.IOException
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProxySelector
import java.net.ProtocolException
import java.net.SocketAddress
import java.net.URI

/**
 * The single egress policy for every outbound HTTP connection the app makes:
 * LLM streaming, model executors, webhook tools, and the named HTTP tools.
 *
 * Two rules, both owned here so no caller has to remember a flag:
 *
 * 1. Loopback, the local network, link-local, carrier-grade NAT and mDNS names
 *    go DIRECT. Tor cannot reach them, and asking it to was issue #215: every
 *    LAN tool failed with an empty result that read like a dead device.
 *
 * 2. Everything else MUST ride Tor's SOCKS proxy, and if Tor is not ready the
 *    request FAILS CLOSED with an exception rather than a direct connection.
 *
 * The routing decision itself lives in [EgressClassifier], which is pure and
 * unit tested. This object is only the OkHttp plumbing around it.
 *
 * WHY THE FAIL-CLOSED PATH THROWS. The previous version returned an empty
 * proxy list when Tor was down, under a comment claiming that refused clearnet.
 * It did the opposite: OkHttp's own RouteSelector treats a null or empty
 * selector result as PROXY.NO_PROXY (okhttp3/internal/connection/
 * RouteSelector.kt, resetNextProxy), so "fail closed" was a direct connection
 * to the public internet with no proxy at all. Throwing a ProtocolException is
 * what actually stops it, because RetryAndFollowUpInterceptor treats a
 * ProtocolException as unrecoverable, so the call fails instead of retrying
 * around the policy.
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

    /**
     * Whether a host requires a live Tor circuit before it can be reached.
     * Local and LAN endpoints never do. Exposed so the send path can wait for
     * Tor instead of firing a request that the policy will refuse.
     */
    fun needsTorFor(host: String?): Boolean =
        EgressClassifier.routeForHost(host) == EgressClassifier.Route.TOR

    /** The route this host will take, for reporting and for tests. */
    fun routeFor(host: String?): EgressClassifier.Route = EgressClassifier.routeForHost(host)

    /**
     * The route for a URL, deciding on the host exactly as the selector will.
     * Convenience for callers that hold a URL string rather than a host.
     */
    fun routeForUrl(url: String?): EgressClassifier.Route {
        val host = try {
            url?.let { URI(it).host }
        } catch (_: Exception) {
            null
        }
        return EgressClassifier.routeForHost(host)
    }

    /**
     * Proxy selector applied to every TorEgress client. Per-request lookup so
     * circuit changes and port rebinds from TorService are always honoured.
     */
    fun selector(): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host
            when (EgressClassifier.routeForHost(host)) {
                EgressClassifier.Route.DIRECT -> return listOf(Proxy.NO_PROXY)
                EgressClassifier.Route.TOR -> Unit
            }

            val manager = torManager
            if (manager == null || !manager.isReady.value) {
                // Fail closed and mean it. See the class comment: an empty list
                // here would be read by OkHttp as NO_PROXY and would dial
                // clearnet, which is the exact leak this replaced.
                throw ProtocolException(
                    "Tor is not ready, so ${host ?: "this host"} was not contacted. " +
                        "Public requests ride Tor and will not fall back to a direct connection. " +
                        "Local and private addresses are not affected by this."
                )
            }
            val addr = manager.getSocksProxy().address() as? InetSocketAddress
                ?: throw ProtocolException(
                    "Tor reported ready but its SOCKS listener has no address, so ${host ?: "this host"} " +
                        "was not contacted rather than being sent direct."
                )
            val port = if (addr.port > 0) addr.port else 9050
            return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
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
