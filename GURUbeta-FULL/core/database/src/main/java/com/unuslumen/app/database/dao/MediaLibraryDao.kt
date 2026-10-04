// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.SkipQueryVerification
import androidx.room.Upsert
import com.unuslumen.app.database.entity.MediaItemEntity

/**
 * MediaLibraryDao — every query on the persistent media library.
 *
 * FTS search rides media_items_fts over the derived real columns
 * transcript_text / ocr_text (schema declared in Migration23To24 mirroring
 * the tool_results_fts external-content pattern). BM25 ranks matches.
 */
@Dao
interface MediaLibraryDao {

    @Upsert
    suspend fun insert(entity: MediaItemEntity)

    @Query("SELECT * FROM media_items WHERE id = :id")
    suspend fun getById(id: String): MediaItemEntity?

    @Query("SELECT * FROM media_items WHERE sha256 = :hash LIMIT 1")
    suspend fun getBySha256(hash: String): MediaItemEntity?

    @Query(
        "SELECT * FROM media_items ORDER BY created_at DESC LIMIT :limit"
    )
    suspend fun getRecent(limit: Int): List<MediaItemEntity>

    @Query("SELECT COUNT(*) FROM media_items")
    suspend fun count(): Int


    @SkipQueryVerification
    @Query(
        """
        SELECT m.* FROM media_items m
        JOIN media_items_fts f ON m.rowid = f.rowid
        WHERE media_items_fts MATCH :query
        ORDER BY bm25(media_items_fts)
        LIMIT :limit
        """
    )
    suspend fun searchByFts(query: String, limit: Int): List<MediaItemEntity>

    @Query(
        "SELECT * FROM media_items WHERE transcript_text LIKE '%' || :query || '%' " +
        "OR ocr_text LIKE '%' || :query || '%' OR source_filename LIKE '%' || :query || '%' " +
        "ORDER BY created_at DESC LIMIT :limit"
    )
    suspend fun searchByLike(query: String, limit: Int): List<MediaItemEntity>

    @Query("UPDATE media_items SET ocr_text = :text WHERE id = :mediaId")
    suspend fun updateOcr(mediaId: String, text: String)

    @Query("DELETE FROM media_items WHERE id = :id")
    suspend fun deleteById(id: String): Int
}