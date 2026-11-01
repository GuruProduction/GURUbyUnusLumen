// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import android.content.Context
import androidx.annotation.VisibleForTesting
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Agent catalogue force-push, the sibling of SkillLibrarySyncWorker.
 *
 * CONTRACT, DOCTRINE: the publisher leads, the device follows.
 *  - manifestVersions() gives slug -> version for the whole agent catalogue
 *  - a slug whose published version beats the locally applied marker is
 *    fetched and landed as source="synced"
 *  - a synced row whose slug is GONE from the manifest is withdrawn —
 *    the retraction valve
 *  - a person's own agents are NEVER candidates for either operation
 *
 * OFFLINE IS A NORMAL STATE. A null manifest means the transport could not
 * answer; the pass reports offline and the installed set is left exactly as it
 * was. Nothing is ever removed because the network was unavailable, which is
 * the failure that would otherwise let a dead Tor circuit look like a
 * withdrawal.
 */
class AgentLibrarySyncWorker(
    private val context: Context,
    private val agentRepository: GuruAgentRepository
) {

    companion object {
        private const val TAG = "AgentSync"

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

    @VisibleForTesting
    internal var now: () -> Long = { System.currentTimeMillis() }

    private fun ranRecently(): Boolean {
        val last = AgentSyncMarks.lastSyncAt(context)
        return last > 0L && now() - last < SYNC_FLOOR_MS
    }

    suspend fun syncNow(force: Boolean = false): SyncOutcome = withContext(Dispatchers.IO) {
        if (!force && ranRecently()) {
            return@withContext SyncOutcome(
                0, 0, 0, offline = false, "Skipped — sync ran recently."
            )
        }

        val manifest = AgentForge.manifestVersions(context)
        if (manifest == null) {
            AgentSyncMarks.markSyncError(context, "unreachable")
            return@withContext SyncOutcome(
                0, 0, 0, offline = true,
                "Offline (or egress down) — installed agents unchanged"
            )
        }

        var pulled = 0
        var updated = 0
        var withdrawn = 0

        val namesBefore = agentRepository.getSyncedAgents().map { it.name }.toSet()

        for ((slug, publishedVersion) in manifest) {
            val applied = AgentSyncMarks.appliedVersion(context, slug)
            if (publishedVersion <= applied) continue

            val fetched = AgentForge.fetchAgent(context, slug) ?: continue

            agentRepository.installSyncedAgent(
                slug = fetched.slug,
                name = fetched.name,
                description = fetched.roleDescription,
                systemPrompt = fetched.systemPrompt,
                toolPermissions = fetched.toolPermissions,
                version = publishedVersion
            )
            AgentSyncMarks.recordApplied(context, fetched.slug, fetched.name, publishedVersion)

            if (fetched.name in namesBefore) updated++ else pulled++
        }

        // Retraction. A synced row is removed only when its slug could not
        // have come from any manifest entry. User rows are not candidates.
        withdrawn = agentRepository.retractSyncedAgentsNotIn(manifest.keys)

        AgentSyncMarks.markSynced(context)
        SyncOutcome(
            pulled, updated, withdrawn, offline = false,
            message = "Sync ok — pulled $pulled new, updated $updated, withdrew $withdrawn."
        )
    }
}
