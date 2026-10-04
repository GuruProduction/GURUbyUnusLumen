// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.model

import com.unuslumen.app.database.entity.BookmarkEntity
import com.unuslumen.app.database.entity.JournalEntryEntity
import com.unuslumen.app.database.entity.NoteEntity
import com.unuslumen.app.database.entity.NoteFolderEntity
import com.unuslumen.app.database.entity.TaskEntity
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class JsonBackupData(
    @SerialName("notes") val notes: List<NoteEntity> = emptyList(),
    @SerialName("noteFolders") val noteFolders: List<NoteFolderEntity> = emptyList(),
    @SerialName("tasks") val tasks: List<TaskEntity> = emptyList(),
    @SerialName("journal") val journal: List<JournalEntryEntity> = emptyList(),
    @SerialName("bookmarks") val bookmarks: List<BookmarkEntity> = emptyList()
)