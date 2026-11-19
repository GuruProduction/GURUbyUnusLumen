// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.portal

import com.unuslumen.app.domain.portal.SqlStatementRisk.Risk
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a SQL statement can cost, decided before it runs (issue 496).
 *
 * THE DEFECT. `sqlExecute` was a bare open-write-execute against any path, with no
 * confirmation, no target check, no snapshot and no transaction. `DROP TABLE` was
 * one call away. Every agent prompt carries a rule against exactly that — Vault's
 * sections 6 and 16, Archivist's section 10, the Self Improvement Engine's section
 * 14 — so the prompts were doing safety work that belongs in the tool.
 *
 * THE GATE IS ON CONSEQUENCE, NOT ON THE TOOL. Vault's whole remit depends on this
 * call, so reading must stay free and a careful migration must stay possible. What
 * costs is a change to structure or to bulk data.
 */
class SqlStatementRiskTest {

    // ------------------------------------------------------------------
    // Reads are free.
    // ------------------------------------------------------------------

    @Test
    fun a_select_needs_no_gate() {
        assertEquals(Risk.READ, SqlStatementRisk.classify("SELECT * FROM messages LIMIT 10"))
        assertFalse(SqlStatementRisk.requiresGate(Risk.READ))
        assertFalse(SqlStatementRisk.requiresSnapshot(Risk.READ))
    }

    @Test
    fun a_cte_and_an_explain_are_reads() {
        // `WITH ... SELECT` is how a real query is written; treating it as unsafe
        // would make every careful query pay a confirmation.
        assertEquals(Risk.READ, SqlStatementRisk.classify("WITH recent AS (SELECT 1) SELECT * FROM recent"))
        assertEquals(Risk.READ, SqlStatementRisk.classify("EXPLAIN QUERY PLAN SELECT 1"))
    }

    // ------------------------------------------------------------------
    // Mutations need a confirmation.
    // ------------------------------------------------------------------

    @Test
    fun a_bounded_update_or_delete_is_a_mutation() {
        assertEquals(Risk.MUTATION, SqlStatementRisk.classify("UPDATE messages SET role='x' WHERE id='1'"))
        assertEquals(Risk.MUTATION, SqlStatementRisk.classify("DELETE FROM messages WHERE timestamp < 5"))
        assertEquals(Risk.MUTATION, SqlStatementRisk.classify("INSERT INTO notes VALUES ('a')"))
        assertEquals(Risk.MUTATION, SqlStatementRisk.classify("CREATE TABLE t (id INTEGER)"))
        assertTrue(SqlStatementRisk.requiresGate(Risk.MUTATION))
        assertFalse(
            "a bounded change does not need a snapshot",
            SqlStatementRisk.requiresSnapshot(Risk.MUTATION),
        )
    }

    // ------------------------------------------------------------------
    // Structure and bulk data need a confirmation AND a recovery point.
    // ------------------------------------------------------------------

    @Test
    fun dropping_a_table_is_destructive() {
        assertEquals(Risk.DESTRUCTIVE, SqlStatementRisk.classify("DROP TABLE messages"))
        assertTrue(SqlStatementRisk.requiresGate(Risk.DESTRUCTIVE))
        assertTrue(SqlStatementRisk.requiresSnapshot(Risk.DESTRUCTIVE))
    }

    @Test
    fun altering_or_truncating_is_destructive() {
        assertEquals(Risk.DESTRUCTIVE, SqlStatementRisk.classify("ALTER TABLE messages ADD COLUMN x TEXT"))
        assertEquals(Risk.DESTRUCTIVE, SqlStatementRisk.classify("TRUNCATE TABLE messages"))
    }

    @Test
    fun an_unbounded_delete_or_update_is_destructive() {
        // From the caller's point of view an UPDATE or DELETE with no WHERE loses as
        // much as a DROP, and it is one missing clause away from any well-meant one.
        assertEquals(Risk.DESTRUCTIVE, SqlStatementRisk.classify("DELETE FROM messages"))
        assertEquals(Risk.DESTRUCTIVE, SqlStatementRisk.classify("UPDATE messages SET role='x'"))
    }

    @Test
    fun attaching_another_database_is_destructive() {
        // A write can land in a file the caller never named.
        assertEquals(
            Risk.DESTRUCTIVE,
            SqlStatementRisk.classify("ATTACH DATABASE '/sdcard/other.db' AS other"),
        )
    }

    @Test
    fun an_unclassifiable_statement_is_destructive_rather_than_a_read() {
        // THIS IS THE RULE THAT KEEPS THE CLASSIFIER HONEST. It is not a SQL parser and
        // must not pretend to be one: an unrecognised statement is not a safe
        // statement, it is one this layer cannot reason about. The cost of a needless
        // confirmation is a second call; the cost of a wrong pass is a lost table.
        assertEquals(Risk.DESTRUCTIVE, SqlStatementRisk.classify("SOMETHING TOTALLY NEW"))
        assertEquals(Risk.DESTRUCTIVE, SqlStatementRisk.classify(""))
        assertEquals(Risk.DESTRUCTIVE, SqlStatementRisk.classify(null))
    }

    // ------------------------------------------------------------------
    // The classifier must not be fooled by text.
    // ------------------------------------------------------------------

    @Test
    fun a_keyword_inside_a_string_literal_does_not_raise_the_risk() {
        // `INSERT INTO notes VALUES ('DROP TABLE everything')` writes a note that says
        // DROP; it does not drop anything. Gating it would train a caller to pass
        // confirm=true without reading, which is the failure mode a gate exists to
        // prevent.
        assertEquals(
            Risk.MUTATION,
            SqlStatementRisk.classify("INSERT INTO notes VALUES ('DROP TABLE everything') WHERE 1"),
        )
        assertEquals(Risk.READ, SqlStatementRisk.classify("SELECT 'TRUNCATE' AS word"))
    }

    @Test
    fun a_commented_out_statement_is_not_a_statement() {
        assertEquals(
            Risk.READ,
            SqlStatementRisk.classify("SELECT 1 -- DROP TABLE messages"),
        )
        assertEquals(
            Risk.READ,
            SqlStatementRisk.classify("SELECT 1 /* DROP TABLE messages */"),
        )
    }

    @Test
    fun a_dangerous_second_statement_after_a_semicolon_still_counts() {
        // SQLite's exec runs every statement in the string, so the dangerous half
        // decides. This is the case a naive "look at the first keyword" check misses.
        assertEquals(
            Risk.DESTRUCTIVE,
            SqlStatementRisk.classify("SELECT 1; DROP TABLE messages"),
        )
    }

    @Test
    fun a_pragma_is_a_mutation_because_several_of_them_write() {
        // `PRAGMA journal_mode = DELETE` and `PRAGMA writable_schema = ON` change the
        // file. Guessing which pragmas are read-only is exactly the mis-parse this
        // refuses to attempt, so the whole family is a mutation.
        assertEquals(Risk.MUTATION, SqlStatementRisk.classify("PRAGMA table_info('messages')"))
    }

    @Test
    fun a_table_named_after_a_keyword_is_still_just_a_table() {
        // An identifier's contents must not become a keyword. `SELECT * FROM "drop"`
        // reads a table, it does not drop one.
        assertEquals(Risk.READ, SqlStatementRisk.classify("""SELECT * FROM "drop""""))
    }
}
