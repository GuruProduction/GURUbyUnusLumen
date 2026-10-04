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
