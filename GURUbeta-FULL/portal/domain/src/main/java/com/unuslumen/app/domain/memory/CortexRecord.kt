// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.memory

/**
 * One record written through the full curation surface (issue 486).
 *
 * WHY THIS EXISTS BESIDE `addMemoryFact`. The brain underneath is typed: an entry
 * carries a domain, a topic, a subtopic, content, relations, provenance and a
 * rationale, and `cerebrum_curate` on the MCP side already accepts all of them. The
 * app's four memory tools flattened that to three strings — a fact, a category and
 * a confidence — and packed the last two into the provenance label so the server
 * could split them back out. `domain`, `topic`, `subtopic`, `rationale` and
 * `relations` were not settable from the tool layer at all, and
 * `CerebrumBrainApi` hardcoded `memory`/`recall`/`fact` for every write.
 *
 * So an agent asked to build a structured knowledge graph was being handed a text
 * field. Archivist's whole job is deciding where a memory belongs and how it
 * connects, which are exactly the fields the tool layer discarded.
 *
 * `addMemoryFact` keeps its shape, because a caller that wants one line of prose
 * stored should not have to name a hierarchy to do it. This is the second door,
 * for a caller that does.
 */
data class CortexRecord(
    /** What the memory says, in prose. Required: a record with no content is not a record. */
    val content: String,

    /**
     * The top-level branch: the subject area, such as `the_human`, `guru`, `work`.
     *
     * Blank falls back to `memory`, which is the same default the old path used, so
     * a caller that does not care sees no change.
     */
    val domain: String = "",

    /** The branch inside the domain, such as `preferences` or `projects`. */
    val topic: String = "",

    /** The leaf inside the topic, such as `food` or `guru_itself`. */
    val subtopic: String = "",

    /**
     * Why this memory is worth keeping, in the caller's own words.
     *
     * The Archivist prompt requires the reason a revised position changed to be
     * preserved alongside the position, and this is the field that carries it. An
     * absent rationale is legitimate for an ordinary fact; a change without one is
     * what the prompt forbids, and only the caller knows which this is.
     */
    val rationale: String = "",

    /**
     * Ids of memories this one relates to, as 64-hex strings.
     *
     * The brain stores these as typed `Relation` rows; this is the caller-facing
     * shape. An id that is not 64 hex characters is a caller error and is refused
     * rather than silently dropped, because a dropped relation is a link the
     * caller believes exists.
     */
    val relatedTo: List<String> = emptyList(),

    /** Where the fact came from, such as `conversation`, `tool_result`, `web`. */
    val provenance: String = "guru",

    /** How far the caller believes it, 0 to 1. */
    val confidence: Float = 0.9f,
)
