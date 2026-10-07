// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.skills.SkillForge
import com.unuslumen.app.data.skills.SkillSyncMarks
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.repository.LuxifyRepository
import kotlinx.serialization.json.Json

/**
 * The skills-library executor. Four verbs, one transport (SkillForge), one
 * persistent install path (LuxifyRepository.installDynamicSkill).
 *
 * Install marks the stored skill's source "synced", distinct from the
 * numen's own installs ("dynamic") and from bundled originals. That split
 * lets a republish over an existing install land as a fresh row (latest
 * wins) with same-name stale rows disabled for exactly-one-live-copy
 * guarantees.
 *
 * Every network verb fails HONEST rather than throwing: reachable=false
 * and a human-readable reason go back to the numen so it can decide what
 * to do next. The library path never looks like a device failure.
 */
class SkillLibraryToolExecutor(
    private val context: Context,
    private val luxifyRepository: LuxifyRepository
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            SkillLibraryToolDefinitions.BROWSE_SKILL_LIBRARY -> browseLibrary()
            SkillLibraryToolDefinitions.SEARCH_SKILL_LIBRARY -> searchLibrary(args)
            SkillLibraryToolDefinitions.GET_SKILL_FROM_LIBRARY -> getSkill(args)
            SkillLibraryToolDefinitions.INSTALL_SKILL_FROM_LIBRARY -> installSkill(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun browseLibrary(): ToolExecutionResult {
        val manifest = SkillForge.checkManifest(context)
        val installedVersions = SkillSyncMarks.allVersions(context)
        val syncedNames = luxifyRepository.getAllSkills().filter { it.source == "synced" }.map { it.name }.toSet()

        if (manifest == null) {
            val r = BrowseLibraryResult(
                reachable = false,
                totalInLibrary = 0,
                skills = emptyList(),
                error = "Library unreachable (device offline or egress refused). The installed set is unaffected."
            )
            return ToolExecutionResult.success(r, json.encodeToString(BrowseLibraryResult.serializer(), r))
        }

        val entries = manifest.skills.map { s ->
            val installedVersion = installedVersions[s.name] ?: installedVolumesFor(s.slug, installedVersions)
            LibraryBrowseEntry(
                slug = s.slug,
                name = s.name,
                version = s.version,
                installed = installedVersion != null || s.name in syncedNames,
                installedVersion = installedVersion,
                updateAvailable = installedVersion != null && installedVersion < s.version
            )
        }

        val r = BrowseLibraryResult(reachable = true, totalInLibrary = entries.size, skills = entries)
        return ToolExecutionResult.success(r, json.encodeToString(BrowseLibraryResult.serializer(), r))
    }

    /** Version by slug key form, when the name form misses. */
    private fun installedVolumesFor(slug: String, versions: Map<String, Int>): Int? = versions[slug]

    private suspend fun searchLibrary(args: Map<String, Any?>): ToolExecutionResult {
        val query = args["query"] as? String ?: return ToolExecutionResult.error("Missing 'query' parameter")
        val manifest = SkillForge.checkManifest(context)
        if (manifest == null) {
            val r = SearchLibraryResult(
                query = query,
                reachable = false,
                totalMatches = 0,
                skills = emptyList(),
                error = "Library unreachable (device offline or egress refused). The installed set is unaffected."
            )
            return ToolExecutionResult.success(r, json.encodeToString(SearchLibraryResult.serializer(), r))
        }

        val installedVersions = SkillSyncMarks.allVersions(context)
        val syncedNames = luxifyRepository.getAllSkills().filter { it.source == "synced" }.map { it.name }.toSet()

        // Pass 1 (free): match name/slug only.
        val terms = query.lowercase().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (terms.isEmpty()) return ToolExecutionResult.error("Query was empty — name the thing you're looking for")

        val cheapHits = manifest.skills.filter { s ->
            terms.any { s.slug.lowercase().contains(it) || s.name.lowercase().contains(it) }
        }
        val installedCheck = { entryName: String ->
            installedVersions.containsKey(entryName) || entryName in syncedNames
        }

        val finalResults: List<LibrarySearchEntry>
        if (cheapHits.isNotEmpty()) {
            finalResults = cheapHits.map { s ->
                LibrarySearchEntry(
                    slug = s.slug,
                    name = s.name,
                    whenToUse = "",
                    version = s.version,
                    installed = installedCheck(s.name),
                    updateAvailable = installedVersions[s.name]?.let { it < s.version } ?: false
                )
            }
        } else {
            // Pass 2: skill's real name/description/whenToUse. Capped at 12
            // fetches so big-catalog searches don't hammer the transport.
            finalResults = if (manifest.skills.size <= 12) {
                manifest.skills.mapNotNull { s ->
                    val fetched = SkillForge.fetchSkill(context, s.slug) ?: return@mapNotNull null
                    val hay = "${fetched.name} ${fetched.description} ${fetched.whenToUse}".lowercase()
                    if (terms.any { hay.contains(it) }) {
                        LibrarySearchEntry(
                            slug = fetched.slug, name = fetched.name, whenToUse = fetched.whenToUse,
                            version = fetched.version,
                            installed = installedCheck(fetched.name),
                            updateAvailable = installedVersions[fetched.name]?.let { it < fetched.version } ?: false
                        )
                    } else null
                }
            } else emptyList()
        }

        val r = SearchLibraryResult(
            query = query,
            reachable = true,
            totalMatches = finalResults.size,
            skills = finalResults
        )
        return ToolExecutionResult.success(r, json.encodeToString(SearchLibraryResult.serializer(), r))
    }

    private suspend fun getSkill(args: Map<String, Any?>): ToolExecutionResult {
        val slug = args["slug"] as? String ?: return ToolExecutionResult.error("Missing 'slug' parameter")
        val fetched = SkillForge.fetchSkill(context, slug.trim())
        if (fetched == null) {
            val r = GetSkillLibraryResult(success = false, slug = slug, error = "Couldn't fetch '$slug' — wrong slug or the library is unreachable right now.")
            return ToolExecutionResult.success(r, json.encodeToString(GetSkillLibraryResult.serializer(), r))
        }

        val r = GetSkillLibraryResult(
            success = true,
            slug = fetched.slug,
            name = fetched.name,
            description = fetched.description,
            whenToUse = fetched.whenToUse,
            allowedTools = fetched.allowedTools,
            bodyMarkdown = fetched.content,
            version = fetched.version
        )
        return ToolExecutionResult.success(r, json.encodeToString(GetSkillLibraryResult.serializer(), r))
    }

    private suspend fun installSkill(args: Map<String, Any?>): ToolExecutionResult {
        val slug = args["slug"] as? String ?: return ToolExecutionResult.error("Missing 'slug' parameter")
        val fetched = SkillForge.fetchSkill(context, slug.trim())
        if (fetched == null) {
            val r = InstallSkillLibraryResult(success = false, slug = slug, installed = false, error = "Install failed — bad slug or the library is unreachable. Nothing changed locally.")
            return ToolExecutionResult.success(r, json.encodeToString(InstallSkillLibraryResult.serializer(), r))
        }

        val allRows = luxifyRepository.getAllSkills()
        val matchingNameBefore = allRows.filter { it.name == fetched.name || it.name == fetched.slug }
        val wasUpdate = matchingNameBefore.isNotEmpty()

        /* One live copy per installed name: upsert freshest, retire everything
         * else. install itself is insert-on-row (source="synced"). */
        val installResult = luxifyRepository.installDynamicSkill(
            name = fetched.name,
            description = fetched.description,
            whenToUse = fetched.whenToUse,
            allowedTools = fetched.allowedTools,
            bodyMarkdown = fetched.content,
            source = "synced"
        )

        val allAfter = luxifyRepository.getAllSkills()
            .filter { it.name == fetched.name && it.id != installResult.id }
        for (stale in allAfter) {
            luxifyRepository.disableSkill(stale.id)
        }

        // Same canonical ledger the sync worker writes, so a manual install
        // and a forced-push land mark one source of truth for version state.
        SkillSyncMarks.recordApplied(context, fetched.slug, fetched.name, fetched.version)

        val r = InstallSkillLibraryResult(
            success = true,
            slug = fetched.slug,
            name = fetched.name,
            version = fetched.version,
            installed = true,
            wasUpdate = wasUpdate,
            message = if (wasUpdate)
                "Skill '${fetched.name}' updated to v${fetched.version}. The new version is live in your store."
            else
                "Skill '${fetched.name}' v${fetched.version} installed. It appears in your listing, persists across reboots, behaves like a bundled skill."
        )
        return ToolExecutionResult.success(r, json.encodeToString(InstallSkillLibraryResult.serializer(), r))
    }
}