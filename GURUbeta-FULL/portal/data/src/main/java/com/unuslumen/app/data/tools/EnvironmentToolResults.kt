package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class MdnsDiscoverResult(val success: Boolean, val message: String?, val error: String?) : ToolResultData
@Serializable data class MdnsDevicesResult(val success: Boolean, val devices: List<MdnsDevice>, val error: String?) : ToolResultData
@Serializable data class MdnsDevice(val serviceName: String, val serviceType: String, val host: String, val port: Int, val discoveredAt: Long) : ToolResultData
@Serializable data class SsdpDiscoverResult(val success: Boolean, val message: String?, val error: String?) : ToolResultData
@Serializable data class SsdpDevicesResult(val success: Boolean, val devices: List<SsdpDeviceDto>, val error: String?) : ToolResultData
@Serializable data class SsdpDeviceDto(val usn: String, val st: String, val location: String, val server: String, val host: String, val friendlyName: String, val manufacturer: String, val modelName: String, val discoveredAt: Long) : ToolResultData
@Serializable data class ArpScanResult(val success: Boolean, val devices: List<ArpDeviceDto>, val error: String?) : ToolResultData
@Serializable data class ArpDeviceDto(val ip: String, val mac: String, val manufacturer: String, val hostname: String, val discoveredAt: Long) : ToolResultData
@Serializable data class WifiScanResult(val success: Boolean, val networks: List<WifiNetworkDto>, val error: String?) : ToolResultData
@Serializable data class WifiNetworkDto(val ssid: String, val bssid: String, val signalStrength: Int, val frequency: Int, val channel: Int, val securityType: String, val isHidden: Boolean, val isCurrentlyConnected: Boolean, val capabilities: String) : ToolResultData
@Serializable data class BluetoothScanResult(val success: Boolean, val devices: List<DiscoveredBluetoothDevice>, val error: String?) : ToolResultData
@Serializable data class DiscoveredBluetoothDevice(val name: String, val mac: String, val deviceType: String, val majorDeviceClass: String, val minorDeviceClass: String, val bondState: String, val isPaired: Boolean, val rssi: Int?, val timestamp: Long) : ToolResultData
@Serializable data class TcpConnectResult(val success: Boolean, val host: String, val port: Int, val response: String?, val responseHex: String?, val bytesReceived: Int, val error: String?) : ToolResultData