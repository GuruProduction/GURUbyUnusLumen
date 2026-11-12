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
 * 2. Everything else MUST ride Tor's SOCKS proxy. If Tor has no circuit the
 *    request WAITS for one rather than going direct or being handed back as an
 *    error, because Tor is infrastructure and a dead circuit is a condition to
 *    recover from.
 *
 * The routing decision itself lives in [EgressClassifier], which is pure and
 * unit tested. This object is only the OkHttp plumbing around it.
 *
 * WHY THE OLD FAIL-CLOSED PATH WAS A CRASH. It threw a freshly built
 * ProtocolException every time Tor had no circuit. OkHttp's RouteSelector
 * treats a thrown selector as a route failure, rebuilds itself and asks again,
 * for ever, so the loop compiled regexes and allocated lists on the dispatcher
 * thread until the heap died: 466 suppressed ProtocolExceptions from one fetch,
 * then a 32-byte substring failed. Two changes stop that. The selector no longer
 * throws while recovery is possible, and the one exception it can still throw is
 * a single shared instance rather than a new object per attempt.
 *
 * AN EMPTY OR NULL SELECTOR RESULT IS STILL FORBIDDEN, and that is the other
 * half of the original reasoning: OkHttp reads it as PROXY.NO_PROXY
 * (okhttp3/internal/connection/RouteSelector.kt, resetNextProxy), so an absence
 * would be a direct connection to the public internet with no proxy at all.
 */
object TorEgress {

    private const val TAG = "TorEgress"

    /**
     * How long a public request waits for a circuit before it gives up.
     *
     * Thirty seconds is the starting figure the recovery design called for: a NEWNYM plus a
     * first circuit normally lands in a few seconds, so this is a ceiling rather than an
     * expectation. It is a constant rather than a preference because a request that waited an
     * unbounded time would read as a hang with no cause.
     */
    private const val CIRCUIT_WAIT_MS = 30_000L

    /** How often the wait re-reads the manager's state while it blocks. */
    private const val CIRCUIT_POLL_MS = 100L

    /**
     * How many host classifications are remembered.
     *
     * Bounded on purpose. The key is a hostname, which is attacker-influenced input on any
     * fetch, so an unbounded map keyed by it would be its own leak. Sixty-four covers every
     * endpoint one send touches with room to spare.
     */
    private const val ROUTE_CACHE_ENTRIES = 64

    /**
     * The one exception the selector can still throw, built once.
     *
     * OkHttp attaches every throwable it sees to the failure it is constructing, so building a
     * new ProtocolException per connection attempt is what turned a slow Tor into a dead app.
     * Sharing one instance means the retry loop allocates nothing even on the path that fails.
     *
     * The message names the window rather than a per-call elapsed time, because a shared
     * instance cannot vary its text. The window is a constant, so the sentence stays exact:
     * this is only reachable after the full wait has elapsed.
     */
    private val NO_CIRCUIT: ProtocolException = ProtocolException(
        "Tor has no usable circuit, so this request was not sent. It waited " +
            "${CIRCUIT_WAIT_MS / 1000} seconds for one and Tor did not recover in that window. " +
            "Public traffic never falls back to a direct connection; local and private " +
            "addresses are not affected by this."
    )

    /**
     * Bounded least-recently-used memo of host to route.
     *
     * WHAT IS CACHED AND WHY IT IS SAFE. The value is [EgressClassifier.Route], which is a pure
     * function of the host string: a public name is public whatever Tor's state happens to be,
     * and a private address is local whatever happens to Tor. A cached entry therefore cannot go
     * stale in a way that matters.
     *
     * WHAT IS DELIBERATELY NOT CACHED. Whether a circuit is available. A cached "ready" answer
     * would ride a circuit that had already died, and a cached "not ready" answer would wait
     * after Tor had recovered. Readiness is read fresh on every call, always.
     *
     * This removes the repeated classification work from the selector path: trim, lowercase,
     * strip brackets, the IPv4 scan, the IPv6 probe, the hostname walk and the suffix match.
     * Same host, sixty-four deep, is now an O(1) map hit.
     */
    private val routeCache = object : LinkedHashMap<String, EgressClassifier.Route>(
        ROUTE_CACHE_ENTRIES,
        0.75f,
        true,
    ) {
        override fun removeEldestEntry(
            eldest: MutableMap.MutableEntry<String, EgressClassifier.Route>?,
        ): Boolean = size > ROUTE_CACHE_ENTRIES
    }

    private fun cachedRoute(host: String?): EgressClassifier.Route {
        if (host.isNullOrBlank()) return EgressClassifier.routeForHost(host)
        val key = host.trim().lowercase()
        synchronized(routeCache) {
            routeCache[key]?.let { return it }
        }
        // Classify outside the lock: the classification touches no shared state, and holding the
        // monitor across it would serialise every dispatcher thread for no gain.
        val route = EgressClassifier.routeForHost(host)
        synchronized(routeCache) { routeCache[key] = route }
        return route
    }

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
        cachedRoute(host) == EgressClassifier.Route.TOR

    /** The route this host will take, for reporting and for tests. */
    fun routeFor(host: String?): EgressClassifier.Route = cachedRoute(host)

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
        return cachedRoute(host)
    }

    /** True when Tor currently reports itself ready; false when it does not. */
    fun isReady(): Boolean = torManager?.isReady?.value == true

    /**
     * Ask Tor to rebuild its circuits, once between concurrent callers.
     *
     * Safe to call from the proxy selector, which is not a coroutine: it hands the work to the
     * manager's own scope and returns immediately. The manager coalesces what arrives while a
     * rebuild is already in flight, so a burst of blocked requests produces one NEWNYM.
     */
    fun requestCircuitRebuild() {
        torManager?.requestCircuitRebuildAsync()
    }

    /**
     * Proxy selector applied to every TorEgress client. Per-request lookup so circuit changes
     * and port rebinds from TorService are always honoured.
     *
     * [honoursClearnet] IS FALSE BY DEFAULT, AND THAT DEFAULT IS THE SAFETY PROPERTY.
     * Every caller that already exists keeps the old behaviour: a public host rides Tor or the
     * request fails, whatever the owner's posture says. That matters because the callers here
     * are the model clients, and those carry an API key and the entire conversation. Sending
     * that direct would be the single worst leak this architecture could produce, so a path has
     * to opt IN to the override deliberately rather than inherit it by being written later.
     *
     * The tools that fetch public content and carry no credential pass true. That is the whole
     * distinction the posture exists for.
     */
    fun selector(honoursClearnet: Boolean = false): ProxySelector = object : ProxySelector() {
        override fun select(uri: URI?): List<Proxy> {
            val host = uri?.host
            val manager = torManager

            /** The decision for the state Tor is in right now. Read fresh, never cached. */
            fun decideNow(): EgressDecision = EgressClassifier.decide(
                host = host,
                mode = if (honoursClearnet) currentMode() else FORCE_TOR,
                torReady = isUsable(manager),
            )

            var decision = decideNow()

            // WAIT, DO NOT REFUSE, DO NOT GO DIRECT.
            //
            // A public host with no usable circuit is held here for a bounded window. `select`
            // runs on OkHttp's own dispatcher thread and is allowed to block, so the wait
            // belongs on this thread rather than behind a coroutine bridge: this API is
            // synchronous by contract. Circuits are rebuilt in the background the moment one is
            // lost, so in practice this window is short or not needed at all.
            if (decision.awaitsCircuit && manager != null) {
                awaitUsableCircuit(manager)
                decision = decideNow()
            }

            decision.refusal?.let { sentence ->
                // Nothing in the classifier refuses any more. This stays as the single channel
                // for a torn state that cannot be waited out, because an empty list here would
                // be read by OkHttp as NO_PROXY and would dial clearnet.
                throw ProtocolException(sentence)
            }

            if (decision.route == EgressClassifier.Route.DIRECT) {
                return listOf(Proxy.NO_PROXY)
            }

            if (decision.awaitsCircuit) {
                // The window ran out and Tor still has no circuit. There is nothing to dial and
                // no direct connection is permitted, so the shared instance reports the wait.
                requestCircuitRebuild()
                throw NO_CIRCUIT
            }

            if (manager == null) {
                // No DI graph at all: unit tests and very early boot. Nothing to wait for.
                throw NO_CIRCUIT
            }

            val addr = manager.getSocksProxy().address() as? InetSocketAddress
            if (addr == null) {
                // Ready but no listener address is a torn state rather than a policy decision.
                // Kick a rebuild so the next attempt can succeed.
                requestCircuitRebuild()
                throw NO_CIRCUIT
            }
            val port = if (addr.port > 0) addr.port else 9050
            return listOf(Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", port)))
        }

        override fun connectFailed(uri: URI?, sa: SocketAddress?, ioe: IOException?) {
            // A failed connect is the moment a circuit is most likely to have died, so recovery
            // starts here rather than on the next monitor tick.
            requestCircuitRebuild()
        }
    }

    /**
     * Whether Tor can carry a request right now.
     *
     * TWO CONDITIONS, AND THE SECOND IS CONDITIONAL ON PURPOSE. The daemon must report ready,
     * and when circuit health is actually observable it must also report a built circuit.
     * Health comes from the control connection, so when that connection is absent the health
     * flag reads false for want of a measurement rather than because no circuit exists. Holding
     * a request for the full window on a working Tor, purely because health was unmeasurable,
     * is the failure this distinction prevents.
     */
    private fun isUsable(manager: TorManager?): Boolean {
        if (manager == null) return false
        if (!manager.isReady.value) return false
        if (!manager.canReportCircuitHealth()) return true
        return manager.circuitsHealthy.value
    }

    /**
     * Block, bounded, until Tor can carry a request.
     *
     * SYNCHRONOUS BY DESIGN: this API must not gain a coroutine bridge, so it polls the
     * manager's published state on a short interval instead of suspending.
     *
     * A rebuild is requested once at the top rather than on every poll, so a burst of blocked
     * requests produces one recovery rather than one each. Returns true the moment a circuit is
     * usable and false only when the whole window has elapsed.
     */
    private fun awaitUsableCircuit(manager: TorManager): Boolean {
        if (isUsable(manager)) return true

        manager.requestCircuitRebuildAsync()

        val startedAt = System.currentTimeMillis()
        val deadline = startedAt + CIRCUIT_WAIT_MS
        while (true) {
            if (isUsable(manager)) {
                val waited = System.currentTimeMillis() - startedAt
                if (waited > 1_000L) {
                    android.util.Log.i(
                        TAG,
                        "A public request waited ${waited}ms for a Tor circuit and then proceeded",
                    )
                }
                return true
            }
            val now = System.currentTimeMillis()
            if (now >= deadline) {
                android.util.Log.w(
                    TAG,
                    "A public request waited ${now - startedAt}ms for a Tor circuit and did not get one",
                )
                return false
            }
            val remaining = deadline - now
            try {
                Thread.sleep(minOf(CIRCUIT_POLL_MS, remaining))
            } catch (e: InterruptedException) {
                // The dispatcher thread is being torn down. Put the interrupt back so the caller
                // sees it, and report no circuit rather than claiming success.
                Thread.currentThread().interrupt()
                return false
            }
        }
    }

    /**
     * The posture for a client that has NOT opted into the override: always the lock.
     *
     * This is the posture every existing caller gets, and it is TOR_ONLY rather than AUTO only so
     * the intent reads correctly in the refusal sentence: public traffic rides Tor, full stop.
     * AUTO would produce the same route, but a reader debugging a model call should see that this
     * path is deliberately locked rather than merely defaulted.
     */
    private val FORCE_TOR: com.unuslumen.app.preferences.domain.model.EgressMode =
        com.unuslumen.app.preferences.domain.model.EgressMode.TOR_ONLY

    /**
     * The live posture, for a caller that opted into the override.
     *
     * Resolved per call rather than cached so a /clearnet takes effect on the next request
     * without restarting anything. A missing container means a unit test, and the fallback is
     * the fail-closed posture, which is the only safe guess.
     */
    private fun currentMode(): com.unuslumen.app.preferences.domain.model.EgressMode =
        try {
            val policy: EgressPolicy = org.koin.java.KoinJavaComponent.getKoin()
                .get(EgressPolicy::class)
            policy.current()
        } catch (_: Exception) {
            com.unuslumen.app.preferences.domain.model.EgressMode.DEFAULT
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
