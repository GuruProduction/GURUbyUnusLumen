// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * A named set of device states, applied atomically. Issue #182.
 *
 * [statesJson] is a nested map: device id to capability name to value. Stored as
 * one blob for the same reason as the device state: nothing queries inside it,
 * and a scene is applied as a whole, never in pieces.
 *
 * [name] is unique because a person says "evening", not "scene 3", and two
 * scenes with one name is a bug the user experiences as the wrong lights coming
 * on rather than as a constraint violation.
 */
@Entity(
    tableName = "fabric_scenes",
    indices = [
        Index(value = ["name"], unique = true, name = "index_fabric_scenes_name"),
    ]
)
data class FabricSceneEntity(
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    /** JSON: device id -> capability -> value */
    @ColumnInfo(name = "states_json")
    val statesJson: String,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
