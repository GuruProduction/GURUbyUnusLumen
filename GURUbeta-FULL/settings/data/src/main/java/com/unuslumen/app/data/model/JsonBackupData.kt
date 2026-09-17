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