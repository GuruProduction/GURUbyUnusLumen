// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import com.unuslumen.app.database.dao.NetworkVisionDao
import com.unuslumen.app.database.entity.NetworkDeviceEntity
import com.unuslumen.app.database.entity.NetworkScanEntity
import com.unuslumen.app.database.entity.NetworkSightingEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The map store's decisions, proved without a database or a device.
 *
 * WHY THIS EXISTS. Two of the worst faults in the first version of this feature were in this class
 * and both would have been invisible on the device until he looked for something that was not there:
 *
 *  1. Every sweep retired every device it did not see. Joining one other wifi therefore retired his
 *     whole house in a single pass, because none of his devices answer on a café's network.
 *  2. Identity fell back to the device's vendor, so a Sony television and a Sony speaker became one
 *     row and the second overwrote the first.
 *
 * Both are pure decisions over a list, so both are provable here in milliseconds with a fake DAO.
 * The fake records what it was told rather than pretending to be SQLite, which is what makes the
 * assertions about the DECISIONS rather than about the database.
 */
class NetworkMapStoreTest {

    // ------------------------------------------------------------------
    // Fakes
    // ------------------------------------------------------------------

    /** A DAO that keeps rows in maps, so the store's own logic is what is under test. */
    private class FakeDao : NetworkVisionDao {

        val devices = mutableMapOf<String, NetworkDeviceEntity>()
        val sightings = mutableListOf<NetworkSightingEntity>()
        val scans = mutableListOf<NetworkScanEntity>()
        val retired = mutableSetOf<String>()

        override suspend fun getActiveDevices(): List<NetworkDeviceEntity> =
            devices.values.filter { it.id !in retired && !it.retired }

        override fun getActiveDevicesFlow(): Flow<List<NetworkDeviceEntity>> = flowOf(emptyList())

        override suspend fun getDevice(id: String): NetworkDeviceEntity? = devices[id]

        override suspend fun getDeviceByMac(mac: String): NetworkDeviceEntity? =
            devices.values.firstOrNull { it.mac == mac }

        override suspend fun getDeviceByAddress(address: String): NetworkDeviceEntity? =
            devices.values.firstOrNull { it.lastAddress == address && it.id !in retired }

        override suspend fun getDevicesByClass(deviceClass: String): List<NetworkDeviceEntity> =
            devices.values.filter { it.deviceClass == deviceClass }

        override suspend fun getDevicesByVendor(vendor: String): List<NetworkDeviceEntity> =
            devices.values.filter { it.vendor == vendor }

        override suspend fun searchDevices(query: String): List<NetworkDeviceEntity> =
            devices.values.filter {
                listOfNotNull(
                    it.name, it.room, it.observedName, it.vendor,
                    it.model, it.lastAddress, it.mac, it.deviceClass,
                ).any { field -> field.contains(query, ignoreCase = true) }
            }

        override suspend fun upsertDevice(device: NetworkDeviceEntity) {
            devices[device.id] = device
        }

        override suspend fun upsertDevices(devices: List<NetworkDeviceEntity>) {
            devices.forEach { this.devices[it.id] = it }
        }

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
        ) {
            val existing = devices[id] ?: return
            devices[id] = existing.copy(
                lastAddress = address,
                observedName = observedName,
                vendor = vendor,
                model = model,
                deviceClass = deviceClass,
                openPortsJson = openPortsJson,
                servicesJson = servicesJson,
                sightingCount = existing.sightingCount + 1,
                lastSeen = now,
                updatedAt = now,
                // Mirrors the real query, which un-retires a device it has just seen.
                retired = false,
            )
            retired.remove(id)
        }

        override suspend fun renameDevice(id: String, name: String?, now: Long) {
            devices[id] = devices.getValue(id).copy(name = name, updatedAt = now)
        }

        override suspend fun placeDevice(id: String, room: String?, now: Long) {
            devices[id] = devices.getValue(id).copy(room = room, updatedAt = now)
        }

        override suspend fun retireDevice(id: String, now: Long) {
            retired.add(id)
        }

        override suspend fun getUnseenSince(cutoff: Long): List<NetworkDeviceEntity> =
            devices.values.filter { it.lastSeen < cutoff && it.id !in retired }

        override suspend fun countActiveDevices(): Int = getActiveDevices().size
        override suspend fun countAllDevices(): Int = devices.size

        override suspend fun insertSighting(sighting: NetworkSightingEntity) {
            sightings.add(sighting)
        }

        override suspend fun insertSightings(sightings: List<NetworkSightingEntity>) {
            this.sightings.addAll(sightings)
        }

        override suspend fun getSightingsFor(deviceId: String): List<NetworkSightingEntity> =
            sightings.filter { it.deviceId == deviceId }

        override suspend fun getLatestSighting(deviceId: String): NetworkSightingEntity? =
            sightings.filter { it.deviceId == deviceId }.maxByOrNull { it.seenAt }

        override suspend fun getSightingsForScan(scanId: String): List<NetworkSightingEntity> =
            sightings.filter { it.scanId == scanId }

        override suspend fun getAddressesFor(deviceId: String): List<String> =
            sightings.filter { it.deviceId == deviceId }.map { it.address }.distinct()

        override suspend fun countSightingsFor(deviceId: String): Int =
            sightings.count { it.deviceId == deviceId }

        override suspend fun insertScan(scan: NetworkScanEntity) {
            scans.add(scan)
        }

        override suspend fun finishScan(
            id: String,
            finishedAt: Long,
            probedAddresses: Int,
            aliveAddresses: Int,
            deviceCount: Int,
            problemsJson: String,
        ) {
            val index = scans.indexOfFirst { it.id == id }
            if (index >= 0) {
                scans[index] = scans[index].copy(
                    finishedAt = finishedAt,
                    probedAddresses = probedAddresses,
                    aliveAddresses = aliveAddresses,
                    deviceCount = deviceCount,
                    problemsJson = problemsJson,
                )
            }
        }

        override suspend fun getRecentScans(limit: Int): List<NetworkScanEntity> =
            scans.sortedByDescending { it.startedAt }.take(limit)

        override suspend fun getScan(id: String): NetworkScanEntity? = scans.firstOrNull { it.id == id }

        override suspend fun getLatestCompletedScan(): NetworkScanEntity? =
            scans.filter { it.finishedAt > 0 }.maxByOrNull { it.startedAt }

        override suspend fun countScans(): Int = scans.size

        override suspend fun pruneSightingsBefore(cutoff: Long): Int = 0
        override suspend fun pruneScansBefore(cutoff: Long): Int = 0
    }

    /** One device as a sweep reports it. */
    private fun device(
        address: String,
        name: String? = null,
        vendor: String? = null,
        mac: String? = null,
        ports: List<Int> = emptyList(),
    ) = MergedDevice(
        address = address,
        name = name,
        vendor = vendor,
        deviceClass = DeviceClass.UNKNOWN,
        openPorts = ports,
        evidence = mac?.let {
            listOf(Evidence("SNMP", "hardware address $it", EvidenceKind.DECLARED))
        }.orEmpty(),
    )

    private fun report(vararg devices: MergedDevice) = FullNetworkSweep.SweepReport(
        devices = devices.toList(),
        problems = emptyList(),
        probedAddresses = 254,
        subnet = "192.0.2",
        aliveAddresses = devices.map { it.address },
    )

    // ------------------------------------------------------------------
    // Retirement, which is the fault that would have emptied his map
    // ------------------------------------------------------------------

    @Test
    fun `a sweep on another network does not retire the devices at home`() = runBlocking {
        val dao = FakeDao()
        val store = NetworkMapStore(dao)
        val now = 1_000_000_000_000L

        // The house, seen and stored.
        store.recordSweep(report(device("192.0.2.10", name = "lounge-tv")), "tool", now = now)
        assertEquals(1, dao.getActiveDevices().size)

        // A sweep of a café a day later, which sees none of his devices. Before the grace window,
        // this retired the whole house in one pass.
        store.recordSweep(
            report(device("198.51.100.10", name = "cafe-printer")),
            "tool",
            now = now + 24 * 60 * 60 * 1000,
        )

        val active = dao.getActiveDevices().map { it.id }
        assertEquals("the house must still be on the map", 2, active.size)
        assertTrue(
            "the television must not have been retired by a sweep elsewhere",
            active.any { it.contains("lounge-tv") },
        )
    }

    @Test
    fun `a device missing for longer than the window is retired`() = runBlocking {
        val dao = FakeDao()
        val store = NetworkMapStore(dao)
        val now = 1_000_000_000_000L

        store.recordSweep(report(device("192.0.2.10", name = "old-printer")), "tool", now = now)

        // Eight days later, a sweep that does not see it. The window is seven days, so this is the
        // moment it is genuinely gone rather than merely quiet.
        val muchLater = now + 8L * 24 * 60 * 60 * 1000
        store.recordSweep(report(device("192.0.2.11", name = "new-thing")), "tool", now = muchLater)

        val active = dao.getActiveDevices().map { it.id }
        assertEquals(1, active.size)
        assertTrue(active.none { it.contains("old-printer") })
        // The history is kept, which is the whole reason retirement is a flag rather than a delete.
        assertEquals(1, dao.sightings.count { it.deviceId.contains("old-printer") })
    }

    @Test
    fun `a retired device that comes back returns to the map`() = runBlocking {
        val dao = FakeDao()
        val store = NetworkMapStore(dao)
        val now = 1_000_000_000_000L

        store.recordSweep(report(device("192.0.2.10", name = "spare-tv")), "tool", now = now)

        // Gone for a fortnight, so it is retired.
        val fortnight = now + 14L * 24 * 60 * 60 * 1000
        store.recordSweep(report(device("192.0.2.99", name = "other")), "tool", now = fortnight)
        assertTrue(
            "the spare television should have been retired",
            dao.getActiveDevices().none { it.id.contains("spare-tv") },
        )

        // Plugged back in. It was never deleted, so it must reappear rather than being a row that
        // is invisible for ever while its sightings keep piling up underneath it.
        val later = fortnight + 60_000
        store.recordSweep(report(device("192.0.2.10", name = "spare-tv")), "tool", now = later)

        assertTrue(
            "a device that answered again is on the map again",
            dao.getActiveDevices().any { it.id.contains("spare-tv") },
        )
    }

    // ------------------------------------------------------------------
    // Identity against the stored map
    // ------------------------------------------------------------------

    @Test
    fun `two devices from the same vendor are two rows, not one`() = runBlocking {
        val dao = FakeDao()
        val store = NetworkMapStore(dao)

        // Both answer nothing but their vendor, which is exactly the case that collapsed before:
        // the identity used to fall back to `device.vendor`, so both became "name:sony".
        store.recordSweep(
            report(
                device("192.0.2.20", vendor = "Sony"),
                device("192.0.2.21", vendor = "Sony"),
            ),
            "tool",
            now = 1_000_000_000_000L,
        )

        assertEquals("two devices must be two rows", 2, dao.devices.size)
    }

    @Test
    fun `the same device on a new address updates its row rather than adding one`() = runBlocking {
        val dao = FakeDao()
        val store = NetworkMapStore(dao)
        val now = 1_000_000_000_000L

        store.recordSweep(
            report(device("192.0.2.30", name = "nas", mac = "94:b3:f7:90:92:81")),
            "tool",
            now = now,
        )
        // The lease moved, but the MAC did not, so it is the same device.
        val recorded = store.recordSweep(
            report(device("192.0.2.31", name = "nas", mac = "94:b3:f7:90:92:81")),
            "tool",
            now = now + 60_000,
        )

        assertEquals(1, dao.devices.size)
        assertEquals(0, recorded.inserted)
        assertEquals(1, recorded.updated)
        assertEquals(1, recorded.movedAddress)
    }

    @Test
    fun `the owner's name survives every sweep`() = runBlocking {
        val dao = FakeDao()
        val store = NetworkMapStore(dao)
        val now = 1_000_000_000_000L

        store.recordSweep(
            report(device("192.0.2.40", name = "printer-4f", mac = "94:b3:f7:90:92:81")),
            "tool",
            now = now,
        )
        val id = dao.devices.keys.single()
        store.rename(id, "kitchen printer")
        store.place(id, "kitchen")

        // A later sweep with a different announced name must not undo the owner's words.
        store.recordSweep(
            report(device("192.0.2.40", name = "HP-OfficeJet", mac = "94:b3:f7:90:92:81")),
            "tool",
            now = now + 60_000,
        )

        val row = dao.devices.getValue(id)
        assertEquals("kitchen printer", row.name)
        assertEquals("kitchen", row.room)
        assertEquals("HP-OfficeJet", row.observedName)
    }

    @Test
    fun `a port that answered once is not forgotten by a quiet sweep`() = runBlocking {
        val dao = FakeDao()
        val store = NetworkMapStore(dao)
        val now = 1_000_000_000_000L

        store.recordSweep(
            report(device("192.0.2.50", name = "tv", mac = "94:b3:f7:90:92:81", ports = listOf(8060, 8009))),
            "tool",
            now = now,
        )
        // This sweep only got the DIAL port. The Roku port answered last week and is still a fact.
        store.recordSweep(
            report(device("192.0.2.50", name = "tv", mac = "94:b3:f7:90:92:81", ports = listOf(8009))),
            "tool",
            now = now + 60_000,
        )

        val row = dao.devices.values.single()
        assertTrue(row.openPortsJson.contains("8060"))
        assertTrue(row.openPortsJson.contains("8009"))
    }
}
