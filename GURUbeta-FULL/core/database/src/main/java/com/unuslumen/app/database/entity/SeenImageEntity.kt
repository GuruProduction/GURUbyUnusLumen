package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * Tracks every media file the engine has actually SEEN (received as an image
 * block in a model request). Lets the engine reference earlier images by path
 * in later turns — "the screenshot I showed you earlier" resolves by path.
 */
@Entity(
    tableName = "seen_images",
    indices = [
        Index("timestamp"),
        Index("conversation_id")
    ]
)
data class SeenImageEntity(
    /** Absolute file path of the media on device. Natural key — one row per file. */
    @PrimaryKey
    @ColumnInfo(name = "path")
    val path: String,

    /** MIME type, e.g. image/jpeg, image/png, video/mp4. */
    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    /** When the engine saw it (epoch millis). */
    @ColumnInfo(name = "timestamp")
    val timestamp: Long,

    /** Conversation in which it was captured. */
    @ColumnInfo(name = "conversation_id")
    val conversationId: String,

    /** What produced it — tool name like "takeScreenshot" or "user" for user-attached paths. */
    @ColumnInfo(name = "source")
    val source: String
)