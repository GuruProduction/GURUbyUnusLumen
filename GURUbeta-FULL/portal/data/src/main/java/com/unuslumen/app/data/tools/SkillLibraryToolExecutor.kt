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
import com.unuslumen.app.domain.skills.SkillAuthoring
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
            SkillLibraryToolDefinitions.LIST_SKILLS -> listSkills()
            SkillLibraryToolDefinitions.CREATE_SKILL -> createSkill(args)
            SkillLibraryToolDefinitions.UPDATE_SKILL -> updateSkill(args)
            SkillLibraryToolDefinitions.ENABLE_SKILL -> setEnabled(args, enable = true)
            SkillLibraryToolDefinitions.DISABLE_SKILL -> setEnabled(args, enable = false)
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

    /**
     * Every skill the agent holds, with enough detail to write a new one without
     * duplicating an existing one (issue 492).
     *
     * The old six verbs could use a skill and install someone else's. A model asked
     * to improve GURU could not see the list of methodologies it already had, which is
     * the first step of every "build me a workflow" request.
     */
    private suspend fun listSkills(): ToolExecutionResult {
        val rows = luxifyRepository.getAllSkills()
        val r = SkillAuthoringListResult(
            total = rows.size,
            skills = rows.map { skill ->
                SkillAuthoringEntry(
                    name = skill.name,
                    description = skill.description,
                    whenToUse = skill.whenToUse,
                    source = skill.source,
                    enabled = skill.enabled,
                    bodyChars = skill.bodyMarkdown.length,
                    allowedTools = skill.allowedTools,
                )
            },
        )
        return ToolExecutionResult.success(r, json.encodeToString(SkillAuthoringListResult.serializer(), r))
    }

    /**
     * Write a new skill into the live store the phone reads (issue 492).
     *
     * The validation is `SkillAuthoring.validate`, which refuses five things that
     * would each produce a skill that cannot do its job: no name, an unusable name, no
     * description, a body too short to be a methodology, an unclosed frontmatter block,
     * and an allowed-tool that is not in the registry. That last one matters most: a
     * silently dropped tool grants nothing while reading as though it granted
     * something.
     *
     * The set of known tool names comes from the same registry the dispatcher reads, so
     * a skill cannot be written against a tool that does not exist.
     */
    private suspend fun createSkill(args: Map<String, Any?>): ToolExecutionResult {
        val name = (args["name"] as? String).orEmpty()
        val description = (args["description"] as? String).orEmpty()
        val whenToUse = (args["whenToUse"] as? String).orEmpty()
        val body = (args["bodyMarkdown"] as? String).orEmpty()
        val allowedTools = (args["allowedTools"] as? String)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            .orEmpty()

        val knownTools = com.unuslumen.app.data.tools.registry.ToolRegistration
            .allDefinitions()
            .map { it.name }
            .toSet()

        when (val verdict = SkillAuthoring.validate(name, description, body, allowedTools, knownTools)) {
            is SkillAuthoring.Verdict.Invalid -> {
                val r = SkillAuthoringWriteResult(
                    success = false, name = name.trim(), error = verdict.reason,
                )
                return ToolExecutionResult.success(r, json.encodeToString(SkillAuthoringWriteResult.serializer(), r))
            }
            SkillAuthoring.Verdict.Valid -> Unit
        }

        // Refuse a duplicate name rather than silently replacing an existing skill's
        // body: a write that overwrites a methodology the agent relied on is the same
        // class of loss as the memory update that destroyed its own history.
        val existing = luxifyRepository.getSkillByName(name.trim())
        if (existing != null) {
            val r = SkillAuthoringWriteResult(
                success = false,
                name = name.trim(),
                error = "A skill called \"${name.trim()}\" already exists. Read it with listSkills " +
                    "and use updateSkill to change it, rather than creating a second one under the same name.",
            )
            return ToolExecutionResult.success(r, json.encodeToString(SkillAuthoringWriteResult.serializer(), r))
        }

        val created = luxifyRepository.createSkill(
            com.unuslumen.app.domain.model.CreateLuxifyRequest(
                name = name.trim(),
                description = description.trim(),
                whenToUse = whenToUse.trim().ifEmpty { SkillAuthoring.defaultWhenToUse(name.trim(), description.trim()) },
                allowedTools = allowedTools,
                bodyMarkdown = body,
            ),
        )
        val r = SkillAuthoringWriteResult(
            success = true,
            name = created.name,
            createdAt = created.createdAt,
            error = null,
        )
        return ToolExecutionResult.success(r, json.encodeToString(SkillAuthoringWriteResult.serializer(), r))
    }

    /**
     * Change a skill in place (issue 492).
     *
     * Every field is optional and an absent one keeps what the row already holds, so a
     * caller changing the description does not have to re-send the whole body. The
     * merged result is validated as a whole, because a change to one field can make the
     * set invalid — shortening a body below the minimum, or replacing the allowed tools
     * with a name that does not exist.
     */
    private suspend fun updateSkill(args: Map<String, Any?>): ToolExecutionResult {
        val name = (args["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) {
            val r = SkillAuthoringWriteResult(false, "", error = "Missing 'name'")
            return ToolExecutionResult.success(r, json.encodeToString(SkillAuthoringWriteResult.serializer(), r))
        }
        val existing = luxifyRepository.getSkillByName(name)
        if (existing == null) {
            val r = SkillAuthoringWriteResult(
                false, name,
                error = "No skill called \"$name\". Use listSkills to see the names you hold.",
            )
            return ToolExecutionResult.success(r, json.encodeToString(SkillAuthoringWriteResult.serializer(), r))
        }

        val newDescription = (args["description"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: existing.description
        val newWhenToUse = (args["whenToUse"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: existing.whenToUse
        val newBody = (args["bodyMarkdown"] as? String)?.takeIf { it.isNotBlank() } ?: existing.bodyMarkdown
        val newTools = (args["allowedTools"] as? String)
            ?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?: existing.allowedTools

        val knownTools = com.unuslumen.app.data.tools.registry.ToolRegistration
            .allDefinitions()
            .map { it.name }
            .toSet()

        when (val verdict = SkillAuthoring.validate(name, newDescription, newBody, newTools, knownTools)) {
            is SkillAuthoring.Verdict.Invalid -> {
                val r = SkillAuthoringWriteResult(false, name, error = verdict.reason)
                return ToolExecutionResult.success(r, json.encodeToString(SkillAuthoringWriteResult.serializer(), r))
            }
            SkillAuthoring.Verdict.Valid -> Unit
        }

        val updated = luxifyRepository.updateSkill(
            existing.id,
            com.unuslumen.app.domain.model.CreateLuxifyRequest(
                name = existing.name,
                description = newDescription,
                whenToUse = newWhenToUse,
                allowedTools = newTools,
                bodyMarkdown = newBody,
            ),
        )
        val r = SkillAuthoringWriteResult(
            success = true, name = updated.name, createdAt = updated.updatedAt, error = null,
        )
        return ToolExecutionResult.success(r, json.encodeToString(SkillAuthoringWriteResult.serializer(), r))
    }

    /**
     * Turn a skill on or off without destroying it (issue 492).
     *
     * Two verbs rather than a boolean, because a skill that is off is a methodology that
     * has gone stale, and the house rule is that nothing is deleted when it can be
     * retired. Disabling keeps the text and the handle; deleting takes both.
     */
    private suspend fun setEnabled(args: Map<String, Any?>, enable: Boolean): ToolExecutionResult {
        val name = (args["name"] as? String)?.trim().orEmpty()
        if (name.isEmpty()) {
            val r = SkillAuthoringWriteResult(false, "", error = "Missing 'name'")
            return ToolExecutionResult.success(r, json.encodeToString(SkillAuthoringWriteResult.serializer(), r))
        }
        val existing = luxifyRepository.getSkillByName(name)
        if (existing == null) {
            val r = SkillAuthoringWriteResult(
                false, name,
                error = "No skill called \"$name\". Use listSkills to see the names you hold.",
            )
            return ToolExecutionResult.success(r, json.encodeToString(SkillAuthoringWriteResult.serializer(), r))
        }
        val changed = if (enable) {
            luxifyRepository.enableSkill(existing.id)
        } else {
            luxifyRepository.disableSkill(existing.id)
        }
        val r = SkillAuthoringWriteResult(success = true, name = changed.name, error = null)
        return ToolExecutionResult.success(r, json.encodeToString(SkillAuthoringWriteResult.serializer(), r))
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