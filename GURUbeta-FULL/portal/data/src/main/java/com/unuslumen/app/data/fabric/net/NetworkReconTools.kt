// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.senses.emitPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * The deep recon tools: what the shell sees, and every port on a host.
 *
 * WHY THESE ARE SEPARATE FROM THE QUICK SCAN. A person asking "what is on my network" wants
 * the fast inventory. Asking "what is that box and what will it tell me" wants this, which
 * is slower and much deeper. Keeping them apart means the ordinary question stays instant.
 */

@Serializable
data class NetReconResult(
    val success: Boolean,
    val tier: String = "",
    val tierNote: String = "",
    val neighbours: List<NeighbourView> = emptyList(),
    val interfaces: List<InterfaceView> = emptyList(),
    val refusedCount: Int = 0,
    val refused: List<String> = emptyList(),
    val error: String? = null,
) : ToolResultData

@Serializable
data class NeighbourView(
    val address: String,
    val mac: String,
    val vendor: String? = null,
    val device: String? = null,
)

@Serializable
data class InterfaceView(
    val name: String,
    val address: String,
    val prefix: Int? = null,
    val subnet: String? = null,
)

@Serializable
data class NetDeepScanResult(
    val success: Boolean,
    val address: String = "",
    val portsProbed: Int = 0,
    val count: Int = 0,
    val openPorts: List<OpenPortView> = emptyList(),
    val problem: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class OpenPortView(
    val port: Int,
    val service: String,
    val banner: String? = null,
    val server: String? = null,
    val certificate: String? = null,
    val hint: String? = null,
)

object NetworkReconToolDefinitions : ToolSetRegistration {

    const val NET_RECON = "netRecon"
    const val NET_DEEP_SCAN = "netDeepScan"

    override val definitions = listOf(
        ToolDefinition(
            name = NET_RECON,
            description = "See the local network from shell tier rather than app tier. Reads the " +
                "real ARP table, ip neigh, ip route and the interface list, which Android refuses " +
                "to an ordinary app, and reports which devices are on the wire with their MAC " +
                "vendors. When no shell tier answers it says plainly that the UID is the wall " +
                "rather than reporting an empty network, because those are different facts.",
            category = "network",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = NET_DEEP_SCAN,
            description = "Walk every port on one host and ask each open one to identify itself, " +
                "reading its banner, its HTTP Server header and its TLS certificate. This is how " +
                "a device with no adapter is identified: an RTSP banner is a camera, an SSH string " +
                "is a computer, a certificate subject usually names the vendor. Slow by nature, so " +
                "it is for one host at a time rather than a whole subnet.",
            category = "network",
            parameters = listOf(
                ToolParameter("address", ToolParameterType.String, true, "Host address to scan"),
                ToolParameter(
                    "quick", ToolParameterType.Boolean, false,
                    "True to probe only the common ports, which is fast. Default false, which " +
                        "walks all 65535 and takes a while.",
                ),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = NetworkReconExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class NetworkReconExecutor(
    private val recon: ShellNetworkRecon,
    private val deepScanner: DeepPortScanner,
) : ToolExecutor {

    constructor(context: Context) : this(ShellNetworkRecon(), DeepPortScanner())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            NetworkReconToolDefinitions.NET_RECON -> runRecon()
            NetworkReconToolDefinitions.NET_DEEP_SCAN -> deepScan(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    /** The shell-tier view of the network. */
    private suspend fun runRecon(): ToolExecutionResult {
        val result = recon.recon()
        val r = NetReconResult(
            success = result.usable,
            tier = result.tier.name,
            tierNote = result.tierNote,
            neighbours = result.neighbours.map {
                NeighbourView(it.address, it.mac, it.vendor, it.device)
            },
            interfaces = result.interfaces.map {
                InterfaceView(it.name, it.address, it.prefix, it.subnet)
            },
            refusedCount = result.refused.size,
            refused = result.refused.map { (command, reason) -> "$command: $reason" },
            error = if (result.usable) null else result.tierNote,
        )
        return emitPayload(json, json.encodeToString(NetReconResult.serializer(), r), r, r.success, r.error)
    }

    /** Every port on one host, with what each open one said. */
    private suspend fun deepScan(args: Map<String, Any?>): ToolExecutionResult {
        val address = (args["address"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'address'")
        val quick = (args["quick"] as? Boolean) ?: false

        // The common-ports range is every port the quick scanner already knows plus the
        // ones a device most often hides behind, so a quick pass still finds the interfaces.
        val range = if (quick) 1..10000 else 1..65535
        val result = deepScanner.scanHost(address, range)

        val r = NetDeepScanResult(
            success = result.openPorts.isNotEmpty(),
            address = address,
            portsProbed = result.portsProbed,
            count = result.count,
            openPorts = result.openPorts.map {
                OpenPortView(it.port, it.service, it.banner, it.server, it.certificate, it.hint?.name)
            },
            problem = result.problem,
            error = when {
                result.openPorts.isNotEmpty() -> null
                result.problem != null -> result.problem
                else -> "All ${result.portsProbed} ports on $address refused, which means the host " +
                    "is reachable but exposes nothing this scan can see."
            },
        )
        return emitPayload(json, json.encodeToString(NetDeepScanResult.serializer(), r), r, r.success, r.error)
    }
}
