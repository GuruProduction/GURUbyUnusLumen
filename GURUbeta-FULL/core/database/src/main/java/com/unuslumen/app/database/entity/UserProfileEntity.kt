// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * guru_user_profile — the one row that holds what the human told GURU at
 * first boot.
 *
 * SINGLE ROW, ALWAYS. The primary key is the literal "user_profile"; every
 * write is an upsert against that id. There is no history table because there
 * is nothing to reconcile: the profile is the current truth, and the brain
 * keeps the memory trail of what it used to be.
 *
 * Every free-text column is nullable-free with an empty default. "Not
 * answered" and "answered with nothing" both read as empty, which is honest:
 * GURU cannot distinguish a declined question from one never reached, and
 * pretending otherwise would invent data.
 *
 * The two JSON columns are the coordination layer with the brain:
 *  - facts_by_id_json maps question key -> brain memory id. Brain ids are
 *    content-derived, so a rewritten answer mints a new id and the old row is
 *    archived through the id held here. Without this map an edit would leave
 *    a ghost fact behind.
 *  - pending_brain_sync_json lists keys whose answer has not reached Cerebrum
 *    yet, because the vault had not finished booting. The boot pass flushes
 *    it, so an answer given during first boot is never silently dropped.
 */
@Entity(tableName = "guru_user_profile")
data class UserProfileEntity(
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    @ColumnInfo(name = "preferred_name", defaultValue = "")
    val preferredName: String = "",

    @ColumnInfo(name = "full_name", defaultValue = "")
    val fullName: String = "",

    @ColumnInfo(name = "pronouns", defaultValue = "")
    val pronouns: String = "",

    @ColumnInfo(name = "location", defaultValue = "")
    val location: String = "",

    /** Null means never answered. A real number means answered. */
    @ColumnInfo(name = "age")
    val age: Int? = null,

    @ColumnInfo(name = "identity", defaultValue = "")
    val identity: String = "",

    @ColumnInfo(name = "neurodivergence", defaultValue = "")
    val neurodivergence: String = "",

    @ColumnInfo(name = "beliefs", defaultValue = "")
    val beliefs: String = "",

    @ColumnInfo(name = "sexuality", defaultValue = "")
    val sexuality: String = "",

    @ColumnInfo(name = "politics", defaultValue = "")
    val politics: String = "",

    @ColumnInfo(name = "family", defaultValue = "")
    val family: String = "",

    @ColumnInfo(name = "work", defaultValue = "")
    val work: String = "",

    @ColumnInfo(name = "current_focus", defaultValue = "")
    val currentFocus: String = "",

    @ColumnInfo(name = "adjustments", defaultValue = "")
    val adjustments: String = "",

    @ColumnInfo(name = "health_or_legal", defaultValue = "")
    val healthOrLegal: String = "",

    @ColumnInfo(name = "interests", defaultValue = "")
    val interests: String = "",

    @ColumnInfo(name = "news_sources", defaultValue = "")
    val newsSources: String = "",

    @ColumnInfo(name = "socials", defaultValue = "")
    val socials: String = "",

    @ColumnInfo(name = "never_ask_again", defaultValue = "")
    val neverAskAgain: String = "",

    /** Answers for catalogue questions that have no typed column above. */
    @ColumnInfo(name = "extra_answers_json", defaultValue = "{}")
    val extraAnswersJson: String = "{}",

    /** question key -> brain memory id for the answer currently stored. */
    @ColumnInfo(name = "facts_by_id_json", defaultValue = "{}")
    val factsByIdJson: String = "{}",

    /** question keys waiting for the brain to come up. */
    @ColumnInfo(name = "pending_brain_sync_json", defaultValue = "[]")
    val pendingBrainSyncJson: String = "[]",

    @ColumnInfo(name = "deal_accepted_at", defaultValue = "0")
    val dealAcceptedAt: Long = 0L,

    @ColumnInfo(name = "deal_version", defaultValue = "0")
    val dealVersion: Int = 0,

    @ColumnInfo(name = "completed", defaultValue = "0")
    val completed: Boolean = false,

    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0L,

    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L
)
