package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object SmartHomeToolDefinitions : ToolSetRegistration {
    const val HUE_LIST_LIGHTS = "hueListLights"
    const val HUE_LIST_ROOMS = "hueListRooms"
    const val HUE_LIST_SCENES = "hueListScenes"
    const val HUE_SET_LIGHT = "hueSetLight"
    const val HUE_SET_ROOM = "hueSetRoom"
    const val HUE_ACTIVATE_SCENE = "hueActivateScene"
    const val SONOS_DISCOVER = "sonosDiscover"
    const val SONOS_STATUS = "sonosStatus"
    const val SONOS_PLAY_PAUSE = "sonosPlayPause"
    const val SONOS_VOLUME = "sonosVolume"
    const val SONOS_GROUP = "sonosGroup"
    const val BLUETOOTH_LIST = "bluetoothList"
    const val BLUETOOTH_CONNECT = "bluetoothConnect"
    const val BLUETOOTH_DISCONNECT = "bluetoothDisconnect"
    const val NETWORK_SCAN = "networkScan"
    const val NETWORK_PING = "networkPing"

    override val definitions = listOf(
        ToolDefinition(name = HUE_LIST_LIGHTS, description = "List all Philips Hue lights with their names, IDs, and current states.", category = "smart_home", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = HUE_LIST_ROOMS, description = "List all Philips Hue rooms/zones.", category = "smart_home", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = HUE_LIST_SCENES, description = "List all Philips Hue scenes available.", category = "smart_home", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = HUE_SET_LIGHT, description = "Control a Philips Hue light - turn on/off, set brightness, color, or temperature.", category = "smart_home", parameters = listOf(ToolParameter("light", ToolParameterType.String, true, "Light name or ID"), ToolParameter("power", ToolParameterType.String, false, "Power state: 'on' or 'off'"), ToolParameter("brightness", ToolParameterType.Integer, false, "Brightness level 0-100"), ToolParameter("temperature", ToolParameterType.Integer, false, "Color temperature in mirek (153-500, warm to cool)"), ToolParameter("color", ToolParameterType.String, false, "Color name (e.g., 'red', 'blue', 'green') or hex (e.g., '#FF5500')")), permissions = emptyList()),
        ToolDefinition(name = HUE_SET_ROOM, description = "Control all lights in a Philips Hue room/zone.", category = "smart_home", parameters = listOf(ToolParameter("room", ToolParameterType.String, true, "Room name or ID"), ToolParameter("power", ToolParameterType.String, false, "Power state: 'on' or 'off'"), ToolParameter("brightness", ToolParameterType.Integer, false, "Brightness level 0-100")), permissions = emptyList()),
        ToolDefinition(name = HUE_ACTIVATE_SCENE, description = "Activate a Philips Hue scene in a room.", category = "smart_home", parameters = listOf(ToolParameter("scene", ToolParameterType.String, true, "Scene name (e.g., 'Relax', 'Concentrate', 'Energize')"), ToolParameter("room", ToolParameterType.String, true, "Room name to apply the scene to")), permissions = emptyList()),
        ToolDefinition(name = SONOS_DISCOVER, description = "Discover Sonos speakers on the local network.", category = "smart_home", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SONOS_STATUS, description = "Get playback status of a Sonos speaker.", category = "smart_home", parameters = listOf(ToolParameter("speaker", ToolParameterType.String, true, "Speaker name or IP address")), permissions = emptyList()),
        ToolDefinition(name = SONOS_PLAY_PAUSE, description = "Play or pause a Sonos speaker.", category = "smart_home", parameters = listOf(ToolParameter("speaker", ToolParameterType.String, true, "Speaker name or IP address"), ToolParameter("action", ToolParameterType.String, true, "Action: 'play', 'pause', or 'stop'")), permissions = emptyList()),
        ToolDefinition(name = SONOS_VOLUME, description = "Set volume on a Sonos speaker.", category = "smart_home", parameters = listOf(ToolParameter("speaker", ToolParameterType.String, true, "Speaker name or IP address"), ToolParameter("volume", ToolParameterType.Integer, true, "Volume level 0-100")), permissions = emptyList()),
        ToolDefinition(name = SONOS_GROUP, description = "Group or ungroup Sonos speakers for synchronized playback.", category = "smart_home", parameters = listOf(ToolParameter("action", ToolParameterType.String, true, "Action: 'join' to group, 'unjoin' to ungroup, 'party' for party mode, 'solo' for solo mode"), ToolParameter("speaker", ToolParameterType.String, false, "Speaker name or IP address")), permissions = emptyList()),
        ToolDefinition(name = BLUETOOTH_LIST, description = "List paired and connected Bluetooth devices using Android Bluetooth API.", category = "smart_home", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = BLUETOOTH_CONNECT, description = "Connect to a paired Bluetooth device.", category = "smart_home", parameters = listOf(ToolParameter("device", ToolParameterType.String, true, "Device MAC address or name")), permissions = emptyList()),
        ToolDefinition(name = BLUETOOTH_DISCONNECT, description = "Disconnect from a Bluetooth device.", category = "smart_home", parameters = listOf(ToolParameter("device", ToolParameterType.String, true, "Device MAC address or name")), permissions = emptyList()),
        ToolDefinition(name = NETWORK_SCAN, description = "Scan the local network for devices. Returns IP addresses, MAC addresses, and device names if available.", category = "smart_home", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = NETWORK_PING, description = "Ping a host to check connectivity.", category = "smart_home", parameters = listOf(ToolParameter("host", ToolParameterType.String, true, "Hostname or IP address to ping"), ToolParameter("count", ToolParameterType.Integer, false, "Number of pings. Default 3.")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = SmartHomeToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}