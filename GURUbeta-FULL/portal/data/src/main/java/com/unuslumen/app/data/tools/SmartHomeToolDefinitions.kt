// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object SmartHomeToolDefinitions : ToolSetRegistration {
    const val HUE_DISCOVER = "hueDiscover"
    const val HUE_PAIR = "huePair"
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
        /*
         * THE HUE TOOLS NEED NO CLI AND NO INSTALL (issue 179).
         *
         * They used to run `openhue`, a Go binary Termux does not package and the APK never
         * contained, so every one of them could only fail with a message telling a person to
         * install something that does not exist. The bridge is a JSON HTTP server on the local
         * network, so the two setup steps below plus the six control tools are the whole
         * capability.
         *
         * THE SETUP ORDER MATTERS and is stated in the descriptions, because a model reading these
         * has to know that pairing needs a physical button press before anything else can work.
         */
        ToolDefinition(
            name = HUE_DISCOVER,
            description = "Find Philips Hue Bridges on this network. Asks the LAN for the bridge's " +
                "own announcement first, so it works with no internet access, then falls back to " +
                "the vendor's cloud discovery service. Run this before huePair.",
            category = "smart_home",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = HUE_PAIR,
            description = "Pair with a Hue Bridge found by hueDiscover. This is what issues the " +
                "application key every other Hue tool needs. The bridge only issues a key while " +
                "its physical round button has been pressed within the last 30 seconds, so press " +
                "it first. If it was not pressed, this reports that it is waiting for the button " +
                "rather than reporting a failure, because the bridge is working as designed.",
            category = "smart_home",
            parameters = listOf(
                ToolParameter("bridge", ToolParameterType.String, true, "The bridge's IP address, as hueDiscover returned it"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(name = HUE_LIST_LIGHTS, description = "List all Philips Hue lights with their names, IDs, and current states. Needs a bridge paired with huePair.", category = "smart_home", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = HUE_LIST_ROOMS, description = "List all Philips Hue rooms/zones.", category = "smart_home", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = HUE_LIST_SCENES, description = "List all Philips Hue scenes available.", category = "smart_home", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = HUE_SET_LIGHT, description = "Control a Philips Hue light - turn on/off, set brightness, color, or temperature.", category = "smart_home", parameters = listOf(ToolParameter("light", ToolParameterType.String, true, "Light name or ID"), ToolParameter("power", ToolParameterType.String, false, "Power state: 'on' or 'off'"), ToolParameter("brightness", ToolParameterType.Integer, false, "Brightness level 0-100"), ToolParameter("temperature", ToolParameterType.Integer, false, "Color temperature in mirek (153-500, warm to cool)"), ToolParameter("color", ToolParameterType.String, false, "Color name (e.g., 'red', 'blue', 'green') or hex (e.g., '#FF5500')")), permissions = emptyList()),
        ToolDefinition(name = HUE_SET_ROOM, description = "Control all lights in a Philips Hue room/zone.", category = "smart_home", parameters = listOf(ToolParameter("room", ToolParameterType.String, true, "Room name or ID"), ToolParameter("power", ToolParameterType.String, false, "Power state: 'on' or 'off'"), ToolParameter("brightness", ToolParameterType.Integer, false, "Brightness level 0-100")), permissions = emptyList()),
        ToolDefinition(name = HUE_ACTIVATE_SCENE, description = "Activate a Philips Hue scene in a room.", category = "smart_home", parameters = listOf(ToolParameter("scene", ToolParameterType.String, true, "Scene name (e.g., 'Relax', 'Concentrate', 'Energize')"), ToolParameter("room", ToolParameterType.String, true, "Room name to apply the scene to")), permissions = emptyList()),
        /*
         * THE SONOS TOOLS NEED NO CLI EITHER (issues 179 and 186). They ran `sonos <verb>`, a Node
         * CLI Termux does not package, so every one could only fail. A speaker is a UPnP device on
         * port 1400 and these now talk to it directly.
         */
        ToolDefinition(
            name = SONOS_DISCOVER,
            description = "Discover Sonos speakers on this network. Asks the speakers themselves for " +
                "their zone topology, so one reachable speaker names every other speaker in the " +
                "house along with each room's own name. Run mdnsDiscover first if this finds nothing.",
            category = "smart_home",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SONOS_STATUS,
            description = "Report what a Sonos speaker is doing: playing, paused or stopped, and " +
                "what it has queued. Takes a speaker name from sonosDiscover, or an IP address.",
            category = "smart_home",
            parameters = listOf(ToolParameter("speaker", ToolParameterType.String, true, "Speaker name or IP address")),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SONOS_PLAY_PAUSE,
            description = "Play, pause or stop a Sonos speaker over its own UPnP transport service.",
            category = "smart_home",
            parameters = listOf(ToolParameter("speaker", ToolParameterType.String, true, "Speaker name or IP address"), ToolParameter("action", ToolParameterType.String, true, "Action: 'play', 'pause', or 'stop'")),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SONOS_VOLUME,
            description = "Set the volume on a Sonos speaker. Sonos takes 0 to 100 directly, so the " +
                "number given is the number the speaker receives.",
            category = "smart_home",
            parameters = listOf(ToolParameter("speaker", ToolParameterType.String, true, "Speaker name or IP address"), ToolParameter("volume", ToolParameterType.Integer, true, "Volume level 0-100")),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SONOS_GROUP,
            description = "Grouping is NOT implemented. Changing a Sonos zone group means writing " +
                "the topology back through services this client does not use, so this reports that " +
                "plainly rather than claiming a join happened. Individual speakers still work " +
                "through sonosPlayPause and sonosVolume.",
            category = "smart_home",
            parameters = listOf(ToolParameter("action", ToolParameterType.String, true, "Action: 'join' to group, 'unjoin' to ungroup, 'party' for party mode, 'solo' for solo mode"), ToolParameter("speaker", ToolParameterType.String, false, "Speaker name or IP address")),
            permissions = emptyList(),
        ),
        // BLUETOOTH_LIST DECLARES BLUETOOTH_CONNECT SO THE DISPATCHER REFUSES IT
        // UP FRONT. That is the permission batch's deliberate design: a declared
        // permission makes ToolDispatcher answer with the grant sentence before
        // the executor runs, which names what is missing and where to grant it.
        // The alternative - leaving it undeclared so the tool can raise a dialog
        // mid-conversation - was removed on purpose, because a tool must not
        // interrupt a conversation with a permission prompt.
        ToolDefinition(name = BLUETOOTH_LIST, description = "List the Bluetooth devices paired with this phone, with their names and MAC addresses. Needs the Nearby devices permission.", category = "smart_home", parameters = emptyList(), permissions = listOf("android.permission.BLUETOOTH_CONNECT")),
        ToolDefinition(name = BLUETOOTH_CONNECT, description = "Connect to a Bluetooth device by MAC address or paired-device name. Starts pairing with createBond when the device is not paired, and on Android 16 and later asks the system to connect a paired device's profiles. The connection is asynchronous, so the result reports the request rather than a completed connection.", category = "smart_home", parameters = listOf(ToolParameter("device", ToolParameterType.String, true, "Device MAC address or paired-device name")), permissions = emptyList()),
        ToolDefinition(name = BLUETOOTH_DISCONNECT, description = "Disconnect a Bluetooth device by MAC address or paired-device name. Android offers no general force-disconnect for a paired device; on Android 16 and later this asks the system to bring the device's profiles down, and otherwise reports that the platform cannot do it rather than unpairing the device behind the user's back.", category = "smart_home", parameters = listOf(ToolParameter("device", ToolParameterType.String, true, "Device MAC address or paired-device name")), permissions = emptyList()),
        ToolDefinition(name = NETWORK_SCAN, description = "Scan the local network for devices. Returns IP addresses, MAC addresses, and device names if available.", category = "smart_home", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = NETWORK_PING, description = "Ping a host to check connectivity.", category = "smart_home", parameters = listOf(ToolParameter("host", ToolParameterType.String, true, "Hostname or IP address to ping"), ToolParameter("count", ToolParameterType.Integer, false, "Number of pings. Default 3.")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = SmartHomeToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}