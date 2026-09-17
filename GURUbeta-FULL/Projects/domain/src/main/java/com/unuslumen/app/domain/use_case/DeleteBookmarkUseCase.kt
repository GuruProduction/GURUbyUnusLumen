package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.Bookmark
import com.unuslumen.app.domain.repository.BookmarkRepository
import org.koin.core.annotation.Single

@Single
class DeleteBookmarkUseCase(
    private val bookmarkRepository: BookmarkRepository
) {
    suspend operator fun invoke(bookmark: Bookmark) = bookmarkRepository.deleteBookmark(bookmark)
}