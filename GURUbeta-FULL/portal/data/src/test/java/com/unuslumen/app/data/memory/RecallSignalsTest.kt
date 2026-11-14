// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * RecallSignals tests.
 *
 * These exist because issue 418's first defect is that the whole recall loop was
 * wired to nothing: `confirmUsed` and `recordRecallFailure` had no caller, so
 * `confirmed_used_at` was NULL on all 187 rows and `restated_without_injection`
 * was 0 on all 187. The methods were fine. The decision that would have fed them
 * did not exist.
 *
 * So the tests below are about the decision, and the one that matters most is
 * `a memory the reply does not draw on is not confirmed`. That test is the
 * difference between a precision column that measures retrieval and a precision
 * column that measures how many memories happened to exist.
 */
class RecallSignalsTest {

    private fun memory(id: String, content: String) =
        InjectedMemory(
            memoryId = id,
            lane = "fact",
            rankInLane = 1,
            score = 0.9f,
            content = content,
        )

    @Test
    fun `a memory the reply draws on is confirmed`() {
        val confirmed = RecallSignals.confirmationsFor(
            injected = listOf(memory("aaa", "the spare key is in the kitchen drawer")),
            assistantReply = "You left the spare key in the kitchen drawer, on the left.",
            userQuery = "where did I put it",
        )

        assertEquals(setOf("aaa"), confirmed)
    }

    @Test
    fun `a memory the reply does not draw on is not confirmed`() {
        val confirmed = RecallSignals.confirmationsFor(
            injected = listOf(memory("aaa", "the spare key is in the kitchen drawer")),
            assistantReply = "Your boiler pressure is at 1.2 bar, which is healthy.",
            userQuery = "how is the heating",
        )

        assertTrue(
            "an unrelated reply must not confirm a memory, or precision measures nothing",
            confirmed.isEmpty(),
        )
    }

    @Test
    fun `words the user already typed are not evidence of recall`() {
        // The reply repeats the user's own vocabulary and shares it with the
        // memory. Nothing here proves the model recalled anything: it was handed
        // the words "kitchen drawer" in the question.
        val confirmed = RecallSignals.confirmationsFor(
            injected = listOf(memory("aaa", "the kitchen drawer holds the spare key")),
            assistantReply = "The kitchen drawer is where you said it was.",
            userQuery = "is the spare key in the kitchen drawer",
        )

        assertTrue(
            "a reply echoing the query's own words proves nothing",
            confirmed.isEmpty(),
        )
    }

    @Test
    fun `one shared word is coincidence and confirms nothing`() {
        val confirmed = RecallSignals.confirmationsFor(
            injected = listOf(memory("aaa", "the boiler service is booked for March")),
            assistantReply = "March is coming up soon.",
            userQuery = "what is next",
        )

        assertTrue(
            "a single shared content word is not enough evidence to confirm",
            confirmed.isEmpty(),
        )
    }

    @Test
    fun `function words alone never confirm a memory`() {
        val confirmed = RecallSignals.confirmationsFor(
            injected = listOf(memory("aaa", "this is the one that they were with")),
            assistantReply = "That is the one that they were with, yes.",
            userQuery = "which one",
        )

        assertTrue(
            "overlap on grammar is guaranteed and must not count",
            confirmed.isEmpty(),
        )
    }

    @Test
    fun `two memories sharing different words are confirmed independently`() {
        val confirmed = RecallSignals.confirmationsFor(
            injected = listOf(
                memory("aaa", "boiler service booked march"),
                memory("bbb", "car insurance renews august"),
                memory("ccc", "passport expires 2031"),
            ),
            assistantReply = "The boiler service is in March and your passport expires in 2031.",
            userQuery = "what is coming up",
        )

        assertEquals(
            "only the two memories the reply drew on",
            setOf("aaa", "ccc"),
            confirmed,
        )
    }

    @Test
    fun `an empty reply confirms nothing`() {
        val confirmed = RecallSignals.confirmationsFor(
            injected = listOf(memory("aaa", "boiler service booked march")),
            assistantReply = "",
            userQuery = "what is coming up",
        )

        assertTrue(confirmed.isEmpty())
    }

    @Test
    fun `nothing injected means nothing to confirm`() {
        val confirmed = RecallSignals.confirmationsFor(
            injected = emptyList(),
            assistantReply = "The boiler service is in March.",
            userQuery = "what is coming up",
        )

        assertTrue(confirmed.isEmpty())
    }

    @Test
    fun `a restated fact the preamble missed is a recall failure`() {
        val candidates = listOf(memory("aaa", "spare key lives in the kitchen drawer"))

        val restated = RecallSignals.restatementsFor(
            userMessage = "like I said, the spare key is in the kitchen drawer",
            candidates = candidates,
            alreadyInjectedIds = emptySet(),
        )

        assertEquals(1, restated.size)
        assertEquals("aaa", restated.first().memoryId)
    }

    @Test
    fun `a fact the preamble did inject is not a recall failure`() {
        val candidates = listOf(memory("aaa", "spare key lives in the kitchen drawer"))

        val restated = RecallSignals.restatementsFor(
            userMessage = "the spare key is in the kitchen drawer",
            candidates = candidates,
            alreadyInjectedIds = setOf("aaa"),
        )

        assertTrue(
            "a memory that reached the model was not missed",
            restated.isEmpty(),
        )
    }

    @Test
    fun `a message about something else restates nothing`() {
        val candidates = listOf(memory("aaa", "spare key lives in the kitchen drawer"))

        val restated = RecallSignals.restatementsFor(
            userMessage = "what is the weather like tomorrow",
            candidates = candidates,
            alreadyInjectedIds = emptySet(),
        )

        assertTrue(restated.isEmpty())
    }

    @Test
    fun `tokenising drops punctuation case and stop words`() {
        val tokens = RecallSignals.contentTokens("The Boiler-Service, it's BOOKED for March!")

        assertTrue("stop words must not survive", "the" !in tokens)
        assertTrue("short words must not survive", "for" !in tokens)
        assertTrue("content words are lowercased", "boiler" in tokens)
        assertTrue("hyphens split, both halves kept", "service" in tokens)
        assertTrue("possessive apostrophes are trimmed", "booked" in tokens)
        assertTrue("march" in tokens)
    }
}
