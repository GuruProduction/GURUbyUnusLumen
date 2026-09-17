package com.unuslumen.app.presentation

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.Bookmark
import com.unuslumen.app.domain.use_case.AddBookmarkUseCase
import com.unuslumen.app.domain.use_case.DeleteBookmarkUseCase
import com.unuslumen.app.domain.use_case.GetBookmarkUseCase
import com.unuslumen.app.domain.use_case.UpdateBookmarkUseCase
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.snackbar.showSnackbar
import com.unuslumen.app.util.date.now
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import org.koin.core.annotation.Named

@KoinViewModel
class BookmarkDetailsViewModel(
    private val getBookmark: GetBookmarkUseCase,
    private val updateBookmark: UpdateBookmarkUseCase,
    private val addBookmark: AddBookmarkUseCase,
    private val deleteBookmark: DeleteBookmarkUseCase,
    @Named("applicationScope") private val applicationScope: CoroutineScope,
    bookmarkId: String,
) : ViewModel() {

    var bookmarkDetailsUiState by mutableStateOf(BookmarkDetailsUiState())
        private set

    init {
        viewModelScope.launch {
            val bookmark = if (bookmarkId.isNotBlank()) getBookmark(bookmarkId) else null
            if (bookmarkId.isNotBlank() && bookmark == null) {
                bookmarkDetailsUiState.snackbarHostState.showSnackbar(R.string.error_item_not_found)
            }
            bookmarkDetailsUiState = bookmarkDetailsUiState.copy(
                bookmark = bookmark
            )
        }
    }

    fun onEvent(event: BookmarkDetailsEvent) {
        when (event) {
            // Using applicationScope to avoid cancelling when the user exits the screen
            // and the view model is cleared before the job finishes
            is BookmarkDetailsEvent.ScreenOnStop -> applicationScope.launch {
                if (!bookmarkDetailsUiState.navigateUp) {
                    if (bookmarkDetailsUiState.bookmark == null) {
                        if (event.bookmark.url.isNotBlank()
                            || event.bookmark.title.isNotBlank()
                            || event.bookmark.description.isNotBlank()
                        ) {
                            val bookmark = event.bookmark.copy(
                                createdDate = now(),
                                updatedDate = now()
                            )
                            addBookmark(bookmark)
                            bookmarkDetailsUiState =
                                bookmarkDetailsUiState.copy(bookmark = bookmark)
                        }
                    } else if (bookmarkChanged(bookmarkDetailsUiState.bookmark!!, event.bookmark)) {
                        val newBookmark = bookmarkDetailsUiState.bookmark!!.copy(
                            title = event.bookmark.title,
                            description = event.bookmark.description,
                            url = event.bookmark.url,
                            updatedDate = now()
                        )
                        updateBookmark(newBookmark)
                        bookmarkDetailsUiState = bookmarkDetailsUiState.copy(bookmark = newBookmark)
                    }
                }
            }

            is BookmarkDetailsEvent.DeleteBookmark -> viewModelScope.launch {
                deleteBookmark(bookmarkDetailsUiState.bookmark!!)
                bookmarkDetailsUiState = bookmarkDetailsUiState.copy(navigateUp = true)
            }
        }
    }

    data class BookmarkDetailsUiState(
        val bookmark: Bookmark? = null,
        val navigateUp: Boolean = false,
        val snackbarHostState: SnackbarHostState = SnackbarHostState()
    )

    private fun bookmarkChanged(
        bookmark: Bookmark,
        newBookmark: Bookmark,
    ): Boolean {
        return bookmark.title != newBookmark.title ||
                bookmark.description != newBookmark.description ||
                bookmark.url != newBookmark.url
    }
}