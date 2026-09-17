package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.IdSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "conversation_threads")
@Serializable
data class ConversationThreadEntity(
    @SerialName("id")
    @PrimaryKey
    @Serializable(IdSerializer::class)
    val id: String,
    @SerialName("title")
    @ColumnInfo(defaultValue = "")
    val title: String,
    @SerialName("summary")
    @ColumnInfo(defaultValue = "")
    val summary: String,
    @SerialName("conversationIds")
    @ColumnInfo(name = "conversation_ids", defaultValue = "")
    val conversationIds: String = "",
    @SerialName("createdDate")
    @ColumnInfo(name = "created_date", defaultValue = "0")
    val createdDate: Long
)
