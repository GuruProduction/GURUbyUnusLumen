package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class GitHubStatusResult(val success: Boolean, val isLoggedIn: Boolean, val username: String? = null, val error: String? = null) : ToolResultData
@Serializable data class GitHubPr(val number: Int, val title: String, val state: String, val author: String, val url: String) : ToolResultData
@Serializable data class GitHubPrListResult(val success: Boolean, val prs: List<GitHubPr>, val error: String? = null) : ToolResultData
@Serializable data class GitHubPrDetails(val number: Int, val title: String, val body: String? = null, val author: String, val state: String, val url: String, val files: List<String>, val reviews: List<String>) : ToolResultData
@Serializable data class GitHubPrViewResult(val success: Boolean, val pr: GitHubPrDetails? = null, val error: String? = null) : ToolResultData
@Serializable data class GitHubPrCreateResult(val success: Boolean, val prNumber: Int? = null, val prUrl: String? = null, val error: String? = null) : ToolResultData
@Serializable data class GitHubIssue(val number: Int, val title: String, val state: String, val labels: List<String>, val url: String) : ToolResultData
@Serializable data class GitHubIssueListResult(val success: Boolean, val issues: List<GitHubIssue>, val error: String? = null) : ToolResultData
@Serializable data class GitHubIssueCreateResult(val success: Boolean, val issueNumber: Int? = null, val issueUrl: String? = null, val error: String? = null) : ToolResultData
@Serializable data class GitHubRepo(val name: String, val description: String? = null, val stars: Int, val forks: Int, val language: String? = null, val url: String) : ToolResultData
@Serializable data class GitHubRepoInfoResult(val success: Boolean, val repo: GitHubRepo? = null, val error: String? = null) : ToolResultData
@Serializable data class TrelloBoard(val id: String, val name: String) : ToolResultData
@Serializable data class TrelloBoardsResult(val success: Boolean, val boards: List<TrelloBoard>, val error: String? = null) : ToolResultData
@Serializable data class TrelloList(val id: String, val name: String) : ToolResultData
@Serializable data class TrelloListsResult(val success: Boolean, val lists: List<TrelloList>, val error: String? = null) : ToolResultData
@Serializable data class TrelloCard(val id: String, val name: String) : ToolResultData
@Serializable data class TrelloCardsResult(val success: Boolean, val cards: List<TrelloCard>, val error: String? = null) : ToolResultData
@Serializable data class TrelloCardResult(val success: Boolean, val cardId: String? = null, val error: String? = null) : ToolResultData
@Serializable data class NotionResult(val id: String, val title: String) : ToolResultData
@Serializable data class NotionSearchResult(val success: Boolean, val results: List<NotionResult>, val error: String? = null) : ToolResultData
@Serializable data class NotionPage(val id: String, val title: String, val content: String? = null) : ToolResultData
@Serializable data class NotionPageResult(val success: Boolean, val page: NotionPage? = null, val error: String? = null) : ToolResultData
@Serializable data class DiagramResult(val success: Boolean, val path: String? = null, val format: String, val error: String? = null) : ToolResultData