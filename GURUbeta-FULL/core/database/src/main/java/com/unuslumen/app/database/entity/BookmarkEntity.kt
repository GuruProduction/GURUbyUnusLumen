package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.unuslumen.app.database.converters.IdSerializer
import com.unuslumen.app.domain.model.Bookmark
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Entity(tableName = "bookmarks")
@Serializable
data class BookmarkEntity(
    @SerialName("url")
    val url: String,
    @SerialName("title")
    val title: String = "",
    @SerialName("description")
    val description: String = "",
    @SerialName("createdDate")
    @ColumnInfo(name = "created_date")
    val createdDate: Long = 0L,
    @SerialName("updatedDate")
    @ColumnInfo(name = "updated_date")
    val updatedDate: Long = 0L,
    @SerialName("id")
    @PrimaryKey
    @Serializable(IdSerializer::class)
    val id: String
)

fun BookmarkEntity.toBookmark() = Bookmark(
    url = url,
    title = title,
    description = description,
    createdDate = createdDate,
    updatedDate = updatedDate,
    id = id
)

fun Bookmark.toBookmarkEntity() = BookmarkEntity(
    url = url,
    title = title,
    description = description,
    createdDate = createdDate,
    updatedDate = updatedDate,
    id = id
)