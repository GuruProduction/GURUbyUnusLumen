package com.unuslumen.app.presentation

import com.unuslumen.app.domain.model.Bookmark

sealed class BookmarkDetailsEvent {
    data class ScreenOnStop(val bookmark: Bookmark): BookmarkDetailsEvent()
    data class DeleteBookmark(val bookmark: Bookmark) : BookmarkDetailsEvent()
}