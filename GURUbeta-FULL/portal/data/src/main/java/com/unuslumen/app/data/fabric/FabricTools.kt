// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * The five generic tools. Issue #182.
 *
 * Five tools for every device ever made, because the model asks for a thing and
 * the fabric routes it. Adding a protocol adds no tools.
 */
object FabricToolDefinitions : ToolSetRegistration {

    const val SMART_DEVICES = "smartDevices"
    const val SMART_CONTROL = "smartControl"
    const val SMART_STATE = "smartState"
    const val SMART_SCENE = "smartScene"
    const val SMART_COMMISSION = "smartCommission"

    override val definitions = listOf(
        ToolDefinition(
            name = SMART_DEVICES,
            description = "List every smart home device the fabric knows, across every transport. " +
                "Filter by room, class or capability. Returns name, room, class, capabilities, " +
                "state and reachability for each. Also reports which transports are unreachable " +
                "and why, so an empty list is never mistaken for an empty house.",
            category = "smart_home",
            parameters = listOf(
                ToolParameter("room", ToolParameterType.String, false, "Only devices in this room"),
                ToolParameter("deviceClass", ToolParameterType.String, false, "Only devices of this class, e.g. LIGHT"),
                ToolParameter("capability", ToolParameterType.String, false, "Only devices reporting this capability, e.g. BRIGHTNESS"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SMART_CONTROL,
            description = "Act on one device by id or name. Refuses a capability the device does not " +
                "report, naming what it does report. Every command is read back and the result " +
                "says whether the change was OBSERVED, refused, or sent and ignored.",
            category = "smart_home",
            parameters = listOf(
                ToolParameter("device", ToolParameterType.String, true, "Device name or id, e.g. 'kitchen light'"),
                ToolParameter("capability", ToolParameterType.String, true, "Capability to set, e.g. POWER, BRIGHTNESS"),
                ToolParameter("value", ToolParameterType.String, true, "Value: true/false, a number, or text"),
                ToolParameter("room", ToolParameterType.String, false, "Disambiguate by room"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SMART_STATE,
            description = "Read back current state. Pass a device, or omit it for every device. " +
                "This is what confirms a command landed: it reads the device, it does not repeat " +
                "what the fabric last wrote.",
            category = "smart_home",
            parameters = listOf(
                ToolParameter("device", ToolParameterType.String, false, "Device name or id. Omit for all devices."),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SMART_SCENE,
            description = "Save a named scene from the current state of devices, or apply a saved " +
                "scene. Applying reports every device that failed and why; a scene that half " +
                "applied never reports success.",
            category = "smart_home",
            parameters = listOf(
                ToolParameter("action", ToolParameterType.String, true, "save, apply, list or delete"),
                ToolParameter("name", ToolParameterType.String, false, "Scene name, e.g. 'evening'"),
                ToolParameter("devices", ToolParameterType.String, false, "For save: comma-separated device names or ids"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SMART_COMMISSION,
            description = "Add a device from any transport: a Matter pairing code, a Home Assistant " +
                "entity, an MQTT topic, or a manual entry. Returns the device the fabric ended up " +
                "with, or the reason it could not be added.",
            category = "smart_home",
            parameters = listOf(
                ToolParameter("transport", ToolParameterType.String, true, "MATTER, HA, MQTT, ZIGBEE, ZWAVE, IR, HUE, SONOS or MANUAL"),
                ToolParameter("identifier", ToolParameterType.String, true, "Pairing code, entity id, topic, or a name for manual"),
                ToolParameter("name", ToolParameterType.String, false, "Friendly name"),
                ToolParameter("room", ToolParameterType.String, false, "Room to place it in"),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = FabricToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

// ---------------------------------------------------------------------------
// Results
// ---------------------------------------------------------------------------

@Serializable
data class DeviceView(
    val id: String,
    val name: String,
    val room: String?,
    val transport: String,
    val deviceClass: String,
    val capabilities: List<String>,
    val state: Map<String, String>,
    val available: Boolean,
)

@Serializable
data class SmartDevicesResult(
    val devices: List<DeviceView>,
    val count: Int,
    /** One line per unreachable transport, so an empty list is explained. */
    val transportProblems: List<String>,
) : ToolResultData

@Serializable
data class SmartControlResult(
    val success: Boolean,
    val observed: Boolean,
    val device: String,
    val capability: String,
    val wrote: String?,
    val actual: String?,
    val error: String?,
) : ToolResultData

@Serializable
data class SmartStateResult(
    val devices: List<DeviceView>,
    val count: Int,
    val error: String? = null,
) : ToolResultData

@Serializable
data class SmartSceneResult(
    val success: Boolean,
    val scene: String?,
    val applied: Int,
    val failed: Int,
    val failures: List<String>,
    val error: String? = null,
) : ToolResultData

@Serializable
data class SmartCommissionResult(
    val success: Boolean,
    val device: DeviceView?,
    val error: String?,
) : ToolResultData

// ---------------------------------------------------------------------------
// Executor
// ---------------------------------------------------------------------------

class FabricToolExecutor(
    private val fabric: DeviceFabric,
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            FabricToolDefinitions.SMART_DEVICES -> listDevices(args)
            FabricToolDefinitions.SMART_CONTROL -> control(args)
            FabricToolDefinitions.SMART_STATE -> state(args)
            FabricToolDefinitions.SMART_SCENE -> scene(args)
            FabricToolDefinitions.SMART_COMMISSION -> commission(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun listDevices(args: Map<String, Any?>): ToolExecutionResult {
        val room = (args["room"] as? String)?.trim()?.ifBlank { null }
        val classFilter = (args["deviceClass"] as? String)?.trim()?.ifBlank { null }
        val capFilter = (args["capability"] as? String)?.trim()?.ifBlank { null }

        var devices = fabric.allDevices()
        if (room != null) devices = devices.filter { it.room?.equals(room, ignoreCase = true) == true }
        if (classFilter != null) {
            val wanted = DeviceClass.fromRaw(classFilter)
            devices = devices.filter { it.deviceClass == wanted }
        }
        if (capFilter != null) {
            val wanted = Capability.fromRaw(capFilter)
            devices = if (wanted == null) emptyList() else devices.filter { wanted in it.capabilities }
        }

        // Transport problems are why a list can be short, so they travel with it.
        // Without this an empty list reads as an empty house rather than as a
        // transport that could not be reached.
        val problems = fabric.transportProblems()

        val r = SmartDevicesResult(
            devices = devices.map { it.toView() },
            count = devices.size,
            transportProblems = problems,
        )
        return ToolExecutionResult.success(r, json.encodeToString(SmartDevicesResult.serializer(), r))
    }

    private suspend fun control(args: Map<String, Any?>): ToolExecutionResult {
        val deviceArg = args["device"] as? String
            ?: return ToolExecutionResult.error("Missing 'device'")
        val capArg = args["capability"] as? String
            ?: return ToolExecutionResult.error("Missing 'capability'")
        val rawValue = args["value"]
            ?: return ToolExecutionResult.error("Missing 'value'")
        val room = (args["room"] as? String)?.trim()?.ifBlank { null }

        val capability = Capability.fromRaw(capArg)
            ?: return ToolExecutionResult.error(
                "Unknown capability '$capArg'. Valid: " +
                    Capability.entries.joinToString(", ") { it.name },
            )

        val resolution = fabric.resolve(deviceArg, room)
        if (!resolution.unambiguous) {
            val names = resolution.matches.joinToString(", ") { "${it.name} (${it.id})" }
            val r = SmartControlResult(
                success = false, observed = false, device = deviceArg, capability = capability.name,
                wrote = null, actual = null,
                error = if (resolution.matches.isEmpty()) {
                    "No device matches '$deviceArg'. Use smartDevices to see what exists."
                } else {
                    "'$deviceArg' matches ${resolution.matches.size} devices: $names. Name one exactly or pass a room."
                },
            )
            return ToolExecutionResult.success(r, json.encodeToString(SmartControlResult.serializer(), r))
        }

        val device = resolution.matches.first()
        val value = parseValue(capability, rawValue)
            ?: return ToolExecutionResult.error(
                "'${rawValue}' is not a valid value for ${capability.name}.",
            )

        val outcome = fabric.control(device.id, capability, value)
        val r = SmartControlResult(
            success = outcome.confirmed,
            observed = outcome.observed != null,
            device = device.name,
            capability = capability.name,
            wrote = outcome.wrote?.display(),
            actual = outcome.observed?.display(),
            error = outcome.reason,
        )
        return ToolExecutionResult.success(r, json.encodeToString(SmartControlResult.serializer(), r))
    }

    private suspend fun state(args: Map<String, Any?>): ToolExecutionResult {
        val deviceArg = (args["device"] as? String)?.trim()?.ifBlank { null }
        if (deviceArg == null) {
            val devices = fabric.allDevices()
            val r = SmartStateResult(devices.map { it.toView() }, devices.size)
            return ToolExecutionResult.success(r, json.encodeToString(SmartStateResult.serializer(), r))
        }

        val resolution = fabric.resolve(deviceArg)
        if (!resolution.unambiguous) {
            val r = SmartStateResult(
                devices = emptyList(),
                count = 0,
                error = if (resolution.matches.isEmpty()) {
                    "No device matches '$deviceArg'."
                } else {
                    "'$deviceArg' matches ${resolution.matches.size} devices: " +
                        resolution.matches.joinToString(", ") { it.name }
                },
            )
            return ToolExecutionResult.success(r, json.encodeToString(SmartStateResult.serializer(), r))
        }

        val device = resolution.matches.first()
        // Read the device itself, not the stored copy: a state tool that repeats
        // the last write would confirm nothing.
        val live = fabric.readState(device.id)
        val merged = device.copy(
            state = live ?: device.state,
            available = live != null || device.available,
        )
        val r = SmartStateResult(listOf(merged.toView()), 1)
        return ToolExecutionResult.success(r, json.encodeToString(SmartStateResult.serializer(), r))
    }

    private suspend fun scene(args: Map<String, Any?>): ToolExecutionResult {
        val action = (args["action"] as? String)?.trim()?.lowercase()
            ?: return ToolExecutionResult.error("Missing 'action'")

        return when (action) {
            "save" -> {
                val name = (args["name"] as? String)?.trim()?.ifBlank { null }
                    ?: return ToolExecutionResult.error("Missing 'name' for a scene save")
                val deviceArg = (args["devices"] as? String)?.trim()?.ifBlank { null }
                    ?: return ToolExecutionResult.error("Missing 'devices' for a scene save")

                val ids = deviceArg.split(",").mapNotNull { token ->
                    val resolved = fabric.resolve(token.trim())
                    if (resolved.unambiguous) resolved.matches.first().id else null
                }
                if (ids.isEmpty()) {
                    val r = SmartSceneResult(
                        success = false, scene = name, applied = 0, failed = 0, failures = emptyList(),
                        error = "None of the devices named could be resolved, so no scene was saved.",
                    )
                    return ToolExecutionResult.success(r, json.encodeToString(SmartSceneResult.serializer(), r))
                }
                val saved = fabric.saveScene(name, ids)
                val r = if (saved == null) {
                    SmartSceneResult(false, name, 0, 0, emptyList(), "Nothing had any state to capture.")
                } else {
                    SmartSceneResult(true, saved.name, 0, 0, emptyList(), null)
                }
                ToolExecutionResult.success(r, json.encodeToString(SmartSceneResult.serializer(), r))
            }
            "apply" -> {
                val name = (args["name"] as? String)?.trim()?.ifBlank { null }
                    ?: return ToolExecutionResult.error("Missing 'name' for a scene apply")
                val outcome = fabric.applyScene(name)
                    ?: return withResult(SmartSceneResult(false, name, 0, 0, emptyList(), "No scene named '$name' exists."))
                withResult(
                    SmartSceneResult(
                        success = outcome.complete,
                        scene = outcome.name,
                        applied = outcome.applied,
                        failed = outcome.failed,
                        failures = outcome.failures,
                        error = if (outcome.complete) null else "${outcome.failed} of ${outcome.applied + outcome.failed} actions did not land.",
                    ),
                )
            }
            "list" -> {
                val names = fabric.scenes().map { it.name }
                withResult(SmartSceneResult(true, names.joinToString(", ").ifBlank { null }, 0, 0, emptyList(), null))
            }
            "delete" -> {
                val name = (args["name"] as? String)?.trim()?.ifBlank { null }
                    ?: return ToolExecutionResult.error("Missing 'name' for a scene delete")
                val existed = fabric.scenes().any { it.name.equals(name, ignoreCase = true) }
                fabric.deleteScene(name)
                withResult(
                    SmartSceneResult(
                        success = existed, scene = name, applied = 0, failed = 0, failures = emptyList(),
                        error = if (existed) null else "No scene named '$name' exists.",
                    ),
                )
            }
            else -> ToolExecutionResult.error("Unknown scene action '$action'")
        }
    }

    private fun withResult(r: SmartSceneResult): ToolExecutionResult =
        ToolExecutionResult.success(r, json.encodeToString(SmartSceneResult.serializer(), r))

    private suspend fun commission(args: Map<String, Any?>): ToolExecutionResult {
        val transportName = (args["transport"] as? String)?.trim()?.uppercase()
            ?: return ToolExecutionResult.error("Missing 'transport'")
        val identifier: String = (args["identifier"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'identifier'")
        // The elvis above makes identifier non-null, but Kotlin still types a
        // nullable receiver's ifBlank result loosely, so the fallback is made
        // explicit here rather than left to inference.
        val name: String = (args["name"] as? String)?.trim()?.takeIf { it.isNotBlank() } ?: identifier
        val room = (args["room"] as? String)?.trim()?.ifBlank { null }

        val result = fabric.commission(transportName, identifier, name, room)
        val r = SmartCommissionResult(result.success, result.device?.toView(), result.error)
        return ToolExecutionResult.success(r, json.encodeToString(SmartCommissionResult.serializer(), r))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun parseValue(capability: Capability, raw: Any?): DeviceValue? {
        val asText = raw?.toString()?.trim() ?: return null
        return when (capability) {
            Capability.POWER, Capability.MUTE, Capability.PLAYBACK, Capability.LOCK_STATE,
            Capability.START, Capability.STOP, Capability.PAUSE ->
                DeviceValue.of(asText.toBooleanStrictOrNull())
            Capability.BRIGHTNESS, Capability.VOLUME, Capability.FAN_SPEED, Capability.POSITION ->
                DeviceValue.of(asText.toIntOrNull())
                    ?: DeviceValue.of(asText.toDoubleOrNull())
            Capability.TARGET_TEMPERATURE, Capability.CURRENT_TEMPERATURE ->
                DeviceValue.of(asText.toDoubleOrNull())
            else ->
                // Mode, source, colour and anything text-shaped.
                asText.toDoubleOrNull()?.let { DeviceValue.Measure(it) } ?: DeviceValue.Text(asText)
        }
    }

    private fun Device.toView(): DeviceView = DeviceView(
        id = id,
        name = name,
        room = room,
        transport = transport.name,
        deviceClass = deviceClass.name,
        capabilities = capabilities.sortedBy { it.name }.map { it.name },
        state = state.entries.associate { it.key.name to it.value.display() },
        available = available,
    )

    private fun DeviceValue.display(): String = when (this) {
        is DeviceValue.Flag -> value.toString()
        is DeviceValue.Count -> value.toString()
        is DeviceValue.Measure -> value.toString()
        is DeviceValue.Text -> value
    }
}
