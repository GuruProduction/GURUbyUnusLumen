// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.GuruTileOrderEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GuruTileOrderDao {

    /** Full order snapshot, one row per known tile key. */
    @Query("SELECT * FROM guru_tile_order ORDER BY sort_order ASC")
    suspend fun getAllOrders(): List<GuruTileOrderEntity>

    @Query("SELECT * FROM guru_tile_order ORDER BY sort_order ASC")
    fun getAllOrdersFlow(): Flow<List<GuruTileOrderEntity>>

    /** Bulk write — a reorder (drag-drop or numen voice) persists the whole list at once. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun saveAll(orders: List<GuruTileOrderEntity>)

    @Query("SELECT sort_order FROM guru_tile_order WHERE id = :tileId")
    suspend fun getSortOrder(tileId: String): Int?

    @Query("DELETE FROM guru_tile_order WHERE id = :tileId")
    suspend fun deleteOrder(tileId: String)

    /** Max current position +1 — where new tiles join before the user drags. */
    @Query("SELECT COALESCE(MAX(sort_order), -1) + 1 FROM guru_tile_order")
    suspend fun getNextSortOrder(): Int
}