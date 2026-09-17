package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.IdSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = ConversationEntity::class,
            parentColumns = ["id"],
            childColumns = ["conversation_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("conversation_id")]
)
@Serializable
data class MessageEntity(
    @SerialName("id")
    @PrimaryKey
    @Serializable(IdSerializer::class)
    val id: String,
    @SerialName("conversationId")
    @ColumnInfo(name = "conversation_id")
    @Serializable(IdSerializer::class)
    val conversationId: String,
    @SerialName("role")
    @ColumnInfo(defaultValue = "'user'")
    val role: String,
    @SerialName("content")
    @ColumnInfo(defaultValue = "")
    val content: String,
    @SerialName("timestamp")
    @ColumnInfo(defaultValue = "0")
    val timestamp: Long,
    @SerialName("embedding")
    @ColumnInfo(defaultValue = "")
    val embedding: List<Float> = emptyList(),
    @SerialName("toolCalls")
    @ColumnInfo(name = "tool_calls", defaultValue = "")
    val toolCalls: String = "",
    @SerialName("toolResults")
    @ColumnInfo(name = "tool_results", defaultValue = "")
    val toolResults: String = ""
)
