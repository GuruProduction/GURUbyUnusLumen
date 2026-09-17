package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.util.shell.ShellExecutor
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class SmartHomeToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = when (toolName) {
        SmartHomeToolDefinitions.HUE_LIST_LIGHTS -> hueListLights()
        SmartHomeToolDefinitions.HUE_LIST_ROOMS -> hueListRooms()
        SmartHomeToolDefinitions.HUE_LIST_SCENES -> hueListScenes()
        SmartHomeToolDefinitions.HUE_SET_LIGHT -> hueSetLight(args)
        SmartHomeToolDefinitions.HUE_SET_ROOM -> hueSetRoom(args)
        SmartHomeToolDefinitions.HUE_ACTIVATE_SCENE -> hueActivateScene(args)
        SmartHomeToolDefinitions.SONOS_DISCOVER -> sonosDiscover()
        SmartHomeToolDefinitions.SONOS_STATUS -> sonosStatus(args)
        SmartHomeToolDefinitions.SONOS_PLAY_PAUSE -> sonosPlayPause(args)
        SmartHomeToolDefinitions.SONOS_VOLUME -> sonosVolume(args)
        SmartHomeToolDefinitions.SONOS_GROUP -> sonosGroup(args)
        SmartHomeToolDefinitions.BLUETOOTH_LIST -> bluetoothList()
        SmartHomeToolDefinitions.BLUETOOTH_CONNECT -> bluetoothConnect(args)
        SmartHomeToolDefinitions.BLUETOOTH_DISCONNECT -> bluetoothDisconnect(args)
        SmartHomeToolDefinitions.NETWORK_SCAN -> networkScan()
        SmartHomeToolDefinitions.NETWORK_PING -> networkPing(args)
        else -> ToolExecutionResult.error("Unknown tool: $toolName")
    }

    private suspend fun hueListLights(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("openhue get light 2>/dev/null")
        val r = if (result.success) HueLightsResult(success = true, lights = parseHueLights(result.stdout), error = null)
        else HueLightsResult(success = false, lights = emptyList(), error = "OpenHue CLI not available. Install openhue and configure with Hue Bridge IP.")
        ToolExecutionResult.success(r, json.encodeToString(HueLightsResult.serializer(), r))
    }

    private suspend fun hueListRooms(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("openhue get room 2>/dev/null")
        val r = if (result.success) HueRoomsResult(success = true, rooms = parseHueRooms(result.stdout), error = null)
        else HueRoomsResult(success = false, rooms = emptyList(), error = "Failed to list rooms: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(HueRoomsResult.serializer(), r))
    }

    private suspend fun hueListScenes(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("openhue get scene 2>/dev/null")
        val r = if (result.success) HueScenesResult(success = true, scenes = parseHueScenes(result.stdout), error = null)
        else HueScenesResult(success = false, scenes = emptyList(), error = "Failed to list scenes: ${result.stderr}")
        ToolExecutionResult.success(r, json.encodeToString(HueScenesResult.serializer(), r))
    }

    private suspend fun hueSetLight(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val light = args["light"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'light'")
        val power = args["power"] as? String
        val brightness = (args["brightness"] as? Number)?.toInt()
        val temperature = (args["temperature"] as? Number)?.toInt()
        val color = args["color"] as? String
        val cmdArgs = buildString {
            append("openhue set light \"$light\"")
            power?.let { append(" --$it") }
            brightness?.let { append(" --brightness $it") }
            temperature?.let { append(" --temperature $it") }
            color?.let { append(" --color $it") }
        }
        val result = shellExecutor.execute("$cmdArgs 2>/dev/null")
        val r = HueResult(success = result.success, action = "set_light", target = light, error = if (!result.success) "Failed to set light: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
    }

    private suspend fun hueSetRoom(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val room = args["room"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'room'")
        val power = args["power"] as? String
        val brightness = (args["brightness"] as? Number)?.toInt()
        val cmdArgs = buildString {
            append("openhue set room \"$room\"")
            power?.let { append(" --$it") }
            brightness?.let { append(" --brightness $it") }
        }
        val result = shellExecutor.execute("$cmdArgs 2>/dev/null")
        val r = HueResult(success = result.success, action = "set_room", target = room, error = if (!result.success) "Failed to set room: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
    }

    private suspend fun hueActivateScene(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val scene = args["scene"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'scene'")
        val room = args["room"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'room'")
        val result = shellExecutor.execute("openhue set scene \"$scene\" --room \"$room\" 2>/dev/null")
        val r = HueResult(success = result.success, action = "activate_scene", target = "$scene in $room", error = if (!result.success) "Failed to activate scene: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(HueResult.serializer(), r))
    }

    private suspend fun sonosDiscover(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val result = shellExecutor.execute("sonos discover 2>/dev/null")
        val r = if (result.success) SonosDiscoverResult(success = true, speakers = parseSonosSpeakers(result.stdout), error = null)
        else SonosDiscoverResult(success = false, speakers = emptyList(), error = "Sonos CLI not available or no speakers found. Install sonoscli and ensure network access.")
        ToolExecutionResult.success(r, json.encodeToString(SonosDiscoverResult.serializer(), r))
    }

    private suspend fun sonosStatus(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val speaker = args["speaker"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'speaker'")
        val result = shellExecutor.execute("sonos status --name \"$speaker\" 2>/dev/null")
        val r = SonosStatusResult(success = result.success, status = result.stdout.trim(), error = if (!result.success) "Failed to get status: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(SonosStatusResult.serializer(), r))
    }

    private suspend fun sonosPlayPause(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val speaker = args["speaker"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'speaker'")
        val action = args["action"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'action'")
        val result = shellExecutor.execute("sonos $action --name \"$speaker\" 2>/dev/null")
        val r = SonosResult(success = result.success, action = action, speaker = speaker, error = if (!result.success) "Failed to $action: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(SonosResult.serializer(), r))
    }

    private suspend fun sonosVolume(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val speaker = args["speaker"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'speaker'")
        val volume = (args["volume"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'volume'")
        val result = shellExecutor.execute("sonos volume set $volume --name \"$speaker\" 2>/dev/null")
        val r = SonosResult(success = result.success, action = "volume", speaker = speaker, error = if (!result.success) "Failed to set volume: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(SonosResult.serializer(), r))
    }

    private suspend fun sonosGroup(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val action = args["action"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'action'")
        val speaker = args["speaker"] as? String
        val cmd = when (action.lowercase()) {
            "join" -> "sonos group join --name \"$speaker\""
            "unjoin" -> "sonos group unjoin --name \"$speaker\""
            "party" -> "sonos group party"
            "solo" -> "sonos group solo"
            else -> {
                val r = SonosResult(success = false, action = action, speaker = speaker, error = "Invalid action: $action")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(SonosResult.serializer(), r))
            }
        }
        val result = shellExecutor.execute("$cmd 2>/dev/null")
        val r = SonosResult(success = result.success, action = "group_$action", speaker = speaker, error = if (!result.success) "Group action failed: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(SonosResult.serializer(), r))
    }

    private suspend fun bluetoothList(): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val bluetoothManager = context.getSystemService(android.bluetooth.BluetoothManager::class.java)
            val adapter = bluetoothManager?.adapter
            if (adapter == null || !adapter.isEnabled) {
                val r = BluetoothDevicesResult(success = false, devices = emptyList(), error = "Bluetooth is not enabled on this device. Enable Bluetooth first.")
                return@withContext ToolExecutionResult.success(r, json.encodeToString(BluetoothDevicesResult.serializer(), r))
            }
            val pairedDevices = adapter.bondedDevices ?: emptySet()
            val devices = pairedDevices.map { device -> BluetoothDevice(mac = device.address, name = device.name ?: "Unknown") }
            val r = BluetoothDevicesResult(success = true, devices = devices, error = null)
            ToolExecutionResult.success(r, json.encodeToString(BluetoothDevicesResult.serializer(), r))
        } catch (e: SecurityException) {
            try { val intent = android.content.Intent("com.unuslumen.app.guru.REQUEST_BLUETOOTH_PERMISSION"); intent.setPackage(context.packageName); context.sendBroadcast(intent) } catch (_: Exception) {}
            val r = BluetoothDevicesResult(success = false, devices = emptyList(), error = "Bluetooth permission denied. A permission request has been sent — grant BLUETOOTH_CONNECT in the dialog, then try again.")
            ToolExecutionResult.success(r, json.encodeToString(BluetoothDevicesResult.serializer(), r))
        } catch (e: Exception) {
            val r = BluetoothDevicesResult(success = false, devices = emptyList(), error = "Bluetooth access failed: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(BluetoothDevicesResult.serializer(), r))
        }
    }

    private suspend fun bluetoothConnect(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val device = args["device"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'device'")
        val result = shellExecutor.execute("bluetoothctl connect \"$device\" 2>/dev/null")
        val r = BluetoothResult(success = result.success, action = "connect", device = device, error = if (!result.success) "Failed to connect: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(BluetoothResult.serializer(), r))
    }

    private suspend fun bluetoothDisconnect(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val device = args["device"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'device'")
        val result = shellExecutor.execute("bluetoothctl disconnect \"$device\" 2>/dev/null")
        val r = BluetoothResult(success = result.success, action = "disconnect", device = device, error = if (!result.success) "Failed to disconnect: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(BluetoothResult.serializer(), r))
    }

    private suspend fun networkScan(): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val arpResult = shellExecutor.execute("cat /proc/net/arp 2>/dev/null")
            if (arpResult.success && arpResult.stdout.isNotBlank()) {
                val devices = parseNetworkDevices(arpResult.stdout)
                if (devices.isNotEmpty()) { val r = NetworkScanResult(success = true, devices = devices, error = null); return@withContext ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r)) }
            }
            val neighResult = shellExecutor.execute("ip neigh show 2>/dev/null")
            if (neighResult.success && neighResult.stdout.isNotBlank()) {
                val devices = parseNetworkDevices(neighResult.stdout)
                if (devices.isNotEmpty()) { val r = NetworkScanResult(success = true, devices = devices, error = null); return@withContext ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r)) }
            }
            val arpAResult = shellExecutor.execute("arp -a 2>/dev/null")
            if (arpAResult.success && arpAResult.stdout.isNotBlank()) {
                val devices = parseNetworkDevices(arpAResult.stdout)
                if (devices.isNotEmpty()) { val r = NetworkScanResult(success = true, devices = devices, error = null); return@withContext ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r)) }
            }
            try {
                val wifiManager = context.applicationContext.getSystemService(android.content.Context.WIFI_SERVICE) as? android.net.wifi.WifiManager
                if (wifiManager != null) {
                    @Suppress("DEPRECATION") val dhcpInfo = wifiManager.dhcpInfo
                    val gatewayIp = intToIp(dhcpInfo.gateway); val deviceIp = intToIp(dhcpInfo.ipAddress); val netmask = intToIp(dhcpInfo.netmask)
                    val dns1 = intToIp(dhcpInfo.dns1); val dns2 = intToIp(dhcpInfo.dns2); val server = intToIp(dhcpInfo.serverAddress)
                    val devices = mutableListOf<NetworkDevice>()
                    if (gatewayIp != "0.0.0.0") devices.add(NetworkDevice(ip = gatewayIp, mac = "", name = "Gateway/Router"))
                    if (deviceIp != "0.0.0.0") devices.add(NetworkDevice(ip = deviceIp, mac = "", name = "This device"))
                    if (dns1 != "0.0.0.0") devices.add(NetworkDevice(ip = dns1, mac = "", name = "DNS Server 1"))
                    if (dns2 != "0.0.0.0") devices.add(NetworkDevice(ip = dns2, mac = "", name = "DNS Server 2"))
                    if (server != "0.0.0.0" && server != gatewayIp) devices.add(NetworkDevice(ip = server, mac = "", name = "DHCP Server"))
                    if (devices.isNotEmpty()) { val r = NetworkScanResult(success = true, devices = devices, error = "Partial scan — ARP table inaccessible. Showing network infrastructure from DHCP info. Subnet: $deviceIp/$netmask"); return@withContext ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r)) }
                }
            } catch (_: SecurityException) {} catch (_: Exception) {}
            val r = NetworkScanResult(success = false, devices = emptyList(), error = "No network devices found. The ARP table is inaccessible on this Android version. Grant location permission for WiFi-based scanning.")
            ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r))
        } catch (e: Exception) {
            val r = NetworkScanResult(success = false, devices = emptyList(), error = "Network scan failed: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(NetworkScanResult.serializer(), r))
        }
    }

    private fun intToIp(i: Int): String = "${i and 0xFF}.${i shr 8 and 0xFF}.${i shr 16 and 0xFF}.${i shr 24 and 0xFF}"

    private suspend fun networkPing(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val host = args["host"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'host'")
        val count = (args["count"] as? Number)?.toInt() ?: 3
        val result = shellExecutor.execute("ping -c $count \"$host\" 2>/dev/null")
        val success = result.stdout.contains("bytes from") || result.stdout.contains("time=")
        val r = NetworkPingResult(success = success, host = host, output = result.stdout, error = if (!success) "Ping failed: ${result.stderr}" else null)
        ToolExecutionResult.success(r, json.encodeToString(NetworkPingResult.serializer(), r))
    }

    private fun parseHueLights(output: String): List<HueLight> {
        return output.lines().filter { it.isNotBlank() && !it.startsWith("ID") }.take(50).map { line ->
            val parts = line.split(Regex("\\s+"))
            HueLight(id = parts.getOrNull(0) ?: "", name = parts.getOrNull(1) ?: line.trim(), on = line.contains("on", ignoreCase = true), brightness = extractNumber(line, "brightness") ?: 100)
        }
    }

    private fun parseHueRooms(output: String): List<HueRoom> {
        return output.lines().filter { it.isNotBlank() }.take(20).map { line ->
            val parts = line.split(Regex("\\s+")); HueRoom(id = parts.getOrNull(0) ?: "", name = parts.getOrNull(1) ?: line.trim())
        }
    }

    private fun parseHueScenes(output: String): List<HueScene> {
        return output.lines().filter { it.isNotBlank() }.take(50).map { line ->
            val parts = line.split(Regex("\\s+")); HueScene(id = parts.getOrNull(0) ?: "", name = parts.getOrNull(1) ?: line.trim())
        }
    }

    private fun parseSonosSpeakers(output: String): List<SonosSpeaker> {
        return output.lines().filter { it.isNotBlank() && it.contains(":") }.take(20).map { line ->
            val parts = line.split(Regex("\\s+")); SonosSpeaker(name = parts.getOrNull(0) ?: line.trim(), ip = parts.find { it.contains(".") } ?: "", model = parts.getOrNull(2) ?: "Sonos")
        }
    }

    private fun parseNetworkDevices(output: String): List<NetworkDevice> {
        return output.lines().filter { it.isNotBlank() && it.contains(".") }.take(50).map { line ->
            val ip = Regex("\\d+\\.\\d+\\.\\d+\\.\\d+").find(line)?.value ?: ""
            val mac = Regex("([0-9a-fA-F]{2}:){5}[0-9a-fA-F]{2}").find(line)?.value ?: ""
            NetworkDevice(ip = ip, mac = mac, name = line.trim())
        }
    }

    private fun extractNumber(text: String, key: String): Int? {
        return Regex("$key[:\\s]*(\\d+)", RegexOption.IGNORE_CASE).find(text)?.groupValues?.get(1)?.toIntOrNull()
    }
}