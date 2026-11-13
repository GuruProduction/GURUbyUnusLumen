// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Holds which buttons have an effect this build can actually read back, because issue 216
 * was `tvPress` reporting a power state as if it were a volume observation.
 *
 * THE DEFECT. After any successful press the executor read the set's power state and put it
 * in `observed`. A volume press therefore came back with `observed: "PowerOn"`, which reads
 * as confirmation that the volume changed when nothing about volume was observed. The field
 * was answering a question the caller had not asked, and a filled-in field is believed.
 *
 * THE RULE. A button names the piece of state its own effect would be visible in, or null
 * when this build cannot see the outcome. Null is the honest answer for a press that was sent
 * and not observed.
 */
class TvPressObservationTest {

    @Test
    fun aPowerButtonIsObservedThroughPowerState() {
        assertEquals(TvStateKey.POWER, TvButton.POWER.observableState())
        assertEquals(TvStateKey.POWER, TvButton.POWER_ON.observableState())
        assertEquals(TvStateKey.POWER, TvButton.POWER_OFF.observableState())
    }

    @Test
    fun aVolumePressObservesNothingRatherThanReportingPower() {
        // The ticket's exact case: a volume press that reported PowerOn.
        assertNull(
            "A volume press says nothing about power, and reporting power there is the " +
                "false confirmation the ticket describes.",
            TvButton.VOLUME_UP.observableState(),
        )
        assertNull(TvButton.VOLUME_DOWN.observableState())
        assertNull(TvButton.MUTE.observableState())
    }

    @Test
    fun aChannelOrNavigationPressObservesNothing() {
        listOf(
            TvButton.CHANNEL_UP, TvButton.CHANNEL_DOWN,
            TvButton.UP, TvButton.DOWN, TvButton.LEFT, TvButton.RIGHT,
            TvButton.OK, TvButton.BACK, TvButton.HOME, TvButton.MENU,
        ).forEach { button ->
            assertNull(
                "${button.name} has an effect this build cannot read back, so it must not " +
                    "claim a reading.",
                button.observableState(),
            )
        }
    }

    @Test
    fun aTransportPressObservesNothing() {
        listOf(
            TvButton.PLAY, TvButton.PAUSE, TvButton.STOP,
            TvButton.REWIND, TvButton.FAST_FORWARD, TvButton.INPUT,
        ).forEach { button ->
            assertNull("${button.name} cannot be observed here", button.observableState())
        }
    }

    @Test
    fun everyButtonIsAccountedForSoANewOneCannotSilentlyClaimPower() {
        // An exhaustive check: any button added later must make a deliberate decision rather
        // than inheriting a reading by falling through to an else branch.
        val claimPower = TvButton.entries.filter { it.observableState() == TvStateKey.POWER }
        assertEquals(
            "Exactly the three power buttons may claim a power reading. Got: " +
                claimPower.joinToString(", "),
            setOf(TvButton.POWER, TvButton.POWER_ON, TvButton.POWER_OFF),
            claimPower.toSet(),
        )
    }
}
