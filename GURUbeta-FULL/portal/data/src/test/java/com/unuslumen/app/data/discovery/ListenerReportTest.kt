// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.discovery

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the five listener states apart, because issue 222 was `mdnsListDevices`
 * returning `[]` for all of them.
 *
 * THE FOUR FACTS THE TICKET NAMED, and the reason each needs its own answer: a
 * listener that was never started, one Android refused to bind, one that is up and
 * has heard nothing, and one that is up with results. An empty array cannot carry
 * any of them, and the model reported all four as "there is no Apple TV here".
 */
class ListenerReportTest {

    private val airplay = "_airplay._tcp."
    private val cast = "_googlecast._tcp."

    @Test
    fun aListenerThatWasNeverStartedMakesNoClaimAboutTheNetwork() {
        val report = NetworkReads.listenerReport(
            started = false,
            listening = emptyList(),
            refused = emptyList(),
            found = 0,
        )

        assertEquals(ListenerState.STOPPED, report.state)
        assertFalse("a stopped listener heard nothing at all", report.watching)
        assertNotNull("the caller must be told this says nothing about the network", report.sentence)
    }

    @Test
    fun everyTypeRefusedIsABindFailureAndNotAQuietNetwork() {
        val report = NetworkReads.listenerReport(
            started = true,
            listening = emptyList(),
            refused = listOf("$airplay (error 3)", "$cast (error 3)"),
            found = 0,
        )

        assertEquals(
            "Android refused every type, so nothing is being listened for. Reporting this " +
                "as an empty network is exactly issue 222.",
            ListenerState.BIND_FAILED,
            report.state,
        )
        assertFalse(report.watching)
        assertTrue(
            "the refusal must name a cause the reader can act on. Got: ${report.sentence}",
            report.sentence?.contains("multicast") == true,
        )
    }

    @Test
    fun aListeningTypeWithNoResultsIsARealAnswerAboutTheNetwork() {
        val report = NetworkReads.listenerReport(
            started = true,
            listening = listOf(airplay),
            refused = emptyList(),
            found = 0,
        )

        assertEquals(
            "The listener is up and has heard nothing, which is a real fact about the " +
                "wifi rather than a broken listener.",
            ListenerState.LISTENING,
            report.state,
        )
        assertTrue(report.watching)
        assertNull("a real answer needs no apology", report.sentence)
    }

    @Test
    fun resultsIsFound() {
        val report = NetworkReads.listenerReport(
            started = true,
            listening = listOf(airplay, cast),
            refused = emptyList(),
            found = 2,
        )

        assertEquals(ListenerState.FOUND, report.state)
        assertNull(report.sentence)
    }

    @Test
    fun aPartialBindSaysWhichTypesAreWatchedAndWhichAreNot() {
        // The case where silence is least trustworthy: one type is up and one was
        // refused, so "no AirPlay devices" means something and "no Chromecasts" does not.
        val report = NetworkReads.listenerReport(
            started = true,
            listening = listOf(cast),
            refused = listOf("$airplay (error 3)"),
            found = 0,
        )

        assertEquals(
            "A partial bind must not be reported as a plain listening state, because " +
                "silence on the refused type proves nothing.",
            ListenerState.PARTIAL,
            report.state,
        )
        assertTrue("it is still watching something", report.watching)
        assertTrue(
            "the refused type must be named. Got: ${report.sentence}",
            report.sentence?.contains(airplay) == true,
        )
        assertTrue(
            "and so must the type that is being watched. Got: ${report.sentence}",
            report.sentence?.contains(cast) == true,
        )
    }

    @Test
    fun aPartialBindWithResultsIsStillPartialBecauseOneTypeWasNeverWatched() {
        // Results do not upgrade a partial bind. Finding a Chromecast says nothing about
        // whether an Apple TV was missed, because the AirPlay listener never ran.
        val report = NetworkReads.listenerReport(
            started = true,
            listening = listOf(cast),
            refused = listOf("$airplay (error 3)"),
            found = 1,
        )

        assertEquals(
            "Refused types outrank a finding: the list is known to be incomplete.",
            ListenerState.PARTIAL,
            report.state,
        )
        assertEquals(1, report.found)
    }
}
