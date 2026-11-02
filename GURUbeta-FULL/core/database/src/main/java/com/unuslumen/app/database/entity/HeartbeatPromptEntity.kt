// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * The prompt a heartbeat beat injects into the newest conversation.
 *
 * WHY THIS IS A TABLE AND NOT AN ASSET. The prompt used to ship inside the APK at
 * assets/heartbeat/HEARTBEAT_PROMPT.md, authored by the founder and never writable
 * from the app. That made it a piece of behaviour nobody but a developer could
 * change, which is backwards: what GURU says to itself every half hour is exactly
 * the sort of thing its owner should be able to tune, and it is the sort of thing
 * that should not need a release to adjust.
 *
 * SO IT LIVES HERE. One row, edited from the Heartbeat room. The shipped text is
 * seeded on first open so a fresh install behaves exactly as it did before, and
 * from then on the row is the truth.
 *
 * [id] is a constant rather than random because there is exactly one prompt. A
 * fixed key means an edit is an update to the same row rather than a new row
 * appearing beside the old one.
 */
@Entity(tableName = "heartbeat_prompt")
data class HeartbeatPromptEntity(
    @PrimaryKey
    val id: String = SINGLETON_ID,
    /** The full text injected at beat time, tokens included. */
    val content: String,
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L,
) {
    companion object {
        /**
         * There is one heartbeat, so there is one row.
         *
         * A constant key rather than a random uuid: an update upserts onto this row,
         * which is what stops a second row silently becoming a second heartbeat.
         */
        const val SINGLETON_ID = "heartbeat"
    }
}
