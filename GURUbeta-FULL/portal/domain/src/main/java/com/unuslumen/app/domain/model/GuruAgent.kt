// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

import kotlinx.serialization.Serializable

/**
 * One agent definition the numen can call by name.
 *
 * [slug] is the catalogue identity once the agent has been published or
 * downloaded, and empty for one that only ever exists on this phone. [source]
 * is "user", "synced" or "bundled" and it is the trust boundary: the sync
 * worker may overwrite or withdraw a synced agent and must never touch a user
 * one. [version] is the applied publication version.
 */
@Serializable
data class GuruAgent(
    val id: String,
    val slug: String,
    val name: String,
    val description: String,
    val systemPrompt: String,
    val toolPermissions: List<String>,
    val source: String,
    val enabled: Boolean = true,
    val version: Int = 1,
    val createdAt: Long,
    val updatedAt: Long
) {
    /** True when this agent is the person's own and cannot be overwritten or withdrawn. */
    val isUserOwned: Boolean get() = source == SOURCE_USER

    companion object {
        const val SOURCE_USER = "user"
        const val SOURCE_SYNCED = "synced"
        const val SOURCE_BUNDLED = "bundled"
    }
}

/** A create or edit request from the creator screen. */
@Serializable
data class SaveAgentRequest(
    val name: String,
    val description: String = "",
    val systemPrompt: String,
    val toolPermissions: List<String> = emptyList()
)

/** Counts for the agents list header. */
@Serializable
data class AgentSummary(
    val total: Int,
    val enabled: Int,
    val userMade: Int,
    val downloaded: Int
)
