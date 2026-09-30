package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * MediaZoomLogEntity — one row per zoom Guru performs against a media item.
 * Timestamps and extracted frame paths are logged so any past zoom is
 * recallable; deleting the item deletes all its zoom rows with it.
 */
@Entity(
    tableName = "media_zoom_log",
    indices = [
        Index(value = ["media_id"], name = "index_media_zoom_log_media_id"),
        Index(value = ["created_at"], name = "index_media_zoom_log_created_at")
    ]
)
data class MediaZoomLogEntity(
    /** Auto-increment rowid primary key, SQLite managed. */
    @PrimaryKey(autoGenerate = true)
    @ColumnInfo(name = "id")
    val id: Long = 0L,

    /** The media item zoomed. */
    @ColumnInfo(name = "media_id")
    val mediaId: String,

    /** Second offset into the media where the frame lives. */
    @ColumnInfo(name = "timestamp_sec")
    val timestampSec: Double,

    /** Disk path to the extracted frame file (already inside the library). */
    @ColumnInfo(name = "extracted_path")
    val extractedPath: String,

    /** Epoch millis of the zoom time. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long
)