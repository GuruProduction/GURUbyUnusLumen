// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.brain.cerebrum

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * The frame dispatch, proved by the bytes production would actually send.
 *
 * THE DEFECT THIS HOLDS SHUT. `FRAME_QUERY` and `FRAME_PREFETCH` were declared as
 * constants and nothing ever sent them. The brain answers both over its unix socket and
 * the CLI uses both, so the whole tiered retrieval pipeline and the whole prefetch engine
 * were dead weight inside the shipped library: reachable by a command line, unreachable
 * by the app. Nothing caught it, because a constant plus a data class looks exactly like a
 * working feature from the inside.
 *
 * WHY THIS TEST CALLS THE REAL OBJECT. It would be easy, and useless, to re-implement the
 * payload builder here and assert on the copy: that proves the copy. Instead
 * [CerebrumHost.dispatchFrame] is a seam at the LAST hop only, so every byte before it is
 * the production encoder, and the responses below are hand-written from the engine's own
 * serde shapes so a parser reading the wrong field fails here rather than on a phone.
 */
class CerebrumFrameDispatchTest {

    /** Frame type and decoded payload of everything the app tried to send. */
    private data class Sent(val frameType: Int, val payload: String)

    private val sent = mutableListOf<Sent>()
    private var reply: String = "{}"

    @Before
    fun installTransport() {
        sent.clear()
        CerebrumHost.runningOverrideForTest = true
        CerebrumHost.frameTransportForTest = { frameType, payloadHex ->
            val payloadBytes = CerebrumBrainApi.hexToBytes(payloadHex)
            sent += Sent(frameType, String(payloadBytes, StandardCharsets.UTF_8))
            // The reply crosses back as hex, exactly as the JNI returns it.
            CerebrumBrainApi.bytesToHex(reply.toByteArray(StandardCharsets.UTF_8))
        }
    }

    @After
    fun removeTransport() {
        CerebrumHost.frameTransportForTest = null
        CerebrumHost.runningOverrideForTest = null
    }

    /** One 32-byte id as the engine serialises it. */
    private fun idJson(byte: Int): String = (0 until 32).joinToString(",", "[", "]") { byte.toString() }

    @Test
    fun `a query is actually dispatched with the engine's own request shape`() {
        reply = """{"results":[],"confidence":0.0,"tier_used":"HotCache","latency_ms":1}"""

        CerebrumBrainApi.query("where did I leave the van keys")

        assertEquals("exactly one frame must leave the app", 1, sent.size)
        assertEquals(
            "it must be a QUERY frame, which is the whole point: the constant existed and " +
                "no code ever used it",
            CerebrumBrainApi.FRAME_QUERY,
            sent.single().frameType,
        )
        val payload = sent.single().payload
        assertTrue("query_type must be present: $payload", payload.contains("\"query_type\""))
        assertTrue("text must be present: $payload", payload.contains("\"text\""))
        assertTrue("budget must be present: $payload", payload.contains("\"max_tokens\""))
        assertTrue(
            "the caller's words must survive the encoder: $payload",
            payload.contains("where did I leave the van keys"),
        )
    }

    @Test
    fun `a query response yields the ids and the tier that answered`() {
        reply = """{"results":[${idJson(7)}],"confidence":0.82,"tier_used":"DwmSearch","latency_ms":9}"""

        val outcome = CerebrumBrainApi.query("van keys")

        requireNotNull(outcome)
        assertEquals(1, outcome.memoryIds.size)
        assertEquals("the id must decode to 64 hex characters", 64, outcome.memoryIds.single().length)
        assertEquals("the id bytes must be exact", "07".repeat(32), outcome.memoryIds.single())
        assertEquals(0.82f, outcome.confidence, 0.0001f)
        assertEquals(
            "the answering tier is part of the answer, and it is what makes this frame " +
                "different from a search",
            "DwmSearch",
            outcome.tierUsed,
        )
        assertEquals(9L, outcome.latencyMs)
    }

    @Test
    fun `an empty query result keeps the tier and is not a parse failure`() {
        reply = """{"results":[],"confidence":0.0,"tier_used":"HotCache","latency_ms":1}"""
        val outcome = CerebrumBrainApi.query("nothing matches this")
        requireNotNull(outcome)
        assertTrue(outcome.memoryIds.isEmpty())
        assertEquals("HotCache", outcome.tierUsed)
    }

    @Test
    fun `a blank query is refused without touching the brain`() {
        assertNull(CerebrumBrainApi.query("   "))
        assertTrue(
            "a blank query must not reach the engine, which would answer it from whatever " +
                "its tiers happen to hold",
            sent.isEmpty(),
        )
    }

    @Test
    fun `an unreachable brain is reported as unreachable rather than as no results`() {
        CerebrumHost.runningOverrideForTest = false
        assertNull(CerebrumBrainApi.query("van keys"))
        assertTrue("nothing may be sent to a brain that is not running", sent.isEmpty())
    }

    @Test
    fun `a prefetch is dispatched with the engine's externally tagged enum`() {
        /*
         * `PrefetchTrigger` is an externally tagged enum, so serde expects
         * `{"SpecialistActivated":"doctor"}`. A `{kind, value}` pair parses as an unknown
         * variant, and a caller reading the error as "nothing to prefetch" would warm a
         * cache with nothing on every turn.
         */
        reply = """{"prefetched":[],"hit":false}"""

        CerebrumBrainApi.prefetch("specialist", "doctor")

        assertEquals(1, sent.size)
        assertEquals(
            "it must be a PREFETCH frame, declared as FRAME_PREFETCH and never sent before",
            CerebrumBrainApi.FRAME_PREFETCH,
            sent.single().frameType,
        )
        assertEquals(
            "the payload must be the enum's own externally tagged shape",
            """{"SpecialistActivated":"doctor"}""",
            sent.single().payload,
        )
    }

    @Test
    fun `every prefetch kind maps to its own variant`() {
        reply = """{"prefetched":[],"hit":false}"""
        CerebrumBrainApi.prefetch("topic", "mot")
        CerebrumBrainApi.prefetch("event", "dentist")
        assertEquals("""{"TopicShift":"mot"}""", sent[0].payload)
        assertEquals("""{"ScheduledEvent":"dentist"}""", sent[1].payload)
    }

    @Test
    fun `an unknown prefetch kind is refused rather than guessed at`() {
        assertNull(CerebrumBrainApi.prefetch("feeling", "lucky"))
        assertTrue("an unrecognised kind must not reach the engine", sent.isEmpty())
    }

    @Test
    fun `a prefetch hit and a miss are distinguishable`() {
        reply = """{"prefetched":[${idJson(3)}],"hit":true}"""
        val hit = CerebrumBrainApi.prefetch("specialist", "doctor")
        requireNotNull(hit)
        assertTrue(hit.hit)
        assertEquals("03".repeat(32), hit.memoryIds.single())

        sent.clear()
        reply = """{"prefetched":[],"hit":false}"""
        val miss = CerebrumBrainApi.prefetch("specialist", "doctor")
        requireNotNull(miss)
        assertFalse(
            "a miss is the engine's own verdict and must not be read as a cold engine",
            miss.hit,
        )
        assertTrue(miss.memoryIds.isEmpty())
    }

    @Test
    fun `a history request is dispatched on its own frame and parses every revision`() {
        /*
         * HISTORY HAD NO DOOR. `EventStore::history_for` and the superseded text in
         * `memory.updated` were merged, tested and reachable by nothing, so mutation audit was
         * stored and unusable. This proves the frame exists, that it carries the id, and that
         * every field a person would ask for comes back.
         */
        reply = """
            {"memory_id":${idJson(7)},"lifecycle":"Active","category":"work",
             "ever_retired":false,
             "revisions":[
               {"kind":"Created","content":"the meeting is on monday","superseded":null,
                "reason":"","actor":"guru.role=","recorded_at":"2026-11-21T10:00:00Z"},
               {"kind":"Updated","content":"the meeting is on tuesday",
                "superseded":"the meeting is on monday","reason":"the meeting moved a day",
                "actor":"guru update|confidence=0.4200","recorded_at":"2026-11-21T11:00:00Z"}]}
        """.trimIndent()

        val history = CerebrumBrainApi.history("07".repeat(32))

        assertEquals(
            "it must travel on the HISTORY frame",
            listOf(CerebrumBrainApi.FRAME_HISTORY),
            sent.map { it.frameType },
        )
        assertTrue("the id must be in the request: ${sent.single().payload}", sent.single().payload.contains("memory_id"))
        requireNotNull(history)
        assertEquals(2, history.revisions.size)
        assertEquals("Created", history.revisions[0].kind)
        assertNull(
            "a create replaced nothing; the string null must not become the word null",
            history.revisions[0].superseded,
        )
        assertEquals(
            "the superseded text is the whole point of the native Update",
            "the meeting is on monday",
            history.revisions[1].superseded,
        )
        assertEquals("the meeting moved a day", history.revisions[1].reason)
        assertEquals("Active", history.lifecycle)
        assertFalse(history.everRetired)
    }

    @Test
    fun `a deleted memory still answers a history request and says it was retired`() {
        /*
         * THE QUESTION IS ASKED PRECISELY WHEN THE MEMORY IS GONE. Every other read refuses a
         * retired row; this one must not, or a person can never find out what was deleted, by
         * whom, or why.
         */
        reply = """
            {"memory_id":${idJson(7)},"lifecycle":"Archived","category":"home",
             "ever_retired":true,
             "revisions":[{"kind":"Created","content":"the spare key is under the blue pot",
               "superseded":null,"reason":"","actor":"","recorded_at":"2026-11-21T10:00:00Z"}]}
        """.trimIndent()

        val history = CerebrumBrainApi.history("07".repeat(32))
        requireNotNull(history)
        assertTrue("it must report the retirement", history.everRetired)
        assertEquals("Archived", history.lifecycle)
        assertEquals(
            "and the text it held must still be readable from the trail",
            "the spare key is under the blue pot",
            history.revisions.single().content,
        )
    }

    @Test
    fun `a history request for a non brain id is refused without touching the brain`() {
        assertNull(CerebrumBrainApi.history("not-a-brain-id"))
        assertTrue(sent.isEmpty())
    }

    @Test
    fun `a query response that is not an object is refused rather than read as zero results`() {
        reply = "not json at all"
        assertNull(CerebrumBrainApi.query("van keys"))
    }
}
