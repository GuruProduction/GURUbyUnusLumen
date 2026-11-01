// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import android.content.Context

/**
 * The one writer of agent-catalogue sync state, mirroring SkillSyncMarks.
 *
 * Ledger shape (one prefs file, two key families):
 *  - v_<slug>   latest applied version of a publication
 *  - v_<name>   the same version keyed by store name, for reads by name
 *
 * Kept separate from the skills ledger on purpose: a slug can legally exist in
 * both catalogues, and sharing one file would let a skill version suppress an
 * agent version of the same slug.
 */
object AgentSyncMarks {

    private const val PREFS_NAME = "guru_agent_versions"
    private const val PREFIX_VERSION_KEY = "v_"

    fun recordApplied(context: Context, slug: String, name: String, version: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt("$PREFIX_VERSION_KEY$slug", version)
            .putInt("$PREFIX_VERSION_KEY$name", version)
            .apply()
    }

    /** Applied version for either key form (slug or store name), 0 if never. */
    fun appliedVersion(context: Context, key: String): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("$PREFIX_VERSION_KEY$key", 0)

    fun forget(context: Context, name: String, slug: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            remove("$PREFIX_VERSION_KEY$name")
            slug?.let { remove("$PREFIX_VERSION_KEY$it") }
        }.apply()
    }

    fun allVersions(context: Context): Map<String, Int> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
            .entries
            .filter { it.key.startsWith(PREFIX_VERSION_KEY) }
            .mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix(PREFIX_VERSION_KEY) to it } }
            .toMap()

    fun lastSyncAt(context: Context): Long =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getLong("last_sync_at", 0L)

    fun markSynced(context: Context) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putLong("last_sync_at", System.currentTimeMillis())
            .putString("last_sync_error", null)
            .apply()
    }

    fun markSyncError(context: Context, message: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putString("last_sync_error", message)
            .apply()
    }
}
