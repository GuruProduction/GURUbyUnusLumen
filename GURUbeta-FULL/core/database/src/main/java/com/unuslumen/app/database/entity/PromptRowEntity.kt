// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One prompt row on this device: the local mirror of the server's `prompt_sections`.
 *
 * WHY THE DEVICE NEEDS THE ROWS AND NOT JUST THE ASSEMBLED STRING. The app receives one
 * assembled blob from `https://api.unuslumen.com/prompts/assembled`. A model reading
 * that blob can see that an instruction is wrong and cannot find the row that says it,
 * so every edit becomes a guess at a paragraph offset. That is why `prompt_sections`
 * existed with five columns, was never populated, held nothing, and answered every
 * question about the stack with silence that read as "the stack is empty".
 *
 * THIS MIRRORS THE SERVER COLUMN FOR COLUMN, measured from
 * `guru-db/src/models.rs` and migrations `0001` and `0003`, because a local shape that
 * drifts from the server's is a shape that cannot be synced. `slug` and `name` are
 * separate on purpose: `slug` is the handle a tool addresses and survives a rename,
 * `name` is what a person reads.
 *
 * TWO COLUMNS THE SERVER DOES NOT HAVE, and both exist for a question it never has to
 * ask. [isEdited] is whether the owner changed this row on this device, which is what
 * [resetPromptOverride] acts on. [syncedVersion] is the server version last taken, so a
 * sync can tell an untouched row from an edited one and never overwrites the owner's
 * words with text it already replaced.
 */
@Entity(
    tableName = "prompt_rows",
    indices = [
        Index(value = ["slug"], name = "index_prompt_rows_slug", unique = true),
        Index(value = ["category"], name = "index_prompt_rows_category"),
        Index(value = ["is_active"], name = "index_prompt_rows_is_active"),
    ],
)
data class PromptRowEntity(
    /** The server's own uuid, so a row identifies the same record on both sides. */
    @PrimaryKey
    val id: String,

    /** The stable handle. A rename changes [name] and never this. */
    val slug: String,

    val category: String,

    /** What a person reads. */
    val name: String,

    val content: String,

    /** `always`, or the keyword-triggered mode. Text so a new mode needs no migration. */
    @ColumnInfo(name = "delivery_mode", defaultValue = "always")
    val deliveryMode: String = "always",

    /**
     * The keywords that make a keyword-triggered row assemble.
     *
     * JSON ARRAY TEXT, NOT A PACKED STRING. The server holds a Postgres `text[]`; a
     * comma-joined local column would make a keyword containing a comma unrepresentable,
     * and the first one that did would silently split into two keywords nobody wrote.
     */
    @ColumnInfo(name = "trigger_keywords", defaultValue = "[]")
    val triggerKeywordsJson: String = "[]",

    /** Per-row threshold, or null to use [globalThreshold]. */
    @ColumnInfo(name = "match_threshold")
    val matchThreshold: Double? = null,

    @ColumnInfo(name = "global_threshold", defaultValue = "0.75")
    val globalThreshold: Double = 0.75,

    /** False when the server has withdrawn the row. It stays on the device; see below. */
    @ColumnInfo(name = "is_active", defaultValue = "1")
    val isActive: Boolean = true,

    /** The server's version for this row. Every local edit bumps it. */
    val version: Int = 1,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,

    /**
     * Whether the owner has changed this row on this device.
     *
     * THE FLAG THAT STOPS A SYNC EATING THEIR WORK. A withdrawn or updated server row is
     * never allowed to overwrite an edited one, because the local text is the owner's and
     * the server's copy is the one that has fallen behind their intent.
     */
    @ColumnInfo(name = "is_edited", defaultValue = "0")
    val isEdited: Boolean = false,

    /** The server version last taken. Distinguishes untouched from edited after a sync. */
    @ColumnInfo(name = "synced_version", defaultValue = "0")
    val syncedVersion: Int = 0,
) {
    companion object {
        /** The only delivery mode that assembles unconditionally. */
        const val DELIVERY_ALWAYS = "always"

        /** A row that assembles only when one of its keywords fires. */
        const val DELIVERY_KEYWORD = "keyword"
    }
}
