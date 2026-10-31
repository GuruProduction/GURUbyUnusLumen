// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.FabricDeviceEntity
import com.unuslumen.app.database.entity.FabricSceneEntity
import kotlinx.coroutines.flow.Flow

/**
 * The smart home fabric's store. Issue #182.
 *
 * Separate DAO rather than methods on an existing one, because the fabric is a
 * domain with its own lifecycle: devices are upserted wholesale on every
 * discovery pass and deleted when a transport stops seeing them, and no other
 * feature should ever be able to write those rows.
 */
@Dao
interface FabricDao {

    // ------------------------- devices -------------------------

    @Query("SELECT * FROM fabric_devices ORDER BY name ASC")
    suspend fun getAllDevices(): List<FabricDeviceEntity>

    @Query("SELECT * FROM fabric_devices ORDER BY name ASC")
    fun getAllDevicesFlow(): Flow<List<FabricDeviceEntity>>

    @Query("SELECT * FROM fabric_devices WHERE id = :id")
    suspend fun getDevice(id: String): FabricDeviceEntity?

    @Query("SELECT * FROM fabric_devices WHERE transport = :transport ORDER BY name ASC")
    suspend fun getDevicesByTransport(transport: String): List<FabricDeviceEntity>

    @Query("SELECT * FROM fabric_devices WHERE room = :room ORDER BY name ASC")
    suspend fun getDevicesInRoom(room: String): List<FabricDeviceEntity>

    /** Distinct rooms, so the fabric can offer the ones that actually exist. */
    @Query("SELECT DISTINCT room FROM fabric_devices WHERE room IS NOT NULL ORDER BY room ASC")
    suspend fun getRooms(): List<String>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: FabricDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevices(devices: List<FabricDeviceEntity>)

    /**
     * Rename and re-room.
     *
     * A separate query from upsert because placing a device is a user decision
     * and must not be overwritten by the next discovery pass, which knows
     * nothing about names or rooms.
     */
    @Query("UPDATE fabric_devices SET name = :name, room = :room, updated_at = :now WHERE id = :id")
    suspend fun placeDevice(id: String, name: String, room: String?, now: Long)

    @Query("UPDATE fabric_devices SET state_json = :stateJson, available = :available, last_seen = :now, updated_at = :now WHERE id = :id")
    suspend fun updateState(id: String, stateJson: String, available: Boolean, now: Long)

    @Query("DELETE FROM fabric_devices WHERE id = :id")
    suspend fun deleteDevice(id: String)

    @Query("DELETE FROM fabric_devices")
    suspend fun deleteAllDevices()

    @Query("SELECT COUNT(*) FROM fabric_devices")
    suspend fun countDevices(): Int

    // ------------------------- scenes -------------------------

    @Query("SELECT * FROM fabric_scenes ORDER BY name ASC")
    suspend fun getAllScenes(): List<FabricSceneEntity>

    @Query("SELECT * FROM fabric_scenes ORDER BY name ASC")
    fun getAllScenesFlow(): Flow<List<FabricSceneEntity>>

    @Query("SELECT * FROM fabric_scenes WHERE name = :name")
    suspend fun getSceneByName(name: String): FabricSceneEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertScene(scene: FabricSceneEntity)

    @Query("DELETE FROM fabric_scenes WHERE name = :name")
    suspend fun deleteScene(name: String)
}
