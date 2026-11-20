// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Replay, proved on the JVM.
 *
 * THESE ARE THE TESTS THAT MAKE RECOVERY TRUSTWORTHY. Everything else in Phase 3 is about
 * storing events faithfully; this is about whether reading them back produces the session
 * that actually ran. A reducer that silently drops a frame, merges two responses or loses
 * a tool-call id would pass every storage test and still hand an agent a history it cannot
 * continue from.
 */
class AgentRunReplayTest {

    private fun event(seq: Long, type: String, payload: Map<String, String>) =
        AgentRunReplay.Event(sequence = seq, type = type, payload = payload)

    /** A text delta and its completion must produce exactly one assistant message. */
    @Test
    fun aCompletedRequestProducesOneAssistantMessage() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                event(1, AgentEventTypes.MODEL_REQUEST_STARTED, mapOf("requestId" to "r1", "roundIndex" to "1")),
                event(2, AgentEventTypes.MODEL_OUTPUT_BATCH, mapOf(
                    "requestId" to "r1",
                    "frames" to OutputFrames.encode(listOf(
                        AgentRunReplay.OutputFrame(AgentRunReplay.OutputFrame.Kind.TEXT, "Hel"),
                        AgentRunReplay.OutputFrame(AgentRunReplay.OutputFrame.Kind.TEXT, "lo"),
                    ))
                )),
                event(3, AgentEventTypes.MODEL_TEXT_COMPLETED, mapOf("requestId" to "r1", "text" to "Hello")),
                event(4, AgentEventTypes.MODEL_REQUEST_COMPLETED, mapOf()),
            )
        )

        assertTrue("a clean ledger must replay consistently", rebuilt.consistent)
        assertEquals(1, rebuilt.messages.size)
        assertEquals(AgentRunReplay.Message.Said("Hello"), rebuilt.messages.first())
        assertEquals("Hello", rebuilt.requests.single().text)
        assertEquals(2, rebuilt.requests.single().frames.size)
    }

    /** Reasoning and text stay in separate accumulators and keep their arrival order. */
    @Test
    fun reasoningAndTextDoNotMergeIntoOneAnother() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                event(1, AgentEventTypes.MODEL_REQUEST_STARTED, mapOf("requestId" to "r1")),
                event(2, AgentEventTypes.MODEL_OUTPUT_BATCH, mapOf(
                    "frames" to OutputFrames.encode(listOf(
                        AgentRunReplay.OutputFrame(AgentRunReplay.OutputFrame.Kind.REASONING, "thinking"),
                        AgentRunReplay.OutputFrame(AgentRunReplay.OutputFrame.Kind.TEXT, "answer"),
                        AgentRunReplay.OutputFrame(AgentRunReplay.OutputFrame.Kind.REASONING, " more"),
                    ))
                )),
                event(3, AgentEventTypes.MODEL_REASONING_COMPLETED, mapOf("requestId" to "r1", "reasoning" to "thinking more")),
                event(4, AgentEventTypes.MODEL_TEXT_COMPLETED, mapOf("requestId" to "r1", "text" to "answer")),
            )
        )

        val request = rebuilt.requests.single()
        assertEquals("answer", request.text)
        assertEquals("thinking more", request.reasoning)
        assertEquals(3, request.frames.size)
        assertEquals(AgentRunReplay.OutputFrame.Kind.REASONING, request.frames[0].kind)
        assertEquals(AgentRunReplay.OutputFrame.Kind.TEXT, request.frames[1].kind)
        assertEquals(AgentRunReplay.OutputFrame.Kind.REASONING, request.frames[2].kind)
    }

    /**
     * The one that matters most: a completion with no request is refused, not absorbed.
     *
     * Attaching it to whatever request happened to be open is how two separate responses
     * get merged and nobody finds out, so the reducer reports the inconsistency instead.
     */
    @Test
    fun aCompletionWithNoRequestIsReportedRatherThanAttached() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                event(1, AgentEventTypes.MODEL_TEXT_COMPLETED, mapOf("requestId" to "ghost", "text" to "orphan")),
            )
        )

        assertFalse("an unattributable completion must not read as consistent", rebuilt.consistent)
        assertTrue(rebuilt.problems.any { "unknown request" in it })
        assertTrue("nothing may be materialised from it", rebuilt.messages.isEmpty())
    }

    /** A tool call and its result pair on the model's own call id. */
    @Test
    fun aToolCallAndItsResultShareTheModelsCallId() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                event(1, AgentEventTypes.MODEL_REQUEST_STARTED, mapOf("requestId" to "r1")),
                event(2, AgentEventTypes.MODEL_TOOL_CALL_PROPOSED, mapOf(
                    "tool" to "shellExec", "callId" to "call_abc", "arguments" to "{\"cmd\":\"ls\"}"
                )),
                event(3, AgentEventTypes.TOOL_CALL_STARTED, mapOf(
                    "invocationId" to "inv1", "tool" to "shellExec",
                    "arguments" to "{\"cmd\":\"ls\"}", "proposedArguments" to "{\"cmd\":\"ls\"}"
                )),
                event(4, AgentEventTypes.TOOL_CALL_COMPLETED, mapOf(
                    "invocationId" to "inv1", "tool" to "shellExec", "result" to "{\"out\":\"file.txt\"}"
                )),
            )
        )

        val call = rebuilt.messages.filterIsInstance<AgentRunReplay.Message.ToolCall>().single()
        assertEquals("call_abc", call.callId)
        assertEquals("shellExec", call.name)
        assertFalse(call.failed)
        assertEquals(AgentRunReplay.Invocation.State.COMPLETED, rebuilt.invocations.single().state)
    }

    /** Two calls to the same tool take two different call ids rather than both taking the first. */
    @Test
    fun twoCallsToOneToolDoNotShareACallId() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                event(1, AgentEventTypes.MODEL_REQUEST_STARTED, mapOf("requestId" to "r1")),
                event(2, AgentEventTypes.MODEL_TOOL_CALL_PROPOSED, mapOf("tool" to "readFile", "callId" to "call_1", "arguments" to "{}")),
                event(3, AgentEventTypes.MODEL_TOOL_CALL_PROPOSED, mapOf("tool" to "readFile", "callId" to "call_2", "arguments" to "{}")),
                event(4, AgentEventTypes.TOOL_CALL_STARTED, mapOf("invocationId" to "a", "tool" to "readFile", "arguments" to "{}")),
                event(5, AgentEventTypes.TOOL_CALL_COMPLETED, mapOf("invocationId" to "a", "result" to "one")),
                event(6, AgentEventTypes.TOOL_CALL_STARTED, mapOf("invocationId" to "b", "tool" to "readFile", "arguments" to "{}")),
                event(7, AgentEventTypes.TOOL_CALL_COMPLETED, mapOf("invocationId" to "b", "result" to "two")),
            )
        )

        val calls = rebuilt.messages.filterIsInstance<AgentRunReplay.Message.ToolCall>()
        assertEquals(2, calls.size)
        assertEquals(listOf("call_1", "call_2"), calls.map { it.callId })
    }

    /** A started call with no completion stays outstanding and never becomes a fabricated result. */
    @Test
    fun anUnfinishedToolCallStaysOutstandingAndIsNotInvented() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                event(1, AgentEventTypes.MODEL_REQUEST_STARTED, mapOf("requestId" to "r1")),
                event(2, AgentEventTypes.TOOL_CALL_STARTED, mapOf(
                    "invocationId" to "inv1", "tool" to "sendSms", "arguments" to "{\"to\":\"x\"}"
                )),
            )
        )

        assertEquals(
            "an unresolved call must remain visibly started",
            AgentRunReplay.Invocation.State.STARTED,
            rebuilt.invocations.single().state
        )
        assertTrue("no result may be invented for it", rebuilt.messages.none { it is AgentRunReplay.Message.ToolCall })
    }

    /** An unknown outcome is a fact about the run, not a message the model was given. */
    @Test
    fun anUnknownOutcomeIsRecordedButNotMaterialisedAsAMessage() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                event(1, AgentEventTypes.TOOL_CALL_STARTED, mapOf("invocationId" to "inv1", "tool" to "placeCall")),
                event(2, AgentEventTypes.TOOL_CALL_OUTCOME_UNKNOWN, mapOf(
                    "invocationId" to "inv1", "reason" to "the process died mid-call"
                )),
            )
        )

        assertEquals(
            AgentRunReplay.Invocation.State.OUTCOME_UNKNOWN,
            rebuilt.invocations.single().state
        )
        assertTrue(
            "an unknown outcome must not be dressed as a tool response",
            rebuilt.messages.none { it is AgentRunReplay.Message.ToolCall }
        )
    }

    /** A question survives replay, and its answer clears it and enters the conversation. */
    @Test
    fun aQuestionSurvivesAndItsAnswerClearsIt() {
        val withQuestion = AgentRunReplay.replay(
            listOf(event(1, AgentEventTypes.QUESTION_OPENED, mapOf("question" to "which database?")))
        )
        assertEquals("which database?", withQuestion.pendingQuestion)

        val answered = AgentRunReplay.replay(
            listOf(
                event(1, AgentEventTypes.QUESTION_OPENED, mapOf("question" to "which database?")),
                event(2, AgentEventTypes.QUESTION_ANSWERED, mapOf("answer" to "the local one")),
            )
        )
        assertNull("an answered question is no longer pending", answered.pendingQuestion)
        assertEquals(
            AgentRunReplay.Message.Instruction("the local one"),
            answered.messages.single()
        )
    }

    /** Counters come from the events, not from an estimate. */
    @Test
    fun roundsAndExchangesAreReadFromTheEvents() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                event(1, AgentEventTypes.MODEL_REQUEST_STARTED, mapOf("requestId" to "r1", "roundIndex" to "1", "exchangeIndex" to "0")),
                event(2, AgentEventTypes.MODEL_REQUEST_STARTED, mapOf("requestId" to "r2", "roundIndex" to "2", "exchangeIndex" to "1")),
                event(3, AgentEventTypes.MODEL_REQUEST_STARTED, mapOf("requestId" to "r3", "roundIndex" to "3", "exchangeIndex" to "2")),
            )
        )
        assertEquals(3, rebuilt.rounds)
        assertEquals(2, rebuilt.exchangeCount)
    }

    /** A compaction event carries the whole notebook, so replay never re-derives it. */
    @Test
    fun aCompactionCarriesTheNotebookWhole() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                event(1, AgentEventTypes.SESSION_COMPACTED, mapOf("notebook" to "folded at round 4: ran shellExec")),
            )
        )
        assertEquals("folded at round 4: ran shellExec", rebuilt.notebook)
    }

    /** Events arriving out of order are still applied in sequence, because the list is sorted. */
    @Test
    fun replayAppliesEventsInSequenceOrderRegardlessOfInputOrder() {
        val rebuilt = AgentRunReplay.replay(
            listOf(
                event(3, AgentEventTypes.MODEL_TEXT_COMPLETED, mapOf("requestId" to "r1", "text" to "done")),
                event(1, AgentEventTypes.MODEL_REQUEST_STARTED, mapOf("requestId" to "r1")),
                event(4, AgentEventTypes.MODEL_REQUEST_COMPLETED, mapOf()),
            )
        )
        assertTrue(rebuilt.consistent)
        assertEquals(AgentRunReplay.Message.Said("done"), rebuilt.messages.single())
    }

    /** The frame codec round-trips, including quotes, newlines and a source index. */
    @Test
    fun theFrameCodecRoundTripsAwkwardText() {
        val original = listOf(
            AgentRunReplay.OutputFrame(AgentRunReplay.OutputFrame.Kind.TEXT, "he said \"hi\"\nthen left", 7),
            AgentRunReplay.OutputFrame(AgentRunReplay.OutputFrame.Kind.REASONING, "tab\there", null),
        )
        val decoded = OutputFrames.decode(OutputFrames.encode(original))
        assertEquals(original, decoded)
    }

    /** The writer's names and the reducer's names are the same set; a typo would drop an event. */
    @Test
    fun theDomainEventNamesCoverEveryTypeTheReducerHandles() {
        // Every branch the reducer reads must be a name this set contains, so an event can
        // never silently fall through to `else -> Unit` because of a spelling difference.
        val handled = listOf(
            AgentEventTypes.MODEL_REQUEST_STARTED,
            AgentEventTypes.MODEL_OUTPUT_BATCH,
            AgentEventTypes.MODEL_TEXT_COMPLETED,
            AgentEventTypes.MODEL_REASONING_COMPLETED,
            AgentEventTypes.MODEL_TOOL_CALL_PROPOSED,
            AgentEventTypes.MODEL_REQUEST_COMPLETED,
            AgentEventTypes.TOOL_CALL_STARTED,
            AgentEventTypes.TOOL_CALL_COMPLETED,
            AgentEventTypes.TOOL_CALL_FAILED,
            AgentEventTypes.TOOL_CALL_REFUSED,
            AgentEventTypes.TOOL_CALL_OUTCOME_UNKNOWN,
            AgentEventTypes.QUESTION_OPENED,
            AgentEventTypes.QUESTION_ANSWERED,
            AgentEventTypes.SESSION_COMPACTED,
            AgentEventTypes.MESSAGE_CREATED,
        )
        handled.forEach {
            assertTrue("$it must be in ALL so writers and readers agree", it in AgentEventTypes.ALL)
        }
        assertEquals("no duplicate names", AgentEventTypes.ALL.size, AgentEventTypes.ALL.distinct().size)
    }
}
