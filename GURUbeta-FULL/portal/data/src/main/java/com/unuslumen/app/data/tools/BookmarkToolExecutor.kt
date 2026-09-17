package com.unuslumen.app.data.tools

import com.unuslumen.app.data.nowMillis
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.domain.model.Bookmark
import com.unuslumen.app.domain.use_case.AddBookmarkUseCase
import com.unuslumen.app.domain.use_case.DeleteBookmarkUseCase
import com.unuslumen.app.domain.use_case.GetAllBookmarksUseCase
import com.unuslumen.app.domain.use_case.GetBookmarkUseCase
import com.unuslumen.app.domain.use_case.SearchBookmarksUseCase
import com.unuslumen.app.domain.use_case.UpdateBookmarkUseCase
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.Json
import kotlin.uuid.Uuid

class BookmarkToolExecutor(
    private val addBookmark: AddBookmarkUseCase,
    private val searchBookmarksUseCase: SearchBookmarksUseCase,
    private val updateBookmarkUseCase: UpdateBookmarkUseCase,
    private val deleteBookmarkUseCase: DeleteBookmarkUseCase,
    private val getAllBookmarksUseCase: GetAllBookmarksUseCase,
    private val getBookmarkUseCase: GetBookmarkUseCase
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        BookmarkToolDefinitions.CREATE_BOOKMARK -> createBookmark(args)
        BookmarkToolDefinitions.SEARCH_BOOKMARKS -> { val r = SearchBookmarksResult(searchBookmarksUseCase(args["query"] as? String ?: "")); ToolExecutionResult.success(r, json.encodeToString(SearchBookmarksResult.serializer(), r)) }
        BookmarkToolDefinitions.UPDATE_BOOKMARK -> updateBookmark(args)
        BookmarkToolDefinitions.DELETE_BOOKMARK -> deleteBookmark(args)
        BookmarkToolDefinitions.GET_ALL_BOOKMARKS -> { val b = getAllBookmarksUseCase(Order.DateModified(OrderType.DESC)).first(); val r = SearchBookmarksResult(b); ToolExecutionResult.success(r, json.encodeToString(SearchBookmarksResult.serializer(), r)) }
        BookmarkToolDefinitions.GET_BOOKMARK -> { val r = BookmarkResult(getBookmarkUseCase(args["id"] as? String ?: "")); ToolExecutionResult.success(r, json.encodeToString(BookmarkResult.serializer(), r)) }
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun createBookmark(args: Map<String, Any?>): ToolExecutionResult {
        val url = args["url"] as? String ?: return ToolExecutionResult.error("Missing 'url'")
        val id = Uuid.random().toString()
        val bm = Bookmark(url = url, title = args["title"] as? String ?: "", description = args["description"] as? String ?: "", createdDate = nowMillis(), updatedDate = nowMillis(), id = id)
        addBookmark(bm)
        val r = BookmarkIdResult(id); return ToolExecutionResult.success(r, json.encodeToString(BookmarkIdResult.serializer(), r))
    }

    private suspend fun updateBookmark(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val existing = getBookmarkUseCase(id)
        val updated = existing.copy(url = (args["url"] as? String) ?: existing.url, title = (args["title"] as? String) ?: existing.title, description = (args["description"] as? String) ?: existing.description, updatedDate = nowMillis())
        updateBookmarkUseCase(updated)
        val r = BookmarkResult(updated); return ToolExecutionResult.success(r, json.encodeToString(BookmarkResult.serializer(), r))
    }

    private suspend fun deleteBookmark(args: Map<String, Any?>): ToolExecutionResult {
        val id = args["id"] as? String ?: return ToolExecutionResult.error("Missing 'id'")
        val bm = getBookmarkUseCase(id)
        deleteBookmarkUseCase(bm)
        val r = DeleteBookmarkResult(id); return ToolExecutionResult.success(r, json.encodeToString(DeleteBookmarkResult.serializer(), r))
    }
}