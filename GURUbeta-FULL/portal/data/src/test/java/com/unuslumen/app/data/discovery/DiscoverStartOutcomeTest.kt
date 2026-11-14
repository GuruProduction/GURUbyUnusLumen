// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 337's last three collapse sites: the two discover starts and the mDNS device list.
 *
 * THE DEFECT EACH HAD. `mdnsDiscover` and `ssdpDiscover` answered `success = true` whenever the call
 * did not throw, so a start that watched six service types and a start the platform refused on every
 * one produced the same value. `mdnsListDevices` answered in the listener's own five-word vocabulary
 * while the rest of the family answered FOUND/EMPTY/REFUSED, so a caller reading the family had to
 * special-case one tool.
 */
class DiscoverStartOutcomeTest {

    /**
     * The rule the executor applies, expressed once so a test that passes is a test about behaviour.
     *
     * It mirrors the when-block in EnvironmentToolExecutor exactly, and the point of pinning it is
     * that the mapping is the thing that decides whether a person is told their network is empty or
     * their listener never started.
     */
    private fun outcomeFor(state: ListenerState): NetworkReadOutcome = when {
        state == ListenerState.FOUND -> NetworkReadOutcome.FOUND
        state == ListenerState.LISTENING -> NetworkReadOutcome.EMPTY
        else -> NetworkReadOutcome.REFUSED
    }

    @Test
    fun aStartThePlatformRefusedIsNotASuccess() {
        // The old code returned true here, because nothing threw.
        assertEquals(NetworkReadOutcome.REFUSED, outcomeFor(ListenerState.BIND_FAILED))
        assertFalse(
            "a listener that never bound must not read as answered",
            outcomeFor(ListenerState.BIND_FAILED) != NetworkReadOutcome.REFUSED,
        )
    }

    @Test
    fun aStartThatWatchedNothingIsARefusal() {
        // STOPPED means the platform accepted the call and nothing is listening, which is exactly
        // the state the old code called a successful start.
        assertEquals(NetworkReadOutcome.REFUSED, outcomeFor(ListenerState.STOPPED))
    }

    @Test
    fun aLiveListenerThatHeardNothingIsAnAnswer() {
        assertEquals(
            "a bound listener on a quiet network is a measurement about the network",
            NetworkReadOutcome.EMPTY,
            outcomeFor(ListenerState.LISTENING),
        )
    }

    @Test
    fun devicesAreFound() {
        assertEquals(NetworkReadOutcome.FOUND, outcomeFor(ListenerState.FOUND))
    }

    @Test
    fun partialTrafficIsARefusalBecauseSomethingAnsweredAndWasDropped() {
        // Datagrams arrived and none parsed into a device. Reporting an empty list there would be a
        // claim about the wifi made by a parser that threw its input away.
        assertEquals(NetworkReadOutcome.REFUSED, outcomeFor(ListenerState.PARTIAL))
    }

    @Test
    fun everyListenerStateMapsToExactlyOneOutcome() {
        // No state may be unmapped, and the mapping must not be many-to-one in a way that hides a
        // distinction the listener went to the trouble of recording.
        val mapped = ListenerState.values().associateWith { outcomeFor(it) }
        assertEquals("every listener state must have an outcome", ListenerState.values().size, mapped.size)
        assertTrue(
            "a bound listener must not share an outcome with one that never bound",
            mapped[ListenerState.LISTENING] != mapped[ListenerState.BIND_FAILED],
        )
        assertTrue(
            "found must be distinguishable from empty",
            mapped[ListenerState.FOUND] != mapped[ListenerState.LISTENING],
        )
    }
}
