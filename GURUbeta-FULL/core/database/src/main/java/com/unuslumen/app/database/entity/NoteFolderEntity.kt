// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.IdSerializer
import com.unuslumen.app.domain.model.NoteFolder
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "note_folders",
)
@Serializable
data class NoteFolderEntity(
    @SerialName("name")
    val name: String = "",
    @SerialName("id")
    @PrimaryKey
    @Serializable(IdSerializer::class)
    val id: String
)

fun NoteFolderEntity.toNoteFolder(): NoteFolder {
    return NoteFolder(
        name = name,
        id = id,
    )
}

fun NoteFolder.toNoteFolderEntity(): NoteFolderEntity {
    return NoteFolderEntity(
        name = name,
        id = id,
    )
}