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
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.senses.emitPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * The tools that turn discovery into control. Requirements 3, 4 and 5 of the spec.
 *
 * WHY THESE FOUR AND NOT ONE PER DEVICE CLASS. Each drives a protocol rather than a
 * product, so together they cover every UPnP box, every DIAL device and anything that can
 * be woken. That is how "control everything on the network" stops being a per-vendor
 * treadmill.
 */

@Serializable
data class NetUpnpResult(
    val success: Boolean,
    val address: String = "",
    val friendlyName: String? = null,
    val services: List<String> = emptyList(),
    val servicesDetail: List<UpnpServiceView> = emptyList(),
    val actionResult: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class UpnpServiceView(
    val name: String,
    val serviceType: String,
    val controlUrl: String,
)

@Serializable
data class NetDialResult(
    val success: Boolean,
    val address: String = "",
    val applicationUrl: String? = null,
    val apps: List<String> = emptyList(),
    val actionResult: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class NetWakeResult(
    val success: Boolean,
    val mac: String = "",
    val sent: Boolean = false,
    val result: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class NetAdaptersResult(
    val success: Boolean,
    val built: List<AdapterView> = emptyList(),
    val gaps: List<AdapterView> = emptyList(),
    val gapSentence: String = "",
) : ToolResultData

@Serializable
data class AdapterView(
    val id: String,
    val target: String,
    val mechanism: String,
    val requirement: Int,
    val detail: String,
)

object NetworkControlToolDefinitions : ToolSetRegistration {

    const val NET_UPNP = "netUpnp"
    const val NET_DIAL = "netDial"
    const val NET_WAKE = "netWake"
    const val NET_ADAPTERS = "netAdapters"

    override val definitions = listOf(
        ToolDefinition(
            name = NET_UPNP,
            description = "Drive any UPnP device on the network. With no action it reads the " +
                "device's description and lists every service it exposes, which is how you find " +
                "out what a box can actually do. With an action it builds the SOAP envelope and " +
                "invokes it, so media transport on a television, volume on a speaker, port " +
                "mapping on a router and pan and tilt on an ONVIF camera all become drivable. " +
                "A UPnP fault is reported with the device's own error code rather than as a " +
                "success.",
            category = "network",
            parameters = listOf(
                ToolParameter(
                    "address", ToolParameterType.String, true,
                    "Host address, ideally one netHosts reported with port 49152 or a LOCATION.",
                ),
                ToolParameter("port", ToolParameterType.Integer, false, "Description port. Default 49152."),
                ToolParameter(
                    "action", ToolParameterType.String, false,
                    "Action to invoke, e.g. Play, Pause, SetVolume. Omit to just list the services.",
                ),
                ToolParameter(
                    "service", ToolParameterType.String, false,
                    "Which service to invoke on, by short name, e.g. AVTransport or RenderingControl. " +
                        "Defaults to the first service whose name matches the action's usual home.",
                ),
                ToolParameter(
                    "arguments", ToolParameterType.String, false,
                    "Arguments as key=value pairs separated by commas, e.g. InstanceID=0,Channel=Master,DesiredVolume=20.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = NET_DIAL,
            description = "Launch, stop or check an app on a device that speaks DIAL, which " +
                "includes Roku, Fire TV and Virgin boxes. With no action it reads the device's " +
                "app list and the URL launches go to. With action launch it starts the named app, " +
                "which is how a film gets put on a television rather than pressed into it with a " +
                "D-pad. Reports the device's own HTTP status for every call.",
            category = "network",
            parameters = listOf(
                ToolParameter("address", ToolParameterType.String, true, "Host address"),
                ToolParameter("port", ToolParameterType.Integer, false, "DIAL port. Default 56890."),
                ToolParameter(
                    "action", ToolParameterType.String, false,
                    "list, launch, stop or status. Default list.",
                    enumValues = listOf("list", "launch", "stop", "status"),
                ),
                ToolParameter(
                    "app", ToolParameterType.String, false,
                    "DIAL app name, e.g. Netflix, YouTube, Amazon, Spotify, BBCiPlayer.",
                ),
                ToolParameter(
                    "applicationUrl", ToolParameterType.String, false,
                    "The Application-URL from a previous list call. Omit to have this call fetch it.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = NET_WAKE,
            description = "Send a Wake-on-LAN magic packet to wake a sleeping device by its MAC " +
                "address. Do this before trying to control a set that is in standby, because a " +
                "sleeping device has no listening socket and every command fails against it. " +
                "The device must have Wake-on-LAN enabled in its own settings.",
            category = "network",
            parameters = listOf(
                ToolParameter(
                    "mac", ToolParameterType.String, true,
                    "MAC address, e.g. AA:BB:CC:DD:EE:FF. netHosts reports these.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = NET_ADAPTERS,
            description = "List every network adapter GURU has and every one it still needs, with " +
                "what each drives and why a missing one is missing. Use this to find out what " +
                "GURU can actually control on a network, and what it can only see. Never claims a " +
                "device class is controllable when its adapter is still a gap.",
            category = "network",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = NetworkControlExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class NetworkControlExecutor(
    private val scanner: NetworkScanner,
    private val upnp: UpnpSoapClient,
    private val store: NetworkStore,
) : ToolExecutor {

    constructor(context: Context) : this(NetworkScanner(), UpnpSoapClient(), NetworkStore())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            NetworkControlToolDefinitions.NET_UPNP -> upnpCall(args)
            NetworkControlToolDefinitions.NET_DIAL -> dial(args)
            NetworkControlToolDefinitions.NET_WAKE -> wake(args)
            NetworkControlToolDefinitions.NET_ADAPTERS -> adapters()
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    /**
     * Read a device's services, or invoke an action on one.
     *
     * A read is the honest first step: a person cannot ask a box to do something until
     * something has told them what it can do, and the service list is that answer.
     */
    private suspend fun upnpCall(args: Map<String, Any?>): ToolExecutionResult {
        val address = (args["address"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'address'")
        val port = (args["port"] as? Number)?.toInt() ?: 49152
        val action = (args["action"] as? String)?.trim()?.ifBlank { null }
        val serviceName = (args["service"] as? String)?.trim()?.ifBlank { null }

        val location = "http://$address:$port/"
        val described = upnp.describe(location)
        val description = described.getOrElse { error ->
            val r = NetUpnpResult(
                success = false,
                address = address,
                error = error.message ?: "Could not read a UPnP description from $address:$port.",
            )
            return emitPayload(json, json.encodeToString(NetUpnpResult.serializer(), r), r, false, r.error)
        }

        val views = description.services.map {
            UpnpServiceView(it.shortName, it.serviceType, it.controlUrl)
        }

        if (action == null) {
            val r = NetUpnpResult(
                success = description.services.isNotEmpty(),
                address = address,
                friendlyName = description.friendlyName,
                services = description.services.map { it.shortName },
                servicesDetail = views,
                error = if (description.services.isEmpty()) {
                    "The description at $address:$port names no services, so there is nothing to invoke."
                } else null,
            )
            return emitPayload(json, json.encodeToString(NetUpnpResult.serializer(), r), r, r.success, r.error)
        }

        val service = serviceName?.let { wanted ->
            description.services.firstOrNull { it.shortName.equals(wanted, ignoreCase = true) }
        } ?: description.services.firstOrNull { it.shortName.equals(serviceForAction(action), ignoreCase = true) }
            ?: description.services.firstOrNull()

        if (service == null) {
            val r = NetUpnpResult(
                success = false,
                address = address,
                friendlyName = description.friendlyName,
                services = description.services.map { it.shortName },
                servicesDetail = views,
                error = "No service named '${serviceName ?: serviceForAction(action)}' is exposed by " +
                    "$address. It exposes: " +
                    description.services.joinToString(", ") { it.shortName } + ".",
            )
            return emitPayload(json, json.encodeToString(NetUpnpResult.serializer(), r), r, false, r.error)
        }

        val arguments = parseArguments(args["arguments"] as? String)
        val outcome = upnp.invoke(service, action, arguments)
        val r = outcome.fold(
            onSuccess = { message ->
                NetUpnpResult(
                    success = true,
                    address = address,
                    friendlyName = description.friendlyName,
                    services = description.services.map { it.shortName },
                    servicesDetail = views,
                    actionResult = message,
                )
            },
            onFailure = { error ->
                NetUpnpResult(
                    success = false,
                    address = address,
                    friendlyName = description.friendlyName,
                    services = description.services.map { it.shortName },
                    servicesDetail = views,
                    error = error.message ?: "$action failed on $address.",
                )
            },
        )
        return emitPayload(json, json.encodeToString(NetUpnpResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Which service an action usually lives on.
     *
     * A guess that saves the caller from naming it, and it never decides alone: the exact
     * service can always be passed, and a guess that finds nothing falls back to the first
     * service the device exposes rather than failing.
     */
    private fun serviceForAction(action: String): String = when (action.lowercase()) {
        "play", "pause", "stop", "seek", "next", "previous", "setavtransporturi" -> "AVTransport"
        "setvolume", "getvolume", "setmute", "getmute" -> "RenderingControl"
        "addportmapping", "deleteportmapping", "getexternalipaddress" -> "WANIPConnection"
        "getstatusinfo", "getgenericportmappingentry" -> "WANCommonInterfaceConfig"
        else -> ""
    }

    /** Read `key=value,key=value` into a map, refusing nothing and inventing nothing. */
    private fun parseArguments(raw: String?): Map<String, String> {
        if (raw.isNullOrBlank()) return emptyMap()
        return raw.split(",")
            .mapNotNull { pair ->
                val index = pair.indexOf('=')
                if (index <= 0) null
                else pair.substring(0, index).trim() to pair.substring(index + 1).trim()
            }
            .toMap()
    }

    /** Read the DIAL app list, or launch, stop or check an app. */
    private suspend fun dial(args: Map<String, Any?>): ToolExecutionResult {
        val address = (args["address"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'address'")
        val port = (args["port"] as? Number)?.toInt() ?: 56890
        val action = (args["action"] as? String)?.trim()?.lowercase() ?: "list"
        val app = (args["app"] as? String)?.trim()?.ifBlank { null }
        val suppliedUrl = (args["applicationUrl"] as? String)?.trim()?.ifBlank { null }

        val client = DialClient(address, port)

        // The application URL is needed for everything except a list, so it is fetched
        // when the caller did not pass one rather than failing.
        val applicationUrl = suppliedUrl ?: run {
            val list = client.appList().getOrElse { error ->
                val r = NetDialResult(
                    success = false,
                    address = address,
                    error = error.message ?: "Could not read the DIAL description from $address:$port.",
                )
                return emitPayload(json, json.encodeToString(NetDialResult.serializer(), r), r, false, r.error)
            }
            list.applicationUrl
        }

        if (action == "list") {
            val list = client.appList()
            val r = list.fold(
                onSuccess = { found ->
                    NetDialResult(
                        success = true,
                        address = address,
                        applicationUrl = found.applicationUrl,
                        apps = found.apps,
                        note = found.note,
                    )
                },
                onFailure = { error ->
                    NetDialResult(
                        success = false,
                        address = address,
                        error = error.message ?: "Could not read the app list from $address:$port.",
                    )
                },
            )
            return emitPayload(json, json.encodeToString(NetDialResult.serializer(), r), r, r.success, r.error)
        }

        if (app == null) {
            return ToolExecutionResult.error("Missing 'app' for action '$action'")
        }

        val outcome = when (action) {
            "launch" -> client.launch(applicationUrl, app)
            "stop" -> client.stop(applicationUrl, app)
            "status" -> client.isRunning(applicationUrl, app)
            else -> return ToolExecutionResult.error("Unknown DIAL action '$action'. Use list, launch, stop or status.")
        }

        val r = outcome.fold(
            onSuccess = { message ->
                NetDialResult(
                    success = true,
                    address = address,
                    applicationUrl = applicationUrl,
                    actionResult = message,
                    note = "DIAL reports the device accepted the request. Whether the app appeared " +
                        "on screen cannot be read back over DIAL.",
                )
            },
            onFailure = { error ->
                NetDialResult(
                    success = false,
                    address = address,
                    applicationUrl = applicationUrl,
                    error = error.message ?: "$action $app failed on $address.",
                )
            },
        )
        return emitPayload(json, json.encodeToString(NetDialResult.serializer(), r), r, r.success, r.error)
    }

    /** Wake a sleeping device. */
    private suspend fun wake(args: Map<String, Any?>): ToolExecutionResult {
        val mac = (args["mac"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'mac'")

        val outcome = WakeOnLan.wake(mac)
        val r = NetWakeResult(
            success = outcome.sent,
            mac = mac,
            sent = outcome.sent,
            result = if (outcome.sent) outcome.reason else null,
            error = if (outcome.sent) null else outcome.reason,
        )
        return emitPayload(json, json.encodeToString(NetWakeResult.serializer(), r), r, r.success, r.error)
    }

    /** The adapter catalogue, built and missing, which is the honest capability report. */
    private fun adapters(): ToolExecutionResult {
        val r = NetAdaptersResult(
            success = true,
            built = AdapterCatalogue.built.map {
                AdapterView(it.id, it.target, it.mechanism, it.requirement, it.detail)
            },
            gaps = AdapterCatalogue.gaps.map {
                AdapterView(it.id, it.target, it.mechanism, it.requirement, it.detail)
            },
            gapSentence = AdapterCatalogue.gapSentence(),
        )
        return emitPayload(json, json.encodeToString(NetAdaptersResult.serializer(), r), r, true, null)
    }
}
