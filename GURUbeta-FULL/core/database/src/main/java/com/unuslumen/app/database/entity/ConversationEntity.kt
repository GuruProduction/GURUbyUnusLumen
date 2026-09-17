package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.IdSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "conversations")
@Serializable
data class ConversationEntity(
    @SerialName("id")
    @PrimaryKey
    @Serializable(IdSerializer::class)
    val id: String,
    @SerialName("title")
    @ColumnInfo(defaultValue = "")
    val title: String,
    @SerialName("createdDate")
    @ColumnInfo(name = "created_date", defaultValue = "0")
    val createdDate: Long,
    @SerialName("updatedDate")
    @ColumnInfo(name = "updated_date", defaultValue = "0")
    val updatedDate: Long,
    @SerialName("messageCount")
    @ColumnInfo(name = "message_count", defaultValue = "0")
    val messageCount: Int = 0
)
