package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.IdSerializer
import com.unuslumen.app.domain.model.Project
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "projects",
    indices = [
        Index(value = ["title"]),
        Index(value = ["updated_date"])
    ]
)
@Serializable
data class ProjectEntity(
    @SerialName("id")
    @PrimaryKey
    @Serializable(IdSerializer::class)
    val id: String,

    @SerialName("title")
    val title: String,

    @SerialName("description")
    @ColumnInfo(defaultValue = "")
    val description: String = "",

    @SerialName("promptOverlay")
    @ColumnInfo(name = "prompt_overlay", defaultValue = "")
    val promptOverlay: String = "",

    @SerialName("createdDate")
    @ColumnInfo(name = "created_date", defaultValue = "0")
    val createdDate: Long = System.currentTimeMillis(),

    @SerialName("updatedDate")
    @ColumnInfo(name = "updated_date", defaultValue = "0")
    val updatedDate: Long = System.currentTimeMillis(),

    @SerialName("color")
    @ColumnInfo(defaultValue = "'#6366f1'")
    val color: String = "#6366f1",

    @SerialName("icon")
    @ColumnInfo(defaultValue = "'folder'")
    val icon: String = "folder",

    @SerialName("isActive")
    @ColumnInfo(name = "is_active", defaultValue = "1")
    val isActive: Boolean = true,

    @SerialName("messageCount")
    @ColumnInfo(name = "message_count", defaultValue = "0")
    val messageCount: Int = 0,

    @SerialName("documentCount")
    @ColumnInfo(name = "document_count", defaultValue = "0")
    val documentCount: Int = 0,

    @SerialName("lastMessagePreview")
    @ColumnInfo(name = "last_message_preview", defaultValue = "")
    val lastMessagePreview: String = "",

    @SerialName("lastMessageDate")
    @ColumnInfo(name = "last_message_date", defaultValue = "0")
    val lastMessageDate: Long = 0L
)

fun ProjectEntity.toProject() = Project(
    id = id,
    title = title,
    description = description,
    promptOverlay = promptOverlay,
    createdDate = createdDate,
    updatedDate = updatedDate,
    color = color,
    icon = icon,
    isActive = isActive,
    messageCount = messageCount,
    documentCount = documentCount,
    lastMessagePreview = lastMessagePreview,
    lastMessageDate = lastMessageDate
)

fun Project.toProjectEntity() = ProjectEntity(
    id = id,
    title = title,
    description = description,
    promptOverlay = promptOverlay,
    createdDate = createdDate,
    updatedDate = updatedDate,
    color = color,
    icon = icon,
    isActive = isActive,
    messageCount = messageCount,
    documentCount = documentCount,
    lastMessagePreview = lastMessagePreview,
    lastMessageDate = lastMessageDate
)