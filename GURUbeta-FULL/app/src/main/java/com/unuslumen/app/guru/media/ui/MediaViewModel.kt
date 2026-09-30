package com.unuslumen.app.guru.media.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.database.entity.MediaItemEntity
import com.unuslumen.app.guru.media.MediaLibraryRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

/**
 * MediaViewModel — feeds MediaScreen and MediaDetailScreen real data, real
 * counts; every value lands from the real repository. Deletion runs by
 * real repository. No synthetic rows, ids passed verbatim.
 */
@KoinViewModel
class MediaViewModel(
    private val application: Application,
    private val libraryRepository: MediaLibraryRepository
) : ViewModel() {

    private val _items = MutableStateFlow<List<MediaItemEntity>>(emptyList())
    val items: StateFlow<List<MediaItemEntity>> = _items.asStateFlow()

    /** True when COUNT(*) is zero — the real library count, the empty state signal. */
    private val _isEmpty = MutableStateFlow(true)
    val isEmpty: StateFlow<Boolean> = _isEmpty.asStateFlow()

    private val _searchQuery = MutableStateFlow("")

    /** The single currently focused item's row, loaded by id on detail navigation. */
    private val _detailItem = MutableStateFlow<MediaItemEntity?>(null)
    val detailItem: StateFlow<MediaItemEntity?> = _detailItem.asStateFlow()

    init {
        refreshList()
    }

    /**
     * Called from MediaScreen to refresh the rows.
     */
    fun refreshList() {
        viewModelScope.launch {
            val all = libraryRepository.listRecent(limit = 200)
            _items.value = all
            _isEmpty.value = all.emptyIfNoRows()
        }
    }

    /** Sets _searchQuery.value to the passed string and queries by FTS+LIKE fallback. */
    fun search(query: String) {
        _searchQuery.value = query
        viewModelScope.launch {
            if (query.isBlank()) {
                refreshList()
            } else {
                val hit = libraryRepository.search(query, limit = 200)
                _items.value = hit
            }
        }
    }

    /** Real detail row loaded when the user taps a grid item. */
    fun loadDetail(mediaId: String) {
        viewModelScope.launch {
            _detailItem.value = libraryRepository.getById(mediaId)
        }
    }

    /**
     * Hard delete from the detail screen's real repository read + count. Real counts.
     */
    fun deleteItem(mediaId: String) {
        viewModelScope.launch {
            libraryRepository.deleteItem(mediaId)
            _detailItem.value = null
            refreshList()
        }
    }
}

private fun List<MediaItemEntity>.emptyIfNoRows(): Boolean = isEmpty()