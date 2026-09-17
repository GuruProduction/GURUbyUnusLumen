package com.unuslumen.app.presentation

import com.unuslumen.app.domain.model.Bookmark
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.ui.ItemView

sealed class BookmarkEvent {
    data class AddBookmark(val bookmark: Bookmark) : BookmarkEvent()
    data class SearchBookmarks(val query: String) : BookmarkEvent()
    data class UpdateOrder(val order: Order) : BookmarkEvent()
    data class UpdateView(val view: ItemView) : BookmarkEvent()
    data object ErrorDisplayed: BookmarkEvent()
}
