// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.skills

import android.content.Context

/**
 * The one writer of skill-library sync state. The worker and the tool
 * executor both land installs and both need the same ledger: which version
 * of which publication (slug) is applied under which store name. Splitting
 * this into an object with all the discipline in one place keeps the
 * SharedPreferences file guru_luxify_skill_versions self-consistent
 * regardless of who lands the install.
 *
 * Ledger shape (one file, three key families):
 *  - v_<slug>         latest applied version of a publication
 *  - v_<name>         same version, keyed by store name (reads by name)
 *  - slug_<name>      the publication slug the local copy came from
 */
object SkillSyncMarks {

    private const val PREFS_NAME = "guru_luxify_skill_versions"
    private const val PREFIX_VERSION_KEY = "v_"
    private const val PREFIX_SLUG_KEY = "slug_"

    /** Record that a publication (slug+name) is now locally applied at version. */
    fun recordApplied(context: Context, slug: String, name: String, version: Int) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit()
            .putInt("$PREFIX_VERSION_KEY$slug", version)
            .putInt("$PREFIX_VERSION_KEY$name", version)
            .putString("$PREFIX_SLUG_KEY$name", slug)
            .apply()
    }

    /** Applied version for either key form (slug or store name), 0 if never. */
    fun appliedVersion(context: Context, key: String): Int =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getInt("$PREFIX_VERSION_KEY$key", 0)

    /** The publication slug a name's installed copy rode in under, null if unknown (legacy rows). */
    fun publicationSlug(context: Context, name: String): String? =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString("$PREFIX_SLUG_KEY$name", null)

    /** Forget every trace of a stored name (retraction: row + both key families). */
    fun forgetName(context: Context, name: String, publicationSlug: String?) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).edit().apply {
            remove("$PREFIX_VERSION_KEY$name")
            publicationSlug?.let { remove("$PREFIX_VERSION_KEY$it") }
            remove("$PREFIX_SLUG_KEY$name")
        }.apply()
    }

    /** All version markers, keyed verbatim (both v_<slug> and v_<name> present). */
    fun allVersions(context: Context): Map<String, Int> =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE).all
            .entries
            .filter { it.key.startsWith(PREFIX_VERSION_KEY) }
            .mapNotNull { (k, v) -> (v as? Int)?.let { k.removePrefix(PREFIX_VERSION_KEY) to it } }
            .toMap()

    /** One-off sweep of the pre-canonical ledger: raw name keys with an Int
     *  payload and no family prefix. Runs once per full sync pass after
     *  canonical marks exist; cost is a tiny prefs scan. */
    fun sweepLegacyKeys(context: Context) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stale = prefs.all.keys.filter {
            !it.startsWith(PREFIX_VERSION_KEY) && !it.startsWith(PREFIX_SLUG_KEY) && prefs.all[it] is Int
        }
        if (stale.isEmpty()) return
        prefs.edit().apply { stale.forEach { remove(it) } }.apply()
    }
}