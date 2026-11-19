// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.portal

/**
 * What a `database` argument on the SQL tools actually means (issue 478).
 *
 * WHY THIS IS ITS OWN OBJECT WITH A TEST. The SQL family took a `database`
 * string and passed it straight to `SQLiteDatabase.openDatabase`, which requires
 * an absolute path. The tool description said "Full path to SQLite database
 * file", so an agent that read it and passed `guru_db` got SQLite's own refusal
 * back:
 *
 *     No such file or directory: 'guru_db' with flags 0x1:
 *     Directory not specified in the file path
 *
 * That sentence is accurate if you already know the answer. To a reader who does
 * not, it says "this database does not exist", and the natural next move is to
 * conclude the SQL tools are broken and hand-roll a `sqlite3` connection inside a
 * Python script instead. Which works, and quietly bypasses the whole tool family
 * for the rest of the session.
 *
 * Two things are wrong there and both are fixed here: a bare name is a reasonable
 * thing to pass and is now RESOLVED rather than refused, and a refusal that is
 * genuinely a refusal names the path it tried and the directory it would have
 * used.
 */
object SqlDatabasePath {

    /** Result of interpreting a caller's `database` argument. */
    sealed interface Resolution {
        /** An absolute path to open. */
        data class Open(val path: String) : Resolution

        /**
         * A refusal that a caller can act on.
         *
         * `resolvedPath` is what would have been opened, so the sentence can name
         * it. `directory` is where a bare name is looked up, which is the one fact
         * an agent cannot discover from the tool descriptions.
         */
        data class Refused(
            val reason: String,
            val resolvedPath: String,
            val directory: String,
        ) : Resolution
    }

    /** The SQLite sidecar suffixes. Present beside a database, never opened directly. */
    private val SIDECARS = listOf("-wal", "-shm", "-journal")

    /**
     * Decide what to open for `database`, given the app's own database directory.
     *
     * THE RULES, in the order they matter:
     *
     * 1. Blank is a missing argument, not a path.
     * 2. An absolute path is used as given, because the caller has named a file.
     * 3. A bare name is resolved against [databaseDir]. This is the case the whole
     *    change exists for: `guru_db` is the app's own database and the most useful
     *    target an agent has.
     * 4. Anything with a slash that is not absolute is refused with the resolved
     *    path named, rather than silently joined, because `foo/bar.db` is a caller
     *    who thinks they are writing a relative path and Android has no such thing.
     * 5. A SQLite sidecar (`.db-wal`, `.db-shm`, `.db-journal`) is refused with a
     *    sentence saying so, because SQLite opens those itself and a caller who
     *    opens one directly reads a half-written database.
     */
    fun resolve(database: String?, databaseDir: String): Resolution {
        val raw = database?.trim().orEmpty()
        if (raw.isEmpty()) {
            return Resolution.Refused(
                reason = "The 'database' argument is required. Pass a bare name such as " +
                    "\"guru_db\", or an absolute path.",
                resolvedPath = "",
                directory = databaseDir,
            )
        }

        if (raw.startsWith("/")) {
            if (isSidecar(raw)) {
                return Resolution.Refused(
                    reason = "$raw is a SQLite sidecar file. SQLite opens it automatically " +
                        "beside its database; open the database itself instead.",
                    resolvedPath = raw,
                    directory = databaseDir,
                )
            }
            return Resolution.Open(raw)
        }

        if (raw.contains("/")) {
            val joined = "$databaseDir/$raw"
            return Resolution.Refused(
                reason = "\"$raw\" looks like a relative path, and Android has no working " +
                    "directory to be relative to. It would have been read as $joined. " +
                    "Pass either the bare name \"${raw.substringAfterLast('/')}\" or the " +
                    "absolute path.",
                resolvedPath = joined,
                directory = databaseDir,
            )
        }

        if (isSidecar(raw)) {
            val joined = "$databaseDir/$raw"
            return Resolution.Refused(
                reason = "$raw is a SQLite sidecar file. SQLite opens it automatically " +
                    "beside its database; pass \"${raw.substringBefore('-')}\" instead.",
                resolvedPath = joined,
                directory = databaseDir,
            )
        }

        return Resolution.Open("$databaseDir/$raw")
    }

    private fun isSidecar(path: String): Boolean = SIDECARS.any { path.endsWith(it) }

    /**
     * The sentence for a path that resolved but would not open.
     *
     * This is the other half of the fix. SQLite's message for a missing file does
     * not distinguish "wrong path shape" from "no such database", so the tool
     * supplies the distinction it knows: here is the path that was tried, here is
     * the directory a bare name is looked up in, and here is how to find out what
     * is actually there.
     */
    fun openFailureMessage(resolvedPath: String, directory: String, sqliteMessage: String?): String {
        val detail = sqliteMessage?.substringAfter(": ")?.trim().orEmpty()
        return buildString {
            append("Could not open $resolvedPath")
            if (detail.isNotEmpty()) append(" ($detail)")
            append(". ")
            append("A bare name is looked up in $directory; anything else needs an absolute path. ")
            append("Call sqlListDatabases to see what is actually there.")
        }
    }
}
