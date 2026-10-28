// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

/**
 * Result types for the extended GitHub REST family (beyond the original ten):
 * repo admin, file reading and single-file commits, the Git Data atomic push,
 * binary downloads and uploads, branches, commit history, PR and issue
 * completion, releases, Actions, search, gists and the universal gh-api
 * passthrough. Error fields carry GitHub's own message verbatim.
 */

@Serializable
data class GitHubContentEntry(
    val name: String,
    val path: String,
    val type: String,
    val size: Long = 0,
    val sha: String? = null
) : ToolResultData

@Serializable
data class GitHubFileReadResult(
    val success: Boolean,
    val path: String? = null,
    val ref: String? = null,
    val kind: String? = null, // "file" | "dir"
    val content: String? = null,
    val size: Long? = null,
    val sha: String? = null,
    val entries: List<GitHubContentEntry> = emptyList(),
    val downloadUrl: String? = null,
    val truncated: Boolean = false,
    val note: String? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubTreeEntryItem(
    val path: String,
    val type: String,
    val size: Long = 0
) : ToolResultData

@Serializable
data class GitHubTreeResult(
    val success: Boolean,
    val ref: String? = null,
    val truncated: Boolean = false,
    val count: Int = 0,
    val entries: List<GitHubTreeEntryItem> = emptyList(),
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubDownloadResult(
    val success: Boolean,
    val path: String? = null,
    val bytes: Long = 0,
    val sourceUrl: String? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubMutationResult(
    val success: Boolean,
    val message: String? = null,
    val url: String? = null,
    val sha: String? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubRepoListItem(
    val fullName: String,
    val description: String? = null,
    val isPrivate: Boolean = false,
    val language: String? = null,
    val updatedAt: String? = null,
    val url: String? = null
) : ToolResultData

@Serializable
data class GitHubRepoListResult(
    val success: Boolean,
    val repos: List<GitHubRepoListItem> = emptyList(),
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubRepoResult(
    val success: Boolean,
    val repo: GitHubRepoListItem? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubBranchItem(
    val name: String,
    val sha: String? = null,
    val isProtected: Boolean = false
) : ToolResultData

@Serializable
data class GitHubBranchListResult(
    val success: Boolean,
    val branches: List<GitHubBranchItem> = emptyList(),
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubCommitSummaryItem(
    val sha: String,
    val title: String,
    val author: String? = null,
    val date: String? = null,
    val url: String? = null
) : ToolResultData

@Serializable
data class GitHubCommitListResult(
    val success: Boolean,
    val commits: List<GitHubCommitSummaryItem> = emptyList(),
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubCommitFileEntry(
    val path: String,
    val status: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    val patch: String? = null
) : ToolResultData

@Serializable
data class GitHubCommitDetailResult(
    val success: Boolean,
    val sha: String? = null,
    val author: String? = null,
    val date: String? = null,
    val message: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val files: List<GitHubCommitFileEntry> = emptyList(),
    val url: String? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubPushResult(
    val success: Boolean,
    val branch: String? = null,
    val commitSha: String? = null,
    val commitUrl: String? = null,
    val filesChanged: Int = 0,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubMergeResult(
    val success: Boolean,
    val merged: Boolean = false,
    val sha: String? = null,
    val message: String? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubIssueDetailItem(
    val number: Int,
    val title: String,
    val state: String,
    val body: String? = null,
    val labels: List<String> = emptyList(),
    val assignees: List<String> = emptyList(),
    val author: String? = null,
    val isPullRequest: Boolean = false,
    val url: String? = null
) : ToolResultData

@Serializable
data class GitHubIssueDetailResult(
    val success: Boolean,
    val issue: GitHubIssueDetailItem? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubReleaseAsset(
    val id: Long,
    val name: String,
    val size: Long = 0,
    val browserDownloadUrl: String? = null
) : ToolResultData

@Serializable
data class GitHubReleaseItem(
    val id: Long,
    val tagName: String,
    val name: String? = null,
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val createdAt: String? = null,
    val htmlUrl: String? = null,
    val assets: List<GitHubReleaseAsset> = emptyList()
) : ToolResultData

@Serializable
data class GitHubReleaseListResult(
    val success: Boolean,
    val releases: List<GitHubReleaseItem> = emptyList(),
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubReleaseResult(
    val success: Boolean,
    val release: GitHubReleaseItem? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubRunItem(
    val id: Long,
    val name: String? = null,
    val branch: String? = null,
    val status: String? = null,
    val conclusion: String? = null,
    val createdAt: String? = null,
    val url: String? = null
) : ToolResultData

@Serializable
data class GitHubRunListResult(
    val success: Boolean,
    val runs: List<GitHubRunItem> = emptyList(),
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubRunViewResult(
    val success: Boolean,
    val run: GitHubRunItem? = null,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubSearchItem(
    val title: String,
    val subtitle: String? = null,
    val url: String? = null
) : ToolResultData

@Serializable
data class GitHubSearchResult(
    val success: Boolean,
    val total: Int = 0,
    val items: List<GitHubSearchItem> = emptyList(),
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubGistItem(
    val id: String,
    val description: String? = null,
    val isPublic: Boolean = false,
    val files: List<String> = emptyList(),
    val htmlUrl: String? = null,
    val updatedAt: String? = null
) : ToolResultData

@Serializable
data class GitHubGistListResult(
    val success: Boolean,
    val gists: List<GitHubGistItem> = emptyList(),
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubApiResult(
    val success: Boolean,
    val status: Int = 0,
    val body: String? = null,
    val truncated: Boolean = false,
    val error: String? = null
) : ToolResultData

@Serializable
data class GitHubPrFileEntry(
    val path: String,
    val status: String? = null,
    val additions: Int = 0,
    val deletions: Int = 0,
    val changes: Int = 0,
    val patch: String? = null
) : ToolResultData

@Serializable
data class GitHubPrFilesResult(
    val success: Boolean,
    val files: List<GitHubPrFileEntry> = emptyList(),
    val error: String? = null
) : ToolResultData
