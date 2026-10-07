// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One tile's position in the unified lobby order.
 *
 * The lobby is a home screen: static doors and GURU-grown doors share one
 * persisted order and all of them drag. [id] is the tile key — "module:<id>"
 * for grown rooms, "static:<key>" for the built-in doors (notes, calendar…).
 * The numen rearranges by voice through the same store the user drags with
 * a finger; one order, two ways to write it.
 */
@Entity(
    tableName = "guru_tile_order",
    indices = [
        Index(value = ["sort_order"], name = "index_guru_tile_order_sort_order")
    ]
)
data class GuruTileOrderEntity(
    /** Tile key: "module:<id>" or "static:<key>" */
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "sort_order")
    val sortOrder: Int,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long
)