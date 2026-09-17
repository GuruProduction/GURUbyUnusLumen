package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(
    tableName = "memory_cross_references",
    primaryKeys = ["fact_id_a", "fact_id_b"],
    indices = [
        Index("fact_id_a"),
        Index("fact_id_b"),
        Index("ref_type")
    ]
)
@Serializable
data class MemoryCrossReferenceEntity(
    @SerialName("fact_id_a")
    @ColumnInfo(name = "fact_id_a")
    val factIdA: String,
    @SerialName("fact_id_b")
    @ColumnInfo(name = "fact_id_b")
    val factIdB: String,
    @SerialName("ref_type")
    @ColumnInfo(name = "ref_type", defaultValue = "RELATED")
    val refType: String = "RELATED", // RELATED, CONTRADICTS, SUPERSEDES, DUPLICATE
    @SerialName("created_at")
    @ColumnInfo(name = "created_at", defaultValue = "0")
    val createdAt: Long = 0L
)
