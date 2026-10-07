// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object ProductivityToolDefinitions : ToolSetRegistration {
    const val TRELLO_LOGIN = "trelloLogin"
    const val NOTION_LOGIN = "notionLogin"
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
        ToolDefinition(
            name = TRELLO_LOGIN,
            description = "Connect this install to the user's own Trello account. Takes the API key and token from trello.com/power-ups/admin, verifies them against the real Trello API, and seals them in device hardware storage so every future session stays connected.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("apiKey", ToolParameterType.String, true, "Trello Power-Up API key from trello.com/power-ups/admin"),
                ToolParameter("token", ToolParameterType.String, true, "Trello member token generated beside the API key")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(
            name = NOTION_LOGIN,
            description = "Connect this install to the user's own Notion workspace. Takes an internal integration token from notion.so/my-integrations, verifies it against the real Notion API, and seals it in device hardware so every future session stays connected. Wiki pages must still be shared to the integration via Notion's 'Add connections'.",
            category = "productivity",
            parameters = listOf(
                ToolParameter("token", ToolParameterType.String, true, "Internal integration secret from notion.so/my-integrations")
            ),
            permissions = emptyList()
        ),
        ToolDefinition(name = TRELLO_BOARDS, description = "List all Trello boards for the authenticated user. All traffic routed through Tor. Requires trelloLogin connection.", category = "productivity", parameters = emptyList(), permissions = emptyList()),
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