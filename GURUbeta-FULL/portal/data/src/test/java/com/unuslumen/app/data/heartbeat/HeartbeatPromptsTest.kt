// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 327's substitution contract.
 *
 * The two rules that matter: a state token always becomes a real value rather than staying visible,
 * and the preview and the beat use one function so they cannot drift.
 */
class HeartbeatPromptsTest {

    private val context = HeartbeatPrompts.BeatContext(
        idleBeats = 6,
        silentFor = "3 hours 20 minutes",
        lastBeatNote = "sent a notification about the failing build",
    )

    @Test
    fun theStateTokensSubstituteToRealValues() {
        val prompt = "Idle: {{idle_beats}}. Silent: {{silent_for}}. Last: {{last_beat_note}}."
        assertEquals(
            "Idle: 6. Silent: 3 hours 20 minutes. Last: sent a notification about the failing build.",
            HeartbeatPrompts.substitute(prompt, "Steven", context),
        )
    }

    @Test
    fun theStateTokensAndTheDollarSpellingCannotBothSurvive() {
        // The shipped asset used ${human} while the loader knew {{human}}, and every release
        // injected the literal token. The same class of fault must not return for the new ones.
        val prompt = "{{idle_beats}} {{silent_for}} {{last_beat_note}}"
        val out = HeartbeatPrompts.substitute(prompt, "Steven", context)
        assertTrue(
            "no literal state token may survive substitution: $out",
            !out.contains("{{") && !out.contains("}}"),
        )
    }

    @Test
    fun aZeroedContextSubstitutesRealZeroesNotVisibleTokens() {
        // The room's preview passes the default, and a preview showing {{idle_beats}} would read
        // as a broken token rather than as a preview of a beat.
        val out = HeartbeatPrompts.substitute(
            "idle {{idle_beats}} silent [{{silent_for}}] note [{{last_beat_note}}]",
            "Steven",
        )
        assertEquals(
            "a preview must show what a beat would receive, which for unknown state is 0 and empty",
            "idle 0 silent [] note []",
            out,
        )
    }

    @Test
    fun theExistingTokensStillWork() {
        // The signature gained a defaulted parameter, so every existing caller must behave
        // identically. This is the assertion that says so.
        val out = HeartbeatPrompts.substitute("Hello {{human}} and \${human}.", "Steven")
        assertEquals("Hello Steven and Steven.", out)
    }

    @Test
    fun aBlankHumanNameFallsBackRatherThanLeavingAGap() {
        val out = HeartbeatPrompts.substitute("Hello {{human}}.", "")
        assertEquals("Hello your human.", out)
    }

    @Test
    fun theTimeTokenIsStillSubstituted() {
        val out = HeartbeatPrompts.substitute("It is {{time}}.", "Steven")
        assertTrue("the time must be filled in: $out", !out.contains("{{time}}"))
        assertTrue("and must read as a date: $out", out.length > "It is .".length + 8)
    }

    @Test
    fun theTokenHelpNamesEveryTokenTheSubstitutionAnswers() {
        val help = HeartbeatPrompts.tokenHelp(context).map { it.first }
        for (token in listOf(
            "{{human}}", "{{human_name}}", "{{time}}",
            "{{idle_beats}}", "{{silent_for}}", "{{last_beat_note}}",
        )) {
            assertTrue("$token must be documented, because the room reads this list", token in help)
        }
    }

    @Test
    fun theTokenHelpShowsTheLiveValuesWhereItHasThem() {
        val help = HeartbeatPrompts.tokenHelp(context).toMap()
        assertTrue(
            "the idle count must appear, so a person can see what their prompt will receive: " +
                "${help["{{idle_beats}}"]}",
            help["{{idle_beats}}"]!!.contains("6"),
        )
        assertTrue(
            "and the gap: ${help["{{silent_for}}"]}",
            help["{{silent_for}}"]!!.contains("3 hours 20 minutes"),
        )
    }
}
