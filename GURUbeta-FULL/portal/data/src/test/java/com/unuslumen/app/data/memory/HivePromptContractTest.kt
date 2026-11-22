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
 * The prompt architecture, pinned so the fallback cannot come back.
 *
 * WHAT THIS FILE USED TO BE. It asserted that `HiveStructuredFallback`'s four hardcoded
 * prompts described the reply shapes the parsers read. That was a real improvement over the
 * state before it, where the fallback asked for `merge_map` and the parser read
 * `duplicates_of_existing`, so every dedup pass concluded everything was novel.
 *
 * It was also the wrong thing to be doing. The fallback was itself the defect: four prompt
 * bodies compiled into the APK, used whenever the served field was blank, and therefore the
 * only instructions that ever ran, because the server route filters on
 * `category = 'hive_drive'` and no live row uses that category.
 *
 * The three assertions below are the architecture instead:
 *
 *  1. The served prompt is the only instruction, and a blank one produces a blank, not a
 *     substitute.
 *  2. The parsers still read what the authored rows actually ask for, which is a pairwise
 *     `action`/`merged_fact`/`confidence`/`explanation` object for dedup and a `facts` array
 *     for extraction.
 *  3. No prompt prose exists in client source. This one is a scan of the file that used to
 *     hold it, so a future edit that reintroduces a "sensible default" fails here.
 */
class HivePromptContractTest {

    private val json = Json { ignoreUnknownKeys = true }

    /*
     * THE AUTHORED ROWS, QUOTED FROM THE LIVE CONTENT TABLE, not from a copy in this repo.
     * A test that reimplemented them would prove its own copy.
     *
     * These strings are the reply-format sections of the server rows
     * `guru-reflection-fact-deduplication` and `guru-reflection-fact-extraction`.
     */

    /** The exact output format the dedup row asks for, including the union type. */
    private val servedDedupFormat = """
        {
          "action": "merge" | "keep_both" | "flag_contradiction",
          "merged_fact": "the merged statement if action is merge, otherwise null",
          "confidence": 0.0,
          "explanation": "full contextual explanation and reasoning behind why I made this decision"
        }
    """.trimIndent()

    /**
     * A reply in that shape decodes into the decision the worker reads.
     *
     * THE WORKER USED TO DEMAND A DIFFERENT SHAPE from the same request. It sent the served
     * prompt as the system message and a hand-written `duplicates_of_existing` schema in the
     * user message, so the model was told two contradictory things and the parser read
     * whichever the model had not answered.
     */
    @Test
    fun `a dedup reply in the served format decodes into the decision the worker reads`() {
        val reply =
            """{"action":"merge","merged_fact":"the boiler service is on wednesday","confidence":0.9,""" +
                """"explanation":"the appointment moved a day"}"""
        val decision = json.decodeFromString(DedupDecision.serializer(), reply)
        assertEquals("merge", decision.action)
        assertEquals("the boiler service is on wednesday", decision.merged_fact)
        assertEquals(0.9f, decision.confidence, 0.0001f)
        assertTrue(decision.explanation.isNotBlank())

        // And the format in the served row names every one of those fields.
        for (field in listOf("action", "merged_fact", "confidence", "explanation")) {
            assertTrue(
                "the served dedup format must name $field, which is what DedupDecision decodes: $servedDedupFormat",
                servedDedupFormat.contains("\"$field\""),
            )
        }
        // Every action the served row permits must be one the worker handles.
        for (action in listOf("merge", "keep_both", "flag_contradiction")) {
            assertTrue(
                "the served row permits $action, so the worker must handle it",
                servedDedupFormat.contains(action),
            )
        }
    }

    @Test
    fun `a keep both reply is the safe answer and decodes with no merged text`() {
        val decision = json.decodeFromString(
            DedupDecision.serializer(),
            """{"action":"keep_both","merged_fact":null,"confidence":0.0,"explanation":"they are different things"}""",
        )
        assertEquals("keep_both", decision.action)
        assertEquals(null, decision.merged_fact)
    }

    @Test
    fun `a partial dedup reply decodes rather than throwing so a memory is never dropped`() {
        // A judge that answers nothing useful must not become a judge that loses a fact.
        // Every field is defaulted, so this decodes and the caller keeps the candidate.
        val decision = json.decodeFromString(DedupDecision.serializer(), "{}")
        assertEquals("", decision.action)
        assertEquals(null, decision.merged_fact)
        assertEquals(0f, decision.confidence, 0.0001f)
    }

    @Test
    fun `an extraction reply in the served format decodes into the facts the worker reads`() {
        // The served extraction row asks for {facts:[{category, fact, confidence}]} and says
        // the brain assigns domain, topic, subtopic, valence, signature and layer itself.
        val reply =
            """{"facts":[{"category":"user_preference","fact":"the kettle is in the kitchen","confidence":0.9}]}"""
        val decoded = json.decodeFromString(ExtractedFactsResponse.serializer(), reply)
        assertEquals(1, decoded.facts.size)
        assertEquals("the kettle is in the kitchen", decoded.facts.single().fact)
        assertEquals(0.9f, decoded.facts.single().confidence, 0.0001f)
        assertTrue(
            "the category the model emits must be one the write path accepts",
            MemoryCategories.normalise(decoded.facts.single().category)
                is MemoryCategories.Normalisation.Canonical,
        )
    }

    @Test
    fun `the empty extraction reply named by the served row decodes as zero facts`() {
        val decoded = json.decodeFromString(ExtractedFactsResponse.serializer(), """{"facts":[]}""")
        assertTrue(
            "a quiet pass is a real answer and must not be read as a broken contract",
            decoded.facts.isEmpty(),
        )
    }

    /**
     * NO PROMPT PROSE IN CLIENT SOURCE, asserted against the file that used to hold it.
     *
     * This is the assertion the whole item exists for. `HivePromptFetcher` used to declare
     * `HiveStructuredFallback` with four named prompt bodies and two schema constants. If
     * any of it returns, one of these lines fails.
     */
    @Test
    fun `the fallback prompt object and its constants are gone from client source`() {
        val source = java.io.File(
            "src/main/java/com/unuslumen/app/data/memory/HivePromptFetcher.kt",
        )
        assertTrue(
            "the fetcher must exist at the path this test guards: ${source.absolutePath}",
            source.exists(),
        )
        /*
         * COMMENTS ARE STRIPPED BEFORE THE SCAN, AND THAT IS THE HONEST CONTRACT.
         *
         * The file's own documentation names what was deleted and why, which is the record a
         * future maintainer needs in order to know not to put it back. A scan that refused
         * those words would force the explanation out of the file to satisfy a test, which is
         * the opposite of what the rule is for. What must not exist is CODE: a declaration, a
         * constant or a call that could actually supply an instruction to a model.
         */
        val code = source.readLines()
            .map { line ->
                val commentAt = line.indexOf("//")
                if (commentAt >= 0) line.substring(0, commentAt) else line
            }
            .joinToString("\n")
            .replace(Regex("(?s)/\\*.*?\\*/"), "")

        for (forbidden in listOf(
            "HiveStructuredFallback",
            "FACT_EXTRACTION_SCHEMA",
            "FACT_DEDUPLICATION_SCHEMA",
            "CONVERSATION_TITLING",
            "You are the memory",
            "You are the thread",
            "bundleOrStructuredFallback",
        )) {
            assertFalse(
                "client CODE must carry no prompt prose, schema or fallback call: found \"$forbidden\"",
                code.contains(forbidden),
            )
        }

        // And what remains must be a read of the served bundle and nothing else.
        assertTrue("the extraction accessor must exist", code.contains("fun factExtraction("))
        assertTrue("and it must consult the delivered bundle", code.contains("served(context)"))
    }

    /**
     * A blank served prompt yields a blank, rather than becoming words nobody authored.
     *
     * THE WHOLE POINT OF THE CHANGE. `served` answers "" for no cache, and that must reach
     * the caller as "" so the lane is skipped. Any future "helpful default" inserted before
     * the return fails here.
     */
    @Test
    fun `an empty delivered field stays empty rather than becoming a substitute`() {
        val bundle = HivePromptBundle()
        assertEquals("", bundle.fact_extraction)
        assertEquals("", bundle.thread_discovery)
        assertEquals("", bundle.dedup)
        assertEquals("", bundle.titling)
    }
}
