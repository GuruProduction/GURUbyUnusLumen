// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.discovery.ArpDiscoveryManager
import com.unuslumen.app.data.discovery.BluetoothScanManager
import com.unuslumen.app.data.discovery.ListenerState
import com.unuslumen.app.data.discovery.MdnsDiscoveryManager
import com.unuslumen.app.data.discovery.NetworkReadOutcome
import com.unuslumen.app.data.discovery.SsdpDescriptions
import com.unuslumen.app.data.discovery.SsdpDiscoveryManager
import com.unuslumen.app.data.discovery.WifiScanManager
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolRead
import com.unuslumen.app.data.tools.registry.ToolReads
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import com.unuslumen.app.data.tools.senses.emitPayload
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
    /**
     * `encodeDefaults = true` matters for `arpScan` specifically. Without it a field
     * equal to its default is dropped from the JSON, so an EMPTY outcome would encode
     * as `{"success":true,"devices":[],"error":null}` with the outcome missing and look
     * exactly like the refusal this change exists to distinguish. Writing every field
     * means the model always sees which of the three states it got.
     */
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (toolName) {
            EnvironmentToolDefinitions.MDNS_DISCOVER -> { try { mdnsDiscoveryManager.startDiscovery(); val r = MdnsDiscoverResult(true, "mDNS discovery started", null); ToolExecutionResult.success(r, json.encodeToString(MdnsDiscoverResult.serializer(), r)) } catch (e: Exception) { val r = MdnsDiscoverResult(false, null, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(MdnsDiscoverResult.serializer(), r)) } }
            /*
             * MDNS LIST DEVICES CARRIES THE LISTENER'S STATE (#222).
             *
             * This branch used to report `success = true` with an empty device list, so the
             * model could not tell "the listener never bound" from "the wifi is quiet". It
             * now reports which of the five listener states applies, and which service
             * types are actually being watched, so silence on a refused type is never read
             * as evidence that a device is absent.
             *
             * A LISTENING listener with no results stays a success: it genuinely looked and
             * found nothing, which is a real answer. BIND_FAILED and STOPPED fail, because
             * in both cases nothing was heard and the empty list describes the app.
             */
            EnvironmentToolDefinitions.MDNS_LIST_DEVICES -> {
                try {
                    val report = mdnsDiscoveryManager.report()
                    val devs = mdnsDiscoveryManager.getDiscoveredDevices()
                        .map { MdnsDevice(it.serviceName, it.serviceType, it.host, it.port, it.discoveredAt) }
                    val honest = report.state == ListenerState.LISTENING || report.state == ListenerState.FOUND
                    val r = MdnsDevicesResult(
                        success = honest,
                        devices = devs,
                        error = report.sentence,
                        listener = report.state.name,
                        listeningTypes = report.listening,
                        refusedTypes = report.refusedTypes,
                    )
                    emitPayload(json, json.encodeToString(MdnsDevicesResult.serializer(), r), r, r.success, r.error)
                } catch (e: Exception) {
                    val r = MdnsDevicesResult(
                        success = false,
                        devices = emptyList(),
                        error = "mDNS list failed: ${e.message}",
                        listener = ListenerState.BIND_FAILED.name,
                    )
                    emitPayload(json, json.encodeToString(MdnsDevicesResult.serializer(), r), r, false, r.error)
                }
            }
            EnvironmentToolDefinitions.MDNS_STOP_DISCOVERY -> { try { mdnsDiscoveryManager.stopDiscovery(); val r = MdnsDiscoverResult(true, "Stopped", null); ToolExecutionResult.success(r, json.encodeToString(MdnsDiscoverResult.serializer(), r)) } catch (e: Exception) { val r = MdnsDiscoverResult(false, null, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(MdnsDiscoverResult.serializer(), r)) } }
            EnvironmentToolDefinitions.SSDP_DISCOVER -> { try { ssdpDiscoveryManager.startDiscovery(); val r = SsdpDiscoverResult(true, "SSDP discovery started", null); ToolExecutionResult.success(r, json.encodeToString(SsdpDiscoverResult.serializer(), r)) } catch (e: Exception) { val r = SsdpDiscoverResult(false, null, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(SsdpDiscoverResult.serializer(), r)) } }
            /*
             * SSDP LIST DEVICES NAMES WHY A DEVICE HAS NO NAME (#333, #332).
             *
             * On the reported network three of six televisions came back with an empty
             * friendlyName and nothing said why. Each device now carries its description
             * state, and every unnamed one appears in `problems[]` with the reason, so an
             * empty name is never read as "this device has no name" when the truth may be
             * "its description was never fetched".
             */
            EnvironmentToolDefinitions.SSDP_LIST_DEVICES -> {
                try {
                    val devices = ssdpDiscoveryManager.getDiscoveredDevices()
                    val devs = devices.map {
                        SsdpDeviceDto(
                            usn = it.usn,
                            st = it.st,
                            location = it.location,
                            server = it.server,
                            host = it.host,
                            friendlyName = it.friendlyName,
                            manufacturer = it.manufacturer,
                            modelName = it.modelName,
                            discoveredAt = it.discoveredAt,
                            descriptionState = it.descriptionState.name,
                        )
                    }
                    // One line per device that answered but has no name, so a partial list is
                    // visibly partial rather than looking like a complete one.
                    val problems = devices
                        .filter { it.friendlyName.isBlank() }
                        .map {
                            SsdpDescriptions.problemSentence(
                                host = it.host,
                                usn = it.usn,
                                state = it.descriptionState,
                                detail = it.descriptionDetail,
                            )
                        }
                    val r = SsdpDevicesResult(
                        success = true,
                        devices = devs,
                        error = null,
                        problems = problems,
                    )
                    emitPayload(json, json.encodeToString(SsdpDevicesResult.serializer(), r), r, r.success, r.error)
                } catch (e: Exception) {
                    val r = SsdpDevicesResult(success = false, devices = emptyList(), error = "SSDP list failed: ${e.message}")
                    emitPayload(json, json.encodeToString(SsdpDevicesResult.serializer(), r), r, false, r.error)
                }
            }
            EnvironmentToolDefinitions.SSDP_STOP_DISCOVERY -> { try { ssdpDiscoveryManager.stopDiscovery(); val r = SsdpDiscoverResult(true, "Stopped", null); ToolExecutionResult.success(r, json.encodeToString(SsdpDiscoverResult.serializer(), r)) } catch (e: Exception) { val r = SsdpDiscoverResult(false, null, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(SsdpDiscoverResult.serializer(), r)) } }
            /*
             * ARP SCAN REPORTS WHICH OF THREE THINGS HAPPENED (#221, #224).
             *
             * This branch used to be `ToolExecutionResult.success(ArpScanResult(true, devs, null))`
             * unconditionally, so a refusal from Android arrived at the model as
             * `{"success":true,"devices":[]}`, and the model reported that as "there is
             * nothing on your network". The result now carries the outcome, the tier that
             * served the read and which table answered, and the payload's own flag decides
             * `success` through emitPayload rather than a constant.
             *
             * EMPTY stays a success: a table that was read and has no neighbours is a real
             * answer, and calling it a failure would send a caller hunting for a fault that
             * is not there. Only REFUSED fails, because only REFUSED means we did not look.
             */
            EnvironmentToolDefinitions.ARP_SCAN -> {
                try {
                    val sweep = arpDiscoveryManager.sweepSuspending()
                    val devs = sweep.devices.map { ArpDeviceDto(it.ip, it.mac, it.manufacturer, it.hostname, it.discoveredAt) }
                    val r = ArpScanResult(
                        success = sweep.read.outcome != NetworkReadOutcome.REFUSED,
                        devices = devs,
                        error = sweep.read.sentence,
                        outcome = sweep.read.outcome.name,
                        tier = sweep.read.tier.name,
                        subnet = sweep.subnet,
                        servedBy = sweep.servedBy,
                    )
                    emitPayload(json, json.encodeToString(ArpScanResult.serializer(), r), r, r.success, r.error)
                } catch (e: Exception) {
                    val r = ArpScanResult(
                        success = false,
                        devices = emptyList(),
                        error = "ARP scan failed: ${e.message}",
                        outcome = NetworkReadOutcome.REFUSED.name,
                        tier = "NONE",
                    )
                    emitPayload(json, json.encodeToString(ArpScanResult.serializer(), r), r, false, r.error)
                }
            }
            /*
             * A FAILED SCAN IS A REFUSAL, NOT AN EMPTY NETWORK (issue 337).
             *
             * This branch answered a failed scan with `success=false, networks=[]` plus an error
             * string. The MODEL reads the list, and "no wifi networks are visible" is a value a
             * working scan genuinely produces, so for the only reader that matters the two were
             * indistinguishable. `outcome` now carries FOUND, EMPTY or REFUSED from the shared
             * rule in [ToolReads], and a refusal also carries the sentence naming the route.
             *
             * Location permission is the usual cause: Android will not return scan results to an
             * app without it, and a phone with wifi switched off produces the same empty list as
             * a flat with no router in it.
             */
            EnvironmentToolDefinitions.WIFI_SCAN -> {
                try {
                    val nets = wifiScanManager.scanNetworks().map { WifiNetworkDto(it.ssid, it.bssid, it.signalStrength, it.frequency, it.channel, it.securityType, it.isHidden, it.isCurrentlyConnected, it.capabilities) }
                    val read = ToolReads.fromShell(
                        succeeded = true,
                        items = nets,
                        tier = "APP_UID",
                        refusalReason = "The wifi scan did not run.",
                        remedy = "Grant location permission, which Android requires before it returns scan results.",
                    )
                    val r = WifiScanResult(
                        success = read.answered,
                        networks = read.values.orEmpty(),
                        error = null,
                        outcome = ToolReads.outcomeName(read),
                        outcomeSentence = if (read.answered) null else ToolReads.sentence(read, "wifi networks"),
                    )
                    ToolExecutionResult.success(r, json.encodeToString(WifiScanResult.serializer(), r))
                } catch (e: Exception) {
                    val read: ToolRead<WifiNetworkDto> = ToolRead.Refused(
                        tier = "APP_UID",
                        reason = "The wifi scan threw ${e.javaClass.simpleName}: ${e.message}",
                        remedy = "Grant location permission and check that wifi is switched on. " +
                            "Android refuses scan results to an app without location.",
                    )
                    val r = WifiScanResult(
                        success = false,
                        networks = emptyList(),
                        error = ToolReads.sentence(read, "wifi networks"),
                        outcome = ToolReads.outcomeName(read),
                        outcomeSentence = ToolReads.sentence(read, "wifi networks"),
                    )
                    ToolExecutionResult.success(r, json.encodeToString(WifiScanResult.serializer(), r))
                }
            }
            // BluetoothScanManager now refuses through PermissionGrants itself, so this
            // branch only maps its result. A refusal there carries the catalogue
            // sentence in `error`, which is exactly what the model should read.
            /*
             * A BLUETOOTH REFUSAL IS NOT AN EMPTY ROOM (issue 337).
             *
             * Bluetooth has an extra way to be meaningless that no other scan here has: the radio
             * can be OFF. When it is, there are genuinely no devices visible, and reporting that
             * as an empty scan is a statement about the room made by a radio that was not
             * transmitting. So the manager's own success flag feeds the shared rule and the
             * outcome says which of the three happened.
             *
             * The manager already refuses through PermissionGrants, so a permission refusal
             * carries the catalogue sentence in `error`, and it is repeated into
             * `outcomeSentence` so a reader that only looks at the new field still sees it.
             */
            EnvironmentToolDefinitions.BLUETOOTH_SCAN -> {
                try {
                    val result = bluetoothScanManager.scan()
                    val devs = result.devices.map { DiscoveredBluetoothDevice(it.name, it.mac, it.deviceType, it.majorDeviceClass, it.minorDeviceClass, it.bondState, it.isPaired, it.rssi, it.timestamp) }
                    val read = ToolReads.fromShell(
                        succeeded = result.success,
                        items = devs,
                        tier = "APP_UID",
                        refusalReason = result.error ?: "The Bluetooth scan did not run.",
                        remedy = "Grant BLUETOOTH_SCAN and turn Bluetooth on. A scan with the radio " +
                            "off is not a report about nearby devices.",
                    )
                    val r = BluetoothScanResult(
                        success = read.answered,
                        devices = read.values.orEmpty(),
                        error = if (read.answered) result.error else null,
                        outcome = ToolReads.outcomeName(read),
                        outcomeSentence = if (read.answered) null else ToolReads.sentence(read, "Bluetooth devices"),
                    )
                    ToolExecutionResult.success(r, json.encodeToString(BluetoothScanResult.serializer(), r))
                } catch (e: SecurityException) {
                    PermissionGrants.refusal(PermissionGrants.check(context, TeamThreePermissions.BLUETOOTH_SCAN))
                } catch (e: Exception) {
                    val read = ToolRead.Refused(
                        tier = "APP_UID",
                        reason = "The Bluetooth scan threw ${e.javaClass.simpleName}: ${e.message}",
                        remedy = "Grant BLUETOOTH_SCAN and check that Bluetooth is switched on.",
                    )
                    val r = BluetoothScanResult(
                        success = false,
                        devices = emptyList(),
                        error = ToolReads.sentence(read, "Bluetooth devices"),
                        outcome = ToolReads.outcomeName(read),
                        outcomeSentence = ToolReads.sentence(read, "Bluetooth devices"),
                    )
                    ToolExecutionResult.success(r, json.encodeToString(BluetoothScanResult.serializer(), r))
                }
            }
            EnvironmentToolDefinitions.TCP_CONNECT -> { val host = args["host"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'host'"); val port = (args["port"] as? Number)?.toInt() ?: return@withContext ToolExecutionResult.error("Missing 'port'"); val data = args["data"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'data'"); val timeoutMs = (args["timeoutMs"] as? Number)?.toLong() ?: 5000L; var socket: java.net.Socket? = null; try { socket = java.net.Socket(); socket.connect(java.net.InetSocketAddress(host, port), timeoutMs.toInt()); socket.soTimeout = timeoutMs.toInt(); val out = socket.getOutputStream(); out.write(data.toByteArray()); out.flush(); val inp = socket.getInputStream(); val buf = ByteArray(8192); val bytes = inp.read(buf); val resp = if (bytes > 0) String(buf, 0, bytes, Charsets.UTF_8) else ""; val r = TcpConnectResult(true, host, port, resp, resp.toByteArray().joinToString("") { "%02x".format(it) }, bytes, null); ToolExecutionResult.success(r, json.encodeToString(TcpConnectResult.serializer(), r)) } catch (e: Exception) { val r = TcpConnectResult(false, host, port, null, null, 0, "Failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(TcpConnectResult.serializer(), r)) } finally { try { socket?.close() } catch (_: Exception) {} } }
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }
}