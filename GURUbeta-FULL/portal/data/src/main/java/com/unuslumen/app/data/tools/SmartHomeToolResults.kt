package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class HueLight(val id: String, val name: String, val on: Boolean, val brightness: Int) : ToolResultData
@Serializable data class HueLightsResult(val success: Boolean, val lights: List<HueLight>, val error: String? = null) : ToolResultData
@Serializable data class HueRoom(val id: String, val name: String) : ToolResultData
@Serializable data class HueRoomsResult(val success: Boolean, val rooms: List<HueRoom>, val error: String? = null) : ToolResultData
@Serializable data class HueScene(val id: String, val name: String) : ToolResultData
@Serializable data class HueScenesResult(val success: Boolean, val scenes: List<HueScene>, val error: String? = null) : ToolResultData
@Serializable data class HueResult(val success: Boolean, val action: String, val target: String? = null, val error: String? = null) : ToolResultData
@Serializable data class SonosSpeaker(val name: String, val ip: String, val model: String) : ToolResultData
@Serializable data class SonosDiscoverResult(val success: Boolean, val speakers: List<SonosSpeaker>, val error: String? = null) : ToolResultData
@Serializable data class SonosStatusResult(val success: Boolean, val status: String? = null, val error: String? = null) : ToolResultData
@Serializable data class SonosResult(val success: Boolean, val action: String, val speaker: String? = null, val error: String? = null) : ToolResultData
@Serializable data class BluetoothDevice(val mac: String, val name: String) : ToolResultData
@Serializable data class BluetoothDevicesResult(val success: Boolean, val devices: List<BluetoothDevice>, val error: String? = null) : ToolResultData
@Serializable data class BluetoothResult(val success: Boolean, val action: String, val device: String? = null, val error: String? = null) : ToolResultData
@Serializable data class NetworkDevice(val ip: String, val mac: String, val name: String) : ToolResultData
@Serializable data class NetworkScanResult(val success: Boolean, val devices: List<NetworkDevice>, val error: String? = null) : ToolResultData
@Serializable data class NetworkPingResult(val success: Boolean, val host: String, val output: String? = null, val error: String? = null) : ToolResultData