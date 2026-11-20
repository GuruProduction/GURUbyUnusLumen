// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an agent may say, and which of those stops the work.
 *
 * THE SPECIFICATION'S SECTION 5.3 NAMES SEVEN REASONS an agent initiates communication:
 * clarification, findings, blockage, approval, risk, milestone and review. Until this existed all of
 * them arrived as a clarification, which halted the run, so an agent stopped to mention a finding.
 * The test pins both halves: that the six that should not stop do not, and that the one that must
 * stop still does.
 */
class AgentInitiatedMessagesTest {

    @Test
    fun everyReasonTheSpecificationNamesHasAType() {
        // The seven in section 5.3. Approval is not here because it is its own pathway with its own
        // six facts and its own decision, not a message an agent composes.
        val stored = AgentInitiatedMessages.OFFERED.map { it.type }.toSet()
        assertEquals(
            "six kinds, each a name the vocabulary knows",
            setOf(
                AgentMessageTypes.CLARIFICATION,
                AgentMessageTypes.FINDING,
                AgentMessageTypes.BLOCKER,
                AgentMessageTypes.MILESTONE,
                AgentMessageTypes.RISK,
                AgentMessageTypes.REVIEW_REQUEST,
            ),
            stored,
        )
        assertTrue(
            "and every one is in the message vocabulary rather than invented",
            AgentMessageTypes.ALL.containsAll(stored),
        )
    }

    @Test
    fun onlyAClarificationStopsTheRun() {
        /*
         * THE WHOLE POINT OF THE SPLIT. A finding that halted the work, or a risk that blocked it,
         * would make an agent stop for things it should simply mention, and the person would be
         * interrupted for something that needed no interruption.
         */
        assertEquals(
            "exactly one shape pauses",
            listOf(AgentMessageTypes.CLARIFICATION),
            AgentInitiatedMessages.OFFERED.filter { it.pauses }.map { it.type },
        )
        assertTrue(AgentInitiatedMessages.pauses(AgentMessageTypes.CLARIFICATION))
        assertFalse(AgentInitiatedMessages.pauses(AgentMessageTypes.FINDING))
        assertFalse(AgentInitiatedMessages.pauses(AgentMessageTypes.BLOCKER))
        assertFalse(AgentInitiatedMessages.pauses(AgentMessageTypes.MILESTONE))
        assertFalse(AgentInitiatedMessages.pauses(AgentMessageTypes.RISK))
        assertFalse(AgentInitiatedMessages.pauses(AgentMessageTypes.REVIEW_REQUEST))
    }

    @Test
    fun aMilestoneIsNotACompletion() {
        /*
         * COLLAPSING THESE WOULD BE A REAL DEFECT. A completion means the assignment is done and the
         * report is the answer; a milestone means the agent is still working. A room that drew a
         * milestone as a completion would show a finished run while the agent was still typing.
         */
        assertTrue(AgentMessageTypes.MILESTONE != AgentMessageTypes.COMPLETION)
    }

    @Test
    fun aRiskIsNotAnError() {
        assertTrue("a risk has not failed", AgentMessageTypes.RISK != AgentMessageTypes.ERROR)
    }

    @Test
    fun theModelMayNameEitherItsWordOrTheStoredType() {
        /*
         * TOLERANCE AT ONE BOUNDARY. A model offered "finding" may echo FINDING, and refusing that
         * would teach it nothing except that the tool is unreliable. Both spellings resolve to the
         * same option, and case is not a distinction anybody meant.
         */
        assertEquals(AgentMessageTypes.FINDING, AgentInitiatedMessages.option("finding")!!.type)
        assertEquals(AgentMessageTypes.FINDING, AgentInitiatedMessages.option("FINDING")!!.type)
        assertEquals(AgentMessageTypes.FINDING, AgentInitiatedMessages.option(" Finding ")!!.type)
        assertEquals(
            AgentMessageTypes.REVIEW_REQUEST,
            AgentInitiatedMessages.option("review")!!.type,
        )
    }

    @Test
    fun anUnknownKindResolvesToNothingRatherThanTheNearestThing() {
        /*
         * A GUESS HERE WOULD FILE A REPORT UNDER A TYPE THE PERSON DID NOT CHOOSE and a surface
         * cannot draw. Null is the honest answer, and the executor turns it into a refusal naming
         * the kinds that exist.
         */
        assertNull(AgentInitiatedMessages.option("complaint"))
        assertNull(AgentInitiatedMessages.option(""))
        assertFalse(
            "and an unknown type never pauses a run",
            AgentInitiatedMessages.pauses("COMPLAINT"),
        )
    }

    @Test
    fun everyOptionCarriesARuleRatherThanALabel() {
        /*
         * THE DESCRIPTION IS THE CONTRACT WITH THE MODEL. A model choosing between a finding and a
         * risk needs to know which one it has, so a one-word label is not enough and an empty
         * description would leave the choice to guesswork.
         */
        AgentInitiatedMessages.OFFERED.forEach { option ->
            assertTrue(
                "${option.value} needs a real rule: '${option.description}'",
                option.description.length > 40,
            )
            assertNotNull(AgentInitiatedMessages.option(option.value))
        }
    }

    @Test
    fun theDescriptionListNamesEveryOption() {
        val described = AgentInitiatedMessages.describeForModel()
        AgentInitiatedMessages.OFFERED.forEach { option ->
            assertTrue("'${option.value}' is offered in the prompt: $described", described.contains(option.value))
        }
        assertEquals("and the values are what the tool parameter lists",
            AgentInitiatedMessages.OFFERED.map { it.value },
            AgentInitiatedMessages.values(),
        )
    }
}
