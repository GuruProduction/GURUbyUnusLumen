// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.IdSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "conversation_threads")
@Serializable
data class ConversationThreadEntity(
    @SerialName("id")
    @PrimaryKey
    @Serializable(IdSerializer::class)
    val id: String,
    @SerialName("title")
    @ColumnInfo(defaultValue = "")
    val title: String,
    @SerialName("summary")
    @ColumnInfo(defaultValue = "")
    val summary: String,
    @SerialName("conversationIds")
    @ColumnInfo(name = "conversation_ids", defaultValue = "")
    val conversationIds: String = "",
    @SerialName("createdDate")
    @ColumnInfo(name = "created_date", defaultValue = "0")
    val createdDate: Long
)
