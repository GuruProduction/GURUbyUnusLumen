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
    val updatedAt: Long = 0L,

    /**
     * The stored operating configuration, as JSON documents.
     *
     * WHY JSON COLUMNS RATHER THAN FORTY COLUMNS. These are documents whose shape is owned by the
     * domain types, not by this table: a visibility rule has three fields, an autonomy policy has
     * nine booleans and a set, a manifest has ten lists. Flattening them into columns would mean a
     * migration every time a setting gains a field, and would put the shape in two places that
     * must be kept identical by hand.
     *
     * THE COST IS THAT SQL CANNOT QUERY INSIDE THEM, and that is acceptable because nothing does:
     * they are read whole when an agent is loaded and written whole when it is saved. What SQL
     * does need to filter on — name, slug, source, enabled — stays a real column.
     *
     * EVERY ONE DEFAULTS TO A VERSIONED EMPTY DOCUMENT SO AN OLD ROW IS READABLE. A row written
     * before this migration carries `{"schemaVersion":1}`, the domain decoder fills the defaults,
     * and an existing agent loads with exactly the behaviour it had. No backfill is needed and no
     * row is rewritten.
     */
    @ColumnInfo(name = "visual_json", defaultValue = "{\"schemaVersion\":1}")
    val visualJson: String = "{\"schemaVersion\":1}",

    @ColumnInfo(name = "visibility_json", defaultValue = "{\"schemaVersion\":1}")
    val visibilityJson: String = "{\"schemaVersion\":1}",

    @ColumnInfo(name = "autonomy_json", defaultValue = "{\"schemaVersion\":1}")
    val autonomyJson: String = "{\"schemaVersion\":1}",

    @ColumnInfo(name = "budgets_json", defaultValue = "{\"schemaVersion\":1}")
    val budgetsJson: String = "{\"schemaVersion\":1}",

    @ColumnInfo(name = "model_preference_json", defaultValue = "{\"schemaVersion\":1}")
    val modelPreferenceJson: String = "{\"schemaVersion\":1}",

    @ColumnInfo(name = "notifications_json", defaultValue = "{\"schemaVersion\":1}")
    val notificationsJson: String = "{\"schemaVersion\":1}",

    @ColumnInfo(name = "memory_scope_json", defaultValue = "{\"schemaVersion\":1}")
    val memoryScopeJson: String = "{\"schemaVersion\":1}",

    @ColumnInfo(name = "context_scope_json", defaultValue = "{\"schemaVersion\":1}")
    val contextScopeJson: String = "{\"schemaVersion\":1}",

    @ColumnInfo(name = "manifest_json", defaultValue = "{\"schemaVersion\":1}")
    val manifestJson: String = "{\"schemaVersion\":1}",

    /** Comma-joined skill ids granted, matching how tools are stored. */
    @ColumnInfo(name = "skill_ids", defaultValue = "")
    val skillIds: String = "",

    /**
     * A local restriction on an imported agent's tools, comma-joined, empty meaning none.
     *
     * SEPARATE FROM [toolPermissions] BECAUSE THE PUBLISHED GRANT MUST STAY INTACT. A later catalogue
     * version is diffed against what the publisher shipped, so rewriting the stored grant to hold an
     * agent to fewer tools would destroy the comparison that stops an update smuggling in a
     * permission. This column holds what the person chose to allow, and the effective set is the
     * published list filtered by it.
     */
    @ColumnInfo(name = "tool_narrowing", defaultValue = "")
    val toolNarrowing: String = "",

    /**
     * Counts local definition edits, and is what a history row is keyed on.
     *
     * NOT THE SAME AS [version]. [version] is the published catalogue version the sync worker
     * force-pushes on; this counts edits made here, and every increment appends a history row so a
     * previous prompt or tool set survives the edit. Before this field, `updateAgent` overwrote in
     * place and the previous definition was gone.
     */
    @ColumnInfo(name = "definition_version", defaultValue = "1")
    val definitionVersion: Int = 1,
)
