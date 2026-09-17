package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.discovery.ArpDiscoveryManager
import com.unuslumen.app.data.discovery.BluetoothScanManager
import com.unuslumen.app.data.discovery.MdnsDiscoveryManager
import com.unuslumen.app.data.discovery.SsdpDiscoveryManager
import com.unuslumen.app.data.discovery.WifiScanManager
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

class EnvironmentToolExecutor(
    private val context: Context,
    private val mdnsDiscoveryManager: MdnsDiscoveryManager,
    private val ssdpDiscoveryManager: SsdpDiscoveryManager,
    private val arpDiscoveryManager: ArpDiscoveryManager,
    private val wifiScanManager: WifiScanManager,
    private val bluetoothScanManager: BluetoothScanManager
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (toolName) {
            EnvironmentToolDefinitions.MDNS_DISCOVER -> { try { mdnsDiscoveryManager.startDiscovery(); val r = MdnsDiscoverResult(true, "mDNS discovery started", null); ToolExecutionResult.success(r, json.encodeToString(MdnsDiscoverResult.serializer(), r)) } catch (e: Exception) { val r = MdnsDiscoverResult(false, null, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(MdnsDiscoverResult.serializer(), r)) } }
            EnvironmentToolDefinitions.MDNS_LIST_DEVICES -> { try { val devs = mdnsDiscoveryManager.getDiscoveredDevices().map { MdnsDevice(it.serviceName, it.serviceType, it.host, it.port, it.discoveredAt) }; val r = MdnsDevicesResult(true, devs, null); ToolExecutionResult.success(r, json.encodeToString(MdnsDevicesResult.serializer(), r)) } catch (e: Exception) { val r = MdnsDevicesResult(false, emptyList(), "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(MdnsDevicesResult.serializer(), r)) } }
            EnvironmentToolDefinitions.MDNS_STOP_DISCOVERY -> { try { mdnsDiscoveryManager.stopDiscovery(); val r = MdnsDiscoverResult(true, "Stopped", null); ToolExecutionResult.success(r, json.encodeToString(MdnsDiscoverResult.serializer(), r)) } catch (e: Exception) { val r = MdnsDiscoverResult(false, null, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(MdnsDiscoverResult.serializer(), r)) } }
            EnvironmentToolDefinitions.SSDP_DISCOVER -> { try { ssdpDiscoveryManager.startDiscovery(); val r = SsdpDiscoverResult(true, "SSDP discovery started", null); ToolExecutionResult.success(r, json.encodeToString(SsdpDiscoverResult.serializer(), r)) } catch (e: Exception) { val r = SsdpDiscoverResult(false, null, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(SsdpDiscoverResult.serializer(), r)) } }
            EnvironmentToolDefinitions.SSDP_LIST_DEVICES -> { try { val devs = ssdpDiscoveryManager.getDiscoveredDevices().map { SsdpDeviceDto(it.usn, it.st, it.location, it.server, it.host, it.friendlyName, it.manufacturer, it.modelName, it.discoveredAt) }; val r = SsdpDevicesResult(true, devs, null); ToolExecutionResult.success(r, json.encodeToString(SsdpDevicesResult.serializer(), r)) } catch (e: Exception) { val r = SsdpDevicesResult(false, emptyList(), "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(SsdpDevicesResult.serializer(), r)) } }
            EnvironmentToolDefinitions.SSDP_STOP_DISCOVERY -> { try { ssdpDiscoveryManager.stopDiscovery(); val r = SsdpDiscoverResult(true, "Stopped", null); ToolExecutionResult.success(r, json.encodeToString(SsdpDiscoverResult.serializer(), r)) } catch (e: Exception) { val r = SsdpDiscoverResult(false, null, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(SsdpDiscoverResult.serializer(), r)) } }
            EnvironmentToolDefinitions.ARP_SCAN -> { try { val devs = arpDiscoveryManager.discoverDevices().map { ArpDeviceDto(it.ip, it.mac, it.manufacturer, it.hostname, it.discoveredAt) }; val r = ArpScanResult(true, devs, null); ToolExecutionResult.success(r, json.encodeToString(ArpScanResult.serializer(), r)) } catch (e: Exception) { val r = ArpScanResult(false, emptyList(), "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(ArpScanResult.serializer(), r)) } }
            EnvironmentToolDefinitions.WIFI_SCAN -> { try { val nets = wifiScanManager.scanNetworks().map { WifiNetworkDto(it.ssid, it.bssid, it.signalStrength, it.frequency, it.channel, it.securityType, it.isHidden, it.isCurrentlyConnected, it.capabilities) }; val r = WifiScanResult(true, nets, null); ToolExecutionResult.success(r, json.encodeToString(WifiScanResult.serializer(), r)) } catch (e: Exception) { val r = WifiScanResult(false, emptyList(), "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(WifiScanResult.serializer(), r)) } }
            EnvironmentToolDefinitions.BLUETOOTH_SCAN -> { try { val result = bluetoothScanManager.scan(); val devs = result.devices.map { DiscoveredBluetoothDevice(it.name, it.mac, it.deviceType, it.majorDeviceClass, it.minorDeviceClass, it.bondState, it.isPaired, it.rssi, it.timestamp) }; val r = BluetoothScanResult(result.success, devs, result.error); ToolExecutionResult.success(r, json.encodeToString(BluetoothScanResult.serializer(), r)) } catch (e: Exception) { val r = BluetoothScanResult(false, emptyList(), "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(BluetoothScanResult.serializer(), r)) } }
            EnvironmentToolDefinitions.TCP_CONNECT -> { val host = args["host"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'host'"); val port = (args["port"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'port'"); val data = args["data"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'data'"); val timeoutMs = (args["timeoutMs"] as? Number)?.toLong() ?: 5000L; var socket: java.net.Socket? = null; try { socket = java.net.Socket(); socket.connect(java.net.InetSocketAddress(host, port), timeoutMs.toInt()); socket.soTimeout = timeoutMs.toInt(); val out = socket.getOutputStream(); out.write(data.toByteArray()); out.flush(); val inp = socket.getInputStream(); val buf = ByteArray(8192); val bytes = inp.read(buf); val resp = if (bytes > 0) String(buf, 0, bytes, Charsets.UTF_8) else ""; val r = TcpConnectResult(true, host, port, resp, resp.toByteArray().joinToString("") { "%02x".format(it) }, bytes, null); ToolExecutionResult.success(r, json.encodeToString(TcpConnectResult.serializer(), r)) } catch (e: Exception) { val r = TcpConnectResult(false, host, port, null, null, 0, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(TcpConnectResult.serializer(), r)) } finally { try { socket?.close() } catch (_: Exception) {} } }
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }
}