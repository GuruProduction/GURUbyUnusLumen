// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each quick settings tile is allowed to say about itself.
 *
 * WHY THIS IS A PURE TEST AND NOT A DEVICE ONE. A tile's label is the whole of what a
 * person reads before tapping, and the failure that matters is a label claiming a state the
 * phone is not in: "GURU is floating" on a phone where the orb is off, or a stop tile that
 * says it stopped something when nothing was running. That rule is a function of four
 * booleans, so it is proved here in milliseconds rather than on a handset where the same
 * mistake would look like a working tile.
 *
 * WHAT THIS CANNOT PROVE: that Android draws the tile, that the shade accepts it, that a tap
 * reaches the service, or that the orb actually draws over another app. Those need the
 * device and are named in the PR body.
 */
class GuruTileSpecsTest {

    private fun state(
        id: String,
        orbRunning: Boolean = false,
        overlayPermitted: Boolean = true,
        heartbeatEnabled: Boolean = false,
        heartbeatWasRunning: Boolean = false,
        lastStopAtMillis: Long? = null,
    ) = GuruTileSpecs.stateFor(
        id = id,
        orbRunning = orbRunning,
        overlayPermitted = overlayPermitted,
        heartbeatEnabled = heartbeatEnabled,
        heartbeatWasRunning = heartbeatWasRunning,
        lastStopAtMillis = lastStopAtMillis,
    )

    @Test
    fun everyTileInTheListHasASpecAndEverySpecHasATile() {
        assertEquals(
            listOf("talk", "listen", "stop", "add_task"),
            GuruTileSpecs.all,
        )
        for (id in GuruTileSpecs.all) {
            assertNotEquals(
                "an unknown tile id means the list and the rule disagree",
                "Unknown GURU tile",
                state(id).label,
            )
        }
    }

    /**
     * The tiles that only open the app must never claim a running state.
     *
     * This is the defect that makes a status icon worthless: a tile reading ACTIVE while
     * nothing is on. It is asserted for every combination, because a READY tile has no
     * switch behind it and no read could ever justify ON.
     */
    @Test
    fun theTwoOpeningTilesAlwaysReadReadyWhateverThePhoneIsDoing() {
        for (id in listOf(GuruTileSpecs.TALK, GuruTileSpecs.ADD_TASK)) {
            for (orb in listOf(false, true)) {
                for (overlay in listOf(false, true)) {
                    for (heartbeat in listOf(false, true)) {
                        val tile = state(id, orb, overlay, heartbeat)
                        assertEquals(
                            "$id must read READY, not a running state, with orb=$orb overlay=$overlay",
                            TileState.READY,
                            tile.state,
                        )
                    }
                }
            }
        }
    }

    /** The orb tile follows the service's own state, so stopping it from the notification reads off. */
    @Test
    fun theListenTileFollowsTheOrbServiceRatherThanARememberedPreference() {
        assertEquals(TileState.OFF, state(GuruTileSpecs.LISTEN, orbRunning = false).state)
        assertEquals(TileState.ON, state(GuruTileSpecs.LISTEN, orbRunning = true).state)
    }

    /**
     * Without the overlay grant the orb cannot draw, so the tile is unavailable and names
     * the screen. Starting a service that shows nothing and drawing ON for it is the exact
     * lie this state exists to prevent.
     */
    @Test
    fun theListenTileIsBlockedAndNamesTheGrantWhenOverlayIsMissing() {
        val tile = state(GuruTileSpecs.LISTEN, orbRunning = false, overlayPermitted = false)
        assertEquals(TileState.BLOCKED, tile.state)
        assertTrue(
            "the label must name what is missing: ${tile.label}",
            tile.label.contains("Display over other apps"),
        )
        assertFalse(
            "it must not claim the orb is running: ${tile.label}",
            tile.label.contains("floating"),
        )
    }

    @Test
    fun theStopTileReadsOnWhenTheHeartbeatIsActuallyEnabled() {
        val tile = state(GuruTileSpecs.STOP, heartbeatEnabled = true)
        assertEquals(TileState.ON, tile.state)
        assertTrue("it must say what it will stop: ${tile.subtitle}", tile.subtitle.contains("cancels"))
    }

    /**
     * After a tap that stopped a running chain, the label says what happened. This is the
     * difference between an action and a no-op that looks identical.
     */
    @Test
    fun theStopTileSaysWhatItJustStopped() {
        val tile = state(
            GuruTileSpecs.STOP,
            heartbeatEnabled = false,
            heartbeatWasRunning = true,
            lastStopAtMillis = 1_700_000_000_000L,
        )
        assertEquals(TileState.OFF, tile.state)
        assertEquals("Heartbeat stopped", tile.label)
    }

    @Test
    fun theStopTileDoesNotClaimAStopWhenNothingWasRunning() {
        val tile = state(GuruTileSpecs.STOP, heartbeatEnabled = false, heartbeatWasRunning = false)
        assertEquals(TileState.OFF, tile.state)
        assertEquals("Heartbeat is off", tile.label)
        assertTrue(
            "it must say nothing was scheduled to stop: ${tile.subtitle}",
            tile.subtitle.contains("Nothing was scheduled"),
        )
    }

    @Test
    fun anUnknownTileIdReportsABuildFaultRatherThanAnAction() {
        val tile = state("not_a_tile")
        assertEquals(TileState.BLOCKED, tile.state)
        assertTrue("it must call itself a fault: ${tile.subtitle}", tile.subtitle.contains("build fault"))
    }

    @Test
    fun onlyTheOrbAndTheHeartbeatAreToggles() {
        assertTrue(GuruTileSpecs.isToggle(GuruTileSpecs.LISTEN))
        assertTrue(GuruTileSpecs.isToggle(GuruTileSpecs.STOP))
        assertFalse("opening the app is not a toggle", GuruTileSpecs.isToggle(GuruTileSpecs.TALK))
        assertFalse("adding a task is not a toggle", GuruTileSpecs.isToggle(GuruTileSpecs.ADD_TASK))
    }

    /** Every tile must carry a subtitle, because it is the only text that says what a tap does. */
    @Test
    fun everyTileExplainsItselfInItsSubtitle() {
        val blank = GuruTileSpecs.all
            .map { state(it) }
            .filter { it.subtitle.isBlank() }
            .map { it.id }
        assertTrue("tiles with no subtitle: $blank", blank.isEmpty())
    }
}
