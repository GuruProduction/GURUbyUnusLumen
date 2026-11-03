// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.ToolProgressEvent
import com.unuslumen.app.data.tools.registry.ToolProgressSink
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.senses.emitPayload
import com.unuslumen.app.database.entity.NetworkDeviceEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The tools that make the network map something GURU uses rather than something GURU has.
 *
 * WHY THIS IS A SEPARATE TOOL SET FROM netEverything. `netEverything` answers a question at a
 * moment. These answer questions across time: what have I seen before, what have I named, when did
 * this appear, has it moved. Splitting them is what lets a sweep be cheap and a recall be instant,
 * rather than a recall having to rescan the network to answer.
 *
 * THE SHAPE OF THE FOUR, and why each earns a tool rather than being a parameter:
 *
 *  - `netMap` looks at what is already known, without touching the network. That separation
 *    matters: a person asking "what's on my network" wants an answer now, and a person asking
 *    "what did you see last week" must not cause a scan.
 *  - `netLook` sweeps AND saves, which is the one a skill calls.
 *  - `netName` is the owner's own words for their own device, which no sweep may overwrite.
 *  - `netHistory` is one device across time, which is the whole reason the sighting table exists.
 */

@Serializable
data class NetMapResult(
    val success: Boolean,
    val count: Int = 0,
    val devices: List<MapDeviceView> = emptyList(),
    /** When the map was last refreshed by a sweep, so its age is visible. */
    val lastScanAt: Long? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class MapDeviceView(
    val id: String,
    val name: String,
    val address: String,
    val kind: String,
    val vendor: String? = null,
    val model: String? = null,
    val mac: String? = null,
    val room: String? = null,
    val openPorts: List<Int> = emptyList(),
    val services: List<String> = emptyList(),
    /** How many sweeps have seen it, which is what makes a device a fixture rather than a guest. */
    val timesSeen: Int = 0,
    /** Whether this device is known well enough to survive its address changing. */
    val identity: String = "address",
    val firstSeen: Long = 0,
    val lastSeen: Long = 0,
)

@Serializable
data class NetLookResult(
    val success: Boolean,
    val deviceCount: Int = 0,
    val newDevices: Int = 0,
    val knownDevices: Int = 0,
    /** How many devices appeared on a different address than last time. */
    val movedAddress: Int = 0,
    val subnet: String? = null,
    val devices: List<MapDeviceView> = emptyList(),
    val problems: List<String> = emptyList(),
    val error: String? = null,
) : ToolResultData

@Serializable
data class NetHistoryResult(
    val success: Boolean,
    val device: String = "",
    val sightingCount: Int = 0,
    /** Every address this device has had, newest first, so a move is visible. */
    val addresses: List<String> = emptyList(),
    val sightings: List<SightingView> = emptyList(),
    val error: String? = null,
) : ToolResultData

@Serializable
data class SightingView(
    val address: String,
    val seenAt: Long,
    val kind: String,
    val openPorts: List<Int> = emptyList(),
    val evidence: List<String> = emptyList(),
)

@Serializable
data class NetNameResult(
    val success: Boolean,
    val device: String = "",
    val name: String? = null,
    val room: String? = null,
    val error: String? = null,
) : ToolResultData

object NetworkMapToolDefinitions : ToolSetRegistration {

    const val NET_MAP = "netMap"
    const val NET_LOOK = "netLook"
    const val NET_NAME = "netName"
    const val NET_HISTORY = "netHistory"

    override val definitions = listOf(
        ToolDefinition(
            name = NET_MAP,
            description = "Show the map of every device GURU has ever seen on this wifi network, " +
                "without touching the network. This is what to call when someone asks what is on " +
                "their network, what GURU can see, what is around them, or what devices they have: " +
                "it answers from what is already known and is instant. Each device comes back with " +
                "what it is, who made it, its address, the ports it exposes, what the owner named " +
                "it, and how reliably it is identified. Use netLook instead when the answer must be " +
                "current rather than remembered.",
            category = "network",
            parameters = listOf(
                ToolParameter(
                    "query", ToolParameterType.String, false,
                    "Optional text to filter by, such as a name, room, make or address. Omit for everything.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = NET_LOOK,
            description = "Sweep the whole wifi network now, save everything it finds to the map, and " +
                "report what is new. Asks over SNMP, NetBIOS, reverse DNS through the router and a " +
                "connect on every port a device actually opens, all at once. This is the tool for " +
                "'what can you see', 'what's around us', 'scan my network' or 'find everything'. It " +
                "returns every device, says how many were already known, what is new and what has " +
                "moved to a different address, and names what it could not reach rather than " +
                "reporting an empty network.",
            category = "network",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = NET_NAME,
            description = "Give a device on the map a name of the owner's choosing, and optionally " +
                "put it in a room. Naming is the owner's own record of their own house, so no " +
                "future scan ever overwrites it. Use this after netMap or netLook when a device has " +
                "an unhelpful name such as a bare address or a serial number.",
            category = "network",
            parameters = listOf(
                ToolParameter(
                    "device", ToolParameterType.String, true,
                    "The device to name: its address, its current name, or the id netMap reported.",
                ),
                ToolParameter(
                    "name", ToolParameterType.String, false,
                    "What to call it, such as 'kitchen printer'. Omit to clear the name.",
                ),
                ToolParameter(
                    "room", ToolParameterType.String, false,
                    "A room, such as 'kitchen' or 'front room'. Omit to leave it where it is.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = NET_HISTORY,
            description = "Show one device across time: every time it was seen, what address it had, " +
                "what ports it exposed and what it said about itself. This is how a question about " +
                "change is answered, such as when something first appeared, whether a device has " +
                "moved, or what it looked like last week. Use netMap first to find the device.",
            category = "network",
            parameters = listOf(
                ToolParameter(
                    "device", ToolParameterType.String, true,
                    "The device: its address, its name, or the id netMap reported.",
                ),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = NetworkMapExecutor::class
    override fun extractorClass(): KClass<out com.unuslumen.app.data.tools.registry.ToolResultExtractor>? = null
}

/**
 * The map's tool executor.
 *
 * THE COLLABORATORS ARE INJECTED, and the single-Context constructor beside them exists because
 * every domain executor in this app is built that way by the honesty contract test, which fails a
 * domain whose executor cannot be constructed from a Context. That test is right to exist: a
 * registration nobody can build throws only when a person calls the tool.
 *
 * NO KOIN ANNOTATION HERE. The Koin compiler scans `com.unuslumen.app.data` and would emit a second
 * binding for this class beside the one in [NetworkDomainModule]. With `allowOverride(true)` the
 * duplicate resolves silently, so the app would run while the wiring nobody reads wins, which is
 * the sort of fault that only shows up when one of the two paths is changed.
 */
class NetworkMapExecutor(
    private val store: NetworkMapStore,
    private val sweep: FullNetworkSweep,
) : ToolExecutor {

    /**
     * The single-Context constructor the domain honesty contract requires.
     *
     * It mirrors [NetworkToolExecutor], [XboxToolExecutor] and [FabricToolExecutor] rather than
     * opening its own Room connection. A second `Room.databaseBuilder` here would build a second
     * connection to the same file with no migrations attached, so the first launch after an upgrade
     * would crash on a schema mismatch instead of reading the map it already had. The DAO is
     * resolved through [KoinNetworkVisionDao], which returns the app's own DAO when Koin is started
     * and an object that refuses loudly when it is not.
     */
    constructor(context: Context) : this(
        NetworkMapStore(KoinNetworkVisionDao.resolve(context)),
        FullNetworkSweep(),
    )

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            NetworkMapToolDefinitions.NET_MAP -> map(args)
            NetworkMapToolDefinitions.NET_LOOK -> look(ToolProgressSink.NONE)
            NetworkMapToolDefinitions.NET_NAME -> name(args)
            NetworkMapToolDefinitions.NET_HISTORY -> history(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    /**
     * A sweep reports what it is finding while it runs, because it is the one tool here that takes
     * real time.
     *
     * WHY ONLY `netLook` OVERRIDES THIS. The other three answer from storage in milliseconds and
     * have nothing honest to report mid-flight. This one walks 254 addresses, and a phone showing a
     * spinner with no words for twenty seconds reads as a hang, which is how a working tool gets
     * closed before it finishes. Every stage the sweep announces and every device it identifies is
     * forwarded to the sink, so the work is visible as it happens.
     */
    override suspend fun executeWithProgress(
        toolName: String,
        args: Map<String, Any?>,
        progress: ToolProgressSink,
    ): ToolExecutionResult = when (toolName) {
        NetworkMapToolDefinitions.NET_LOOK -> look(progress)
        else -> execute(toolName, args)
    }

    /** Report what is already known, without touching the network. */
    private suspend fun map(args: Map<String, Any?>): ToolExecutionResult {
        val query = (args["query"] as? String)?.trim()?.ifBlank { null }
        val devices = if (query == null) store.devices() else store.search(query)
        val latest = store.scans(1).firstOrNull()

        val r = NetMapResult(
            success = devices.isNotEmpty(),
            count = devices.size,
            devices = devices.map { it.toView() },
            lastScanAt = latest?.finishedAt?.takeIf { it > 0 },
            error = if (devices.isEmpty()) {
                if (query != null) {
                    "Nothing on the map matches \"$query\". Run netLook to sweep the network first."
                } else {
                    "The map is empty. Run netLook to sweep the network and build one."
                }
            } else {
                null
            },
        )
        return emitPayload(json, json.encodeToString(NetMapResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Sweep, save, and report what changed.
     *
     * [progress] CARRIES BOTH KINDS OF NEWS THE SWEEP HAS: a stage sentence as each pass starts, and
     * one line per device the moment it is identified. A device is reported as it is found rather
     * than collected and dumped at the end, because a list that appears all at once twenty seconds
     * later is indistinguishable from a hang while it is running.
     */
    private suspend fun look(progress: ToolProgressSink): ToolExecutionResult {
        var found = 0
        val report = sweep.sweep(
            onFound = { device ->
                found++
                progress.report(
                    ToolProgressEvent(
                        toolName = NetworkMapToolDefinitions.NET_LOOK,
                        message = "found ${device.displayName} at ${device.address}",
                        payload = mapOf(
                            "address" to device.address,
                            "name" to device.displayName,
                            "kind" to device.deviceClass.name,
                        ),
                    ),
                )
            },
            onStage = { stage ->
                progress.report(
                    ToolProgressEvent(
                        toolName = NetworkMapToolDefinitions.NET_LOOK,
                        message = stage,
                    ),
                )
            },
        )
        progress.report(
            ToolProgressEvent(
                toolName = NetworkMapToolDefinitions.NET_LOOK,
                message = "saving $found device(s) to the map",
            ),
        )

        // The sweep is saved before anything is reported, because a map that only exists in the
        // answer is a map that is gone by the next question.
        val recorded = store.recordSweep(report, trigger = "tool")

        // WHAT THIS SWEEP SAW, NOT EVERYTHING EVER STORED. The first version answered from
        // `store.devices()`, which is the whole map across every network the phone has been on, so a
        // sweep of a café would list his television at home as though it were in the room. A person
        // asking "what can you see" wants this network now; the map is what `netMap` is for, and the
        // two answers must not be the same list.
        //
        // ONE READ OF THE MAP, KEYED BY ADDRESS, rather than a query per device. The stored row
        // carries the owner's name and room, which the sweep itself never learns, so each device is
        // matched back to its row. A device that somehow has no row still appears, built from the
        // sighting alone, because dropping a device that plainly answered would be the empty-network
        // lie this whole tool exists to avoid.
        val storedByAddress = store.devices().associateBy { it.lastAddress }
        val views = report.devices.map { device ->
            storedByAddress[device.address]?.toView() ?: MapDeviceView(
                id = device.address,
                name = device.displayName,
                address = device.address,
                kind = device.deviceClass.name,
                vendor = device.vendor,
                model = device.model,
                openPorts = device.openPorts,
                services = device.services,
            )
        }

        val r = NetLookResult(
            success = report.devices.isNotEmpty(),
            deviceCount = report.devices.size,
            newDevices = recorded.inserted,
            knownDevices = recorded.updated,
            movedAddress = recorded.movedAddress,
            subnet = report.subnet,
            devices = views,
            problems = report.problems,
            error = if (report.devices.isEmpty()) {
                report.problems.firstOrNull() ?: "Nothing answered on this network."
            } else {
                null
            },
        )
        return emitPayload(json, json.encodeToString(NetLookResult.serializer(), r), r, r.success, r.error)
    }

    /** The owner's own name for their own device. */
    private suspend fun name(args: Map<String, Any?>): ToolExecutionResult {
        val query = (args["device"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'device'")
        val newName = (args["name"] as? String)?.trim()?.ifBlank { null }
        val room = (args["room"] as? String)?.trim()?.ifBlank { null }
        if (newName == null && room == null) {
            return ToolExecutionResult.error("Give a name, a room, or both.")
        }

        val matches = resolve(query)
        if (matches.isEmpty()) {
            return failure(query, "No device on the map matches \"$query\". Run netMap to see what is there.")
        }
        if (matches.size > 1) {
            return failure(
                query,
                "\"$query\" matches ${matches.size} devices: " +
                    matches.joinToString(", ") { it.displayName } + ". Name one exactly.",
            )
        }

        val device = matches.first()
        if (newName != null) store.rename(device.id, newName)
        if (room != null) store.place(device.id, room)

        val updated = store.devices().firstOrNull { it.id == device.id }
        val r = NetNameResult(
            success = true,
            device = device.id,
            name = updated?.name ?: newName,
            room = updated?.room ?: room,
        )
        return emitPayload(json, json.encodeToString(NetNameResult.serializer(), r), r, true, null)
    }

    /** One device across time. */
    private suspend fun history(args: Map<String, Any?>): ToolExecutionResult {
        val query = (args["device"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'device'")

        val matches = resolve(query)
        if (matches.isEmpty()) {
            return historyFailure(query, "No device on the map matches \"$query\".")
        }
        if (matches.size > 1) {
            return historyFailure(
                query,
                "\"$query\" matches ${matches.size} devices: " +
                    matches.joinToString(", ") { it.displayName } + ". Name one exactly.",
            )
        }

        val device = matches.first()
        val sightings = store.history(device.id)
        val addresses = sightings.map { it.address }.distinct()

        val r = NetHistoryResult(
            success = sightings.isNotEmpty(),
            device = device.displayName,
            sightingCount = sightings.size,
            addresses = addresses,
            sightings = sightings.map { sighting ->
                SightingView(
                    address = sighting.address,
                    seenAt = sighting.seenAt,
                    kind = sighting.deviceClass,
                    openPorts = decodeInts(sighting.openPortsJson),
                    evidence = decodeStrings(sighting.evidenceJson),
                )
            },
            error = if (sightings.isEmpty()) "No sightings recorded for ${device.displayName}." else null,
        )
        return emitPayload(json, json.encodeToString(NetHistoryResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Resolve a spoken device to exactly one row, refusing to guess between two.
     *
     * THE ID IS TRIED FIRST, AS AN ADDITION TO THE TEXT SEARCH RATHER THAN A REPLACEMENT. `netMap`
     * reports each device with an id, and the docstring of both tools tells the model to pass it
     * back, but the search query matched only name, observed name, vendor, model, address and MAC.
     * So the one identifier the tool itself handed out was the one thing it could not look up, and
     * a model following the instructions reached "no device matches" every time.
     *
     * The text search still runs, because a person says "the printer" rather than
     * "mac:94:b3:f7:90:92:81".
     */
    private suspend fun resolve(query: String): List<NetworkDeviceEntity> {
        val byId = store.device(query)?.let { listOf(it) }.orEmpty()
        if (byId.isNotEmpty()) return byId
        return store.search(query)
    }

    private fun failure(query: String, reason: String): ToolExecutionResult {
        val r = NetNameResult(success = false, device = query, error = reason)
        return emitPayload(json, json.encodeToString(NetNameResult.serializer(), r), r, false, reason)
    }

    private fun historyFailure(query: String, reason: String): ToolExecutionResult {
        val r = NetHistoryResult(success = false, device = query, error = reason)
        return emitPayload(json, json.encodeToString(NetHistoryResult.serializer(), r), r, false, reason)
    }

    private fun decodeInts(raw: String): List<Int> = runCatching {
        json.parseToJsonElement(raw).let { element ->
            (element as? kotlinx.serialization.json.JsonArray)?.mapNotNull {
                it.toString().trim('"').toIntOrNull()
            } ?: emptyList()
        }
    }.getOrDefault(emptyList())

    private fun decodeStrings(raw: String): List<String> = runCatching {
        json.parseToJsonElement(raw).let { element ->
            (element as? kotlinx.serialization.json.JsonArray)?.map { it.toString().trim('"') }
                ?: emptyList()
        }
    }.getOrDefault(emptyList())

    private fun NetworkDeviceEntity.toView(): MapDeviceView = MapDeviceView(
        id = id,
        name = displayName,
        address = lastAddress,
        kind = deviceClass,
        vendor = vendor,
        model = model,
        mac = mac,
        room = room,
        openPorts = decodeInts(openPortsJson),
        services = decodeStrings(servicesJson),
        timesSeen = sightingCount,
        identity = identityKind,
        firstSeen = firstSeen,
        lastSeen = lastSeen,
    )
}

// WIRING LIVES IN NetworkDomainModule, not here.
//
// The map is part of the network domain rather than a tenth domain of its own, because a person
// asking about their network does not distinguish "what is there now" from "what I have seen
// before". Two domains would mean two entries in the batch registry and two chances for one to be
// forgotten, which is exactly how the smart home fabric shipped without its tools existing.
