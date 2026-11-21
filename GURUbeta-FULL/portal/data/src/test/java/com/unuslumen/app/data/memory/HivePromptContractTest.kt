// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.memory

import com.unuslumen.app.domain.memory.MemoryCategories
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The extraction and dedup prompts describe the same reply shapes their parsers read.
 *
 * THE DEFECT THIS PINS. The offline fallback asked the model for
 * `{"merge_map":[{"keepFactIndex":0,"mergeFactIndices":[1]}]}`, while `HiveMindWorker`
 * decodes a `DedupJudgement` of `{duplicates_of_existing, internal_duplicates}` and sent that
 * instruction in the same request. So the system prompt and the user prompt contradicted
 * each other, the model answered one of them, the parser read the other, found nothing, and
 * every dedup pass concluded that everything was novel.
 *
 * That is the silent half of the resurrection defect. A pass that always says "novel"
 * re-proposes facts the brain already holds, and the write that follows resolves to the id
 * derived from that content, which is the row a deletion archived.
 *
 * The fallback also listed seven categories while `MemoryCategories` permits seventeen, so
 * six names the app accepts were unreachable exactly when the server was unreachable.
 *
 * EVERY ASSERTION HERE IS A ROUND TRIP: the prompt says a shape, and then that shape is
 * actually decoded by the same serializer the worker uses. A prompt that merely mentions a
 * field is not a contract; one whose example parses is.
 */
class HivePromptContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `the dedup prompt example decodes into the judgement the worker parses`() {
        val schema = HiveStructuredFallback.FACT_DEDUPLICATION_SCHEMA

        // The prompt must name the exact fields the parser reads, by name.
        assertTrue(
            "the prompt must name duplicates_of_existing, which is what DedupJudgement decodes: $schema",
            schema.contains("duplicates_of_existing"),
        )
        assertTrue(
            "and internal_duplicates, for the same reason: $schema",
            schema.contains("internal_duplicates"),
        )
        assertFalse(
            "the old merge_map shape is what the parser could never read, so it must not return",
            schema.contains("merge_map"),
        )

        // And a reply in that shape must actually decode, with its indices intact.
        val reply = "{\"duplicates_of_existing\":[0],\"internal_duplicates\":[[1,2]]}"
        val judgement = json.decodeFromString(DedupJudgement.serializer(), reply)
        assertEquals(listOf(0), judgement.duplicates_of_existing)
        assertEquals(listOf(listOf(1, 2)), judgement.internal_duplicates)
    }

    @Test
    fun `an empty dedup reply in the prompt's shape decodes as nothing duplicated`() {
        // The prompt tells the model that everything distinct produces [] in both lists.
        // That has to decode rather than throw, or the "keep everything" path is unreachable.
        val judgement = json.decodeFromString(
            DedupJudgement.serializer(),
            "{\"duplicates_of_existing\":[],\"internal_duplicates\":[]}",
        )
        assertTrue(judgement.duplicates_of_existing.isEmpty())
        assertTrue(judgement.internal_duplicates.isEmpty())
    }

    @Test
    fun `the extraction prompt offers every category the write path permits`() {
        /*
         * THE VOCABULARY IS ONE SET. The fallback used to list seven names, so a model
         * running offline could not emit `tool_defect`, `workaround`, `lesson`, `correction`,
         * `capability` or `commitment`, and the write path would have refused them anyway.
         */
        val prompt = HiveStructuredFallback.FACT_EXTRACTION
        for (category in MemoryCategories.ALL) {
            assertTrue(
                "the extraction prompt must permit $category, because the write path does: $prompt",
                prompt.contains(category),
            )
        }
    }

    @Test
    fun `an extraction reply in the prompt's shape decodes into the facts the worker reads`() {
        // The same round trip as the dedup case: the prompt's example must parse.
        val reply = """{"facts":[{"category":"knowledge","fact":"the kettle is in the kitchen","confidence":0.9}]}"""
        val decoded = json.decodeFromString(ExtractedFactsResponse.serializer(), reply)
        assertEquals(1, decoded.facts.size)
        assertEquals("knowledge", decoded.facts.single().category)
        assertEquals(0.9f, decoded.facts.single().confidence, 0.0001f)
        assertTrue(
            "the category the model emits must be one the write path accepts",
            MemoryCategories.normalise(decoded.facts.single().category)
                is MemoryCategories.Normalisation.Canonical,
        )
    }

    @Test
    fun `the empty extraction reply named by the prompt decodes as zero facts`() {
        val decoded = json.decodeFromString(ExtractedFactsResponse.serializer(), """{"facts":[]}""")
        assertTrue(
            "a quiet pass is a real answer and must not be read as a broken contract",
            decoded.facts.isEmpty(),
        )
    }

    @Test
    fun `the thread discovery prompt example decodes into the threads the worker reads`() {
        val reply =
            """{"threads":[{"title":"the boiler","summary":"the boiler keeps failing","conversationIds":["conv-1"]}]}"""
        val decoded = json.decodeFromString(DiscoveredThreadsResponse.serializer(), reply)
        assertEquals(1, decoded.threads.size)
        assertEquals("the boiler", decoded.threads.single().title)
        assertEquals(listOf("conv-1"), decoded.threads.single().conversationIds)
    }

    @Test
    fun `every fallback prompt asks for JSON and forbids prose outside it`() {
        /*
         * ALL FOUR SHARE ONE FRAMING RULE. Each downstream parser finds the first `{` and
         * the last `}` and decodes what lies between, so a reply wrapped in prose or a code
         * fence still parses. But a reply that is prose INSTEAD of an object does not, and
         * the lane goes silently empty. So the instruction is part of the contract, not
         * politeness.
         */
        val prompts = mapOf(
            "extraction" to HiveStructuredFallback.FACT_EXTRACTION,
            "thread discovery" to HiveStructuredFallback.THREAD_DISCOVERY,
            "deduplication" to HiveStructuredFallback.FACT_DEDUPLICATION,
            "titling" to HiveStructuredFallback.CONVERSATION_TITLING,
        )
        for ((name, prompt) in prompts) {
            assertTrue("$name must demand JSON: $prompt", prompt.contains("JSON"))
            assertTrue(
                "$name must forbid prose outside the object, or the lane goes silently empty: $prompt",
                prompt.contains("no prose outside the object", ignoreCase = true),
            )
        }
    }
}
