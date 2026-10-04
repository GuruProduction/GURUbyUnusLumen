// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.unuslumen.app.database.entity.MediaItemEntity
import com.unuslumen.app.database.entity.MediaZoomLogEntity

/**
 * MediaZoomLogDao — records every frame zoom Guru (or the human via detail
 * view) pulls from a media item, so zooms are themselves recallable.
 *
 * Also owns the transactional full delete of a media item with all its zoom
 * log rows: a hard delete leaves zero ghost rows.
 */
@Dao
interface MediaZoomLogDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entry: MediaZoomLogEntity)

    @Query("SELECT * FROM media_zoom_log WHERE media_id = :mediaId ORDER BY created_at DESC")
    suspend fun getByMediaId(mediaId: String): List<MediaZoomLogEntity>

    /** Exact real rows count for a media item's zoom log — no ORM guesses. */
    @Query("SELECT COUNT(*) FROM media_zoom_log WHERE media_id = :mediaId")
    suspend fun countByMediaId(mediaId: Int): Int

    @Delete
    suspend fun deleteZoomeLogEntryByEnt(entity: List<MediaZoomLogEntity>)

    @Transaction
    suspend fun delete(mediaId: String) {
        deleteByMediaId(mediaId)
    }

    @Query("DELETE FROM media_zoom_log WHERE media_id = :mediaId")
    suspend fun deleteByMediaId(mediaId: String): Int
}