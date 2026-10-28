// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

/**
 * Git tool result types. Every field carries the genuine state of the bundled
 * git repository work: where a clone landed, what status was read, what was
 * pushed. Failure fields carry git's own words verbatim, never a paraphrase.
 */

@Serializable
data class GitCloneResult(
    val success: Boolean,
    val repository: String? = null,
    val path: String? = null,
    val url: String? = null,
    val message: String? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitFileStatus(
    val path: String,
    val indexCode: String,
    val worktreeCode: String
) : ToolResultData

@Serializable
data class GitStatusResult(
    val success: Boolean,
    val repository: String? = null,
    val branch: String? = null,
    val upstream: String? = null,
    val clean: Boolean,
    val stagedCount: Int,
    val modifiedCount: Int,
    val untrackedCount: Int,
    val totalChanged: Int,
    val files: List<GitFileStatus> = emptyList(),
    val output: String? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitBranchResult(
    val success: Boolean,
    val repository: String? = null,
    val current: String? = null,
    val branches: List<String> = emptyList(),
    val localBranches: List<String> = emptyList(),
    val remoteBranches: List<String> = emptyList(),
    val output: String? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitLogEntry(
    val hash: String,
    val author: String,
    val date: String,
    val subject: String
) : ToolResultData

@Serializable
data class GitLogResult(
    val success: Boolean,
    val repository: String? = null,
    val entries: List<GitLogEntry> = emptyList(),
    val output: String? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitDiffResult(
    val success: Boolean,
    val repository: String? = null,
    val staged: Boolean,
    val stat: Boolean,
    val diff: String? = null,
    val truncated: Boolean = false,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitCommandResult(
    val success: Boolean,
    val repository: String? = null,
    val command: String,
    val exitCode: Int,
    val stdout: String = "",
    val stderr: String = "",
    val error: String? = null
) : ToolResultData

@Serializable
data class GitVersionResult(
    val success: Boolean,
    val version: String? = null,
    val binaryPath: String? = null,
    val error: String? = null
) : ToolResultData
