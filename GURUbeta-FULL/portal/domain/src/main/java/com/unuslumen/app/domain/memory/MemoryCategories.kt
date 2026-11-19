// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.memory

/**
 * The memory category vocabulary, in one place (issue 485).
 *
 * WHY THIS EXISTS. `addMemoryFact` took a category as free text with no
 * enumeration, no normalisation and no check, so the categories in the brain were
 * whatever a model happened to type. `user_preference`, `user-preferences`,
 * `preference`, `preferences` and `Preferences` were five different categories
 * holding one kind of fact, and `searchMemoryFacts`'s category filter could find
 * only one of them at a time. A filter over an open vocabulary is not a filter.
 *
 * THE SET IS NOT INVENTED HERE. Every name below is taken from Steven's own
 * Archivist prompt (`Desktop/guru-agents/archivist.md`, section 6), which splits
 * them into seven established categories and ten further ones it permits. Until
 * now the app knew none of those names existed, so the prompt described a
 * vocabulary the storage layer did not have, which is the exact class of defect
 * where a prompt reads as configured while the system does nothing.
 *
 * WHY A CLOSED SET WITH ALIASES RATHER THAN FREE TEXT. Writing an unknown category
 * is refused, and the refusal names the permitted set, so a caller that reaches for
 * `tool_defect` learns in one sentence that the vocabulary exists and what is in
 * it. Silently accepting anything is the behaviour being removed. Adding a member
 * is a one-line change in this file, which is what issue 466 needs.
 */
object MemoryCategories {

    /** The seven the Archivist prompt calls established. */
    val ESTABLISHED: List<String> = listOf(
        "user_preference",
        "personal_info",
        "project_info",
        "decision",
        "pattern",
        "relationship",
        "knowledge",
    )

    /**
     * The further ones the Archivist prompt permits.
     *
     * The prompt's wording is "use additional meaningful categories where
     * appropriate, such as", so this list is open by construction and adding to it
     * is following the document rather than overriding it.
     */
    val ADDITIONAL: List<String> = listOf(
        "guru_identity",
        "guru_preference",
        "guru_experience",
        "guru_belief",
        "shared_history",
        "temporary_state",
        "correction",
        "lesson",
        "capability",
        "commitment",

        // A BROKEN TOOL AND THE WAY ROUND IT (issue 466).
        //
        // Workarounds are the most frequently reused knowledge an agent has: a tool
        // with a bug is hit repeatedly, across sessions, on the same device, and the
        // fix is always the same. Before these two names there was no shape for it,
        // so the three available homes were all wrong. `knowledge` competes with
        // every other fact and is retrieved by similarity rather than relevance. A
        // note to self is keyed on what the human will say, so it never fires when
        // the agent hits the same bug again. The conversation is eaten by compaction.
        //
        // This is the storage the self-correction loop needs: "every mistake I make
        // is a signal, every correction I apply is a permanent upgrade" had no place
        // to write the upgrade down.
        "tool_defect",

        // The method that works while the defect stands, kept separate because a
        // workaround outlives neither the defect nor its own usefulness, and an agent
        // needs to be able to find the working method without reading the whole bug
        // report every time.
        "workaround",
    )

    /** Every permitted category, established first, in the prompt's own order. */
    val ALL: List<String> = ESTABLISHED + ADDITIONAL

    /**
     * Spellings that mean one of the canonical categories.
     *
     * THE ISSUE NAMED THESE EXACTLY: `user-preference`, `preference` and
     * `Preferences` all holding user preferences. They are normalised rather than
     * refused, because a caller that wrote one of them was reaching for a real
     * category and refusing it would teach the wrong lesson. A caller that wrote
     * something genuinely outside the set IS refused, because there is no honest
     * way to guess which category a word like `tool_defect` was meant to be.
     */
    private val ALIASES: Map<String, String> = buildMap {
        // Separator and case variation for every canonical name.
        for (name in ALL) {
            put(name.replace('_', '-'), name)
            put(name.replace("_", ""), name)
        }
        // The specific near-misses the issue measured. The plural with a separator
        // is here explicitly because the separator sweep above only covers the
        // canonical SINGULAR name, and `user-preferences` is one of the spellings
        // the issue found holding a fifth of the user preferences.
        put("preference", "user_preference")
        put("preferences", "user_preference")
        put("userpreference", "user_preference")
        put("userpreferences", "user_preference")
        put("user-preference", "user_preference")
        put("user-preferences", "user_preference")
        put("personal", "personal_info")
        put("personalinfo", "personal_info")
        put("project", "project_info")
        put("projectinfo", "project_info")
        put("decisions", "decision")
        put("patterns", "pattern")
        put("relationships", "relationship")
        put("facts", "knowledge")
        put("fact", "knowledge")
        put("identity", "guru_identity")
        put("history", "shared_history")
        put("lesson_learned", "lesson")
        put("lessons", "lesson")
        // The defect pair, including the plural, because an agent writing one of
        // these is reaching for the real category and the plural is what people type.
        put("tool-defect", "tool_defect")
        put("tooldefect", "tool_defect")
        put("tool_defects", "tool_defect")
        put("tool-defects", "tool_defect")
        put("work-arounds", "workaround")
        put("work_arounds", "workaround")
        put("workarounds", "workaround")
    }

    /**
     * The category to store for what the caller wrote, or null with the reason.
     *
     * `Result` rather than a sentinel, because "not permitted" and "normalise it to
     * this" are different outcomes and a caller has to be able to tell them apart:
     * the first is a refusal to pass to the model, the second is a silent repair it
     * should never see.
     */
    fun normalise(raw: String?): Normalisation {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty()) {
            return Normalisation.Refused(
                "The 'category' argument is required. Permitted categories: ${ALL.joinToString(", ")}."
            )
        }

        val canonicalShape = trimmed.lowercase().replace(' ', '_')

        if (canonicalShape in ALL) return Normalisation.Canonical(canonicalShape)

        ALIASES[canonicalShape]?.let { return Normalisation.Repaired(it, trimmed) }

        return Normalisation.Refused(
            "\"$trimmed\" is not a memory category. Permitted categories: ${ALL.joinToString(", ")}."
        )
    }

    /**
     * Whether a stored row's category matches a caller's filter.
     *
     * BOTH SIDES ARE NORMALISED. Rows already in the brain were written before this
     * vocabulary existed, so a filter for `user_preference` must still find a row
     * stored as `preferences`, or the repair only applies to facts written from
     * today and every memory the user already has stays unfindable by category.
     *
     * That is the issue's fourth requirement — existing rows are matched, not
     * silently left — answered without a migration, because the mapping is total
     * in the read direction even when a stored spelling was never canonical.
     */
    fun matches(filter: String?, storedCategory: String): Boolean {
        if (filter == null) return true
        val wanted = normalise(filter)
        val stored = normalise(storedCategory)

        // BOTH SIDES UNRECOGNISED: compare the raw spellings, so a row whose
        // category predates this vocabulary is still findable by the exact word it
        // carries. That is what keeps `hive_marker` and `thread` rows reachable.
        if (wanted is Normalisation.Refused && stored is Normalisation.Refused) {
            return filter.trim().equals(storedCategory.trim(), ignoreCase = true)
        }

        val wantedName = when (wanted) {
            is Normalisation.Canonical -> wanted.category
            is Normalisation.Repaired -> wanted.category
            // An unrecognised filter matches nothing rather than everything: a
            // filter that silently widens to the whole brain is the empty-result
            // lie in the other direction.
            is Normalisation.Refused -> return false
        }
        val storedName = when (stored) {
            is Normalisation.Canonical -> stored.category
            is Normalisation.Repaired -> stored.category
            is Normalisation.Refused -> storedCategory.trim().lowercase().replace(' ', '_')
        }
        return storedName == wantedName
    }

    /** The outcome of reading a caller's category. */
    sealed interface Normalisation {
        /** Already a permitted name. */
        data class Canonical(val category: String) : Normalisation

        /**
         * A spelling of a permitted name, repaired.
         *
         * `asWritten` is kept so the reply can say what it stored and what it was
         * given: a silent repair is better than a refusal but must not be invisible.
         */
        data class Repaired(val category: String, val asWritten: String) : Normalisation

        /** Outside the set, with a sentence naming the permitted set. */
        data class Refused(val reason: String) : Normalisation
    }
}
