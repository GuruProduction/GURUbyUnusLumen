// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "hive_mind_state")
@Serializable
data class HiveMindStateEntity(
    @SerialName("id")
    @PrimaryKey
    val id: Int = 1,
    @SerialName("lastProcessedConversationId")
    @ColumnInfo(name = "last_processed_conversation_id", defaultValue = "")
    val lastProcessedConversationId: String = "",
    @SerialName("lastProcessedTimestamp")
    @ColumnInfo(name = "last_processed_timestamp", defaultValue = "0")
    val lastProcessedTimestamp: Long = 0L,
    @SerialName("totalFactsExtracted")
    @ColumnInfo(name = "total_facts_extracted", defaultValue = "0")
    val totalFactsExtracted: Int = 0,
    @SerialName("totalThreadsDiscovered")
    @ColumnInfo(name = "total_threads_discovered", defaultValue = "0")
    val totalThreadsDiscovered: Int = 0,
    @SerialName("isProcessing")
    @ColumnInfo(name = "is_processing", defaultValue = "0")
    val isProcessing: Boolean = false,
    @SerialName("lastProcessingStart")
    @ColumnInfo(name = "last_processing_start", defaultValue = "0")
    val lastProcessingStart: Long = 0L,
    @SerialName("lastProcessingEnd")
    @ColumnInfo(name = "last_processing_end", defaultValue = "0")
    val lastProcessingEnd: Long = 0L
)
