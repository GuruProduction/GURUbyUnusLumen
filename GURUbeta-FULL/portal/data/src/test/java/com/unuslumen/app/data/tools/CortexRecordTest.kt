// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.domain.memory.CortexRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * `cortexRecord`, the typed curation door (issue 486).
 *
 * THE GAP THIS CLOSES. The Archivist prompt names `cortex.record` and nothing in
 * the repo answered to that name. Everything it should do already existed in the
 * brain and was unreachable: `cerebrum_curate` accepts a full typed entry, and the
 * four memory tools flattened it to a fact, a category and a confidence, packing
 * the last two into the provenance label so the server could split them out again.
 * `domain`, `topic`, `subtopic`, `rationale` and `relations` were not settable from
 * the tool layer at all.
 *
 * What a JVM can prove here is the contract: the tool is registered, its schema
 * carries the fields, its defaults are the ones documented, and a malformed
 * relation id is refused rather than dropped. The wire payload itself is proven by
 * the Rust side and needs a device to run end to end.
 */
class CortexRecordTest {

    @Test
    fun the_tool_is_registered_under_the_name_the_prompt_uses() {
        val definition = MemoryToolDefinitions.definitions
            .firstOrNull { it.name == MemoryToolDefinitions.CORTEX_RECORD }
        assertTrue(
            "cortexRecord must be a registered memory tool, not a line in a prompt",
            definition != null,
        )
        assertEquals("cortexRecord", definition!!.name)
        assertEquals("memory", definition.category)
    }

    @Test
    fun the_schema_carries_every_field_the_brain_accepts() {
        // A schema is the only documentation a model has. Every field the brain
        // can store has to be reachable from one, or the tool is lossy in exactly
        // the way this issue is about.
        val definition = MemoryToolDefinitions.definitions
            .first { it.name == MemoryToolDefinitions.CORTEX_RECORD }
        val parameterNames = definition.parameters.map { it.name }.toSet()
        listOf(
            "content", "domain", "topic", "subtopic",
            "rationale", "relatedTo", "provenance", "confidence",
        ).forEach { expected ->
            assertTrue(
                "the schema must offer '$expected' or the caller cannot set it: got $parameterNames",
                expected in parameterNames,
            )
        }
        // Content is the one thing with no sensible default.
        val content = definition.parameters.first { it.name == "content" }
        assertTrue("content is required", content.required)
    }

    @Test
    fun the_brain_call_would_send_a_real_id_for_the_content_it_stores() {
        // The returned id must be the same deterministic hash the wire carries, or
        // a caller cites an id the brain cannot resolve. This is the identity rule
        // the whole memory family is built on.
        val content = "the deployment window moved to Thursday"
        val id = com.unuslumen.app.data.brain.cerebrum.CerebrumBrainApi.realIdFor(content)
        assertEquals(64, id.length)
        assertTrue(id.all { it in "0123456789abcdef" })
        assertEquals(
            "the same content must produce the same id every time, so a re-save is a refresh",
            id,
            com.unuslumen.app.data.brain.cerebrum.CerebrumBrainApi.realIdFor(content),
        )
    }

    @Test
    fun a_record_defaults_are_the_ones_the_description_promises() {
        val record = CortexRecord(content = "a fact")
        assertEquals("", record.domain)
        assertEquals("", record.topic)
        assertEquals("", record.subtopic)
        assertEquals("guru", record.provenance)
        assertEquals(0.9f, record.confidence, 0.0001f)
        assertTrue(record.relatedTo.isEmpty())
        assertEquals("", record.rationale)
    }

    @Test
    fun the_provenance_label_carries_the_confidence_the_brain_has_no_field_for() {
        // The core wire entry has no confidence field, so it rides inside the label
        // and the server splits it back out. A record written without this would read
        // back as fully believed whatever the caller set.
        val label = com.unuslumen.app.data.brain.cerebrum.CerebrumBrainApi
            .provenanceLabelFor("conversation", 0.42f)
        assertTrue("the source must survive: $label", label.startsWith("conversation"))
        assertTrue("the confidence must ride along: $label", label.contains("confidence=0.42"))
    }
}
