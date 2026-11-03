// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.unuslumen.app.database.entity.NetworkDeviceEntity
import com.unuslumen.app.database.entity.NetworkScanEntity
import com.unuslumen.app.database.entity.NetworkSightingEntity
import kotlinx.coroutines.flow.Flow

/**
 * The network map's store: what GURU has seen, when, and what it said each time.
 *
 * WHY THIS IS ITS OWN DAO RATHER THAN METHODS ON [FabricDao]. The fabric is devices GURU can drive
 * and this is devices GURU has seen, which is a strictly wider set with a different lifecycle. The
 * two must never share a write path, because a discovery sweep must never be able to remove a
 * device the owner has set up, and a fabric sync must never be able to pretend it saw something on
 * the network.
 *
 * THE THREE-WAY SPLIT, and what each is for:
 *
 *  - `network_devices` is the current answer to "what is on this network".
 *  - `network_sightings` is the history, and answers "when did this appear and what changed".
 *  - `network_scans` is the run, and answers "why did GURU look, and did it finish".
 *
 * A question about the present reads one table. A question about change reads two. Nothing here
 * ever reads all three at once, which is why the map stays cheap as the history grows.
 */
@Dao
interface NetworkVisionDao {

    // ------------------------- the current map -------------------------

    /** Every device ever seen, newest sighting first. */
    @Query("SELECT * FROM network_devices WHERE retired = 0 ORDER BY last_seen DESC")
    suspend fun getActiveDevices(): List<NetworkDeviceEntity>

    /** Every device ever seen, as a flow, so a screen updates as a sweep lands. */
    @Query("SELECT * FROM network_devices WHERE retired = 0 ORDER BY last_seen DESC")
    fun getActiveDevicesFlow(): Flow<List<NetworkDeviceEntity>>

    /** One device by its identity. */
    @Query("SELECT * FROM network_devices WHERE id = :id")
    suspend fun getDevice(id: String): NetworkDeviceEntity?

    /** The device with this MAC, which is the strong lookup a sweep uses to avoid a duplicate. */
    @Query("SELECT * FROM network_devices WHERE mac = :mac LIMIT 1")
    suspend fun getDeviceByMac(mac: String): NetworkDeviceEntity?

    /** The device last seen at this address, which is the weak lookup and may be wrong. */
    @Query("SELECT * FROM network_devices WHERE last_address = :address AND retired = 0 LIMIT 1")
    suspend fun getDeviceByAddress(address: String): NetworkDeviceEntity?

    /** Everything of one class, such as every camera, so a question can be scoped. */
    @Query("SELECT * FROM network_devices WHERE device_class = :deviceClass AND retired = 0 ORDER BY last_seen DESC")
    suspend fun getDevicesByClass(deviceClass: String): List<NetworkDeviceEntity>

    /** Everything made by one vendor. */
    @Query("SELECT * FROM network_devices WHERE vendor = :vendor AND retired = 0 ORDER BY last_seen DESC")
    suspend fun getDevicesByVendor(vendor: String): List<NetworkDeviceEntity>

    /**
     * Text search across the facts a person would search by.
     *
     * A map is useless if finding one device in it means reading all of them, and the owner's own
     * words for a device are what they will search with, which is why the owner's name comes first
     * in the match rather than being an afterthought.
     */
    @Query(
        """
        SELECT * FROM network_devices
        WHERE retired = 0 AND (
            name LIKE '%' || :query || '%' OR
            observed_name LIKE '%' || :query || '%' OR
            vendor LIKE '%' || :query || '%' OR
            model LIKE '%' || :query || '%' OR
            last_address LIKE '%' || :query || '%' OR
            mac LIKE '%' || :query || '%'
        )
        ORDER BY last_seen DESC
        """
    )
    suspend fun searchDevices(query: String): List<NetworkDeviceEntity>

    /** Add or replace a device. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevice(device: NetworkDeviceEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDevices(devices: List<NetworkDeviceEntity>)

    /**
     * What a sweep learned about a device, leaving the owner's decisions alone.
     *
     * A SEPARATE QUERY FROM THE UPSERT FOR THE SAME REASON [FabricDao.placeDevice] is separate:
     * `name` and `room` are the owner's, and a sweep that overwrote them would rename a device
     * every time it was seen.
     */
    @Query(
        """
        UPDATE network_devices SET
            last_address = :address,
            observed_name = :observedName,
            vendor = :vendor,
            model = :model,
            device_class = :deviceClass,
            open_ports_json = :openPortsJson,
            services_json = :servicesJson,
            sighting_count = sighting_count + 1,
            last_seen = :now,
            updated_at = :now
        WHERE id = :id
        """
    )
    suspend fun recordSighting(
        id: String,
        address: String,
        observedName: String?,
        vendor: String?,
        model: String?,
        deviceClass: String,
        openPortsJson: String,
        servicesJson: String,
        now: Long,
    )

    /** The owner naming a device, which no sweep may undo. */
    @Query("UPDATE network_devices SET name = :name, updated_at = :now WHERE id = :id")
    suspend fun renameDevice(id: String, name: String?, now: Long)

    /** The owner placing a device in a room. */
    @Query("UPDATE network_devices SET room = :room, updated_at = :now WHERE id = :id")
    suspend fun placeDevice(id: String, room: String?, now: Long)

    /**
     * Retire a device rather than deleting it.
     *
     * A DELETION WOULD DESTROY THE HISTORY THAT MAKES THE MAP WORTH HAVING. A device that leaves the
     * network is exactly the device whose past matters, so it is marked retired and its sightings
     * are kept. Only an explicit purge removes rows.
     */
    @Query("UPDATE network_devices SET retired = 1, updated_at = :now WHERE id = :id")
    suspend fun retireDevice(id: String, now: Long)

    /** Everything unseen for longer than [cutoff], which is how devices are retired in bulk. */
    @Query("SELECT * FROM network_devices WHERE retired = 0 AND last_seen < :cutoff")
    suspend fun getUnseenSince(cutoff: Long): List<NetworkDeviceEntity>

    @Query("SELECT COUNT(*) FROM network_devices WHERE retired = 0")
    suspend fun countActiveDevices(): Int

    @Query("SELECT COUNT(*) FROM network_devices")
    suspend fun countAllDevices(): Int

    // ------------------------- the history -------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSighting(sighting: NetworkSightingEntity)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSightings(sightings: List<NetworkSightingEntity>)

    /** Every time one device was seen, newest first, which is its history. */
    @Query("SELECT * FROM network_sightings WHERE device_id = :deviceId ORDER BY seen_at DESC")
    suspend fun getSightingsFor(deviceId: String): List<NetworkSightingEntity>

    /** The most recent sighting of one device, which is its current truth. */
    @Query("SELECT * FROM network_sightings WHERE device_id = :deviceId ORDER BY seen_at DESC LIMIT 1")
    suspend fun getLatestSighting(deviceId: String): NetworkSightingEntity?

    /** Everything one sweep saw. */
    @Query("SELECT * FROM network_sightings WHERE scan_id = :scanId ORDER BY address ASC")
    suspend fun getSightingsForScan(scanId: String): List<NetworkSightingEntity>

    /**
     * Whether a device has moved address between two sightings.
     *
     * A map that quietly followed an address change would hide the one event a person most wants to
     * know about: a device they know appearing somewhere new. This is the query that surfaces it.
     */
    @Query(
        """
        SELECT DISTINCT address FROM network_sightings
        WHERE device_id = :deviceId ORDER BY seen_at DESC
        """
    )
    suspend fun getAddressesFor(deviceId: String): List<String>

    @Query("SELECT COUNT(*) FROM network_sightings WHERE device_id = :deviceId")
    suspend fun countSightingsFor(deviceId: String): Int

    // ------------------------- the sweeps -------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertScan(scan: NetworkScanEntity)

    /** Mark a sweep finished, with its totals. A sweep still at zero did not complete. */
    @Query(
        """
        UPDATE network_scans SET
            finished_at = :finishedAt,
            probed_addresses = :probedAddresses,
            alive_addresses = :aliveAddresses,
            device_count = :deviceCount,
            problems_json = :problemsJson
        WHERE id = :id
        """
    )
    suspend fun finishScan(
        id: String,
        finishedAt: Long,
        probedAddresses: Int,
        aliveAddresses: Int,
        deviceCount: Int,
        problemsJson: String,
    )

    /** The sweeps that ran, newest first. */
    @Query("SELECT * FROM network_scans ORDER BY started_at DESC LIMIT :limit")
    suspend fun getRecentScans(limit: Int = 20): List<NetworkScanEntity>

    @Query("SELECT * FROM network_scans WHERE id = :id")
    suspend fun getScan(id: String): NetworkScanEntity?

    /** The newest completed sweep, which is what the map is currently built from. */
    @Query("SELECT * FROM network_scans WHERE finished_at > 0 ORDER BY started_at DESC LIMIT 1")
    suspend fun getLatestCompletedScan(): NetworkScanEntity?

    @Query("SELECT COUNT(*) FROM network_scans")
    suspend fun countScans(): Int

    // ------------------------- housekeeping -------------------------

    /**
     * Drop sightings older than a cutoff.
     *
     * HISTORY IS KEPT BUT NOT FOREVER. A sweep a day for a year is thousands of rows per device,
     * and the useful questions are about the last few weeks. The device row and its first and last
     * sighting always survive, so the shape of the map is never lost.
     */
    @Query("DELETE FROM network_sightings WHERE seen_at < :cutoff")
    suspend fun pruneSightingsBefore(cutoff: Long): Int

    @Query("DELETE FROM network_scans WHERE started_at < :cutoff")
    suspend fun pruneScansBefore(cutoff: Long): Int
}
