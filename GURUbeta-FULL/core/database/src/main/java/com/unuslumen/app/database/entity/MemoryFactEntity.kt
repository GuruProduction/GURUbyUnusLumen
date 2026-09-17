package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.IdSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "memory_facts")
@Serializable
data class MemoryFactEntity(
    @SerialName("id")
    @PrimaryKey
    @Serializable(IdSerializer::class)
    val id: String,
    @SerialName("category")
    @ColumnInfo(defaultValue = "")
    val category: String,
    @SerialName("fact")
    @ColumnInfo(defaultValue = "")
    val fact: String,
    @SerialName("embedding")
    @ColumnInfo(defaultValue = "")
    val embedding: List<Float> = emptyList(),
    @SerialName("confidence")
    @ColumnInfo(defaultValue = "1.0")
    val confidence: Float = 1.0f,
    @SerialName("sourceConversationIds")
    @ColumnInfo(name = "source_conversation_ids", defaultValue = "")
    val sourceConversationIds: String = "",
    @SerialName("extractedDate")
    @ColumnInfo(name = "extracted_date", defaultValue = "0")
    val extractedDate: Long,
    @SerialName("lastRecalledDate")
    @ColumnInfo(name = "last_recalled_date", defaultValue = "0")
    val lastRecalledDate: Long = 0L,

    // --- Brain Plan Phase 1: New columns ---

    // Biological layers: BUFFER (recent, may be pruned), EPISODIC (event-based), SEMANTIC (consolidated knowledge)
    @SerialName("layer")
    @ColumnInfo(name = "layer", defaultValue = "BUFFER")
    val layer: String = "BUFFER",

    // Decay system: strength decays over time, access_count tracks recall frequency
    @SerialName("strength")
    @ColumnInfo(name = "strength", defaultValue = "1.0")
    val strength: Float = 1.0f,
    @SerialName("accessCount")
    @ColumnInfo(name = "access_count", defaultValue = "0")
    val accessCount: Int = 0,

    // Episodic context: where did this fact come from, what triggered it
    @SerialName("source")
    @ColumnInfo(name = "source", defaultValue = "")
    val source: String = "",
    @SerialName("trigger")
    @ColumnInfo(name = "trigger", defaultValue = "")
    val trigger: String = "",
    @SerialName("beforeContext")
    @ColumnInfo(name = "before_context", defaultValue = "")
    val beforeContext: String = "",
    @SerialName("afterContext")
    @ColumnInfo(name = "after_context", defaultValue = "")
    val afterContext: String = "",
    @SerialName("emotionalValence")
    @ColumnInfo(name = "emotional_valence", defaultValue = "0.0")
    val emotionalValence: Float = 0.0f,

    // Curation hierarchy
    @SerialName("domain")
    @ColumnInfo(name = "domain", defaultValue = "")
    val domain: String = "",
    @SerialName("topic")
    @ColumnInfo(name = "topic", defaultValue = "")
    val topic: String = "",
    @SerialName("subtopic")
    @ColumnInfo(name = "subtopic", defaultValue = "")
    val subtopic: String = "",

    // Binary signature for fast similarity search (random indexing, 256-bit)
    @SerialName("signature")
    @ColumnInfo(name = "signature", defaultValue = "")
    val signature: String = ""
)
