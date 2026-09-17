package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.IdSerializer
import com.unuslumen.app.domain.model.Mood
import com.unuslumen.app.domain.model.JournalEntry
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "journal")
@Serializable
data class JournalEntryEntity(
    @SerialName("title")
    val title: String = "",
    @SerialName("content")
    val content: String = "",
    @SerialName("createdDate")
    @ColumnInfo(name = "created_date")
    val createdDate: Long = 0L,
    @SerialName("updatedDate")
    @ColumnInfo(name = "updated_date")
    val updatedDate: Long = 0L,
    @SerialName("mood")
    val mood: Mood,
    @SerialName("id")
    @PrimaryKey
    @Serializable(IdSerializer::class)
    val id: String
)

fun JournalEntryEntity.toJournalEntry() = JournalEntry(
    title = title,
    content = content,
    createdDate = createdDate,
    updatedDate = updatedDate,
    mood = mood,
    id = id
)

fun JournalEntry.toJournalEntryEntity() = JournalEntryEntity(
    title = title,
    content = content,
    createdDate = createdDate,
    updatedDate = updatedDate,
    mood = mood,
    id = id
)