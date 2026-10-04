// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * MediaItemEntity — one row per media file saved into the on-device media
 * library. Every video / audio / image attached in Portal chat is ingested by
 * MediaIngestService into this table. JSON columns (transcript_json,
 * scenes_json) hold the document-store form the Timeline reads; their
 * plain-text twins (transcript_text, ocr_text) hold the FTS search body and
 * are derived at ingest time before the row is written.
 */
@Entity(
    tableName = "media_items",
    indices = [
        Index(value = ["sha256"], unique = true, name = "index_media_items_sha256"),
        Index(value = ["created_at"], name = "index_media_items_created"),
        Index(value = ["media_kind"], name = "index_media_items_kind")
    ]
)
data class MediaItemEntity(
    /** UUID string primary key, generated at ingest. */
    @PrimaryKey
    @ColumnInfo(name = "id")
    val id: String,

    /** The original file name the human attached. */
    @ColumnInfo(name = "source_filename")
    val sourceFilename: String,

    /** Where the cache copy lived at the moment of ingestion (for provenance audit). */
    @ColumnInfo(name = "cached_path")
    val cachedPath: String,

    /** The permanent library home of the file bytes plus sidecars. */
    @ColumnInfo(name = "stored_path")
    val storedPath: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    /** video / image / audio — the kind driving library grouping and pipeline shape. */
    @ColumnInfo(name = "media_kind")
    val mediaKind: String,

    /** Video/audio duration in seconds, 0.0 for images. */
    @ColumnInfo(name = "duration_seconds")
    val durationSeconds: Double,

    @ColumnInfo(name = "width")
    val width: Int,

    @ColumnInfo(name = "height")
    val height: Int,

    /** Video fps. 0.0 when unknown. */
    @ColumnInfo(name = "fps")
    val fps: Double,

    /** 1 when the file carries an audio track, 0 otherwise. */
    @ColumnInfo(name = "has_audio")
    val hasAudio: Boolean,

    /** JSON array of TranscriptChunk — document store for the timeline read. */
    @ColumnInfo(name = "transcript_json")
    val transcriptJson: String,

    /** JSON array of keyframe scenes with time ranges and per-frame OCR. */
    @ColumnInfo(name = "scenes_json")
    val scenesJson: String,

    /** Derived space-joined transcript words, written at ingest for media_items_fts. */
    @ColumnInfo(name = "transcript_text")
    val transcriptText: String,

    /** Derived space-joined OCR text of all keyframes, written at ingest for media_items_fts. */
    @ColumnInfo(name = "ocr_text")
    val ocrText: String,

    /** Library-grid poster thumbnail path. */
    @ColumnInfo(name = "poster_thumb_path")
    val posterThumbPath: String,

    /** INGESTED / FAILED / PROCESSING — honest state at every step. */
    @ColumnInfo(name = "ingest_status")
    val ingestStatus: String,

    /** Exact failure reason when ingest_status = FAILED, empty otherwise. */
    @ColumnInfo(name = "error_message")
    val errorMessage: String,

    /** Epoch millis of ingest time. */
    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    /** Source file size in bytes. */
    @ColumnInfo(name = "size_bytes")
    val sizeBytes: Long,

    /** SHA-256 hex of the file bytes — the idempotency key; re-ingest of identical bytes skips rebuild. */
    @ColumnInfo(name = "sha256")
    val sha256: String
)