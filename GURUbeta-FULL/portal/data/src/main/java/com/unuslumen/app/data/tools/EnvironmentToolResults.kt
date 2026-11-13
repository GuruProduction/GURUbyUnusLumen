// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.discovery.NetworkReadOutcome
import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class MdnsDiscoverResult(val success: Boolean, val message: String?, val error: String?) : ToolResultData
/**
 * What `mdnsListDevices` returns.
 *
 * [listener] IS THE POINT OF THIS SHAPE. Issue 222: this tool returned `[]` whether the
 * listener had never started, had failed to bind, was up and had heard nothing yet, or
 * was up on a network with no mDNS traffic. Four different facts, one empty array, and
 * the model reported all four as "there is no Apple TV on this network".
 *
 * [listener] carries which of those it is, plus which service types are actually being
 * watched. A caller reading only [devices] and [success] is unaffected.
 */
@Serializable data class MdnsDevicesResult(
    val success: Boolean,
    val devices: List<MdnsDevice>,
    val error: String?,
    /** STOPPED, BIND_FAILED, PARTIAL, LISTENING or FOUND. */
    val listener: String = "STOPPED",
    /** Service types the platform accepted, so silence on one can be judged. */
    val listeningTypes: List<String> = emptyList(),
    /** Service types the platform refused, with its own error code. */
    val refusedTypes: List<String> = emptyList(),
) : ToolResultData
@Serializable data class MdnsDevice(val serviceName: String, val serviceType: String, val host: String, val port: Int, val discoveredAt: Long) : ToolResultData
@Serializable data class SsdpDiscoverResult(val success: Boolean, val message: String?, val error: String?) : ToolResultData
/**
 * What `ssdpListDevices` returns.
 *
 * [problems] IS THE POINT (#332, #333). Three of the six televisions on the reported
 * network came back with an empty `friendlyName` while three did not, and nothing in
 * the result said why. A device that answered SSDP and could not be named now appears
 * in [problems] with the reason, so an empty name is never read as "this device has no
 * name" when the truth may be "its description was never fetched".
 */
@Serializable data class SsdpDevicesResult(
    val success: Boolean,
    val devices: List<SsdpDeviceDto>,
    val error: String?,
    /** One line per device whose name is missing, naming the reason. */
    val problems: List<String> = emptyList(),
    /**
     * FOUND, EMPTY or REFUSED (issue 332's definition of done).
     *
     * [problems] explained an unnamed device but said nothing about the case where SSDP produced
     * no devices at all, which is the same defect the arp table and the mDNS listener had: a socket
     * that never bound and a network with nothing on it produced the same empty list. REFUSED is
     * the listen never happening; EMPTY is a real answer about the network.
     */
    val outcome: String = NetworkReadOutcome.EMPTY.name,
    /** The multicast socket's state, so silence can be judged rather than assumed. */
    val listener: String = "STOPPED",
    /** The subnet the multicast group was swept from, or null when the phone has no LAN address. */
    val subnet: String? = null,
    /** Which tier served the read. */
    val tier: String = "APP_UID",
) : ToolResultData
@Serializable data class SsdpDeviceDto(
    val usn: String,
    val st: String,
    val location: String,
    val server: String,
    val host: String,
    val friendlyName: String,
    val manufacturer: String,
    val modelName: String,
    val discoveredAt: Long,
    /** Why the name fields are what they are: FETCHED, UNNAMED, NOT_FETCHED, and so on. */
    val descriptionState: String = "NOT_FETCHED",
) : ToolResultData
/**
 * What `arpScan` returns.
 *
 * [outcome] IS THE POINT OF THIS SHAPE, NOT DECORATION. Issues 221 and 224 were
 * this tool returning `{"success":true,"devices":[]}` when Android had refused the
 * ARP table, so a caller read "the network is empty" as a measurement. The three
 * states [NetworkReadOutcome] carries are the difference between a real empty
 * network, a real finding, and a table nobody was allowed to open. [devices] only
 * means anything in the first two.
 *
 * A caller that reads only [success] keeps working, because it is false for a
 * refusal. A caller that reads [outcome] can tell the three apart, which is what
 * stops the model reporting a refusal as an observation about the house.
 */
@Serializable data class ArpScanResult(
    val success: Boolean,
    val devices: List<ArpDeviceDto>,
    val error: String?,
    /** FOUND, EMPTY or REFUSED. REFUSED means the table was never opened. */
    val outcome: String = NetworkReadOutcome.EMPTY.name,
    /** Which tier served the read, so a caller can see how complete this answer is. */
    val tier: String = "APP_UID",
    /** The subnet that was swept, or null when the phone has no LAN address. */
    val subnet: String? = null,
    /** Which neighbour table answered, so an empty answer can be traced. */
    val servedBy: String? = null,
) : ToolResultData
@Serializable data class ArpDeviceDto(val ip: String, val mac: String, val manufacturer: String, val hostname: String, val discoveredAt: Long) : ToolResultData
/*
 * [outcome] IS THE POINT OF THIS SHAPE (issue 337).
 *
 * This was `success` plus a list, and on a failure it was `success=false, networks=[]`. A caller
 * could tell a failure from a success, but the MODEL reads the list, and an empty list under a
 * failed call is the same value an empty list under a successful call would be. The ticket's rule
 * is that a tool must never return a value a genuine result could also produce, and "no wifi
 * networks are visible from here" is a genuine result a phone can produce.
 *
 * So the same three states arpScan carries: FOUND, EMPTY and REFUSED, with REFUSED meaning the
 * scan never ran — no location permission, wifi turned off, or the platform refusing the request.
 */
@Serializable data class WifiScanResult(
    val success: Boolean,
    val networks: List<WifiNetworkDto>,
    val error: String?,
    /** FOUND, EMPTY or REFUSED. REFUSED means the platform never answered the scan. */
    val outcome: String = "EMPTY",
    /** The sentence to repeat, naming the route when a permission is what is missing. */
    val outcomeSentence: String? = null,
) : ToolResultData
@Serializable data class WifiNetworkDto(val ssid: String, val bssid: String, val signalStrength: Int, val frequency: Int, val channel: Int, val securityType: String, val isHidden: Boolean, val isCurrentlyConnected: Boolean, val capabilities: String) : ToolResultData
/*
 * The same three states, for the same reason (issue 337).
 *
 * A Bluetooth scan has an extra way to be meaningless: Bluetooth can be OFF. In that case there
 * are genuinely no devices visible, and reporting that as an empty scan is a statement about the
 * room made by a radio that was not transmitting. The outcome separates it: REFUSED covers radio
 * off, permission absent and any platform refusal, and each carries a sentence naming which.
 */
@Serializable data class BluetoothScanResult(
    val success: Boolean,
    val devices: List<DiscoveredBluetoothDevice>,
    val error: String?,
    /** FOUND, EMPTY or REFUSED. REFUSED means the radio never scanned. */
    val outcome: String = "EMPTY",
    /** The sentence to repeat, naming the route when a setting or permission is what is missing. */
    val outcomeSentence: String? = null,
) : ToolResultData
@Serializable data class DiscoveredBluetoothDevice(val name: String, val mac: String, val deviceType: String, val majorDeviceClass: String, val minorDeviceClass: String, val bondState: String, val isPaired: Boolean, val rssi: Int?, val timestamp: Long) : ToolResultData
@Serializable data class TcpConnectResult(val success: Boolean, val host: String, val port: Int, val response: String?, val responseHex: String?, val bytesReceived: Int, val error: String?) : ToolResultData