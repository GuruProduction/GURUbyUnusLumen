// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell.shizuku

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three-state Shizuku detection, proven on the JVM.
 *
 * WHY THIS IS WORTH A DEVICE-FREE TEST. The whole value of three states over a
 * boolean is that each one gives a different instruction, and getting the order
 * wrong sends a person to restart something that is already running. That ordering
 * is a pure rule, so it belongs here where a failure names the exact case rather
 * than in a device run where a wrong state looks like an app fault.
 */
class ShizukuStateTest {

    @Test
    fun notInstalledIsReportedFirstEvenWhenNothingElseIsKnown() {
        // A device with no Shizuku cannot have a running binder or a grant, so both
        // other readings are false. The state must still name the install, because
        // that is the only instruction that helps.
        val state = ShizukuState.of(installed = false, running = false, authorised = false)
        assertEquals(ShizukuState.NOT_INSTALLED, state)
        assertFalse(state.usable)
    }

    /**
     * The case that matters for the ordering. Shizuku is installed and its process
     * is up, but GURU has not been allowed. A boolean would report this as simply
     * "unavailable", and the useful instruction is a single tap.
     */
    @Test
    fun installedAndRunningWithoutAGrantIsNotAuthorisedRatherThanNotRunning() {
        val state = ShizukuState.of(installed = true, running = true, authorised = false)
        assertEquals(ShizukuState.NOT_AUTHORISED, state)
        assertFalse(state.usable)
    }

    @Test
    fun installedButNotRunningIsReportedAsNotRunning() {
        val state = ShizukuState.of(installed = true, running = false, authorised = false)
        assertEquals(ShizukuState.NOT_RUNNING, state)
        assertFalse(state.usable)
    }

    @Test
    fun installedRunningAndAuthorisedIsTheOnlyUsableState() {
        val state = ShizukuState.of(installed = true, running = true, authorised = true)
        assertEquals(ShizukuState.AVAILABLE, state)
        assertTrue(state.usable)
    }

    /**
     * An authorisation reading on a binder that is not up is meaningless, so the
     * state must not claim NOT_AUTHORISED from a stale true. This pins the order:
     * running is checked before authorised.
     */
    @Test
    fun aStaleAuthorisationCannotOutrankANonRunningBinder() {
        val state = ShizukuState.of(installed = true, running = false, authorised = true)
        assertEquals(ShizukuState.NOT_RUNNING, state)
    }

    /**
     * Every state except AVAILABLE must tell the person what to do, and must be a
     * complete sentence. This is the same contract the permission refusals keep:
     * an unavailable capability that does not say how to unlock it is a dead end.
     */
    @Test
    fun everyUnavailableStateGivesAnInstructionThatASentenceCanCarry() {
        listOf(
            ShizukuState.NOT_INSTALLED,
            ShizukuState.NOT_RUNNING,
            ShizukuState.NOT_AUTHORISED,
        ).forEach { state ->
            val sentence = state.sentence()
            assertTrue("$state gave an empty sentence", sentence.isNotBlank())
            assertTrue("$state must end in a full stop: $sentence", sentence.trim().endsWith("."))
            assertFalse("$state leaked a package name: $sentence", sentence.contains("moe.shizuku"))
            assertFalse("$state has a doubled space: $sentence", sentence.contains("  "))
            assertTrue(
                "$state must say what to do: $sentence",
                sentence.contains("Install") || sentence.contains("Open Shizuku"),
            )
        }
    }

    /**
     * The instructions must be distinct, because the whole point of three states is
     * three different next actions. If two states produced the same sentence there
     * would be no reason to distinguish them.
     */
    @Test
    fun theThreeUnavailableInstructionsAreAllDifferent() {
        val sentences = listOf(
            ShizukuState.NOT_INSTALLED.sentence(),
            ShizukuState.NOT_RUNNING.sentence(),
            ShizukuState.NOT_AUTHORISED.sentence(),
        )
        assertEquals("states must not share an instruction", 3, sentences.toSet().size)
    }

    @Test
    fun theAvailableStateSaysTheChannelWorksAndNeedsNoInstruction() {
        val sentence = ShizukuState.AVAILABLE.sentence()
        assertTrue("must say it is ready: $sentence", sentence.contains("authorised"))
        assertFalse("must not tell them to install anything: $sentence", sentence.contains("Install"))
    }
}
