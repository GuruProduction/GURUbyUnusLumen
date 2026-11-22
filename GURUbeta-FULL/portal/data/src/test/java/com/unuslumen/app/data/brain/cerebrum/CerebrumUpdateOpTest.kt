// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.brain.cerebrum

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.nio.charset.StandardCharsets

/**
 * A production update sends the brain's own Update op, and nothing else does the job for it.
 *
 * THE DEFECT THIS HOLDS SHUT. `updateMemoryFact` wrote the new text as an Upsert and then
 * deleted the old id. The brain's `Update` op is the ONLY path that records the text a
 * revision replaced, and the merged update-history work is built on it: `history_for`,
 * `previous_content` and the `superseded` field in `memory.updated`. All of it was merged,
 * shipped and unreachable, because nothing in the app ever sent the op.
 *
 * The shape is asserted from the bytes, because a payload the engine cannot parse fails at
 * serde and reads as an unexplained error rather than as a wrong shape.
 */
class CerebrumUpdateOpTest {

    @Before
    fun installTransport() {
        CerebrumHost.runningOverrideForTest = true
    }

    @After
    fun removeTransport() {
        CerebrumHost.frameTransportForTest = null
        CerebrumHost.runningOverrideForTest = null
    }

    private fun idHex(byte: Int): String = "%02x".format(byte).repeat(32)

    private fun capture(frames: MutableList<Pair<Int, String>>): Unit {
        CerebrumHost.frameTransportForTest = { frameType, payloadHex ->
            frames += frameType to String(CerebrumBrainApi.hexToBytes(payloadHex), StandardCharsets.UTF_8)
            // A curate ack the client accepts, then a retrieve body for the read-back.
            val reply = """{"executed":{"success":true,"details":[{"memory_id":[0],"status":"Updated","detail":"x"}]},"side_effects":{}}"""
            CerebrumBrainApi.bytesToHex(reply.toByteArray(StandardCharsets.UTF_8))
        }
    }

    @Test
    fun `the update payload is the engine's Update variant carrying only the changed fields`() {
        val frames = mutableListOf<Pair<Int, String>>()
        capture(frames)

        val payload = CerebrumBrainApi.updateJsonOpByHex(
            idHexLower = idHex(7),
            newContent = "the meeting is on tuesday",
            category = "knowledge",
            confidence = 0.42f,
            reasonText = "the meeting moved a day",
        )

        assertTrue("it must be an Update variant: $payload", payload.contains("\"Update\""))
        assertFalse(
            "an Upsert is a create, and it was the old path: $payload",
            payload.contains("\"Upsert\""),
        )
        assertFalse("and never a Delete: $payload", payload.contains("\"Delete\""))
        assertTrue("the row id must be present: $payload", payload.contains("memory_id"))
        assertTrue("the new text must be present: $payload", payload.contains("the meeting is on tuesday"))
        assertTrue(
            "the category rides in the domain, because that is where a category lives: $payload",
            payload.contains("\"domain\":\"knowledge\""),
        )
        assertTrue(
            "the confidence rides in provenance, or an update that changes it silently keeps the old one: $payload",
            payload.contains("confidence=0.4200"),
        )
        assertTrue(
            "the caller's reason is recorded: $payload",
            payload.contains("the meeting moved a day"),
        )
    }

    @Test
    fun `an update is dispatched as a CURATE frame`() {
        val frames = mutableListOf<Pair<Int, String>>()
        capture(frames)

        // Only the CURATE hop is needed here: the read-back is BrainService's business and is
        // covered where the service is tested. What this asserts is which frame carries the op.
        val curateFrame = CerebrumBrainApi.updateJsonOpByHex(
            idHexLower = idHex(7),
            newContent = "text",
            category = "knowledge",
            confidence = 0.4f,
            reasonText = "r",
        )
        val ack = CerebrumBrainApi.curateJsonOps(listOf(curateFrame))
        assertTrue("the brain must accept the op: ${ack.raw}", ack.success)
        assertEquals(
            "only a CURATE frame may carry it",
            listOf(CerebrumBrainApi.FRAME_CURATE),
            frames.map { it.first },
        )
    }
}
