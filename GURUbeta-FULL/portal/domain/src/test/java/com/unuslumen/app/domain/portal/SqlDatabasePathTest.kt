// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.portal

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a SQL tool's `database` argument means (issue 478).
 *
 * The failure this replaces: `database="guru_db"` reached
 * `SQLiteDatabase.openDatabase`, which needs an absolute path, and came back as
 *
 *     No such file or directory: 'guru_db' with flags 0x1:
 *     Directory not specified in the file path
 *
 * which reads as "this database does not exist". An agent that hit it twice
 * concluded the SQL family was broken and hand-rolled `sqlite3` inside a Python
 * script for the rest of the session, which works and quietly bypasses every SQL
 * tool.
 */
class SqlDatabasePathTest {

    private val dir = "/data/user/0/com.unuslumen.app.gurubeta/databases"

    // ------------------------------------------------------------------
    // A bare name, which is the case the whole change exists for.
    // ------------------------------------------------------------------

    @Test
    fun a_bare_name_resolves_against_the_apps_own_database_directory() {
        val resolved = SqlDatabasePath.resolve("guru_db", dir)
        assertEquals(SqlDatabasePath.Resolution.Open("$dir/guru_db"), resolved)
    }

    @Test
    fun surrounding_whitespace_does_not_break_a_bare_name() {
        assertEquals(
            SqlDatabasePath.Resolution.Open("$dir/guru_db"),
            SqlDatabasePath.resolve("  guru_db  ", dir),
        )
    }

    @Test
    fun an_absolute_path_is_used_as_given() {
        val path = "/sdcard/Download/some.db"
        assertEquals(SqlDatabasePath.Resolution.Open(path), SqlDatabasePath.resolve(path, dir))
    }

    // ------------------------------------------------------------------
    // The refusals a caller can act on.
    // ------------------------------------------------------------------

    @Test
    fun a_missing_argument_names_what_is_accepted() {
        val refused = SqlDatabasePath.resolve(null, dir) as SqlDatabasePath.Resolution.Refused
        assertTrue(refused.reason.contains("guru_db"))
        assertTrue(
            "a missing-argument refusal must name the directory a bare name resolves in",
            refused.reason.contains("absolute path"),
        )
    }

    @Test
    fun a_blank_argument_is_a_missing_argument_not_a_path() {
        assertTrue(SqlDatabasePath.resolve("   ", dir) is SqlDatabasePath.Resolution.Refused)
    }

    @Test
    fun a_relative_path_is_refused_and_the_resolved_path_is_named() {
        // Android has no working directory, so `sub/file.db` is a caller who
        // believes they are writing a relative path. Saying so, and naming what it
        // would have become, is the difference between a retry and giving up.
        val refused = SqlDatabasePath.resolve("sub/guru.db", dir) as SqlDatabasePath.Resolution.Refused
        assertEquals("$dir/sub/guru.db", refused.resolvedPath)
        assertTrue(refused.reason.contains("$dir/sub/guru.db"))
        assertTrue("the repair must be offered: ${refused.reason}", refused.reason.contains("\"guru.db\""))
    }

    @Test
    fun a_sidecar_file_is_refused_with_the_database_that_owns_it() {
        // SQLite opens -wal and -shm itself. A caller who opens one directly reads
        // a half-written database and concludes the data is corrupt.
        val wal = SqlDatabasePath.resolve("guru_db-wal", dir) as SqlDatabasePath.Resolution.Refused
        assertTrue(wal.reason.contains("sidecar"))
        assertTrue("the owning database must be named: ${wal.reason}", wal.reason.contains("\"guru_db\""))

        assertTrue(SqlDatabasePath.resolve("$dir/guru_db-shm", dir) is SqlDatabasePath.Resolution.Refused)
        assertTrue(SqlDatabasePath.resolve("$dir/guru_db-journal", dir) is SqlDatabasePath.Resolution.Refused)
    }

    @Test
    fun an_ordinary_database_name_is_never_mistaken_for_a_sidecar() {
        // A database legitimately named `wal_log.db` must open.
        assertTrue(SqlDatabasePath.resolve("wal_log.db", dir) is SqlDatabasePath.Resolution.Open)
    }

    // ------------------------------------------------------------------
    // The open failure, which is the other half.
    // ------------------------------------------------------------------

    @Test
    fun an_open_failure_names_the_path_the_directory_and_the_tool_to_use_next() {
        val message = SqlDatabasePath.openFailureMessage(
            resolvedPath = "$dir/missing_db",
            directory = dir,
            sqliteMessage = "Failed: android.database.sqlite.SQLiteCantOpenDatabaseException: unable to open database file (code 14)",
        )
        assertTrue("must name the path tried: $message", message.contains("$dir/missing_db"))
        assertTrue("must name the directory: $message", message.contains(dir))
        assertTrue("must name the next tool: $message", message.contains("sqlListDatabases"))
    }

    @Test
    fun an_open_failure_survives_a_null_sqlite_message() {
        val message = SqlDatabasePath.openFailureMessage("$dir/x", dir, null)
        assertTrue(message.contains("$dir/x"))
        assertTrue(message.contains("sqlListDatabases"))
    }
}
