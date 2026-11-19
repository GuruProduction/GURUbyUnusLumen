// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.portal

/**
 * How much a SQL statement can cost, decided before it runs (issue 496).
 *
 * THE DEFECT. `sqlExecute` opened any path read-write and ran whatever it was given.
 * `DROP TABLE` was one call away, with no confirmation, no target check, no snapshot
 * and no transaction. The tool's own description said "This is permanent", which is
 * honest and is also the defect: a capability this destructive needs a gate and a
 * recovery point, not a warning in a docstring.
 *
 * THE GATE BELONGS ON CONSEQUENCE, NOT ON THE TOOL. Vault's whole remit depends on
 * these two tools, so making them safe must not make them useless: an integrity
 * check, a schema inspection and a careful migration all have to remain possible
 * without a human clicking through a dialog for each one. Reading is free. A change
 * to structure or to bulk data costs a confirmation and a snapshot. That is the same
 * shape as the permission model the app already uses everywhere else.
 *
 * WHY THE CLASSIFICATION IS A PURE OBJECT. Every prompt in the app already carries a
 * version of the rule this enforces — Vault's section 16, Archivist's section 10, the
 * Self Improvement Engine's section 14 — and the prompts were doing safety work that
 * belongs in the tool. A rule a model can only satisfy by reading four prompts is a
 * rule that holds until the model is in a hurry. It is decided here, in code, and a
 * test proves each verdict.
 */
object SqlStatementRisk {

    /** What a statement costs if it goes wrong. */
    enum class Risk {
        /** Reads only. Nothing can be lost. */
        READ,

        /** Changes rows but not structure, and names what it touches. */
        MUTATION,

        /**
         * Can destroy structure or unbounded data.
         *
         * `DROP`, `TRUNCATE`, `ALTER`, `VACUUM INTO` nowhere near, and a `DELETE` or
         * `UPDATE` with no `WHERE` all land here. So does attaching another database,
         * because a write can then land somewhere the caller never named.
         */
        DESTRUCTIVE,
    }

    /**
     * Classify one statement.
     *
     * THE PARSING IS DELIBERATELY SIMPLE AND DELIBERATELY CONSERVATIVE. This is not a
     * SQL parser and must not pretend to be one; a classifier that quietly mis-parses
     * a statement is worse than none, because it would wave through the one that
     * mattered. So it looks for the keywords that change what can be lost, ignores
     * comments and leading noise, and treats anything it cannot classify as
     * destructive rather than as a read. An unreadable statement is not evidence of
     * safety.
     */
    fun classify(statement: String?): Risk {
        val cleaned = stripCommentsAndQuotedLiterals(statement?.trim().orEmpty())
        if (cleaned.isEmpty()) return Risk.DESTRUCTIVE

        val words = cleaned
            .uppercase()
            .split(Regex("[^A-Z_]+"))
            .filter { it.isNotEmpty() }

        if (words.isEmpty()) return Risk.DESTRUCTIVE

        // A statement is judged by every keyword it contains, not just its first.
        // `SELECT ... ; DROP TABLE x` is one string to SQLite's exec and two things
        // to a person, and the dangerous half decides.
        val destructive = words.any {
            it == "DROP" || it == "TRUNCATE" || it == "ALTER" ||
                it == "ATTACH" || it == "DETACH" || it == "REINDEX"
        }
        val touchesData = words.any {
            it == "INSERT" || it == "UPDATE" || it == "DELETE" ||
                it == "REPLACE" || it == "CREATE"
        }

        val withoutWhere = words.any { it == "DELETE" || it == "UPDATE" } && "WHERE" !in words

        return when {
            destructive -> Risk.DESTRUCTIVE
            // An unbounded DELETE or UPDATE can empty a table, which is the same loss
            // as dropping it from the caller's point of view.
            touchesData && withoutWhere -> Risk.DESTRUCTIVE
            touchesData -> Risk.MUTATION
            // PRAGMA can write: `PRAGMA journal_mode = DELETE`, `PRAGMA writable_schema
            // = ON` and several others change the file rather than reading it. It is a
            // mutation unless it is one of the read-only forms, and guessing which is
            // which is exactly the mis-parse this refuses to attempt.
            words.firstOrNull() == "PRAGMA" -> Risk.MUTATION
            words.firstOrNull() == "SELECT" || words.firstOrNull() == "WITH" ||
                words.firstOrNull() == "EXPLAIN" || words.firstOrNull() == "VALUES" -> Risk.READ
            // ANYTHING ELSE IS DESTRUCTIVE. An unrecognised statement is not a safe
            // statement; it is one this layer cannot reason about, and the cost of a
            // needless confirmation is a second call while the cost of a wrong pass is
            // a lost table.
            else -> Risk.DESTRUCTIVE
        }
    }

    /** Whether a statement of this risk may run without a confirmation and a snapshot. */
    fun requiresGate(risk: Risk): Boolean = risk != Risk.READ

    /** Whether a statement of this risk must have a recovery point taken first. */
    fun requiresSnapshot(risk: Risk): Boolean = risk == Risk.DESTRUCTIVE

    /**
     * The statement with its comments and quoted literals removed.
     *
     * WITHOUT THIS, A STRING IS A KEYWORD. `INSERT INTO notes VALUES ('DROP TABLE
     * everything')` contains DROP and would be gated as a structural change, and
     * `SELECT 'TRUNCATE'` would be refused outright. Comments matter for the same
     * reason: a commented-out `-- DROP TABLE x` is not a statement.
     *
     * What is kept is the shape: the words outside any literal, which is the only
     * part with meaning to the engine.
     */
    private fun stripCommentsAndQuotedLiterals(statement: String): String {
        val out = StringBuilder(statement.length)
        var index = 0
        while (index < statement.length) {
            val ch = statement[index]
            when {
                // -- line comment
                ch == '-' && index + 1 < statement.length && statement[index + 1] == '-' -> {
                    while (index < statement.length && statement[index] != '\n') index++
                }
                // /* block comment */
                ch == '/' && index + 1 < statement.length && statement[index + 1] == '*' -> {
                    index += 2
                    while (index + 1 < statement.length &&
                        !(statement[index] == '*' && statement[index + 1] == '/')
                    ) {
                        index++
                    }
                    index = (index + 2).coerceAtMost(statement.length)
                }
                // 'literal' with '' as the escape
                ch == '\'' -> {
                    index++
                    while (index < statement.length) {
                        if (statement[index] == '\'') {
                            // A doubled quote is an escaped quote, not the end.
                            if (index + 1 < statement.length && statement[index + 1] == '\'') {
                                index += 2
                                continue
                            }
                            index++
                            break
                        }
                        index++
                    }
                    // A space keeps the surrounding words from fusing into one.
                    out.append(' ')
                }
                // "identifier" and `identifier` keep their content: a table named
                // "drop" is a real name, and the keyword tests look at words, so an
                // identifier's contents do not become a keyword here either.
                ch == '"' || ch == '`' -> {
                    out.append(' ')
                    index++
                    while (index < statement.length && statement[index] != ch) index++
                    index++
                }
                else -> {
                    out.append(ch)
                    index++
                }
            }
        }
        return out.toString().trim()
    }
}
