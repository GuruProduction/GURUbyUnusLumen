// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.Bookmark
import kotlinx.coroutines.flow.Flow

interface BookmarkRepository {

    fun getAllBookmarks(): Flow<List<Bookmark>>

    suspend fun getBookmark(id: String): Bookmark

    suspend fun searchBookmarks(query: String): List<Bookmark>

    suspend fun addBookmark(bookmark: Bookmark): Long

    suspend fun deleteBookmark(bookmark: Bookmark)

    suspend fun updateBookmark(bookmark: Bookmark)
}