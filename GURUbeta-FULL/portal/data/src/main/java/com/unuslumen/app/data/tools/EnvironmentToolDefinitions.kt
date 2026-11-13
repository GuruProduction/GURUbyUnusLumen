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

object EnvironmentToolDefinitions : ToolSetRegistration {
    const val MDNS_DISCOVER = "mdnsDiscover"; const val MDNS_LIST_DEVICES = "mdnsListDevices"; const val MDNS_STOP_DISCOVERY = "mdnsStopDiscovery"
    const val SSDP_DISCOVER = "ssdpDiscover"; const val SSDP_LIST_DEVICES = "ssdpListDevices"; const val SSDP_STOP_DISCOVERY = "ssdpStopDiscovery"
    const val ARP_SCAN = "arpScan"; const val WIFI_SCAN = "wifiScan"; const val BLUETOOTH_SCAN = "bluetoothScan"; const val TCP_CONNECT = "tcpConnect"

    override val definitions = listOf(
        ToolDefinition(name = MDNS_DISCOVER, description = "Start mDNS/Bonjour network discovery. Discovers Apple TV, Chromecast, Sonos, Hue bridges, printers.", category = "environment", parameters = emptyList(), permissions = emptyList()),
        // THE DESCRIPTION TELLS THE MODEL HOW TO READ AN EMPTY LIST (#222). An empty
        // result used to mean four different things at once, and the model reported all
        // four as "there is no Apple TV here". The result now carries the listener state
        // and the service types actually being watched.
        ToolDefinition(
            name = MDNS_LIST_DEVICES,
            description = "List the devices mDNS has heard: Apple TV and AirPlay receivers, " +
                "Chromecasts, Sonos speakers, Hue bridges, printers. Reports the listener state " +
                "alongside the devices, so an empty list is never ambiguous. FOUND means devices " +
                "are listed, LISTENING means the listener is up and the wifi has announced nothing, " +
                "PARTIAL means some service types were refused and their silence proves nothing, " +
                "BIND_FAILED means Android refused every type and nothing was heard at all, and " +
                "STOPPED means discovery was never started. Only LISTENING and FOUND are a " +
                "statement about the network. Call mdnsDiscover first and give it a few seconds.",
            category = "environment",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(name = MDNS_STOP_DISCOVERY, description = "Stop mDNS discovery.", category = "environment", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SSDP_DISCOVER, description = "Start SSDP/UPnP network discovery. Discovers Samsung TVs, DLNA servers, routers.", category = "environment", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SSDP_LIST_DEVICES, description = "List all devices discovered via SSDP/UPnP.", category = "environment", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SSDP_STOP_DISCOVERY, description = "Stop SSDP discovery.", category = "environment", parameters = emptyList(), permissions = emptyList()),
        // THE DESCRIPTION NAMES THE WALL, BECAUSE THE OLD ONE PROMISED WHAT THE TOOL
        // COULD NOT DO (#224). It said "finds all devices on the network" while a
        // refusal from Android came back as an empty success. Android hides the ARP
        // table from an app UID, so without Shizuku or wireless debugging this tool
        // can only report that it was not allowed to look, and the result now says
        // which of the three outcomes it got rather than leaving the model to guess.
        ToolDefinition(
            name = ARP_SCAN,
            description = "Read the phone's neighbour table to list the IP and MAC of every device " +
                "on the local network, with the manufacturer from the MAC prefix. Reports whether " +
                "the table was FOUND, genuinely EMPTY, or REFUSED: a REFUSED result means Android " +
                "would not let this tier read /proc/net/arp, which says nothing about the network " +
                "and is what Shizuku or wireless debugging unlocks. An EMPTY result is the real " +
                "answer that nothing answered. Use netHosts or netEverything to sweep the subnet " +
                "rather than read a table.",
            category = "environment",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(name = WIFI_SCAN, description = "Scan all nearby WiFi networks. Shows SSIDs, signal strengths, security types.", category = "environment", parameters = emptyList(), permissions = listOf("android.permission.ACCESS_FINE_LOCATION")),
        // Discovery reports each device's name and bond state, which Bluetooth treats as
        // a connect-tier read, so both grants are declared and both are guarded. Listing
        // only SCAN here would let the pre-flight pass and the scan throw underneath.
        ToolDefinition(name = BLUETOOTH_SCAN, description = "Active discovery of Bluetooth devices in range, returning each device's name, MAC, class, bond state and signal strength. Needs Nearby devices, which covers both the scan and reading a device's name. For a signal-strength history or a service-UUID filter use bleScan.", category = "environment", parameters = emptyList(), permissions = listOf("android.permission.BLUETOOTH_SCAN", "android.permission.BLUETOOTH_CONNECT")),
        ToolDefinition(name = TCP_CONNECT, description = "Open a raw TCP socket connection to any IP:port. NOT routed through Tor.", category = "environment", parameters = listOf(ToolParameter("host", ToolParameterType.String, true, "IP address or hostname"), ToolParameter("port", ToolParameterType.Integer, true, "Port number"), ToolParameter("data", ToolParameterType.String, true, "Data to send"), ToolParameter("timeoutMs", ToolParameterType.Integer, false, "Timeout in ms, default 5000")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = EnvironmentToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}