package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.FloatListSerializer
import com.unuslumen.app.database.converters.StringListSerializer
import com.unuslumen.app.domain.model.ProjectFact
import kotlinx.serialization.Serializable

@Entity(
    tableName = "project_facts",
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
        Index(value = ["category"])
    ]
)
@Serializable
data class ProjectFactEntity(
    @PrimaryKey
    val id: String,

    @ColumnInfo(name = "project_id")
    val projectId: String,

    @ColumnInfo(defaultValue = "")
    val category: String,

    @ColumnInfo(defaultValue = "")
    val fact: String,

    @ColumnInfo(defaultValue = "")
    @Serializable(FloatListSerializer::class)
    val embedding: List<Float> = emptyList(),

    @ColumnInfo(defaultValue = "1.0")
    val confidence: Float = 1.0f,

    @ColumnInfo(name = "source_message_ids", defaultValue = "")
    @Serializable(StringListSerializer::class)
    val sourceMessageIds: List<String> = emptyList(),

    @ColumnInfo(name = "extracted_date", defaultValue = "0")
    val extractedDate: Long = System.currentTimeMillis(),

    @ColumnInfo(name = "last_recalled_date", defaultValue = "0")
    val lastRecalledDate: Long = 0L
)

fun ProjectFactEntity.toProjectFact() = ProjectFact(
    id = id,
    projectId = projectId,
    category = category,
    fact = fact,
    embedding = embedding,
    confidence = confidence,
    sourceMessageIds = sourceMessageIds,
    extractedDate = extractedDate,
    lastRecalledDate = lastRecalledDate
)

fun ProjectFact.toProjectFactEntity() = ProjectFactEntity(
    id = id,
    projectId = projectId,
    category = category,
    fact = fact,
    embedding = embedding,
    confidence = confidence,
    sourceMessageIds = sourceMessageIds,
    extractedDate = extractedDate,
    lastRecalledDate = lastRecalledDate
)