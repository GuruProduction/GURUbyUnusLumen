// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tor

import com.unuslumen.app.preferences.domain.model.EgressMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The truth table for the egress posture.
 *
 * WHY THIS FILE EXISTS SEPARATELY from [EgressClassifierTest]. That one proves the ADDRESS rules,
 * which are a property of networking and do not change. This proves the POSTURE rules, which are
 * a property of the owner's decision and are the part that can leak if it is wrong.
 *
 * The four claims under test, each of which a caller depends on:
 *
 *  1. A private host is direct under every posture, because Tor cannot reach it.
 *  2. A public host rides Tor under AUTO, and with no circuit it WAITS for one. It is never sent
 *     direct and it is never refused. That is the owner's ruling: Tor is infrastructure which
 *     never fails, so a dead circuit is a bug to recover from rather than a condition to report.
 *  3. CLEARNET sends a public host direct and marks it unproxied, so no caller can report an
 *     exposed request as private.
 *  4. A credential-bearing path ignores CLEARNET entirely, under every posture.
 *
 * CLAIM 2 WAS PREVIOUSLY A REFUSAL AND THE TESTS SAID SO. He rejected that behaviour outright,
 * so the assertions are rewritten rather than deleted: the property being protected is unchanged
 * (a public host never goes direct without a circuit), only the way it is honoured has changed.
 *
 * Every case is driven through the real [EgressClassifier.decide], not a copy of its logic,
 * because a test that reimplements the rule proves only that the test agrees with itself.
 */
class EgressDecisionTest {

    private fun decide(
        host: String?,
        mode: EgressMode,
        torReady: Boolean,
        forcesTor: Boolean = false,
    ): EgressDecision = EgressClassifier.decide(host, mode, torReady, forcesTor)

    // -----------------------------------------------------------------
    // A private host is direct, whatever the posture says
    // -----------------------------------------------------------------

    /**
     * The private hosts used here are local NAMES rather than dotted addresses.
     *
     * WHY NOT AN ADDRESS. The privacy gate refuses every address outside the three
     * documentation ranges, because a real one is a fingerprint and the gate cannot tell a
     * fixture from his television. A documentation range would prove the wrong branch, since
     * 192.0.2.x is classified public. So local names carry the case: they reach the same
     * DIRECT branch through the same rule, and they publish nothing.
     */
    @Test
    fun aPrivateHostIsDirectUnderEveryPosture() {
        for (mode in EgressMode.entries) {
            for (host in listOf("printer.local", "roku", "nas.lan", "127.0.0.1", "device.home")) {
                val decision = decide(host, mode, torReady = true)
                assertEquals(
                    "$host must be direct under $mode, because Tor cannot reach private space",
                    EgressClassifier.Route.DIRECT,
                    decision.route,
                )
                assertFalse(
                    "$host under $mode is not an exposure, so unproxied must be false",
                    decision.unproxied,
                )
                assertNull(decision.refusal)
            }
        }
    }

    @Test
    fun aPrivateHostIsDirectEvenWithNoTor() {
        // The LAN must not care whether a circuit exists. Requiring one was issue #215.
        val decision = decide("printer.local", EgressMode.AUTO, torReady = false)
        assertEquals(EgressClassifier.Route.DIRECT, decision.route)
        assertNull("a LAN request must never be refused for want of Tor", decision.refusal)
    }

    // -----------------------------------------------------------------
    // The public host under Tor-shaped postures
    // -----------------------------------------------------------------

    @Test
    fun aPublicHostRidesTorWhenACircuitExists() {
        for (mode in listOf(EgressMode.AUTO, EgressMode.TOR_ONLY)) {
            val decision = decide("example.com", mode, torReady = true)
            assertEquals(EgressClassifier.Route.TOR, decision.route)
            assertFalse(decision.unproxied)
            assertNull(decision.refusal)
        }
    }

    @Test
    fun aPublicHostWaitsRatherThanLeakingWhenThereIsNoCircuit() {
        // THE ANTI-LEAK GUARANTEE, IN THE FORM HE RULED ON. If this ever returns DIRECT with
        // unproxied false, a public request goes out on the device's own address while the app
        // believes it is private. If it ever REFUSES, the request is lost to a condition that
        // recovery would have fixed, which is the behaviour he rejected.
        for (mode in listOf(EgressMode.AUTO, EgressMode.TOR_ONLY)) {
            val decision = decide("example.com", mode, torReady = false)
            assertTrue("$mode must wait for a circuit, not refuse", decision.awaitsCircuit)
            assertFalse("$mode must not mark a waiting request as refused", decision.refused)
            assertNull("there is no refusal path any more", decision.refusal)
            assertEquals(
                "a waiting request is still destined for Tor and never direct",
                EgressClassifier.Route.TOR,
                decision.route,
            )
            assertFalse("a waiting request is not an exposure", decision.unproxied)
        }
    }

    @Test
    fun theWaitSentenceExplainsItselfWithoutOfferingAWayOut() {
        // The reason has to be readable by a person and a model, and it must not tell the reader
        // to turn an override on, because the override is not the answer to a missing circuit.
        val decision = decide("api.example.com", EgressMode.AUTO, torReady = false)
        assertTrue(
            "the reason must say it waits: ${decision.reason}",
            decision.reason.contains("wait", ignoreCase = true),
        )
        assertFalse(
            "a missing circuit is not an invitation to go direct: ${decision.reason}",
            decision.reason.contains("/clearnet"),
        )
    }

    @Test
    fun aCircuitThatIsReadyProducesNoWaitAtAll() {
        // The wait is a consequence of the state, not a fixed cost of the call.
        val decision = decide("example.com", EgressMode.AUTO, torReady = true)
        assertFalse("a ready Tor must not make the request wait", decision.awaitsCircuit)
        assertFalse(decision.refused)
    }

    // -----------------------------------------------------------------
    // The clearnet override
    // -----------------------------------------------------------------

    @Test
    fun clearnetSendsAPublicHostDirectAndSaysSo() {
        val decision = decide("example.com", EgressMode.CLEARNET, torReady = false)
        assertEquals(EgressClassifier.Route.DIRECT, decision.route)
        assertTrue(
            "a public host sent direct MUST be marked unproxied, or a result can claim privacy " +
                "it did not have",
            decision.unproxied,
        )
        assertNull(decision.refusal)
    }

    @Test
    fun clearnetStillSendsAPublicHostDirectWithTorUp() {
        // Clearnet is a posture, not a fallback. An owner who set it because an exit node was
        // blocked must not have the request quietly put back through Tor.
        val decision = decide("example.com", EgressMode.CLEARNET, torReady = true)
        assertEquals(EgressClassifier.Route.DIRECT, decision.route)
        assertTrue(decision.unproxied)
    }

    @Test
    fun clearnetDoesNotMakeAPrivateHostAnExposure() {
        val decision = decide("printer.local", EgressMode.CLEARNET, torReady = false)
        assertEquals(EgressClassifier.Route.DIRECT, decision.route)
        assertFalse(
            "a LAN address is direct by arithmetic, not by override, so it is not an exposure",
            decision.unproxied,
        )
    }

    // -----------------------------------------------------------------
    // A credential is Tor or nothing
    // -----------------------------------------------------------------

    @Test
    fun aCredentialBearingPathIgnoresClearnet() {
        // A token over his own address identifies him to a service. That is a different act
        // from fetching a public page, and it is not a trade the override makes.
        val decision = decide("api.github.com", EgressMode.CLEARNET, torReady = true, forcesTor = true)
        assertEquals(EgressClassifier.Route.TOR, decision.route)
        assertFalse(decision.unproxied)
        assertNull(decision.refusal)
    }

    @Test
    fun aCredentialBearingPathWaitsWithNoCircuitEvenUnderClearnet() {
        // A credential must never go direct, so the clearnet override cannot rescue it. It waits
        // for a circuit like everything else, rather than being refused.
        val decision = decide("api.github.com", EgressMode.CLEARNET, torReady = false, forcesTor = true)
        assertTrue("a credential must wait for a circuit", decision.awaitsCircuit)
        assertFalse("a credential must never go direct, so this is not an exposure", decision.unproxied)
        assertFalse("waiting is not being refused", decision.refused)
        assertEquals(EgressClassifier.Route.TOR, decision.route)
        assertTrue(
            "the reason must say the credential is why it cannot go direct: ${decision.reason}",
            decision.reason.contains("credential", ignoreCase = true),
        )
    }

    // -----------------------------------------------------------------
    // The posture parser
    // -----------------------------------------------------------------

    @Test
    fun anUnknownOrCorruptPostureFallsBackToTheSafeOne() {
        // A corrupt setting must never widen egress, so the fallback is AUTO and never CLEARNET.
        assertEquals(EgressMode.AUTO, EgressMode.parse(null))
        assertEquals(EgressMode.AUTO, EgressMode.parse(""))
        assertEquals(EgressMode.AUTO, EgressMode.parse("banana"))
        assertEquals(EgressMode.AUTO, EgressMode.parse("   "))
    }

    @Test
    fun everyPostureParsesFromItsOwnId() {
        for (mode in EgressMode.entries) {
            assertEquals(mode, EgressMode.parse(mode.id))
        }
    }

    @Test
    fun theSpellingsAPersonTypesAreAccepted() {
        assertEquals(EgressMode.TOR_ONLY, EgressMode.parse("tor"))
        assertEquals(EgressMode.TOR_ONLY, EgressMode.parse("lock"))
        assertEquals(EgressMode.CLEARNET, EgressMode.parse("direct"))
        assertEquals(EgressMode.AUTO, EgressMode.parse("default"))
        // Case and underscores must not defeat it, because a stored value is written by a
        // different layer than the one that reads it.
        assertEquals(EgressMode.TOR_ONLY, EgressMode.parse("TOR_ONLY"))
    }
}
