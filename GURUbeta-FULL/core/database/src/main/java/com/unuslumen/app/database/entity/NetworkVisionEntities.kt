// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.database.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

/**
 * One device GURU has ever seen on a network, kept across scans.
 *
 * WHY THIS IS NOT [FabricDeviceEntity]. The fabric holds devices GURU can DRIVE: a light, a plug, a
 * television with an adapter. This table holds everything GURU has ever SEEN, which is a
 * different set and a wider one. A neighbour's access point, a printer GURU cannot talk to, a
 * fridge that answers nothing at all — none of those belong in the fabric, and all of them belong
 * here. Conflating the two would either fill the fabric with undrivable junk or throw away
 * everything GURU cannot control, and both are wrong.
 *
 * THE IDENTITY IS THE WHOLE DESIGN. A device's address changes every time a lease expires, so an
 * address is not an identity: the same device on a new address would appear twice and its history
 * would split in two. The identity is therefore the strongest stable fact available, in order:
 *
 *  1. The MAC address, when any protocol gave one up. This is the only genuinely stable hardware
 *     identity, and it survives a DHCP change, a reboot and a rename.
 *  2. A namespaced name, when the device announced itself over mDNS or NetBIOS. A name can change,
 *     but it changes far less often than an address.
 *  3. The address itself, as the weakest fallback, for a device that said nothing but answered.
 *
 * [identityKind] records which of those was used, because a device known only by its address will
 * fork into a second row if its lease moves, and that is a fact a reader deserves to know rather
 * than a defect to hide.
 *
 * WHY THE PLACEMENT IS SEPARATE FROM THE OBSERVATION. [name] and [room] are the owner's decisions
 * and are never overwritten by a scan, exactly as [FabricDao.placeDevice] protects them. A sweep
 * only ever writes the observed columns.
 */
@Entity(
    tableName = "network_devices",
    indices = [
        Index(value = ["mac"], name = "index_network_devices_mac"),
        Index(value = ["device_class"], name = "index_network_devices_class"),
        Index(value = ["vendor"], name = "index_network_devices_vendor"),
        Index(value = ["last_seen"], name = "index_network_devices_last_seen"),
        Index(value = ["first_seen"], name = "index_network_devices_first_seen"),
    ]
)
data class NetworkDeviceEntity(
    /**
     * The durable identity, derived by [com.unuslumen.app.data.fabric.net.DeviceIdentity].
     *
     * A MAC when one is known, a namespaced name when not, and the address as the last resort.
     * Deriving it once in a pure function is what stops two scans identifying the same device two
     * different ways.
     */
    @PrimaryKey
    val id: String,

    /** Which fact the identity was built from: "mac", "name" or "address". */
    @ColumnInfo(name = "identity_kind")
    val identityKind: String,

    /** The hardware address when any protocol gave one up, lowercased and colon-separated. */
    @ColumnInfo(name = "mac")
    val mac: String?,

    /** The address this device was last seen on, which is NOT its identity. */
    @ColumnInfo(name = "last_address")
    val lastAddress: String,

    /** The owner's name for it, and null until they set one. Never written by a scan. */
    @ColumnInfo(name = "name")
    val name: String?,

    /** The owner's room for it, and null until they set one. Never written by a scan. */
    @ColumnInfo(name = "room")
    val room: String?,

    /** The name the device or the router gave it, which a scan may update. */
    @ColumnInfo(name = "observed_name")
    val observedName: String?,

    /** The manufacturer, when a protocol or a MAC prefix named one. */
    @ColumnInfo(name = "vendor")
    val vendor: String?,

    /** The model string, when the device reported one. */
    @ColumnInfo(name = "model")
    val model: String?,

    /** The firmware or software version, when a device reported one. */
    @ColumnInfo(name = "firmware")
    val firmware: String?,

    /** What the device most plausibly is, as a [com.unuslumen.app.data.fabric.net.DeviceClass] name. */
    @ColumnInfo(name = "device_class")
    val deviceClass: String,

    /** Every port ever seen open on it, as a JSON array of integers. */
    @ColumnInfo(name = "open_ports_json", defaultValue = "[]")
    val openPortsJson: String = "[]",

    /** Every service ever seen announced by it, as a JSON array of strings. */
    @ColumnInfo(name = "services_json", defaultValue = "[]")
    val servicesJson: String = "[]",

    /** How many sweeps have seen it, which is the confidence a device is a fixture. */
    @ColumnInfo(name = "sighting_count", defaultValue = "1")
    val sightingCount: Int = 1,

    @ColumnInfo(name = "first_seen")
    val firstSeen: Long,

    @ColumnInfo(name = "last_seen")
    val lastSeen: Long,

    /** Set when GURU decides the device has gone: long unseen, or explicitly retired. */
    @ColumnInfo(name = "retired", defaultValue = "0")
    val retired: Boolean = false,

    @ColumnInfo(name = "created_at")
    val createdAt: Long,

    @ColumnInfo(name = "updated_at")
    val updatedAt: Long,
) {
    /** What to show in a list: the owner's name, then what was observed, then the address. */
    val displayName: String
        get() = name?.takeIf { it.isNotBlank() }
            ?: observedName?.takeIf { it.isNotBlank() }
            ?: vendor?.takeIf { it.isNotBlank() }?.let { "$it device" }
            ?: lastAddress
}

/**
 * One device, seen once, in one sweep.
 *
 * WHY THIS IS A SEPARATE TABLE AND NOT A COLUMN. A single row per device can only hold the latest
 * state, and the questions that make a network map useful are all questions about change: when did
 * this device appear, when did it stop answering, did its address move, did a port open that was
 * closed last week. Those need a row per sighting, and the row is tiny: the address it had at the
 * time and what it said then.
 *
 * THIS TABLE IS THE HISTORY, AND IT IS ALSO THE EVIDENCE. Every fact a sweep worked out goes into
 * [evidenceJson] with the protocol that produced it and whether it was declared or inferred, so a
 * label on a device can always be traced back to the observation that justified it rather than
 * being an assertion the app can no longer explain.
 */
@Entity(
    tableName = "network_sightings",
    indices = [
        Index(value = ["device_id"], name = "index_network_sightings_device"),
        Index(value = ["scan_id"], name = "index_network_sightings_scan"),
        Index(value = ["seen_at"], name = "index_network_sightings_seen_at"),
        Index(value = ["address"], name = "index_network_sightings_address"),
    ]
)
data class NetworkSightingEntity(
    @PrimaryKey
    val id: String,

    /** The [NetworkDeviceEntity] this sighting is about. */
    @ColumnInfo(name = "device_id")
    val deviceId: String,

    /** The [NetworkScanEntity] that produced it. */
    @ColumnInfo(name = "scan_id")
    val scanId: String,

    /** The address it had at that moment, which is the one thing that does change. */
    @ColumnInfo(name = "address")
    val address: String,

    /** What answered on this sighting, as a JSON array of port integers. */
    @ColumnInfo(name = "open_ports_json", defaultValue = "[]")
    val openPortsJson: String = "[]",

    /** What it announced on this sighting, as a JSON array of service names. */
    @ColumnInfo(name = "services_json", defaultValue = "[]")
    val servicesJson: String = "[]",

    /** Every fact, with its protocol and its confidence, as a JSON array. */
    @ColumnInfo(name = "evidence_json", defaultValue = "[]")
    val evidenceJson: String = "[]",

    /** What kind of device it looked like on this sighting, which may differ from the last one. */
    @ColumnInfo(name = "device_class")
    val deviceClass: String,

    /** Whether it answered at all on this sweep, which a silent sighting records honestly. */
    @ColumnInfo(name = "answered")
    val answered: Boolean,

    @ColumnInfo(name = "seen_at")
    val seenAt: Long,
)

/**
 * One sweep of a network, so a map has a timestamp and a reason.
 *
 * WHY A SWEEP IS A ROW RATHER THAN A TIMESTAMP ON THE SIGHTINGS. "What did the network look like at
 * 3pm on Tuesday" is a question a person asks when a device goes missing, and answering it from
 * per-device timestamps alone means guessing which sightings belonged to the same run. A run is a
 * thing that happened, it was started for a reason, and it either finished or it did not.
 *
 * [trigger] is what asked for it: the owner speaking, a skill firing, a scheduled pass, or a tool
 * called by name. That is what turns a pile of scans into an account of why GURU looked when it
 * did.
 */
@Entity(
    tableName = "network_scans",
    indices = [
        Index(value = ["started_at"], name = "index_network_scans_started_at"),
        Index(value = ["subnet"], name = "index_network_scans_subnet"),
    ]
)
data class NetworkScanEntity(
    @PrimaryKey
    val id: String,

    /** The /24 that was swept, e.g. "192.168.0", so a second network is a separate map. */
    @ColumnInfo(name = "subnet")
    val subnet: String?,

    /** What asked for this sweep: "user", "skill", "schedule" or "tool". */
    @ColumnInfo(name = "trigger")
    val trigger: String,

    /** The words that started it, when it came from the owner, so a map keeps its reason. */
    @ColumnInfo(name = "prompt")
    val prompt: String?,

    @ColumnInfo(name = "started_at")
    val startedAt: Long,

    /** Zero while a sweep is running, because a run that never finished is a fact too. */
    @ColumnInfo(name = "finished_at", defaultValue = "0")
    val finishedAt: Long = 0,

    /** How many addresses were probed, so coverage is visible rather than assumed. */
    @ColumnInfo(name = "probed_addresses", defaultValue = "0")
    val probedAddresses: Int = 0,

    /** How many addresses were present at all. */
    @ColumnInfo(name = "alive_addresses", defaultValue = "0")
    val aliveAddresses: Int = 0,

    /** How many devices were identified. */
    @ColumnInfo(name = "device_count", defaultValue = "0")
    val deviceCount: Int = 0,

    /**
     * What the sweep could not do, as a JSON array of sentences.
     *
     * KEPT RATHER THAN DISCARDED. A sweep that ran with no location permission or no router login
     * sees less than a full one, and a map built from it must say so. A short list with no
     * explanation is how a person concludes their network is empty.
     */
    @ColumnInfo(name = "problems_json", defaultValue = "[]")
    val problemsJson: String = "[]",
)
