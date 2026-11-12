// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tor

import com.unuslumen.app.preferences.domain.model.EgressMode
import java.io.IOException
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.URL

/**
 * The only way an outbound HTTP connection leaves this app.
 *
 * WHY A FACTORY RATHER THAN EACH TOOL ASKING THE CLASSIFIER. The defects this replaces were
 * all one shape: a caller that made its own connection and forgot, or never knew, that there
 * was a routing rule. One path returned a bare `openConnection()` for a public host, and
 * because nothing in the app installs a default ProxySelector that branch was a direct
 * clearnet connection wearing the label of a classified one. Another made its own connection
 * with no check at all. Both read as working code. A rule every caller must remember is not
 * a rule.
 *
 * So the connection is built here and the decision is made here, and a caller receives one of
 * two things: a connection already routed correctly, or the sentence that stopped it. There
 * is no third outcome, because "a connection that might be proxied" is exactly the ambiguity
 * that leaked.
 */
object EgressHttp {

    /**
     * Build a routed connection for [url], or refuse with a sentence.
     *
     * [torProxy] is Tor's SOCKS proxy when a circuit exists and null when it does not. It is
     * passed in rather than looked up so this object stays free of Koin and can be driven
     * from a test with a fake proxy.
     *
     * [method] is applied here rather than by the caller, so a refused request never
     * half-configures a connection that some later path might still use.
     */
    fun open(
        url: String,
        mode: EgressMode,
        torProxy: Proxy?,
        forcesTor: Boolean = false,
        method: String = "GET",
    ): Outcome {
        val parsed = try {
            URL(url)
        } catch (_: Exception) {
            return unavailable(mode, "That is not a usable URL, so nothing was sent: $url")
        }

        val decision = EgressClassifier.decide(
            host = parsed.host,
            mode = mode,
            torReady = torProxy != null,
            forcesTor = forcesTor,
        )

        // A Tor route with no proxy cannot be dialled, and this factory has no way to wait:
        // it is synchronous, it holds no TorManager, and its callers ask it to build a
        // connection rather than to hold one. So the caller gets the sentence and can retry.
        //
        // A CALLER THAT CAN WAIT SHOULD. The proxy selector does, for every OkHttp path, which
        // is why a missing circuit is a delay rather than an error in the chat and web tools.
        // This branch exists for the plain-URL callers that predate that and cannot block.
        if (decision.route == EgressClassifier.Route.TOR && torProxy == null) {
            return unavailable(
                mode,
                "Tor has no circuit ready yet, so this request was not sent. It rides Tor by " +
                    "policy and will never fall back to a direct connection. Try again once Tor " +
                    "has a circuit; nothing here needs a setting changed.",
            )
        }

        val connection = try {
            when (decision.route) {
                // An explicit NO_PROXY rather than an omitted proxy. Omitting it defers to
                // the JVM's default selector, and a default installed anywhere in the app
                // would silently re-route this. Naming the intent is the difference between a
                // direct request and a request that merely happens to be direct today.
                EgressClassifier.Route.DIRECT ->
                    parsed.openConnection(Proxy.NO_PROXY) as HttpURLConnection

                EgressClassifier.Route.TOR ->
                    parsed.openConnection(torProxy) as HttpURLConnection
            }
        } catch (error: IOException) {
            return Outcome.Refused(
                "The connection to ${parsed.host} could not be built: " +
                    "${error.message ?: "no detail"}",
                decision,
            )
        }

        connection.requestMethod = method
        return Outcome.Connected(connection, decision)
    }

    private fun unavailable(mode: EgressMode, sentence: String): Outcome.Refused =
        Outcome.Refused(sentence, EgressDecision.unavailable(mode = mode, sentence = sentence))

    /** Either a routed, usable connection or the sentence that stopped it. */
    sealed interface Outcome {

        /** A connection already routed and configured. The caller reads it and disconnects. */
        data class Connected(
            val connection: HttpURLConnection,
            val decision: EgressDecision,
        ) : Outcome

        /** Nothing was sent. [sentence] says why, in a form a model can act on. */
        data class Refused(
            val sentence: String,
            val decision: EgressDecision,
        ) : Outcome
    }

    /**
     * The SOCKS listener as a proxy value.
     *
     * The loopback address is named explicitly rather than left as `localhost`, because a
     * lookup of the proxy itself is one more thing that can fail before the request starts.
     */
    fun socksProxy(port: Int): Proxy =
        Proxy(Proxy.Type.SOCKS, InetSocketAddress("127.0.0.1", if (port > 0) port else 9050))

    /**
     * The note a caller puts in a result when a public request was allowed out unproxied.
     *
     * Empty when the request was not an exposure, so an ordinary result carries no noise.
     */
    fun exposureNote(decision: EgressDecision): String =
        if (decision.unproxied) {
            "This request went direct on the device's own connection, not through Tor, because " +
                "clearnet is on. Turn it off with /clearnet off to put public requests back " +
                "through Tor."
        } else {
            ""
        }
}
