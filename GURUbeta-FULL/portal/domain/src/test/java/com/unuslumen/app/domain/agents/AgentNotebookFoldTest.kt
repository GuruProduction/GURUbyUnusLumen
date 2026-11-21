// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import com.unuslumen.app.domain.model.AiMessage
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What survives the fold, and what is cut.
 *
 * THE SPECIFICATION'S SECTION 2.3.3 ASKS COMPACTION TO PRESERVE OUTSTANDING QUESTIONS, DECISIONS,
 * TOOL RESULTS, CONSTRAINTS AND THE CURRENT PLAN. Tool results and instructions were always kept.
 * A decision and a plan were not, because they arrived as ordinary tool calls and were flattened
 * into a log line that cut before the reason. This is the test that makes those two survive.
 */
class AgentNotebookFoldTest {

    private val documents = AgentNotebookFold.DocumentTools(
        decisions = setOf("recordDecision"),
        plans = setOf("createPlan", "updatePlanStep"),
    )

    private fun tool(
        name: String,
        arguments: String,
        result: String = """{"ok":true}""",
        failed: Boolean = false,
    ) = AiMessage.ToolCall(
        uuid = "u",
        id = "c",
        name = name,
        rawContent = arguments,
        resultRawContent = result,
        time = 0L,
        isFailed = failed,
    )

    @Test
    fun aDecisionIsWrittenUnderItsOwnHeadingRatherThanAsAToolCall() {
        /*
         * THE DEFECT THIS CLOSES. Read back as "RAN recordDecision", the note describes a call and
         * says nothing about what was decided. Read as DECIDED it carries the thing the run turned
         * on, which is the whole reason the tool exists.
         */
        val line = AgentNotebookFold.line(
            tool("recordDecision", """{"decision":"use the cache","reason":"it is already warm"}"""),
            documents,
        )!!

        assertTrue("it is marked as a decision: $line", line.startsWith(AgentNotebookFold.DECIDED_LABEL))
        assertTrue(line.contains("use the cache"))
        assertFalse("and it is not described as a tool that ran", line.contains("RAN"))
    }

    @Test
    fun aDecisionKeepsItsReasonWhereAnOrdinaryResultWouldHaveBeenCut() {
        /*
         * THE PART A TRUNCATION REMOVES FIRST IS THE PART THAT MATTERS. A decision's reason sits
         * behind its problem and its approach, so at 300 characters it is the first thing lost. The
         * larger allowance is what keeps it, and this pins that the allowance is genuinely larger
         * rather than the same number under a second name.
         */
        /*
         * LONG ENOUGH TO BE CUT BY THE ORDINARY ALLOWANCE AND SHORT ENOUGH TO SURVIVE THE DOCUMENT
         * ONE, which is the only length at which the two allowances can be told apart.
         */
        val long = "use the cache because the cache is already warm and the fetch is expensive " +
            "and the data has not changed since the last run, which the checksum confirms. " +
            "The alternative was to re-read every file from storage and re-parse it, which would " +
            "cost several seconds per item and gain nothing, because the checksum already proves " +
            "the content is identical to what was loaded earlier in this run"
        val line = AgentNotebookFold.line(
            tool("recordDecision", """{"decision":"$long","reason":"$long"}"""),
            documents,
        )!!

        assertTrue(
            "a decision gets more room than an ordinary result",
            AgentNotebookFold.DOCUMENT_EXCERPT_CHARS > AgentNotebookFold.EXCERPT_CHARS,
        )
        assertTrue(
            "and the whole decision survives inside it",
            line.contains(long),
        )

        val ordinary = AgentNotebookFold.line(tool("readFile", """{"path":"$long"}"""), documents)!!
        assertFalse(
            "while an ordinary result of the same length is cut",
            ordinary.contains(long),
        )
    }

    @Test
    fun aPlanIsWrittenUnderItsOwnHeading() {
        val line = AgentNotebookFold.line(
            tool("createPlan", """{"title":"ship it","steps":"write,test,release"}"""),
            documents,
        )!!
        assertTrue("it is marked as a plan: $line", line.startsWith(AgentNotebookFold.PLANNED_LABEL))
        assertTrue(line.contains("ship it"))
    }

    @Test
    fun aStepUpdateIsAlsoAPlanRatherThanAStrayToolCall() {
        val line = AgentNotebookFold.line(
            tool("updatePlanStep", """{"planId":"p1","stepIndex":2,"status":"done"}"""),
            documents,
        )!!
        assertTrue("a ticked box is part of the plan: $line", line.startsWith(AgentNotebookFold.PLANNED_LABEL))
    }

    @Test
    fun anOrdinaryToolCallKeepsBothItsArgumentsAndItsResult() {
        val line = AgentNotebookFold.line(
            tool("readFile", """{"path":"/a"}""", """{"content":"hello"}"""),
            documents,
        )!!
        assertTrue(line.contains("RAN readFile"))
        assertTrue("the arguments are kept", line.contains("/a"))
        assertTrue("and so is the result", line.contains("hello"))
    }

    @Test
    fun aFailedCallSaysSoWithoutLosingItsName() {
        val line = AgentNotebookFold.line(
            tool("sendSms", """{"to":"x"}""", """{"error":"denied"}""", failed = true),
            documents,
        )!!
        assertTrue("the failure is visible: $line", line.contains("(FAILED)"))
        assertTrue("and the tool is still named", line.contains("sendSms"))
    }

    @Test
    fun aPartialMessageIsNotWrittenDownAsAFinishedThought() {
        /*
         * A STREAM THAT HAD NOT FINISHED WHEN THE FOLD RAN. Writing it down would turn half a
         * sentence into a note that reads as a completed finding, which is worse than silence
         * because the agent will act on it.
         */
        assertNull(
            AgentNotebookFold.line(
                AiMessage.StreamingAssistant(uuid = "u", partialContent = "I think the answer", time = 0L),
                documents,
            )
        )
        assertNull(
            AgentNotebookFold.line(
                AiMessage.StreamingToolCall(uuid = "u", toolName = "readFile", partialContent = "{", time = 0L),
                documents,
            )
        )
    }

    @Test
    fun anEmptyAssistantMessageLeavesNothing() {
        assertNull(
            AgentNotebookFold.line(AiMessage.AssistantMessage(content = "", time = 0L, uuid = "u"), documents)
        )
    }

    @Test
    fun anInstructionIsKeptBecauseItIsWhatTheWorkWas() {
        val line = AgentNotebookFold.line(
            AiMessage.UserMessage(uuid = "u", content = "sort the invoices", time = 0L),
            documents,
        )!!
        assertTrue(line.startsWith("INSTRUCTION:"))
        assertTrue(line.contains("sort the invoices"))
    }

    @Test
    fun theBoundaryLineSaysWhereTheFoldHappened() {
        /*
         * A READER WHO DID NOT FOLD IT CANNOT OTHERWISE TELL one continuous account from two that
         * were joined, and the round number is what lines the note up with the ledger.
         */
        val folded = AgentNotebookFold.append(
            existing = "",
            middle = listOf(AiMessage.UserMessage(uuid = "u", content = "step one", time = 0L)),
            round = 7,
            documents = documents,
        )
        assertTrue("the boundary is dated to its round: $folded", folded.contains("folded at round 7"))
        assertTrue("and the instruction survives it", folded.contains("step one"))
    }

    @Test
    fun foldingTwiceKeepsTheEarlierNotesAndMarksTheSecondBoundary() {
        val first = AgentNotebookFold.append(
            existing = "",
            middle = listOf(AiMessage.UserMessage(uuid = "a", content = "first era", time = 0L)),
            round = 3,
            documents = documents,
        )
        val second = AgentNotebookFold.append(
            existing = first,
            middle = listOf(AiMessage.UserMessage(uuid = "b", content = "second era", time = 0L)),
            round = 9,
            documents = documents,
        )

        assertTrue("the earlier notes are still there", second.contains("first era"))
        assertTrue("and the later work is added", second.contains("second era"))
        assertTrue("with both boundaries visible", second.contains("round 3") && second.contains("round 9"))
    }

    @Test
    fun aDecisionToolTheCallerDoesNotRecogniseIsStillWrittenDownAsAToolCall() {
        /*
         * THE SET IS SUPPLIED, SO A MISMATCH MUST DEGRADE RATHER THAN DROP. If the caller passed the
         * wrong names, a decision becomes an ordinary call — worse than intended, and still better
         * than a message silently vanishing from the record.
         */
        val line = AgentNotebookFold.line(
            tool("recordDecision", """{"decision":"x","reason":"y"}"""),
            AgentNotebookFold.DocumentTools(),
        )!!
        assertTrue("it falls back to the ordinary shape: $line", line.startsWith("RAN recordDecision"))
    }
}
