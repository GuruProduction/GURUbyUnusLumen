// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric

import android.util.Log
import com.unuslumen.app.database.dao.FabricDao
import com.unuslumen.app.database.entity.FabricDeviceEntity
import com.unuslumen.app.database.entity.FabricSceneEntity

/**
 * The device fabric. Issue #182.
 *
 * The one place that decides what a device is, whether a command is allowed, and
 * whether it actually happened. Transports report and act; this decides. Keeping
 * the decision here rather than in each adapter is what makes refusal identical
 * across seven protocols.
 *
 * TWO RULES, both carried from earlier defects:
 *
 *  1. CAPABILITY ENFORCEMENT. A command targeting a capability the device does
 *     not report is refused with a reason naming both. It is never silently
 *     ignored, which is the same lie as a tool that reports success it did not
 *     observe.
 *
 *  2. READ-BACK. Every write is followed by a read, and the fabric compares the
 *     value it wrote against the value the device reports. A transport claiming
 *     success is not evidence; the observed value is.
 */
class DeviceFabric(
    private val dao: FabricDao,
    private val registry: DeviceTransportRegistry,
) {

    /**
     * Encoding is hand-written, not generated.
     *
     * WHY. The serialization plugin would not generate serializers for the enums
     * in this module: `Capability.serializer()` was unresolved in every position
     * tried, including a companion object, while existing `@Serializable` data
     * classes elsewhere in the same module compiled fine. Rather than fight a
     * generator, the two shapes this class needs are encoded explicitly.
     *
     * That is not a workaround around a bug, it is the better fit here anyway:
     * there are exactly two shapes (a capability set and a map of capability to
     * value), the format is a documented part of this class rather than an
     * incidental serialisation detail, and hand-written parsing can report a bad
     * value instead of throwing. The store never has to be read by another
     * process, so the only contract is this file's own.
     */
    private companion object Serializers {
        const val TAG = "guru"
    }

    /**
     * Quote a string so it round-trips through the store.
     *
     * Handles exactly the characters that would break the encoding: quote,
     * backslash, and the control characters a device name or protocol value can
     * carry. Everything else passes through, so ordinary names stay readable in
     * the database rather than becoming \u sequences.
     */
    private fun escape(value: String): String {
        val sb = StringBuilder(value.length + 2)
        sb.append('"')
        for (c in value) {
            when (c) {
                '"' -> sb.append("\\\"")
                '\\' -> sb.append("\\\\")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> if (c < ' ') sb.append("\\u%04x".format(c.code)) else sb.append(c)
            }
        }
        sb.append('"')
        return sb.toString()
    }

    /** Inverse of [escape]. A value that is not quoted is returned unchanged. */
    private fun unescape(raw: String): String {
        val s = raw.trim()
        if (s.length < 2 || !s.startsWith('"') || !s.endsWith('"')) return s
        val body = s.substring(1, s.length - 1)
        val sb = StringBuilder(body.length)
        var i = 0
        while (i < body.length) {
            val c = body[i]
            if (c != '\\' || i + 1 >= body.length) {
                sb.append(c); i++; continue
            }
            when (val n = body[i + 1]) {
                '"' -> { sb.append('"'); i += 2 }
                '\\' -> { sb.append('\\'); i += 2 }
                'n' -> { sb.append('\n'); i += 2 }
                'r' -> { sb.append('\r'); i += 2 }
                't' -> { sb.append('\t'); i += 2 }
                'u' -> {
                    val hex = body.substring(i + 2, minOf(i + 6, body.length))
                    val code = hex.toIntOrNull(16)
                    if (code != null) { sb.append(code.toChar()); i += 2 + hex.length } else { sb.append(n); i += 2 }
                }
                else -> { sb.append(n); i += 2 }
            }
        }
        return sb.toString()
    }

    /** `["POWER","BRIGHTNESS"]` */
    private fun encodeCapabilities(caps: Collection<Capability>): String =
        caps.joinToString(prefix = "[", postfix = "]") { "\"${it.name}\"" }

    private fun decodeCapabilities(raw: String): Set<Capability> =
        Regex("\"([A-Z_]+)\"").findAll(raw)
            .mapNotNull { Capability.fromRaw(it.groupValues[1]) }
            .toSet()

    /**
     * `{"POWER":["f",true],"BRIGHTNESS":["c",200],"MODE":["t","cool"]}`
     *
     * A tagged pair keeps the value type explicit, so a brightness can never be
     * silently read back as the string "200" and compare unequal to the number
     * that was written. The tags are f/c/m/t for flag, count, measure, text.
     */
    private fun encodeState(state: Map<Capability, DeviceValue>?): String {
        if (state == null || state.isEmpty()) return "{}"
        val body = state.entries.joinToString(",") { (cap, value) ->
            val encoded = when (value) {
                is DeviceValue.Flag -> "[\"f\",${value.value}]"
                is DeviceValue.Count -> "[\"c\",${value.value}]"
                is DeviceValue.Measure -> "[\"m\",${value.value}]"
                is DeviceValue.Text -> "[\"t\",${escape(value.value)}]"
            }
            "\"${cap.name}\":$encoded"
        }
        return "{$body}"
    }

    private fun decodeState(raw: String): Map<Capability, DeviceValue> {
        val out = mutableMapOf<Capability, DeviceValue>()
        // "CAP":["tag",value]  — value may be a quoted string, so match both.
        val pattern = Regex("\"([A-Z_]+)\":\\[\"([fcmt])\",(\"(?:[^\"\\\\]|\\\\.)*\"|[^\\]]+)\\]")
        for (m in pattern.findAll(raw)) {
            val cap = Capability.fromRaw(m.groupValues[1]) ?: continue
            val tag = m.groupValues[2]
            val rawValue = m.groupValues[3]
            val value: DeviceValue? = when (tag) {
                "f" -> DeviceValue.Flag(rawValue.trim().toBoolean())
                "c" -> rawValue.trim().toIntOrNull()?.let { DeviceValue.Count(it) }
                "m" -> rawValue.trim().toDoubleOrNull()?.let { DeviceValue.Measure(it) }
                "t" -> runCatching {
                    DeviceValue.Text(unescape(rawValue))
                }.getOrNull()
                else -> null
            }
            if (value != null) out[cap] = value
        }
        return out
    }

    /**
     * `{"<deviceId>":{...state...},...}`
     *
     * A scene is a map of device id to that device's capability state, so it is
     * the state encoding nested one level. Reuses both halves rather than
     * inventing a third format.
     */
    private fun encodeScene(states: Map<String, Map<Capability, DeviceValue>>): String {
        if (states.isEmpty()) return "{}"
        val body = states.entries.joinToString(",") { (deviceId, caps) ->
            "${escape(deviceId)}:${encodeState(caps)}"
        }
        return "{$body}"
    }

    private fun decodeScene(raw: String): Map<String, Map<Capability, DeviceValue>> {
        val out = mutableMapOf<String, Map<Capability, DeviceValue>>()
        // Each device entry is "id":{...}; find the quoted id then take the brace
        // range that follows it, counting depth so nested maps cannot confuse it.
        var i = 0
        while (i < raw.length) {
            val keyStart = raw.indexOf('"', i)
            if (keyStart < 0) break
            val keyEnd = run {
                var j = keyStart + 1
                while (j < raw.length && !(raw[j] == '"' && raw[j - 1] != '\\')) j++
                j
            }
            if (keyEnd >= raw.length) break
            val id = runCatching {
                unescape(raw.substring(keyStart, keyEnd + 1))
            }.getOrNull()
            val open = raw.indexOf('{', keyEnd)
            if (id == null || open < 0) { i = keyEnd + 1; continue }
            var depth = 0
            var close = open
            while (close < raw.length) {
                when (raw[close]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) break }
                }
                close++
            }
            if (close >= raw.length) break
            out[id] = decodeState(raw.substring(open, close + 1))
            i = close + 1
        }
        return out
    }

    /** `{"capability":"POWER"}` style map of plain strings, used for raw protocol data. */
    private fun encodeStringMap(map: Map<String, String>): String {
        if (map.isEmpty()) return "{}"
        val body = map.entries.joinToString(",") { (k, v) ->
            "${escape(k)}:${escape(v)}"
        }
        return "{$body}"
    }

    private fun decodeStringMap(raw: String): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val pattern = Regex("\"((?:[^\"\\\\]|\\\\.)*)\":\"((?:[^\"\\\\]|\\\\.)*)\"")
        for (m in pattern.findAll(raw)) {
            val k = runCatching { unescape("\"${m.groupValues[1]}\"") }.getOrNull() ?: continue
            val v = runCatching { unescape("\"${m.groupValues[2]}\"") }.getOrNull() ?: continue
            out[k] = v
        }
        return out
    }

    // ------------------------------------------------------------------
    // Reading
    // ------------------------------------------------------------------

    suspend fun allDevices(): List<Device> = dao.getAllDevices().map { it.toModel() }

    fun allDevicesFlow() = dao.getAllDevicesFlow()

    suspend fun device(id: String): Device? = dao.getDevice(id)?.toModel()

    suspend fun rooms(): List<String> = dao.getRooms()

    /**
     * Resolve a device by name, id, or a fragment of either.
     *
     * Case-insensitive, and partial, because a person says "the kitchen light".
     * Returns every match plus whether the answer is unambiguous, so a caller can
     * refuse to guess rather than picking the first.
     */
    suspend fun resolve(query: String, room: String? = null): Resolution {
        val devices = allDevices()
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return Resolution(emptyList(), false)

        val inRoom = if (room == null) devices else devices.filter { it.room?.equals(room, ignoreCase = true) == true }

        // Exact id, then exact name, then room-qualified name, then substring.
        inRoom.firstOrNull { it.id.equals(query.trim(), ignoreCase = true) }?.let {
            return Resolution(listOf(it), true)
        }
        inRoom.firstOrNull { it.name.equals(query.trim(), ignoreCase = true) }?.let {
            return Resolution(listOf(it), true)
        }

        // "kitchen light" against a device named "Light" in room "Kitchen".
        val roomQualified = inRoom.filter { d ->
            val r = d.room?.lowercase() ?: return@filter false
            needle.contains(r) && needle.contains(d.name.lowercase())
        }
        if (roomQualified.size == 1) return Resolution(roomQualified, true)

        val partial = inRoom.filter {
            it.name.lowercase().contains(needle) || (it.room?.lowercase()?.contains(needle) == true)
        }
        return Resolution(partial, partial.size == 1)
    }

    data class Resolution(val matches: List<Device>, val unambiguous: Boolean)

    // ------------------------------------------------------------------
    // Discovery and placement
    // ------------------------------------------------------------------

    /**
     * Ask every available transport what it can see, and merge the results.
     *
     * A device already in the store keeps its NAME and ROOM: placement is a user
     * decision and discovery knows nothing about it. Only the protocol-shaped
     * fields are refreshed.
     */
    suspend fun discoverAll(): DiscoveryReport {
        val found = mutableListOf<Device>()
        val failures = mutableListOf<String>()
        val now = System.currentTimeMillis()

        for (transport in registry.all()) {
            val descriptor = transport.describe()
            if (!descriptor.available) {
                failures.add("${descriptor.displayName}: ${descriptor.unavailableReason ?: "not available"}")
                continue
            }
            try {
                val devices = transport.discover()
                found.addAll(devices)
            } catch (e: Exception) {
                failures.add("${descriptor.displayName}: discovery failed (${e.message?.take(120)})")
                Log.w(TAG, "Fabric: discovery failed for ${transport.transport}: ${e.message}")
            }
        }

        // Merge: existing placement wins over what discovery reports.
        val existing = dao.getAllDevices().associateBy { it.id }
        val rows = found.map { device ->
            val prior = existing[device.id]
            device.toEntity(
                keepName = prior?.name,
                keepRoom = prior?.room,
                createdAt = prior?.createdAt ?: now,
                now = now,
            )
        }
        if (rows.isNotEmpty()) dao.upsertDevices(rows)

        Log.d(TAG, "Fabric: discovery found ${found.size} devices, ${failures.size} transports unavailable")
        return DiscoveryReport(devices = found, failures = failures)
    }

    data class DiscoveryReport(val devices: List<Device>, val failures: List<String>)

    /** Name and room: the user's decision, and it survives discovery. */
    suspend fun place(id: String, name: String, room: String?): Boolean {
        val existing = dao.getDevice(id) ?: return false
        dao.placeDevice(id, name.trim().ifBlank { existing.name }, room?.trim()?.ifBlank { null }, System.currentTimeMillis())
        return true
    }

    // ------------------------------------------------------------------
    // Control, with capability enforcement and read-back
    // ------------------------------------------------------------------

    /**
     * The outcome of a control action.
     *
     * [confirmed] is only true when a read-back observed the value. Everything
     * else carries the reason, because a caller must be able to tell "it worked"
     * from "it was refused" from "it was sent and ignored".
     */
    data class ControlOutcome(
        val confirmed: Boolean,
        val deviceId: String,
        val capability: Capability,
        val wrote: DeviceValue?,
        val observed: DeviceValue?,
        val reason: String?,
    )

    /**
     * Set one capability on one device.
     *
     * The sequence is deliberate: resolve the device, check the capability, check
     * the device is reachable, write, read back, compare. Any step failing
     * returns an outcome that says exactly which one and why.
     */
    suspend fun control(deviceId: String, capability: Capability, value: DeviceValue): ControlOutcome {
        val device = dao.getDevice(deviceId)?.toModel()
            ?: return ControlOutcome(
                false, deviceId, capability, value, null,
                "No device with id '$deviceId' is known to the fabric.",
            )

        // RULE 1: capability enforcement. This is the refusal that stops the tool
        // doing nothing while reporting success.
        if (capability !in device.capabilities) {
            val has = device.capabilities.sortedBy { it.name }.joinToString(", ") { it.name }
            return ControlOutcome(
                false, deviceId, capability, value, null,
                "${device.name} does not report ${capability.name}. It reports: " +
                    if (has.isEmpty()) "no capabilities" else has,
            )
        }
        if (capability in Capability.READ_ONLY) {
            return ControlOutcome(
                false, deviceId, capability, value, null,
                "${capability.name} is a reading, not a command. ${device.name} reports it but does not accept it.",
            )
        }
        if (!device.available) {
            return ControlOutcome(
                false, deviceId, capability, value, null,
                "${device.name} is not reachable right now, so nothing was sent.",
            )
        }

        val transport = registry.forTransport(device.transport)
            ?: return ControlOutcome(
                false, deviceId, capability, value, null,
                "No adapter is installed for transport ${device.transport.name}, so ${device.name} cannot be reached.",
            )

        // Write, then read back. The transport's own read-back is checked again
        // here, so a transport cannot certify its own claim.
        val write = try {
            transport.write(device.id, capability, value)
        } catch (e: Exception) {
            return ControlOutcome(
                false, deviceId, capability, value, null,
                "The ${device.transport.name} adapter threw while writing: ${e.message?.take(160)}",
            )
        }

        return when (write) {
            is TransportWrite.Failed ->
                ControlOutcome(false, deviceId, capability, value, null, write.reason)

            is TransportWrite.NotObserved ->
                ControlOutcome(
                    false, deviceId, capability, value, write.stillReports,
                    "${device.name} accepted the ${capability.name} command but does not report " +
                        "the change: it still shows ${describe(write.stillReports)}.",
                )

            is TransportWrite.Confirmed -> {
                // Independent verification: read the device ourselves.
                val readBack = try {
                    transport.read(device.id)
                } catch (_: Exception) {
                    null
                }
                val observed = readBack?.get(capability)
                val matches = observed != null && observed.asComparable() == write.observed.asComparable()

                if (observed != null) {
                    // Persist what the device reports, which is the truth.
                    dao.updateState(device.id, encodeState(readBack), true, System.currentTimeMillis())
                }

                if (matches) {
                    ControlOutcome(true, deviceId, capability, value, observed, null)
                } else {
                    ControlOutcome(
                        false, deviceId, capability, value, observed,
                        "${device.name} reported ${capability.name} as ${describe(observed)} " +
                            "when ${describe(value)} was written, so the change was not confirmed. " +
                            if (observed == null) "The device could not be read back at all." else "",
                    )
                }
            }
        }
    }

    /** Read one device's live state, updating the store with what it reported. */
    suspend fun readState(deviceId: String): Map<Capability, DeviceValue>? {
        val device = dao.getDevice(deviceId)?.toModel() ?: return null
        val transport = registry.forTransport(device.transport) ?: return null
        val read = try {
            transport.read(device.id)
        } catch (_: Exception) {
            null
        } ?: return null
        dao.updateState(device.id, encodeState(read), true, System.currentTimeMillis())
        return read
    }

    // ------------------------------------------------------------------
    // Scenes
    // ------------------------------------------------------------------

    /**
     * Snapshot the current state of the named devices into a scene.
     *
     * Applied atomically in the sense that matters to a user: every device is
     * attempted, and the report says which ones landed. A scene that half applied
     * and claimed success is the defect this project keeps finding, so the
     * outcome names the failures individually.
     */
    suspend fun saveScene(name: String, deviceIds: List<String>): Scene? {
        val states = mutableMapOf<String, Map<Capability, DeviceValue>>()
        for (id in deviceIds) {
            val device = dao.getDevice(id)?.toModel() ?: continue
            val live = readState(id) ?: device.state
            // Only writable capabilities are worth capturing: restoring a reading
            // is meaningless and would be refused on apply.
            states[id] = live.filterKeys { it.isWritable }
        }
        if (states.isEmpty()) return null

        val existing = dao.getSceneByName(name)
        val scene = FabricSceneEntity(
            id = existing?.id ?: java.util.UUID.randomUUID().toString(),
            name = name.trim(),
            statesJson = encodeScene(states),
            createdAt = existing?.createdAt ?: System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis(),
        )
        dao.upsertScene(scene)
        return Scene(scene.id, scene.name, states, scene.createdAt)
    }

    data class SceneOutcome(
        val name: String,
        val applied: Int,
        val failed: Int,
        val failures: List<String>,
    ) {
        val complete: Boolean get() = failed == 0
    }

    suspend fun applyScene(name: String): SceneOutcome? {
        val row = dao.getSceneByName(name) ?: return null
        val states = decodeScene(row.statesJson)
        var applied = 0
        val failures = mutableListOf<String>()
        for ((deviceId, caps) in states) {
            for ((capability, value) in caps) {
                val outcome = control(deviceId, capability, value)
                if (outcome.confirmed) {
                    applied++
                } else {
                    failures.add("${outcome.reason ?: "unknown failure"}")
                }
            }
        }
        return SceneOutcome(row.name, applied, failures.size, failures)
    }

    suspend fun scenes(): List<Scene> = dao.getAllScenes().map {
        Scene(it.id, it.name, emptyMap(), it.createdAt)
    }

    suspend fun deleteScene(name: String) = dao.deleteScene(name)

    // ------------------------------------------------------------------
    // Commissioning
    // ------------------------------------------------------------------

    /**
     * Add one device through a transport.
     *
     * The fabric owns the flow so refusal and placement are identical across
     * protocols: it asks the named transport for the device, assigns name and
     * room, and stores it. A transport that cannot commission says why, and that
     * reason is returned verbatim rather than replaced with a generic one.
     */
    data class CommissionOutcome(val success: Boolean, val device: Device?, val error: String?)

    suspend fun commission(
        transportName: String,
        identifier: String,
        name: String,
        room: String?,
    ): CommissionOutcome {
        val transport = Transport.fromRaw(transportName)
        if (transport == Transport.MANUAL) {
            // A manual device is one the fabric holds with no adapter behind it.
            // Useful for recording something it cannot reach yet, and honest
            // because control will refuse with "no adapter installed".
            val now = System.currentTimeMillis()
            val device = Device(
                id = "manual:${identifier.lowercase().replace(Regex("[^a-z0-9]+"), "-")}",
                name = name,
                room = room,
                transport = Transport.MANUAL,
                deviceClass = DeviceClass.UNKNOWN,
                capabilities = emptySet(),
                state = emptyMap(),
                available = false,
                lastSeen = now,
            )
            dao.upsertDevice(device.toEntity(keepName = null, keepRoom = null, createdAt = now, now = now))
            return CommissionOutcome(true, device, null)
        }

        val adapter = registry.forTransport(transport)
            ?: return CommissionOutcome(
                false, null,
                "No ${transport.name} adapter is installed, so '$identifier' cannot be added. " +
                    "Installed: " + registry.all().joinToString(", ") { it.transport.name }.ifBlank { "none" },
            )
        val descriptor = adapter.describe()
        if (!descriptor.available) {
            return CommissionOutcome(
                false, null,
                "${descriptor.displayName} is not reachable: ${descriptor.unavailableReason ?: "no reason given"}",
            )
        }

        val discovered = try {
            adapter.discover()
        } catch (e: Exception) {
            return CommissionOutcome(false, null, "Discovery failed: ${e.message?.take(160)}")
        }
        val match = discovered.firstOrNull {
            it.id.equals(identifier, ignoreCase = true) || it.raw.values.any { v -> v.equals(identifier, ignoreCase = true) }
        } ?: return CommissionOutcome(
            false, null,
            "${descriptor.displayName} saw ${discovered.size} devices but none matched '$identifier'.",
        )

        val now = System.currentTimeMillis()
        val placed = match.copy(name = name, room = room ?: match.room)
        dao.upsertDevice(placed.toEntity(keepName = null, keepRoom = null, createdAt = now, now = now))
        return CommissionOutcome(true, placed, null)
    }

    /** One line per transport that cannot be reached, for a devices list to carry. */
    fun transportProblems(): List<String> = registry.descriptors()
        .filter { !it.available }
        .map { "${it.displayName}: ${it.unavailableReason ?: "not available"}" }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    private fun describe(value: DeviceValue?): String = when (value) {
        null -> "nothing"
        is DeviceValue.Flag -> if (value.value) "on" else "off"
        is DeviceValue.Count -> value.value.toString()
        is DeviceValue.Measure -> value.value.toString()
        is DeviceValue.Text -> "\"${value.value}\""
    }

    private fun FabricDeviceEntity.toModel(): Device = Device(
        id = id,
        name = name,
        room = room,
        transport = Transport.fromRaw(transport),
        deviceClass = DeviceClass.fromRaw(deviceClass),
        capabilities = runCatching {
            decodeCapabilities(capabilitiesJson)
        }.getOrDefault(emptySet()),
        state = runCatching {
            decodeState(stateJson)
        }.getOrDefault(emptyMap()),
        available = available,
        lastSeen = lastSeen,
        raw = runCatching {
            decodeStringMap(rawJson)
        }.getOrDefault(emptyMap()),
    )

    private fun Device.toEntity(keepName: String?, keepRoom: String?, createdAt: Long, now: Long): FabricDeviceEntity =
        FabricDeviceEntity(
            id = id,
            name = keepName ?: name,
            room = keepRoom ?: room,
            transport = transport.name,
            deviceClass = deviceClass.name,
            capabilitiesJson = encodeCapabilities(capabilities.sortedBy { it.name }),
            stateJson = encodeState(state),
            rawJson = encodeStringMap(raw),
            available = available,
            lastSeen = lastSeen,
            createdAt = createdAt,
            updatedAt = now,
        )

}
