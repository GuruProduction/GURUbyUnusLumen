// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import android.content.Context
import com.unuslumen.app.database.dao.NetworkVisionDao
import com.unuslumen.app.database.entity.NetworkDeviceEntity
import com.unuslumen.app.database.entity.NetworkScanEntity
import com.unuslumen.app.database.entity.NetworkSightingEntity
import kotlinx.coroutines.flow.Flow
import org.koin.core.context.GlobalContext

/**
 * Resolving the network map's DAO when a caller only has a Context.
 *
 * WHY THIS IS NOT JUST `GlobalContext.get().get()`. The JVM honesty test constructs every domain
 * executor from a bare Context with no Koin container started, so a direct `GlobalContext.get()`
 * throws there and the test reports the executor as unbuildable. That test is right to insist on
 * constructibility, because a registration naming an executor nothing can build fails only when
 * somebody calls the tool.
 *
 * WHY IT RESOLVES THE DAO RATHER THAN BUILDING A DATABASE. The obvious-looking shortcut here is
 * `Room.databaseBuilder(...).build().networkVisionDao()`. That opens a SECOND connection to the
 * same file, and the builder in this file would carry no migrations, so the first launch after a
 * version upgrade would crash on a schema mismatch rather than read a map that is already there.
 * One connection, owned by Koin, is the only shape that cannot drift.
 *
 * THE FALLBACK IS HONEST, NOT A STUB. When Koin is absent or has no binding, the returned DAO
 * refuses every call with a sentence naming what is missing. A real app with a broken binding then
 * fails loudly and specifically, rather than answering "your network is empty" and sending a person
 * to look at their router instead of at the app.
 */
object KoinNetworkVisionDao {

    fun resolve(context: Context): NetworkVisionDao {
        // A started container with the binding is the real path, which is every production call:
        // Koin starts in GuruApplication.onCreate before any tool can run.
        val fromKoin = runCatching {
            GlobalContext.get().get<NetworkVisionDao>()
        }.getOrNull()
        if (fromKoin != null) return fromKoin

        return MissingNetworkVisionDao
    }
}

/**
 * The DAO used when no database binding exists.
 *
 * Every method refuses with the same sentence, which names the real fault: the network map's store
 * was never bound. An empty list here would read as a network with no devices on it, which is the
 * one answer a map must never give by accident.
 */
private object MissingNetworkVisionDao : NetworkVisionDao {

    private fun missing(): Nothing = throw IllegalStateException(
        "The network map has no database binding, so nothing seen on the network could be stored " +
            "or read. This is a fault in the app's wiring rather than a missing permission.",
    )

    // ------------------------- the current map -------------------------

    override suspend fun getActiveDevices(): List<NetworkDeviceEntity> = missing()
    override fun getActiveDevicesFlow(): Flow<List<NetworkDeviceEntity>> = missing()
    override suspend fun getDevice(id: String): NetworkDeviceEntity? = missing()
    override suspend fun getDeviceByMac(mac: String): NetworkDeviceEntity? = missing()
    override suspend fun getDeviceByAddress(address: String): NetworkDeviceEntity? = missing()
    override suspend fun getDevicesByClass(deviceClass: String): List<NetworkDeviceEntity> = missing()
    override suspend fun getDevicesByVendor(vendor: String): List<NetworkDeviceEntity> = missing()
    override suspend fun searchDevices(query: String): List<NetworkDeviceEntity> = missing()
    override suspend fun upsertDevice(device: NetworkDeviceEntity) = missing()
    override suspend fun upsertDevices(devices: List<NetworkDeviceEntity>) = missing()

    override suspend fun recordSighting(
        id: String,
        address: String,
        observedName: String?,
        vendor: String?,
        model: String?,
        deviceClass: String,
        openPortsJson: String,
        servicesJson: String,
        now: Long,
    ) = missing()

    override suspend fun renameDevice(id: String, name: String?, now: Long) = missing()
    override suspend fun placeDevice(id: String, room: String?, now: Long) = missing()
    override suspend fun retireDevice(id: String, now: Long) = missing()
    override suspend fun getUnseenSince(cutoff: Long): List<NetworkDeviceEntity> = missing()
    override suspend fun countActiveDevices(): Int = missing()
    override suspend fun countAllDevices(): Int = missing()

    // ------------------------- the history -------------------------

    override suspend fun insertSighting(sighting: NetworkSightingEntity) = missing()
    override suspend fun insertSightings(sightings: List<NetworkSightingEntity>) = missing()
    override suspend fun getSightingsFor(deviceId: String): List<NetworkSightingEntity> = missing()
    override suspend fun getLatestSighting(deviceId: String): NetworkSightingEntity? = missing()
    override suspend fun getSightingsForScan(scanId: String): List<NetworkSightingEntity> = missing()
    override suspend fun getAddressesFor(deviceId: String): List<String> = missing()
    override suspend fun countSightingsFor(deviceId: String): Int = missing()

    // ------------------------- the sweeps -------------------------

    override suspend fun insertScan(scan: NetworkScanEntity) = missing()

    override suspend fun finishScan(
        id: String,
        finishedAt: Long,
        probedAddresses: Int,
        aliveAddresses: Int,
        deviceCount: Int,
        problemsJson: String,
    ) = missing()

    override suspend fun getRecentScans(limit: Int): List<NetworkScanEntity> = missing()
    override suspend fun getScan(id: String): NetworkScanEntity? = missing()
    override suspend fun getLatestCompletedScan(): NetworkScanEntity? = missing()
    override suspend fun countScans(): Int = missing()

    // ------------------------- housekeeping -------------------------

    override suspend fun pruneSightingsBefore(cutoff: Long): Int = missing()
    override suspend fun pruneScansBefore(cutoff: Long): Int = missing()
}
