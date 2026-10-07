// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.skills

import android.content.Context
import com.unuslumen.app.domain.repository.LuxifyRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Library force-push: the superadmin's version-bumped lands locally.
 *
 * Contract, doctrine: the PUBLISHER leads, the device follows.
 *  - checkManifest() etag-diffs against the shipped catalog
 *  - slugs whose pushed version beats the locally applied marker get
 *    re-fetched and re-applied (source="synced"): force-push
 *  - slugs GONE from the catalog get their "synced" copies withdrawn —
 *    the retraction valve, guaranteed never to touch numen-installed
 *    rows (source stays "dynamic" for those)
 *
 * Offline: manifest null → skip cleanly; the installed set never loses a
 * row by going offline. A new install only reaching us when the transport
 * is up is genuinely fine.
 */
class SkillLibrarySyncWorker(
    private val context: Context,
    private val luxifyRepository: LuxifyRepository
) {

    companion object {
        private const val LIBRARY_SOURCE = "synced"

        /** Minimum gap between full manifest passes regardless of trigger. */
        private const val SYNC_FLOOR_MS = 30L * 1000
    }

    data class SyncOutcome(
        val pulled: Int,
        val updated: Int,
        val withdrawn: Int,
        val offline: Boolean,
        val message: String
    )

    private var lastRunAt: Long = 0L

    private fun ranRecently(): Boolean =
        lastRunAt > 0L && System.currentTimeMillis() - lastRunAt < SYNC_FLOOR_MS

    suspend fun syncNow(force: Boolean = false): SyncOutcome = withContext(Dispatchers.IO) {
        // Debounce floor (30s): multiple calls landing inside it (boot + a
        // tool call + settings saves) skip. The manifest ETag keeps the
        // cadence itself cheap over whole hours.
        if (!force && ranRecently()) {
            return@withContext SyncOutcome(0, 0, 0, offline = false, "Skipped — sync ran recently.")
        }
        lastRunAt = System.currentTimeMillis()

        val manifest = SkillForge.checkManifest(context)
        if (manifest == null) {
            SkillForge.markSyncError(context, "unreachable")
            return@withContext SyncOutcome(0, 0, 0, offline = true, "Offline (or egress down) — installed set unchanged")
        }

        SkillSyncMarks.sweepLegacyKeys(context)

        var pulled = 0
        var updated = 0
        var withdrawn = 0

        val librarySlugs = manifest.skills.map { it.slug }.toSet()

        // Force-push land. installDynamicSkill is insert-not-upsert, so each
        // landed version is a fresh row in the store.
        val syncedNamesBefore = luxifyRepository.getAllSkills()
            .filter { it.source == LIBRARY_SOURCE }
            .map { it.name }
            .toSet()

        for (summary in manifest.skills) {
            val knownVersion = SkillSyncMarks.appliedVersion(context, summary.slug)
            if (summary.version <= knownVersion) continue

            val fetched = SkillForge.fetchSkill(context, summary.slug) ?: continue
            val wasUpdate = fetched.name in syncedNamesBefore

            val freshened = luxifyRepository.installDynamicSkill(
                name = fetched.name,
                description = fetched.description,
                whenToUse = fetched.whenToUse,
                allowedTools = fetched.allowedTools,
                bodyMarkdown = fetched.content,
                source = LIBRARY_SOURCE
            )
            // One live copy: disable the other same-name synced rows. Older
            // generations stay in the store (history, rollback material) but
            // stop being active skills.
            for (stale in luxifyRepository.getAllSkills().filter {
                it.name == freshened.name && it.id != freshened.id && it.source == LIBRARY_SOURCE
            }) {
                luxifyRepository.disableSkill(stale.id)
            }
            SkillSyncMarks.recordApplied(context, fetched.slug, fetched.name, fetched.version)

            if (wasUpdate) updated++ else pulled++
        }

        // Retraction. EXACT when the publication slug was recorded at install
        // time; the slugToName round-trip covers legacy rows set down before
        // markers existed. A synced row is removed only when no manifest slug
        // could have produced its name.
        val installed = luxifyRepository.getAllSkills().filter { it.source == LIBRARY_SOURCE }
        for (row in installed) {
            val recorded = SkillSyncMarks.publicationSlug(context, row.name)
            val survives = when {
                recorded != null -> recorded in librarySlugs
                else -> librarySlugs.any { SkillForge.slugToName(it) == row.name }
            }
            if (!survives) {
                luxifyRepository.deleteSkill(row.id)
                SkillSyncMarks.forgetName(context, row.name, recorded)
                withdrawn++
            }
        }

        SkillForge.markSynced(context)
        SyncOutcome(
            pulled, updated, withdrawn, offline = false,
            message = "Sync ok — pulled $pulled new, updated $updated, withdrew $withdrawn."
        )
    }
}