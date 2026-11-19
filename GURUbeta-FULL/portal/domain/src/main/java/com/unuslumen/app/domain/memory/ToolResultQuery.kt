// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.memory

/**
 * Turns what a caller typed into a query SQLite's FTS5 will actually parse
 * (issues 465 and 470).
 *
 * THE DEFECT. `searchToolResults` handed the caller's text straight to
 * `tool_results_fts MATCH :query`, wrapped in `"<text>" OR <text>` when it
 * contained a space. FTS5 does not read its MATCH argument as literal text: it
 * reads it as a query in its own grammar, where `.`, `/`, `:`, `-`, `(`, `*` and a
 * bare `OR` are all operators. So a dotted address, a slash-separated URL path or
 * a product name with punctuation threw `fts5: syntax error near "..."`, and the
 * call site caught every exception and returned an EMPTY LIST.
 *
 * An empty list from a search is indistinguishable from "nothing matched", which
 * is why an agent re-ran socket probes against the same three devices and fetched
 * the same file three times rather than reading the answer it already had.
 *
 * WHAT THIS DOES. Every term is wrapped in double quotes, which is FTS5's own
 * escape for "this is a literal string, not syntax", and any double quote inside a
 * term is doubled, which is how you embed one. The terms are joined with `OR`, so a
 * multi-word search is a union rather than an implicit AND that no row satisfies.
 *
 * WHY `OR` RATHER THAN THE IMPLICIT AND. FTS5's default between adjacent terms is
 * AND, so "router firmware" only matched a row containing both words. A caller
 * searching its own history for two words wants everything that mentions either,
 * ranked, because the row it is looking for may well mention only one.
 *
 * WHY NOT A PREFIX STAR. `firm*` would match `firmware`, and `*` outside a quoted
 * phrase is legal, but it also turns every search into a scan and produces matches
 * the caller did not ask for. Exact terms are the honest default.
 */
object ToolResultQuery {

    /**
     * Build the FTS5 MATCH expression for a caller's text.
     *
     * Returns null when the text holds nothing searchable, so a caller can report
     * "there is nothing to search for" rather than running a query that matches
     * everything or nothing depending on the engine's mood.
     */
    fun ftsExpression(rawQuery: String?): String? {
        val terms = termsOf(rawQuery)
        if (terms.isEmpty()) return null
        return terms.joinToString(" OR ") { term -> "\"" + term.replace("\"", "\"\"") + "\"" }
    }

    /**
     * The terms, split the way a person reads a query.
     *
     * Whitespace separates terms. Punctuation STAYS INSIDE a term, because a dotted
     * address and a slash path are single identifiers and splitting them would
     * search for their fragments. A term that is nothing but punctuation carries no
     * word to match and is dropped.
     */
    fun termsOf(rawQuery: String?): List<String> {
        val trimmed = rawQuery?.trim().orEmpty()
        if (trimmed.isEmpty()) return emptyList()
        return trimmed
            .split(Regex("\\s+"))
            .map { it.trim() }
            .filter { term -> term.any { it.isLetterOrDigit() } }
    }

    /**
     * The simplest term in a query, for the fallback path.
     *
     * When FTS returns nothing at all, even though the rows exist, the honest
     * second attempt is a LIKE scan over the longest plain word. Choosing the
     * longest alphanumeric run rather than the first token avoids picking a
     * fragment of punctuation and avoids a one-character wildcard scan.
     */
    fun fallbackTerm(rawQuery: String?): String? =
        termsOf(rawQuery)
            .flatMap { term -> term.split(Regex("[^A-Za-z0-9]+")) }
            .filter { it.length >= 3 }
            .maxByOrNull { it.length }
}
