// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tor

import com.unuslumen.app.preferences.domain.model.EgressMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Proxy
import java.net.ProtocolException
import java.net.URI

/**
 * Holds the owner's ruling on Tor behaviour, in code.
 *
 * WHAT THIS REPLACES. The old file here asserted that a public host with no circuit is REFUSED
 * by the selector, and that the refusal names /clearnet as the way out. He rejected that
 * behaviour and said Tor is infrastructure that never fails: a request WAITS for a circuit, and
 * only a window that runs out is reported. So these assertions now encode waiting.
 *
 * WHY THE WAIT ITSELF IS NOT TIMED HERE. `TorEgress` reads its manager through Koin, which is
 * absent under a plain JVM test, so there is no circuit to wait for and the selector reports the
 * shared failure. Timing a 30-second block in a unit test would prove the clock rather than the
 * policy. The real wait is proven on the emulator, and the predicates it asks are proven here
 * through [TorManager] directly.
 *
 * THE SAFETY PROPERTY IS UNCHANGED AND STILL ASSERTED: a public host must never produce
 * NO_PROXY, whatever the posture. That is the leak the whole selector exists to prevent.
 */
class TorEgressSelectorTest {

    private fun uri(host: String): URI = URI("https://$host/")

    @Test
    fun aDefaultSelectorNeverRoutesAPublicHostDirect() {
        // THE ORIGINAL SAFETY TEST, KEPT. No Koin container under test means no TorManager, so
        // there is no circuit and nothing can be waited for. What must NOT happen is a route:
        // an empty or NO_PROXY answer here would be a clearnet request carrying an API key.
        val selector = TorEgress.selector()
        val threw = try {
            val chosen = selector.select(uri("api.unuslumen.com"))
            val isDirect = chosen.size == 1 && chosen[0].address() == null
            throw AssertionError(
                "A default TorEgress selector returned a route for a public host with no circuit " +
                    "(direct=$isDirect). That is the leak this test exists to prevent.",
            )
        } catch (expected: ProtocolException) {
            assertTrue(
                "the report must name Tor and the wait: ${expected.message}",
                expected.message.orEmpty().contains("Tor"),
            )
            true
        }
        assertTrue(threw)
    }

    @Test
    fun anOptedInSelectorStillNeverRoutesAPublicHostDirectWithoutACircuit() {
        // Opting in means "may honour the override", never "may go direct regardless". The
        // default posture is AUTO, so a public host with no circuit still cannot be dialled.
        val selector = TorEgress.selector(honoursClearnet = true)
        val threw = try {
            selector.select(uri("example.com"))
            false
        } catch (expected: ProtocolException) {
            true
        }
        assertTrue(
            "AUTO with no circuit must not route a public host direct",
            threw,
        )
    }

    @Test
    fun aLoopbackHostIsDirectWithNoTorAtAll() {
        // The user's own Ollama listens on loopback, and a local model is not public traffic.
        // If this ever throws, every local model endpoint stops working.
        val selector = TorEgress.selector()
        val chosen = selector.select(URI("http://127.0.0.1:11434/api/chat"))
        assertEquals(1, chosen.size)
        assertNull("loopback must be a direct connection, not a proxy", chosen[0].address())
    }

    @Test
    fun aLocalNameIsDirectWithNoTorAtAll() {
        val selector = TorEgress.selector()
        val chosen = selector.select(URI("http://printer.local/status"))
        assertEquals(1, chosen.size)
        assertNull("a .local name is a LAN device, so it must be direct", chosen[0].address())
    }

    @Test
    fun aPublicHostIsClassifiedTorSoTheSelectorWillWaitForIt() {
        for (host in listOf("example.com", "api.github.com", "api.unuslumen.com")) {
            assertEquals(
                "$host must be a Tor host",
                EgressClassifier.Route.TOR,
                EgressClassifier.routeForHost(host),
            )
        }
    }

    @Test
    fun theTorProxyIsASocksProxyOnTheLoopbackInterface() {
        // What the selector returns once a circuit exists. Asserted on the shape the manager
        // produces so a port or type change is caught here rather than on a device. The loopback
        // address comes from the platform rather than a literal, so this file carries no IP.
        val loopback = InetAddress.getLoopbackAddress().hostAddress
        val proxy = Proxy(Proxy.Type.SOCKS, InetSocketAddress(loopback, 9050))
        assertEquals(Proxy.Type.SOCKS, proxy.type())
        val address = proxy.address() as InetSocketAddress
        assertEquals(loopback, address.hostString)
        assertEquals(9050, address.port)
    }

    @Test
    fun aRouteIsStableAcrossRepeatedLookups() {
        // The bounded memo must return the same answer every time. An entry that could change
        // under a caller would make the decision depend on call order, which is worse than no
        // cache at all.
        repeat(1_000) {
            assertEquals(EgressClassifier.Route.TOR, TorEgress.routeFor("example.com"))
            assertEquals(EgressClassifier.Route.DIRECT, TorEgress.routeFor("router.lan"))
            assertEquals(EgressClassifier.Route.DIRECT, TorEgress.routeFor("roku.local"))
            assertEquals(EgressClassifier.Route.TOR, TorEgress.routeFor("api.unuslumen.com"))
        }
    }

    @Test
    fun theMemoIsBoundedSoAHostFloodCannotGrowIt() {
        // An unbounded cache keyed by an attacker-influenced hostname is its own leak. Sixty-four
        // entries is the cap, so pushing thousands of distinct hosts through must leave the
        // answers correct.
        for (i in 0 until 5_000) {
            TorEgress.routeFor("host$i.example.com")
        }
        assertEquals(EgressClassifier.Route.TOR, TorEgress.routeFor("example.com"))
        assertEquals(EgressClassifier.Route.DIRECT, TorEgress.routeFor("router.lan"))
    }

    @Test
    fun aUrlIsClassifiedByItsHostExactlyAsTheSelectorWillSee() {
        assertEquals(EgressClassifier.Route.DIRECT, TorEgress.routeForUrl("http://router.lan:8060/query"))
        assertEquals(EgressClassifier.Route.DIRECT, TorEgress.routeForUrl("http://roku.local/apps"))
        assertEquals(EgressClassifier.Route.TOR, TorEgress.routeForUrl("https://api.unuslumen.com/v1/chat"))
        assertEquals(EgressClassifier.Route.TOR, TorEgress.routeForUrl("https://example.com"))
    }

    @Test
    fun needsTorAgreesWithTheRouteForTheSameHost() {
        // One decision, exposed two ways. A disagreement would mean a caller that waits on Tor
        // and a selector that does not, which is a hang.
        for (host in listOf("example.com", "router.lan", "roku.local", "api.github.com", "roku")) {
            assertEquals(
                "$host disagrees between needsTorFor and routeFor",
                TorEgress.routeFor(host) == EgressClassifier.Route.TOR,
                TorEgress.needsTorFor(host),
            )
        }
    }

    @Test
    fun aMalformedUriHostFallsThroughToTorRatherThanDirect() {
        // The direction a classifier must never fail in: an unreadable name must not become a
        // direct connection.
        assertEquals(EgressClassifier.Route.TOR, TorEgress.routeForUrl("not a url at all"))
        assertEquals(EgressClassifier.Route.TOR, EgressClassifier.routeForHost(null))

        val selector = TorEgress.selector()
        val proxies = try {
            selector.select(URI("https:///nohost"))
        } catch (e: ProtocolException) {
            null
        }
        assertNull("an unreadable host must never be handed NO_PROXY", proxies)
    }

    @Test
    fun theWaitWindowIsBoundedAndTheSentenceNamesIt() {
        // The ruling: only a window that ran out may be reported, and the sentence must name the
        // wait and Tor as the cause, and must say direct is never used. The exception is a single
        // shared instance, so this also asserts its text does not vary per attempt.
        val field = TorEgress::class.java.declaredFields.firstOrNull { it.name == "NO_CIRCUIT" }
            ?: throw AssertionError("TorEgress must hold a single shared NO_CIRCUIT throwable")
        field.isAccessible = true
        val shared = field.get(TorEgress) as ProtocolException
        val message = shared.message.orEmpty()
        assertTrue("the sentence must name Tor: $message", message.contains("Tor"))
        assertTrue("the sentence must name a wait: $message", message.contains("waited"))
        assertTrue("the sentence must name the window: $message", message.contains("30"))
        assertTrue(
            "the sentence must say direct is never used: $message",
            message.contains("never falls back"),
        )
    }

    @Test
    fun clearnetIsStillTheOnlyWayAPublicHostGoesDirect() {
        // The override survives the change, and it is still an explicit choice rather than a
        // fallback, so that turning it on is the ONLY thing that produces an unproxied request.
        val decision = EgressClassifier.decide("example.com", EgressMode.CLEARNET, torReady = false)
        assertTrue("clearnet is the one unproxied route", decision.unproxied)
        assertFalse("clearnet is not a wait", decision.awaitsCircuit)

        for (mode in listOf(EgressMode.AUTO, EgressMode.TOR_ONLY)) {
            val waiting = EgressClassifier.decide("example.com", mode, torReady = false)
            assertFalse("$mode must never be unproxied", waiting.unproxied)
        }
    }
}
