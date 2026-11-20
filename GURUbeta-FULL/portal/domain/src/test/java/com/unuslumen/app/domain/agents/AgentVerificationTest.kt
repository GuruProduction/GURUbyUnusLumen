// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Three levels of confirmation, and only one of them supports saying the work is done.
 *
 * SECTION 4.4 ASKS FOR AN ATTEMPT, AN ACCEPTANCE AND A REAL-WORLD CONFIRMATION to be distinguishable.
 * A boolean "done" collapses all three, which is what lets an agent report a send as complete because
 * the tool call returned. The rule is one function, so it is proved here rather than by reading the
 * runtime's writer.
 */
class AgentVerificationTest {

    @Test
    fun thereAreExactlyThreeLevels() {
        assertEquals(
            listOf("ATTEMPTED", "ACCEPTED", "CONFIRMED"),
            VerificationLevels.ALL,
        )
    }

    @Test
    fun onlyAConfirmedCheckSupportsACompletionClaim() {
        /*
         * THE RULE THE WHOLE FEATURE EXISTS FOR. Accepted means the mechanism acknowledged it: an API
         * answered 200, a command exited zero. That is not the same as the effect having happened, and
         * an agent that reports it as done has told the person something it did not check.
         */
        assertTrue(VerificationLevels.supportsCompletion(VerificationLevels.CONFIRMED))
        assertFalse(VerificationLevels.supportsCompletion(VerificationLevels.ACCEPTED))
        assertFalse(VerificationLevels.supportsCompletion(VerificationLevels.ATTEMPTED))
    }

    @Test
    fun anUnknownLevelIsRefusedRatherThanFiledUnderAttempted() {
        /*
         * FILING A TYPO UNDER ATTEMPTED WOULD LOSE THE CHECK that was actually made, and it would
         * read as modesty rather than as a fault. Null is the honest answer and the executor turns it
         * into a refusal naming the three that exist.
         */
        assertNull(VerificationLevels.of("verified"))
        assertNull(VerificationLevels.of("done"))
        assertNull(VerificationLevels.of(""))
        assertNull(VerificationLevels.of("CONFIRMED!"))
    }

    @Test
    fun theLevelIsAcceptedInAnyCaseButStoredAsTheVocabularyWord() {
        assertEquals(VerificationLevels.CONFIRMED, VerificationLevels.of("confirmed"))
        assertEquals(VerificationLevels.CONFIRMED, VerificationLevels.of("CONFIRMED"))
        assertEquals(VerificationLevels.CONFIRMED, VerificationLevels.of("  Confirmed "))
        assertEquals(VerificationLevels.ACCEPTED, VerificationLevels.of("accepted"))
    }

    @Test
    fun eachLevelHasItsOwnWordsForAPerson() {
        /*
         * A LEVEL SHOWN AS ITS RAW CONSTANT TELLS A PERSON NOTHING. "ACCEPTED" reads like success;
         * "accepted by the mechanism, not confirmed on the device" reads like what happened.
         */
        val labels = VerificationLevels.ALL.map { VerificationLevels.label(it) }
        assertEquals("every level has a distinct label", 3, labels.toSet().size)
        labels.forEach { label ->
            assertTrue("'$label' says more than the constant", label.length > 12)
        }
        assertNotEquals(
            VerificationLevels.label(VerificationLevels.ACCEPTED),
            VerificationLevels.label(VerificationLevels.CONFIRMED),
        )
    }

    @Test
    fun theEventNameIsInTheVocabularyAndHasARestorePolicy() {
        /*
         * A NEW EVENT TYPE WITH NO POLICY IS AN EVENT THAT VANISHES ON RESTORE, silently. The
         * coverage test asserts the map and the list agree, so this only needs to confirm the name
         * arrived in both rather than checking the whole correspondence again.
         */
        assertTrue(AgentEventTypes.ALL.contains(AgentEventTypes.VERIFICATION_RECORDED))
        assertTrue(
            "and it is classified",
            AgentEventTypes.RESTORE_POLICY.containsKey(AgentEventTypes.VERIFICATION_RECORDED),
        )
        assertEquals(AgentEventTypes.VERIFICATION_RECORDED, AgentVerification.EVENT)
    }
}
