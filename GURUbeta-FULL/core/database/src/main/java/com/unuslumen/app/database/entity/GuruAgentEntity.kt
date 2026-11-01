// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One agent definition the numen can call by name.
 *
 * An agent is not a chat surface and it is not a mask. A mask changes who is
 * answering. An agent is a definition the main GURU hands a job to, which goes
 * away and comes back with a report. The [systemPrompt] is the whole of its
 * character; [toolPermissions] is the exact set of tool names it may use, and
 * the dispatcher enforces that set rather than trusting the definition to
 * behave.
 *
 * Three sources, and the difference is load-bearing exactly as it is for
 * skills:
 *  - "user"   made on this phone. The sync worker may NEVER overwrite or
 *             withdraw one. It is the person's, permanently.
 *  - "synced" arrived from the publisher. Version-gated force-push updates it
 *             and retraction removes it when the publication disappears.
 *  - "bundled" ships in the APK and is never mutable from the catalogue.
 *
 * [slug] is the catalogue identity once an agent has been downloaded or
 * published, empty for a purely local agent. [version] is the applied
 * publication version, 1 for a user-authored agent that has never been
 * published. Both exist so an update can find the same agent again instead of
 * trusting a display name.
 */
@Entity(
    tableName = "guru_agents",
    indices = [
        Index(value = ["name"], name = "index_guru_agents_name"),
        Index(value = ["slug"], name = "index_guru_agents_slug"),
        Index(value = ["source"], name = "index_guru_agents_source"),
        Index(value = ["enabled"], name = "index_guru_agents_enabled")
    ]
)
data class GuruAgentEntity(
    @PrimaryKey
    val id: String,
    /** Catalogue identity. Empty until the agent is downloaded or published. */
    @ColumnInfo(defaultValue = "")
    val slug: String = "",
    val name: String,
    @ColumnInfo(defaultValue = "")
    val description: String = "",
    @ColumnInfo(name = "system_prompt")
    val systemPrompt: String,
    /** Comma-joined tool names, matching the skills lane's storage shape. Empty means no tools. */
    @ColumnInfo(name = "tool_permissions", defaultValue = "")
    val toolPermissions: String = "",
    /** "user" | "synced" | "bundled" */
    @ColumnInfo(defaultValue = "user")
    val source: String = "user",
    @ColumnInfo(defaultValue = "1")
    val enabled: Boolean = true,
    /** Applied publication version. 1 for a local agent that has never been published. */
    @ColumnInfo(defaultValue = "1")
    val version: Int = 1,
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0L,
    @ColumnInfo(name = "updated_at", defaultValue = "0")
    val updatedAt: Long = 0L
)
