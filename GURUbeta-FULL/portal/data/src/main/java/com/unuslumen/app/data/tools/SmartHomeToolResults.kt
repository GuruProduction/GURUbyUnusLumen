// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
/**
 * Bluetooth connect or disconnect outcome.
 *
 * [success] means the requested action was actually carried out. [action] names
 * the mechanism that ran, because Android offers several and they are not
 * interchangeable: `pairing_requested` starts a bond, `connect_requested` asks
 * the system to bring a paired device's profiles up, `disconnect_requested`
 * asks it to bring them down, and `manual_required` / `disconnect_unavailable`
 * mean this Android version exposes no call at all. [detail] carries the
 * sentence the caller needs to act on, which [error] cannot hold without being
 * read as a failure. Additive field; no consumer decodes this type.
 */
@Serializable data class BluetoothResult(val success: Boolean, val action: String, val device: String? = null, val error: String? = null, val detail: String? = null) : ToolResultData
@Serializable data class NetworkDevice(val ip: String, val mac: String, val name: String) : ToolResultData
@Serializable data class NetworkScanResult(val success: Boolean, val devices: List<NetworkDevice>, val error: String? = null) : ToolResultData
@Serializable data class NetworkPingResult(val success: Boolean, val host: String, val output: String? = null, val error: String? = null) : ToolResultData