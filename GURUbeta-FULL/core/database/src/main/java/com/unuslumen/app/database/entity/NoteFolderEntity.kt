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