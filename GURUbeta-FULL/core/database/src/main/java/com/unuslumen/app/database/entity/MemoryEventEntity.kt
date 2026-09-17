package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "memory_events",
    indices = [
        Index("enriched"),
        Index("created_at"),
        Index("conversation_id")
    ]
)
@Serializable
data class MemoryEventEntity(
    @SerialName("id")
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,
    @SerialName("conversation_id")
    @ColumnInfo(name = "conversation_id", defaultValue = "")
    val conversationId: String = "",
    @SerialName("message_id")
    @ColumnInfo(name = "message_id", defaultValue = "")
    val messageId: String = "",
    @SerialName("role")
    @ColumnInfo(name = "role", defaultValue = "")
    val role: String = "",
    @SerialName("content")
    @ColumnInfo(name = "content", defaultValue = "")
    val content: String = "",
    @SerialName("enriched")
    @ColumnInfo(name = "enriched", defaultValue = "0")
    val enriched: Int = 0,
    @SerialName("created_at")
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0L
)
