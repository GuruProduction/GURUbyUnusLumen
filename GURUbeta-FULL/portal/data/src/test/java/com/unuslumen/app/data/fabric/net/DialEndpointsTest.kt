// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where DIAL looks for a description, and what a launch status means. Issue #251.
 *
 * WHY THIS IS THE TEST THAT MATTERS. The bug was not an error message, it was that netDial
 * could not reach either television on the network. It asked for `/ssdp/device-desc.xml` on
 * the port it was given and nothing else, and no set here serves that path: the Roku answers
 * at `/dial/dd.xml` on 8060, the Fire TV at `/dd.xml` on 60000. Every call was a 404 and the
 * 404 was reported as the device not exposing DIAL.
 *
 * So the two facts pinned here are the two that were wrong: the device's own LOCATION is
 * tried first, and a launch is only a launch when the device answers 201.
 */
class DialEndpointsTest {

    /**
     * THE REGRESSION. The Roku's own path must be among the candidates.
     *
     * If `/dial/dd.xml` ever leaves this list, the Roku on this network becomes unreachable
     * again exactly as it was reported.
     */
    @Test
    fun theRokusOwnDescriptionPathIsTried() {
        val candidates = DialEndpoints.descriptionCandidates("192.0.2.10", 8060)
        assertTrue(
            "a Roku serves its description at /dial/dd.xml and the list is: $candidates",
            candidates.contains("http://192.0.2.10:8060/dial/dd.xml"),
        )
    }

    /** The Fire TV's own path must be among the candidates too. */
    @Test
    fun theFireTvsOwnDescriptionPathIsTried() {
        val candidates = DialEndpoints.descriptionCandidates("192.0.2.11", 8009)
        assertTrue(
            "a Fire TV serves its description at /dd.xml and the list is: $candidates",
            candidates.contains("http://192.0.2.11:8009/dd.xml"),
        )
    }

    /**
     * THE OTHER PORT IS TRIED, because a Fire TV's description is not on its app port.
     *
     * Discovery records 8009 for a Fire TV, which is where launches go. Its description
     * lives on 60000. A client that only asks 8009 can never find it.
     */
    @Test
    fun theDescriptionPortIsTriedForAFireTv() {
        val candidates = DialEndpoints.descriptionCandidates("192.0.2.11", 8009)
        assertTrue(
            "a Fire TV publishes its description on 60000 and the list is: $candidates",
            candidates.any { it.contains(":60000/") },
        )
    }

    /**
     * THE DEVICE'S OWN LOCATION COMES FIRST.
     *
     * This is the whole repair. The LOCATION header is the only authoritative statement of
     * where a description lives, so it is asked before any of our fallbacks, and it is not
     * rewritten to our host or port.
     */
    @Test
    fun theDevicesOwnLocationIsAskedFirst() {
        val location = "http://192.0.2.11:60000/dd.xml"
        val candidates = DialEndpoints.descriptionCandidates(
            host = "192.0.2.11",
            port = 8009,
            knownLocation = location,
        )
        assertEquals("the device's own URL must be the first request", location, candidates.first())
    }

    /** A device that published no LOCATION still gets the fallbacks. */
    @Test
    fun noLocationStillProducesCandidates() {
        val candidates = DialEndpoints.descriptionCandidates("192.0.2.10", 8060, knownLocation = null)
        assertTrue("there must be something to try", candidates.isNotEmpty())
        assertTrue(candidates.all { it.startsWith("http://192.0.2.10") })
    }

    /** A LOCATION equal to a fallback is not requested twice. */
    @Test
    fun aLocationIsNotRepeated() {
        val duplicate = "http://192.0.2.10:8060/dial/dd.xml"
        val candidates = DialEndpoints.descriptionCandidates("192.0.2.10", 8060, duplicate)
        assertEquals(
            "the same URL must appear once, and got: $candidates",
            1,
            candidates.count { it == duplicate },
        )
    }

    // ------------------------------------------------------------------
    // Which statuses keep the search going
    // ------------------------------------------------------------------

    /**
     * A 404 or 405 is about the PATH, so the next candidate is tried.
     *
     * This is the difference between "this path is not the description" and "this device
     * refuses". Conflating them is what produced a refusal blaming the set.
     */
    @Test
    fun aWrongPathKeepsTheSearchGoing() {
        assertTrue(DialEndpoints.keepsSearching(404))
        assertTrue(DialEndpoints.keepsSearching(405))
    }

    /** A refusal is the device's answer, so the search stops there. */
    @Test
    fun aRefusalStopsTheSearch() {
        assertFalse(DialEndpoints.keepsSearching(403))
        assertFalse(DialEndpoints.keepsSearching(401))
        assertFalse(DialEndpoints.keepsSearching(500))
        assertFalse(DialEndpoints.keepsSearching(200))
    }

    /** A description without an Application-URL cannot launch anything. */
    @Test
    fun aDescriptionWithoutAnApplicationUrlIsNotUsable() {
        assertFalse(DialEndpoints.isUsable(null))
        assertFalse(DialEndpoints.isUsable(""))
        assertFalse(DialEndpoints.isUsable("   "))
        assertTrue(DialEndpoints.isUsable("http://192.0.2.11:8009/apps/"))
    }

    // ------------------------------------------------------------------
    // A launch is 201
    // ------------------------------------------------------------------

    /**
     * THE DEFECT, PINNED. 201 is a launch; 200 is the device accepting a request without
     * starting anything.
     *
     * The issue reported the same POST answering 201 to one call and being reported as 200
     * by another. Both were called success, so a launch that did nothing read as a launch.
     */
    @Test
    fun onlyTwoZeroOneIsALaunch() {
        assertEquals(DialEndpoints.LaunchOutcome.STARTED, DialEndpoints.launchOutcome(201))
        assertEquals(
            DialEndpoints.LaunchOutcome.ACCEPTED_NOT_STARTED,
            DialEndpoints.launchOutcome(200),
        )
        assertFalse(
            "200 must never be reported as a launch",
            DialEndpoints.launchOutcome(200) == DialEndpoints.LaunchOutcome.STARTED,
        )
    }

    /** Other success statuses are neither a launch nor a refusal. */
    @Test
    fun otherSuccessIsItsOwnAnswer() {
        assertEquals(DialEndpoints.LaunchOutcome.OTHER_SUCCESS, DialEndpoints.launchOutcome(202))
        assertEquals(DialEndpoints.LaunchOutcome.OTHER_SUCCESS, DialEndpoints.launchOutcome(204))
    }

    /** A Roku's 403 is a refusal, which is the honest answer it gave. */
    @Test
    fun aRokuFourOhThreeIsARefusal() {
        assertEquals(DialEndpoints.LaunchOutcome.REFUSED, DialEndpoints.launchOutcome(403))
        assertEquals(DialEndpoints.LaunchOutcome.REFUSED, DialEndpoints.launchOutcome(500))
    }

    // ------------------------------------------------------------------
    // App names
    // ------------------------------------------------------------------

    /** A spoken name maps onto the published DIAL spelling, which is case sensitive. */
    @Test
    fun spokenNamesMapOntoDialSpellings() {
        assertEquals("Netflix", DialEndpoints.appNameFor("netflix"))
        assertEquals("YouTube", DialEndpoints.appNameFor("YouTube"))
        assertEquals("Amazon", DialEndpoints.appNameFor("prime video"))
        assertEquals("Amazon", DialEndpoints.appNameFor("amazon"))
        assertEquals("BBC iPlayer", DialEndpoints.appNameFor("bbc iplayer"))
        assertEquals("Disney", DialEndpoints.appNameFor("disney plus"))
        assertEquals("Spotify", DialEndpoints.appNameFor("spotify"))
    }

    /**
     * AN UNKNOWN NAME IS PASSED THROUGH, not refused.
     *
     * A DIAL device accepts app names this build has never heard of, and the device is the
     * authority on its own apps. Refusing here would be inventing a limit.
     */
    @Test
    fun anUnknownNameIsPassedThroughAsTyped() {
        assertEquals("SomeNewApp", DialEndpoints.appNameFor("SomeNewApp"))
    }

    /** Whitespace and casing are normalised before the lookup. */
    @Test
    fun namesAreNormalisedBeforeMapping() {
        assertEquals("Netflix", DialEndpoints.appNameFor("  NETFLIX  "))
        assertEquals("Amazon", DialEndpoints.appNameFor("Prime  Video"))
    }
}
