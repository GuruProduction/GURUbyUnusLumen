package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.FloatListSerializer
import com.unuslumen.app.database.converters.IdSerializer
import com.unuslumen.app.domain.model.DocumentType
import com.unuslumen.app.domain.model.ProjectDocument
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "project_documents",
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
        Index(value = ["title"])
    ]
)
@Serializable
data class ProjectDocumentEntity(
    @SerialName("id")
    @PrimaryKey
    @Serializable(IdSerializer::class)
    val id: String,

    @SerialName("projectId")
    @ColumnInfo(name = "project_id")
    val projectId: String,

    @SerialName("title")
    val title: String,

    @SerialName("content")
    @ColumnInfo(defaultValue = "")
    val content: String,

    @SerialName("type")
    @ColumnInfo(defaultValue = "'TEXT'")
    val type: String = "TEXT",

    @SerialName("createdDate")
    @ColumnInfo(name = "created_date", defaultValue = "0")
    val createdDate: Long = System.currentTimeMillis(),

    @SerialName("updatedDate")
    @ColumnInfo(name = "updated_date", defaultValue = "0")
    val updatedDate: Long = System.currentTimeMillis(),

    @SerialName("embedding")
    @ColumnInfo(defaultValue = "")
    @Serializable(FloatListSerializer::class)
    val embedding: List<Float> = emptyList(),

    @SerialName("sourceUri")
    @ColumnInfo(name = "source_uri", defaultValue = "")
    val sourceUri: String = "",

    @SerialName("size")
    @ColumnInfo(defaultValue = "0")
    val size: Long = 0L
)

fun ProjectDocumentEntity.toProjectDocument() = ProjectDocument(
    id = id,
    projectId = projectId,
    title = title,
    content = content,
    type = DocumentType.valueOf(type),
    createdDate = createdDate,
    updatedDate = updatedDate,
    embedding = embedding,
    sourceUri = sourceUri,
    size = size
)

fun ProjectDocument.toProjectDocumentEntity() = ProjectDocumentEntity(
    id = id,
    projectId = projectId,
    title = title,
    content = content,
    type = type.name,
    createdDate = createdDate,
    updatedDate = updatedDate,
    embedding = embedding,
    sourceUri = sourceUri,
    size = size
)