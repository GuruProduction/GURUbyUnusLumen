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
        ToolDefinition(name = MDNS_LIST_DEVICES, description = "List all devices discovered via mDNS.", category = "environment", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = MDNS_STOP_DISCOVERY, description = "Stop mDNS discovery.", category = "environment", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SSDP_DISCOVER, description = "Start SSDP/UPnP network discovery. Discovers Samsung TVs, DLNA servers, routers.", category = "environment", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SSDP_LIST_DEVICES, description = "List all devices discovered via SSDP/UPnP.", category = "environment", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = SSDP_STOP_DISCOVERY, description = "Stop SSDP discovery.", category = "environment", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = ARP_SCAN, description = "Scan the local network using ARP table reading. Finds all devices on the network.", category = "environment", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = WIFI_SCAN, description = "Scan all nearby WiFi networks. Shows SSIDs, signal strengths, security types.", category = "environment", parameters = emptyList(), permissions = listOf("android.permission.ACCESS_FINE_LOCATION")),
        ToolDefinition(name = BLUETOOTH_SCAN, description = "Active discovery of Bluetooth devices in range.", category = "environment", parameters = emptyList(), permissions = listOf("android.permission.BLUETOOTH_SCAN")),
        ToolDefinition(name = TCP_CONNECT, description = "Open a raw TCP socket connection to any IP:port. NOT routed through Tor.", category = "environment", parameters = listOf(ToolParameter("host", ToolParameterType.String, true, "IP address or hostname"), ToolParameter("port", ToolParameterType.Integer, true, "Port number"), ToolParameter("data", ToolParameterType.String, true, "Data to send"), ToolParameter("timeoutMs", ToolParameterType.Integer, false, "Timeout in ms, default 5000")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = EnvironmentToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}