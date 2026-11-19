// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The FTS5 query a tool-history search is allowed to send (issues 465 and 470).
 *
 * THE DEFECT. The caller's text went straight into `tool_results_fts MATCH`, with
 * only a space-containing query wrapped in quotes. FTS5 parses that argument as a
 * query in its own grammar, where `.`, `/`, `:`, `-`, `(` and `*` are operators, so
 * the exact strings an agent needs when debugging a network problem threw a syntax
 * error. The call site caught every exception and returned an empty list, and an
 * empty list from a search is indistinguishable from "nothing matched".
 *
 * These tests hold the escaping rule, which is the half that can be proven without
 * a device. The other half, that SQLite accepts the expression and finds the row,
 * is a database test and the PR body says so.
 */
class ToolResultQueryTest {

    // ------------------------------------------------------------------
    // The strings that used to break the query.
    // ------------------------------------------------------------------

    @Test
    fun a_dotted_address_is_one_quoted_term_not_three() {
        val expression = ToolResultQuery.ftsExpression("192.0.2.44")
        assertEquals("\"192.0.2.44\"", expression)
    }

    @Test
    fun a_slash_separated_path_is_one_quoted_term() {
        val expression = ToolResultQuery.ftsExpression("/api/v1/devices?status=ok")
        assertEquals("\"/api/v1/devices?status=ok\"", expression)
    }

    @Test
    fun a_product_name_with_punctuation_stays_intact() {
        // A hyphen, a plus and a colon are all FTS5 operators unquoted.
        assertEquals("\"GL-MT3000+5G:router\"", ToolResultQuery.ftsExpression("GL-MT3000+5G:router"))
    }

    @Test
    fun an_asterisk_cannot_become_a_wildcard_the_caller_did_not_ask_for() {
        // Unquoted, `firm*` is a prefix search. The caller typed a star, not a
        // request to scan every row whose text begins with those letters.
        assertEquals("\"firm*\"", ToolResultQuery.ftsExpression("firm*"))
    }

    @Test
    fun a_paren_cannot_become_grouping_syntax() {
        assertEquals("\"(unclosed\"", ToolResultQuery.ftsExpression("(unclosed"))
    }

    @Test
    fun a_bare_or_cannot_become_an_operator() {
        // "OR" alone is FTS5 syntax; quoted, it is the word.
        assertEquals("\"OR\"", ToolResultQuery.ftsExpression("OR"))
    }

    // ------------------------------------------------------------------
    // Multi-word queries.
    // ------------------------------------------------------------------

    @Test
    fun multiple_terms_are_unioned_rather_than_implicitly_anded() {
        // FTS5's default between adjacent terms is AND, so the old body only found
        // rows containing both words. A caller searching its own history for two
        // words wants everything mentioning either.
        assertEquals(
            "\"router\" OR \"firmware\"",
            ToolResultQuery.ftsExpression("router firmware"),
        )
    }

    @Test
    fun extra_whitespace_does_not_create_empty_terms() {
        assertEquals(
            "\"router\" OR \"firmware\"",
            ToolResultQuery.ftsExpression("   router    firmware   "),
        )
        assertEquals(
            "\"router\" OR \"firmware\"",
            ToolResultQuery.ftsExpression("router\n\tfirmware"),
        )
    }

    @Test
    fun a_double_quote_inside_a_term_is_escaped_by_doubling() {
        // FTS5's own rule: a literal quote inside a quoted string is written twice.
        // Without this, the quote would end the string and the rest became syntax.
        val expression = ToolResultQuery.ftsExpression("""say "hello"""")
        assertNotNull(expression)
        assertTrue(
            "the inner quote must be doubled, got $expression",
            expression!!.contains("\"\""),
        )
    }

    // ------------------------------------------------------------------
    // Queries with nothing to search for.
    // ------------------------------------------------------------------

    @Test
    fun punctuation_on_its_own_returns_null_rather_than_a_query_that_matches_nothing() {
        // A query of nothing but operators would either throw inside FTS5 or match
        // everything depending on the engine. Neither is an answer to "what did I
        // run", so the caller is told there is nothing to search for.
        assertNull(ToolResultQuery.ftsExpression("..."))
        assertNull(ToolResultQuery.ftsExpression("/"))
        assertNull(ToolResultQuery.ftsExpression("   "))
        assertNull(ToolResultQuery.ftsExpression(null))
    }

    // ------------------------------------------------------------------
    // The fallback.
    // ------------------------------------------------------------------

    @Test
    fun the_fallback_takes_the_longest_plain_word_not_a_punctuation_fragment() {
        assertEquals("devices", ToolResultQuery.fallbackTerm("192.0.2.44 /api/v1/devices?status=ok"))
    }

    @Test
    fun the_fallback_ignores_fragments_too_short_to_be_worth_a_scan() {
        assertNull(ToolResultQuery.fallbackTerm("a b /x .y"))
    }
}
