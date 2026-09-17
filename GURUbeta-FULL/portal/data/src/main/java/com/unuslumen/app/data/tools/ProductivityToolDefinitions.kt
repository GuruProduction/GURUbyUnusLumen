package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object ProductivityToolDefinitions : ToolSetRegistration {
    const val GITHUB_STATUS = "githubStatus"
    const val GITHUB_PR_LIST = "githubPrList"
    const val GITHUB_PR_VIEW = "githubPrView"
    const val GITHUB_PR_CREATE = "githubPrCreate"
    const val GITHUB_ISSUE_LIST = "githubIssueList"
    const val GITHUB_ISSUE_CREATE = "githubIssueCreate"
    const val GITHUB_REPO_INFO = "githubRepoInfo"
    const val TRELLO_BOARDS = "trelloBoards"
    const val TRELLO_LISTS = "trelloLists"
    const val TRELLO_CARDS = "trelloCards"
    const val TRELLO_CREATE_CARD = "trelloCreateCard"
    const val TRELLO_MOVE_CARD = "trelloMoveCard"
    const val NOTION_SEARCH = "notionSearch"
    const val NOTION_GET_PAGE = "notionGetPage"
    const val NOTION_CREATE_PAGE = "notionCreatePage"
    const val DIAGRAM_CREATE = "diagramCreate"

    override val definitions = listOf(
        ToolDefinition(name = GITHUB_STATUS, description = "Check GitHub authentication status and current user.", category = "productivity", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GITHUB_PR_LIST, description = "List pull requests in a repository. Returns PR numbers, titles, states, and authors.", category = "productivity", parameters = listOf(ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format (e.g., 'facebook/react')"), ToolParameter("state", ToolParameterType.String, false, "State filter: 'open', 'closed', or 'all'. Default 'open'."), ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results. Default 20.")), permissions = emptyList()),
        ToolDefinition(name = GITHUB_PR_VIEW, description = "View details of a specific pull request including title, body, files, and reviews.", category = "productivity", parameters = listOf(ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format"), ToolParameter("number", ToolParameterType.Integer, true, "PR number")), permissions = emptyList()),
        ToolDefinition(name = GITHUB_PR_CREATE, description = "Create a new pull request.", category = "productivity", parameters = listOf(ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format"), ToolParameter("title", ToolParameterType.String, true, "PR title"), ToolParameter("body", ToolParameterType.String, true, "PR body/description"), ToolParameter("base", ToolParameterType.String, false, "Base branch (target). Default 'main'."), ToolParameter("head", ToolParameterType.String, false, "Head branch (source). Default 'HEAD'.")), permissions = emptyList()),
        ToolDefinition(name = GITHUB_ISSUE_LIST, description = "List issues in a repository. Returns issue numbers, titles, labels, and states.", category = "productivity", parameters = listOf(ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format"), ToolParameter("state", ToolParameterType.String, false, "State filter: 'open', 'closed', or 'all'. Default 'open'."), ToolParameter("label", ToolParameterType.String, false, "Label filter (optional)"), ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results. Default 20.")), permissions = emptyList()),
        ToolDefinition(name = GITHUB_ISSUE_CREATE, description = "Create a new issue in a repository.", category = "productivity", parameters = listOf(ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format"), ToolParameter("title", ToolParameterType.String, true, "Issue title"), ToolParameter("body", ToolParameterType.String, true, "Issue body/description"), ToolParameter("labels", ToolParameterType.String, false, "Labels to apply (comma-separated, optional)")), permissions = emptyList()),
        ToolDefinition(name = GITHUB_REPO_INFO, description = "Get repository information including stars, forks, description, and README.", category = "productivity", parameters = listOf(ToolParameter("repo", ToolParameterType.String, true, "Repository in owner/repo format")), permissions = emptyList()),
        ToolDefinition(name = TRELLO_BOARDS, description = "List all Trello boards for the authenticated user. All traffic routed through Tor.", category = "productivity", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = TRELLO_LISTS, description = "List all lists (columns) in a Trello board through Tor.", category = "productivity", parameters = listOf(ToolParameter("boardId", ToolParameterType.String, true, "Board ID")), permissions = emptyList()),
        ToolDefinition(name = TRELLO_CARDS, description = "List all cards in a Trello list through Tor.", category = "productivity", parameters = listOf(ToolParameter("listId", ToolParameterType.String, true, "List ID")), permissions = emptyList()),
        ToolDefinition(name = TRELLO_CREATE_CARD, description = "Create a new Trello card in a list through Tor.", category = "productivity", parameters = listOf(ToolParameter("listId", ToolParameterType.String, true, "List ID to add the card to"), ToolParameter("name", ToolParameterType.String, true, "Card title"), ToolParameter("description", ToolParameterType.String, false, "Card description (optional)")), permissions = emptyList()),
        ToolDefinition(name = TRELLO_MOVE_CARD, description = "Move a Trello card to a different list through Tor.", category = "productivity", parameters = listOf(ToolParameter("cardId", ToolParameterType.String, true, "Card ID to move"), ToolParameter("targetListId", ToolParameterType.String, true, "Target list ID")), permissions = emptyList()),
        ToolDefinition(name = NOTION_SEARCH, description = "Search Notion pages and databases through Tor. Returns matching results with IDs and titles.", category = "productivity", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query"), ToolParameter("limit", ToolParameterType.Integer, false, "Maximum results. Default 10.")), permissions = emptyList()),
        ToolDefinition(name = NOTION_GET_PAGE, description = "Get a Notion page by ID including its content through Tor.", category = "productivity", parameters = listOf(ToolParameter("pageId", ToolParameterType.String, true, "Page ID")), permissions = emptyList()),
        ToolDefinition(name = NOTION_CREATE_PAGE, description = "Create a new Notion page in a parent page or database.", category = "productivity", parameters = listOf(ToolParameter("parentId", ToolParameterType.String, true, "Parent page ID or database ID"), ToolParameter("title", ToolParameterType.String, true, "Page title"), ToolParameter("content", ToolParameterType.String, false, "Page content in Markdown (optional)")), permissions = emptyList()),
        ToolDefinition(name = DIAGRAM_CREATE, description = "Create a diagram from text description using Mermaid or Excalidraw syntax. Returns the path to the generated image.", category = "productivity", parameters = listOf(ToolParameter("code", ToolParameterType.String, true, "Diagram code in Mermaid or Excalidraw format"), ToolParameter("format", ToolParameterType.String, false, "Output format: 'svg', 'png', or 'excalidraw'. Default 'svg'."), ToolParameter("filename", ToolParameterType.String, false, "Filename without extension. Default 'diagram'.")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = ProductivityToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}