// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.domain.agents.VerificationLevels
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The check behind a completion claim.
 *
 * TWO THINGS ARE PROVED HERE AND NOTHING ELSE: that the level cannot be fuzzed into something the
 * evidence does not support, and that a confirmation with nothing behind it is refused. Those are the
 * two ways this record can lie, and a record that can lie is worse than no record because it looks
 * like evidence.
 */
class VerificationToolTest {

    private val executor = VerificationToolExecutor()

    private suspend fun run(vararg args: Pair<String, Any?>): String {
        val result = executor.execute(
            VerificationToolDefinitions.RECORD_VERIFICATION,
            args.toMap(),
        )
        return result.error ?: result.rawJson
    }

    @Test
    fun aConfirmedCheckIsAcceptedWithItsEvidence() = runTest {
        val out = run(
            "claim" to "the config file is updated",
            "level" to "confirmed",
            "evidence" to "read /data/config.json back after writing and saw the new value",
        )
        assertTrue("it succeeded: $out", out.contains("\"level\":\"CONFIRMED\""))
        assertTrue(out.contains("the config file is updated"))
        assertTrue(out.contains("read /data/config.json back"))
    }

    @Test
    fun aClaimWithNoEvidenceIsRefused() = runTest {
        /*
         * A CONFIRMATION WITH NO SOURCE IS AN ASSERTION WITH A LONGER WORD. The whole point of the
         * record is that a reader can check it, and a level with nothing behind it gives them nothing.
         */
        val out = run("claim" to "it works", "level" to "confirmed")
        assertTrue("it is refused: $out", out.contains("Missing 'evidence'"))
    }

    @Test
    fun anUnknownLevelIsRefusedAndTheThreeAreNamed() = runTest {
        val out = run(
            "claim" to "it works",
            "level" to "verified",
            "evidence" to "I looked",
        )
        assertTrue("it is refused: $out", out.contains("not a level of confirmation"))
        VerificationLevels.ALL.forEach { level ->
            assertTrue("the refusal names ${level.lowercase()}: $out", out.contains(level.lowercase()))
        }
    }

    @Test
    fun aMissingClaimIsRefused() = runTest {
        val out = run("level" to "confirmed", "evidence" to "I looked")
        assertTrue("it is refused: $out", out.contains("Missing 'claim'"))
    }

    @Test
    fun aMissingLevelIsRefused() = runTest {
        val out = run("claim" to "it works", "evidence" to "I looked")
        assertTrue("it is refused: $out", out.contains("Missing 'level'"))
    }

    @Test
    fun anAcceptedCheckIsRecordedWithoutClaimingCompletion() = runTest {
        /*
         * THE DISTINCTION THE FEATURE EXISTS FOR. The command exited zero, which means it was
         * accepted, and nobody has checked whether the effect happened. The record carries the level,
         * and the rule that turns a level into "may this support a completion claim" is the domain
         * function rather than a field in the payload.
         */
        val out = run(
            "claim" to "the migration ran",
            "level" to "accepted",
            "evidence" to "the CLI printed success and exited 0",
        )
        assertTrue(out.contains("\"level\":\"ACCEPTED\""))
        assertFalse(
            "an acceptance is not a confirmation",
            VerificationLevels.supportsCompletion(VerificationLevels.ACCEPTED),
        )
    }

    @Test
    fun aConfirmedLevelIsWhatAllowsTheCompletionClaim() = runTest {
        val out = run(
            "claim" to "the row exists",
            "level" to "confirmed",
            "evidence" to "queried the table and read the row back",
        )
        assertTrue(out.contains("\"level\":\"CONFIRMED\""))
        assertTrue(
            "and that is the one level that allows it",
            VerificationLevels.supportsCompletion(VerificationLevels.CONFIRMED),
        )
    }

    @Test
    fun theVerbIsRegisteredInThePhoneToolList() {
        val registered = com.unuslumen.app.data.tools.registry.ToolRegistration.allDefinitions()
            .map { it.name }
        assertTrue(
            "recordVerification is on the phone: ${registered.size} tools",
            VerificationToolDefinitions.RECORD_VERIFICATION in registered,
        )
    }

    @Test
    fun theRuntimeNamesTheSameVerbTheDefinitionDoes() {
        /*
         * THE DUPLICATED STRING, PINNED. The runtime recognises the call by its own constant and the
         * definition registers it by another; a rename on either side would quietly stop recording
         * checks while the tool still appeared to work.
         */
        org.junit.Assert.assertEquals(
            VerificationToolDefinitions.RECORD_VERIFICATION,
            com.unuslumen.app.data.agents.AgentRuntime.RECORD_VERIFICATION,
        )
    }
}
