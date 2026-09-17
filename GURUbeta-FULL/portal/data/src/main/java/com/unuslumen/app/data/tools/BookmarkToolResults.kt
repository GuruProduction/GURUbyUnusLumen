package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.domain.model.Bookmark
import kotlinx.serialization.Serializable

@Serializable data class BookmarkIdResult(val createdBookmarkId: String) : ToolResultData
@Serializable data class SearchBookmarksResult(val bookmarks: List<Bookmark>) : ToolResultData
@Serializable data class BookmarkResult(val bookmark: Bookmark) : ToolResultData
@Serializable data class DeleteBookmarkResult(val deletedBookmarkId: String) : ToolResultData