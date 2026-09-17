package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.FloatListSerializer
import com.unuslumen.app.database.converters.IdSerializer
import com.unuslumen.app.database.converters.StringListSerializer
import com.unuslumen.app.domain.model.ProjectMessage
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "project_messages",
    foreignKeys = [
        ForeignKey(
            entity = ProjectEntity::class,
            parentColumns = ["id"],
            childColumns = ["project_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [
        Index(value = ["project_id"]),
        Index(value = ["timestamp"])
    ]
)
@Serializable
data class ProjectMessageEntity(
    @SerialName("id")
    @PrimaryKey
    @Serializable(IdSerializer::class)
    val id: String,

    @SerialName("projectId")
    @ColumnInfo(name = "project_id")
    val projectId: String,

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
    @Serializable(FloatListSerializer::class)
    val embedding: List<Float> = emptyList(),

    @SerialName("toolCalls")
    @ColumnInfo(name = "tool_calls", defaultValue = "")
    val toolCalls: String = "",

    @SerialName("toolResults")
    @ColumnInfo(name = "tool_results", defaultValue = "")
    val toolResults: String = ""
)

fun ProjectMessageEntity.toProjectMessage() = ProjectMessage(
    id = id,
    projectId = projectId,
    role = role,
    content = content,
    timestamp = timestamp,
    embedding = embedding,
    toolCalls = toolCalls,
    toolResults = toolResults
)

fun ProjectMessage.toProjectMessageEntity() = ProjectMessageEntity(
    id = id,
    projectId = projectId,
    role = role,
    content = content,
    timestamp = timestamp,
    embedding = embedding,
    toolCalls = toolCalls,
    toolResults = toolResults
)