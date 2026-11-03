// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import com.unuslumen.app.database.dao.NetworkVisionDao
import com.unuslumen.app.database.entity.NetworkDeviceEntity
import com.unuslumen.app.database.entity.NetworkScanEntity
import com.unuslumen.app.database.entity.NetworkSightingEntity
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

/**
 * Turning a sweep into something GURU remembers.
 *
 * WHY THIS EXISTS AS A SEPARATE LAYER FROM THE SWEEP. A sweep knows what answered. This decides what
 * is NEW, what is the same device seen again, what moved address, and what has gone away. Those are
 * decisions about identity and change, and they are the difference between a network map and a list
 * of what happened to be awake at 3pm. Keeping them here means the sweep stays about sockets and
 * this stays about meaning.
 *
 * THE THREE OUTCOMES OF ONE SIGHTING, and each one is a real case:
 *
 *  1. **Known by a strong identity.** Its hardware address or its name matched a stored row, so it
 *     is the same device: the row is updated and one sighting is appended.
 *  2. **Unknown, but it looks like a stored row's address.** This is the dangerous one. A lease
 *     can move, so an address match is NOT proof of identity. It is adopted only when the stored
 *     row has nothing stronger to identify it by, and the adoption is recorded as such.
 *  3. **Genuinely new.** A row is inserted with the owner's name and room fields empty, because
 *     those belong to the owner and a sweep must never invent one.
 *
 * WHAT IS NEVER DONE. The owner's name and room are never written by [recordSweep]. A sweep writes
 * the observed columns only, which is why renaming and rooming are separate DAO queries. That is the
 * same rule the smart home fabric already follows, and breaking it would mean a device renamed once
 * reverts the next time it is seen.
 */
class NetworkMapStore(private val dao: NetworkVisionDao) {

    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Record one completed sweep and return what it did to the map.
     *
     * [trigger] is what asked for the sweep ("user", "skill", "schedule", "tool") and [prompt] is
     * the words that started it, so a map keeps the reason it was built.
     *
     * [retireAfterMillis] IS A GRACE WINDOW, NOT "NOT SEEN THIS TIME", and getting that wrong was a
     * real defect in the first version of this class. It retired every device absent from the
     * current sweep, which meant joining one other wifi retired the entire house: a café network's
     * sweep saw none of his devices and therefore declared all of them gone. A device is retired
     * only after it has been unseen for longer than this window, so a single sweep away from home
     * costs nothing and a device that genuinely left is eventually marked.
     */
    suspend fun recordSweep(
        report: FullNetworkSweep.SweepReport,
        trigger: String,
        prompt: String? = null,
        now: Long = System.currentTimeMillis(),
        retireAfterMillis: Long = RETIRE_AFTER_MILLIS,
    ): SweepRecorded {
        val scanId = "scan:${UUID.randomUUID()}"
        dao.insertScan(
            NetworkScanEntity(
                id = scanId,
                subnet = report.subnet,
                trigger = trigger,
                prompt = prompt,
                startedAt = now,
                finishedAt = 0,
                probedAddresses = report.probedAddresses,
                aliveAddresses = report.aliveAddresses.size,
                deviceCount = 0,
                problemsJson = encodeStrings(report.problems),
            ),
        )

        var inserted = 0
        var updated = 0
        var moved = 0
        val seenIds = mutableSetOf<String>()

        for (device in report.devices) {
            // THE IDENTITY IS DERIVED FROM THE STRONGEST FACT THIS DEVICE GAVE UP, and the name
            // fed in here is the device's OWN name, never its vendor. An earlier version fell back
            // to the vendor, which is a collision waiting to happen: a Sony television and a Sony
            // speaker both became "name:sony", so the second device overwrote the first and the
            // map quietly held one of them. A vendor names a company, not a device.
            val identity = DeviceIdentity.of(
                address = device.address,
                mac = device.evidence.firstNotNullOfOrNull { macFromEvidence(it) },
                name = device.name,
            )

            val existing = findExisting(identity, device.address)
            val deviceId: String
            if (existing != null) {
                deviceId = existing.id

                // A MOVE IS AN EVENT WORTH COUNTING. The device is the same, but it appeared
                // somewhere new, which is what a person wants told rather than absorbed.
                if (existing.lastAddress != device.address) moved++

                // Ports accumulate rather than replace, because a port that answered last week and
                // was quiet this week is still something the device has. A map that replaced the
                // list on every sweep would forget facts it had already learned.
                val mergedPorts = (decodeInts(existing.openPortsJson) + device.openPorts)
                    .distinct()
                    .sorted()
                val mergedServices = (decodeStrings(existing.servicesJson) + device.services)
                    .distinct()
                    .sorted()
                // The stored vendor and model are kept when this sighting has none, because a
                // protocol that answered last week and stayed quiet this week did not un-name the
                // device. Silently dropping a known vendor on a quiet sighting is how a map becomes
                // less useful every time it runs.
                dao.recordSighting(
                    id = existing.id,
                    address = device.address,
                    observedName = device.name ?: existing.observedName,
                    vendor = device.vendor ?: existing.vendor,
                    model = device.model ?: existing.model,
                    deviceClass = device.deviceClass.name,
                    openPortsJson = encodeInts(mergedPorts),
                    servicesJson = encodeStrings(mergedServices),
                    now = now,
                )
                updated++
            } else {
                deviceId = identity.id
                dao.upsertDevice(
                    NetworkDeviceEntity(
                        id = identity.id,
                        identityKind = identity.basis.name.lowercase(),
                        mac = identity.mac,
                        lastAddress = device.address,
                        // The owner has not named this device yet, so the field is empty rather
                        // than pre-filled with a guess they would have to correct.
                        name = null,
                        room = null,
                        observedName = device.name,
                        vendor = device.vendor,
                        model = device.model,
                        firmware = null,
                        deviceClass = device.deviceClass.name,
                        openPortsJson = encodeInts(device.openPorts),
                        servicesJson = encodeStrings(device.services),
                        sightingCount = 1,
                        firstSeen = now,
                        lastSeen = now,
                        retired = false,
                        createdAt = now,
                        updatedAt = now,
                    ),
                )
                inserted++
            }

            seenIds.add(deviceId)

            // The sighting is the history and the evidence, kept even for a device already known,
            // because "when did this last answer and what did it say" is the question a map exists
            // to answer.
            dao.insertSighting(
                NetworkSightingEntity(
                    id = "sight:${UUID.randomUUID()}",
                    deviceId = deviceId,
                    scanId = scanId,
                    address = device.address,
                    openPortsJson = encodeInts(device.openPorts),
                    servicesJson = encodeStrings(device.services),
                    evidenceJson = encodeEvidence(device.evidence),
                    deviceClass = device.deviceClass.name,
                    answered = true,
                    seenAt = now,
                ),
            )
        }

        // A DEVICE UNSEEN IS MARKED RATHER THAN DELETED. Its history is the reason the map is worth
        // having, so a device that has left is retired and its sightings kept. Only a deliberate
        // purge removes rows, and nothing in this class does that.
        //
        // THE CUTOFF IS THE GRACE WINDOW, NOT "NOW". Retiring on `now` meant any sweep from a
        // network that is not his own retired his whole house in one pass, because none of his
        // devices answer on a café's wifi. A device is only retired once it has been missing for the
        // whole window, so one sweep elsewhere changes nothing and a device that really left is
        // marked a week later.
        val cutoff = now - retireAfterMillis
        val unseen = dao.getUnseenSince(cutoff)
        unseen.forEach { dao.retireDevice(it.id, now) }

        dao.finishScan(
            id = scanId,
            finishedAt = now,
            probedAddresses = report.probedAddresses,
            aliveAddresses = report.aliveAddresses.size,
            deviceCount = seenIds.size,
            problemsJson = encodeStrings(report.problems),
        )

        return SweepRecorded(
            scanId = scanId,
            inserted = inserted,
            updated = updated,
            movedAddress = moved,
            total = seenIds.size,
        )
    }

    /**
     * Which stored row, if any, this sighting is.
     *
     * THE ORDER IS THE WHOLE CORRECTNESS. A strong identity is checked first and an address only
     * after it, and the address is only trusted for a row that has nothing stronger. Trusting an
     * address in general would merge a new device onto the previous tenant of that lease, giving a
     * fridge a printer's history.
     */
    private suspend fun findExisting(
        identity: DeviceIdentity.Identity,
        address: String,
    ): NetworkDeviceEntity? {
        identity.mac?.let { mac ->
            dao.getDeviceByMac(mac)?.let { return it }
        }
        when (identity.basis) {
            DeviceIdentity.Basis.MAC, DeviceIdentity.Basis.NAME -> {
                dao.getDevice(identity.id)?.let { return it }
            }
            DeviceIdentity.Basis.ADDRESS -> Unit
        }

        val byAddress = dao.getDeviceByAddress(address) ?: return null
        // An address match is only adopted when both sides are weak. A stored device with a MAC is
        // not the device now sitting on its old address.
        return byAddress.takeIf {
            it.identityKind == DeviceIdentity.Basis.ADDRESS.name.lowercase() &&
                identity.basis == DeviceIdentity.Basis.ADDRESS
        }
    }

    /** Any MAC this evidence line mentions, which is how a MAC survives into the identity. */
    private fun macFromEvidence(evidence: Evidence): String? {
        val match = MAC_PATTERN.find(evidence.detail) ?: return null
        return DeviceIdentity.normaliseMac(match.value)
    }

    // ------------------------- map questions -------------------------

    /** Everything currently on the map, newest sighting first. */
    suspend fun devices(): List<NetworkDeviceEntity> = dao.getActiveDevices()

    /** One device's whole history, newest first. */
    suspend fun history(deviceId: String): List<NetworkSightingEntity> = dao.getSightingsFor(deviceId)

    /** Whether a device has had more than one address, which is how a move is surfaced. */
    suspend fun hasMoved(deviceId: String): Boolean = dao.getAddressesFor(deviceId).size > 1

    /** The sweeps that have run, so a person can see when GURU last looked. */
    suspend fun scans(limit: Int = 20): List<NetworkScanEntity> = dao.getRecentScans(limit)

    /** Search the map by any fact a person would remember a device by. */
    suspend fun search(query: String): List<NetworkDeviceEntity> = dao.searchDevices(query)

    /**
     * One device by the id `netMap` reported, or by an address or MAC a person typed.
     *
     * Needed because the id is what the tools hand out, and a map that cannot be asked about a
     * device by the identifier it printed is a map that makes its own caller guess.
     */
    suspend fun device(id: String): NetworkDeviceEntity? {
        dao.getDevice(id)?.let { return it }
        dao.getDeviceByAddress(id)?.let { return it }
        val mac = DeviceIdentity.normaliseMac(id) ?: return null
        return dao.getDeviceByMac(mac)
    }

    /** Give a device a name. No sweep may undo this. */
    suspend fun rename(deviceId: String, name: String?, now: Long = System.currentTimeMillis()) {
        dao.renameDevice(deviceId, name?.trim()?.ifBlank { null }, now)
    }

    /** Put a device in a room. No sweep may undo this. */
    suspend fun place(deviceId: String, room: String?, now: Long = System.currentTimeMillis()) {
        dao.placeDevice(deviceId, room?.trim()?.ifBlank { null }, now)
    }

    /** Drop history older than [cutoff], keeping the device rows themselves. */
    suspend fun prune(cutoff: Long): Int = dao.pruneSightingsBefore(cutoff)

    // ------------------------- encoding -------------------------

    private fun encodeInts(values: List<Int>): String = buildJsonArray {
        values.forEach { add(it) }
    }.toString()

    private fun decodeInts(raw: String): List<Int> = runCatching {
        json.parseToJsonElement(raw).jsonArray.mapNotNull { it.jsonPrimitive.content.toIntOrNull() }
    }.getOrDefault(emptyList())

    private fun encodeStrings(values: List<String>): String = buildJsonArray {
        values.forEach { add(it) }
    }.toString()

    private fun decodeStrings(raw: String): List<String> = runCatching {
        json.parseToJsonElement(raw).jsonArray.map { it.jsonPrimitive.content }
    }.getOrDefault(emptyList())

    private fun encodeEvidence(values: List<Evidence>): String = buildJsonArray {
        values.forEach { evidence ->
            add("${evidence.source}: ${evidence.detail} (${evidence.kind.name.lowercase()})")
        }
    }.toString()

    /** What one sweep did to the map, so GURU can say "three new devices, one moved". */
    data class SweepRecorded(
        val scanId: String,
        val inserted: Int,
        val updated: Int,
        val movedAddress: Int,
        val total: Int,
    )

    companion object {
        /**
         * How long a device may be missing from sweeps before it is retired.
         *
         * SEVEN DAYS, WHICH IS A JUDGEMENT RATHER THAN A MAGIC NUMBER. A phone that leaves the
         * house for a weekend and comes back is still a device in the house, and retiring it on the
         * first sweep it misses would make its history look like a series of unrelated arrivals.
         * A device genuinely removed is marked within a week, which is soon enough that the map
         * stops listing it while its sightings remain readable.
         */
        const val RETIRE_AFTER_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

        /**
         * A MAC address in any of the spellings a protocol may use.
         *
         * The evidence line is the only place a probe records a MAC, so this is what recovers it
         * for the identity. Matching all three forms matters because SNMP reports hex octets, NBNS
         * reports a colon-separated string and mDNS TXT records vary by vendor.
         *
         * THE BARE TWELVE-HEX FORM IS BRACKETED WITH WORD BOUNDARIES, which matters because the
         * unanchored version matches the first twelve characters of any longer hex run. An SNMP
         * value such as a 16-character serial in hex would otherwise be read as a MAC, and a device
         * would be given a hardware identity it does not have. That is the exact fault this class
         * exists to prevent, so the pattern refuses it at the source.
         */
        private val MAC_PATTERN = Regex(
            "\\b(?:[0-9a-fA-F]{2}[:-]){5}[0-9a-fA-F]{2}\\b|\\b[0-9a-fA-F]{12}\\b",
        )
    }
}
