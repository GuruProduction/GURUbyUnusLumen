package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "memory_edges",
    primaryKeys = ["source_id", "target_id", "edge_type"],
    indices = [
        Index("source_id"),
        Index("target_id"),
        Index("edge_type")
    ]
)
@Serializable
data class MemoryEdgeEntity(
    @SerialName("source_id")
    @ColumnInfo(name = "source_id")
    val sourceId: String,
    @SerialName("target_id")
    @ColumnInfo(name = "target_id")
    val targetId: String,
    @SerialName("edge_type")
    @ColumnInfo(name = "edge_type")
    val edgeType: String, // SEMANTIC, TEMPORAL, CAUSAL, ENTITY
    @SerialName("weight")
    @ColumnInfo(name = "weight", defaultValue = "1.0")
    val weight: Float = 1.0f,
    @SerialName("created_at")
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0L,
    @SerialName("metadata")
    @ColumnInfo(name = "metadata", defaultValue = "")
    val metadata: String = ""
)
