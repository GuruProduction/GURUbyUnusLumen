package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object BookmarkToolDefinitions : ToolSetRegistration {
    const val CREATE_BOOKMARK = "createBookmark"
    const val SEARCH_BOOKMARKS = "searchBookmarks"
    const val UPDATE_BOOKMARK = "updateBookmark"
    const val DELETE_BOOKMARK = "deleteBookmark"
    const val GET_ALL_BOOKMARKS = "getAllBookmarks"
    const val GET_BOOKMARK = "getBookmark"

    override val definitions = listOf(
        ToolDefinition(name = CREATE_BOOKMARK, description = "Create bookmark. Returns ID.", category = "bookmark", parameters = listOf(ToolParameter("url", ToolParameterType.String, true, "URL"), ToolParameter("title", ToolParameterType.String, false, "Title"), ToolParameter("description", ToolParameterType.String, false, "Description")), permissions = emptyList()),
        ToolDefinition(name = SEARCH_BOOKMARKS, description = "Search bookmarks by title/description/URL (partial match).", category = "bookmark", parameters = listOf(ToolParameter("query", ToolParameterType.String, true, "Search query")), permissions = emptyList()),
        ToolDefinition(name = UPDATE_BOOKMARK, description = "Update an existing bookmark. Only the fields you provide will be changed. Returns the updated bookmark.", category = "bookmark", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Bookmark ID"), ToolParameter("url", ToolParameterType.String, false, "New URL"), ToolParameter("title", ToolParameterType.String, false, "New title"), ToolParameter("description", ToolParameterType.String, false, "New description")), permissions = emptyList()),
        ToolDefinition(name = DELETE_BOOKMARK, description = "Delete a bookmark permanently. This cannot be undone.", category = "bookmark", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Bookmark ID")), permissions = emptyList()),
        ToolDefinition(name = GET_ALL_BOOKMARKS, description = "Get all bookmarks. Returns the complete list.", category = "bookmark", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GET_BOOKMARK, description = "Get a bookmark by its ID.", category = "bookmark", parameters = listOf(ToolParameter("id", ToolParameterType.String, true, "Bookmark ID")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = BookmarkToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}