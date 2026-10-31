// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One smart home device in the unified fabric. Issue #182.
 *
 * WHY THE STATE AND CAPABILITIES ARE JSON. A device's capability set is a set of
 * enum names and its state is a map keyed by capability. Modelling that as
 * separate tables would mean a join per device per read, and the fabric reads
 * every device on every tool call. Column-per-capability would mean a schema
 * migration every time a protocol reports something new, which is the opposite
 * of the extensibility this issue is about.
 *
 * So the row is the identity, the placement and the blob: name and room are
 * queryable because a user filters by them, and everything protocol-shaped is
 * one JSON column that no SQL ever inspects.
 *
 * [transport] and [device_class] are stored as their enum names rather than as
 * ordinals, because a reordered enum would silently renumber every stored device
 * and nothing would fail loudly.
 */
@Entity(
    tableName = "fabric_devices",
    indices = [
        Index(value = ["room"], name = "index_fabric_devices_room"),
        Index(value = ["transport"], name = "index_fabric_devices_transport"),
        Index(value = ["device_class"], name = "index_fabric_devices_class"),
        Index(value = ["available"], name = "index_fabric_devices_available"),
    ]
)
data class FabricDeviceEntity(
    /** Stable across restarts: transport-native id, namespaced by transport. */
    @PrimaryKey
    val id: String,
    @ColumnInfo(name = "name")
    val name: String,
    @ColumnInfo(name = "room")
    val room: String?,
    @ColumnInfo(name = "transport")
    val transport: String,
    @ColumnInfo(name = "device_class")
    val deviceClass: String,
    /** JSON array of Capability names. */
    @ColumnInfo(name = "capabilities_json")
    val capabilitiesJson: String,
    /** JSON object of Capability name to value. */
    @ColumnInfo(name = "state_json")
    val stateJson: String,
    /** JSON object of unmapped protocol data, kept rather than dropped. */
    @ColumnInfo(name = "raw_json")
    val rawJson: String = "{}",
    @ColumnInfo(name = "available")
    val available: Boolean,
    @ColumnInfo(name = "last_seen")
    val lastSeen: Long,
    @ColumnInfo(name = "created_at")
    val createdAt: Long,
    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
)
