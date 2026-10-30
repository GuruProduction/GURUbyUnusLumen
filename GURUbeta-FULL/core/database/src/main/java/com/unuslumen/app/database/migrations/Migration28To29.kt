// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.migrations

import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Migration 28 to 29: tool identity on chat rows.
 *
 * THE DEFECT THIS FIXES. A conversation row with role "tool" stored the tool
 * arguments and the tool result, and nothing else. The tool NAME and the call
 * ID — the two fields a model's chat template actually needs to render a
 * tool_call — were never written. Every restore therefore rebuilt those rows as
 * `name = "", id = null`, and the request builder sent the upstream a
 * `tool_calls` entry with an empty function name plus a tool result with a null
 * id. The upstream rejects that shape with HTTP 500, which the app surfaced as
 * the 500 "internal server error" message. The chat could only be rescued by
 * /clear, which discarded the history. That is the reported bug, exactly: any
 * conversation where a tool ran fails to send after a restart, a pure-chat
 * conversation restores fine.
 *
 * Two additive columns, no rebuilds, no data rewritten. Rows written before
 * this migration read back with empty strings, which the repository layer
 * interprets as "identity not recorded" and handles by keeping the row visible
 * while excluding it from the model's prompt. Nothing is guessed and no old row
 * is given a fabricated tool name.
 *
 * Column-for-column identical to MessageEntity because Room validates schema
 * identity on open: a mismatch here crashes the app on first launch after
 * upgrade rather than failing quietly, which is the correct behaviour.
 * Defaults mirror the @ColumnInfo defaults exactly.
 */
val MIGRATION_28_29 = object : Migration(28, 29) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "ALTER TABLE messages ADD COLUMN tool_name TEXT NOT NULL DEFAULT ''"
        )
        db.execSQL(
            "ALTER TABLE messages ADD COLUMN tool_call_id TEXT NOT NULL DEFAULT ''"
        )
    }
}
