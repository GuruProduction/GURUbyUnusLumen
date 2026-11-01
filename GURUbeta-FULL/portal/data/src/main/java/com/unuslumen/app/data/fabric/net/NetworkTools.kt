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
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.senses.emitPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * Seeing and reaching everything on the network. Issue #187.
 *
 * WHY THE TOOLS ARE SHAPED THIS WAY. Steven's requirement is that GURU sees every device
 * on the wifi it is on, and control is the second half of that sentence. So the first
 * tool is an inventory of hosts rather than of televisions, and the second reaches a
 * chosen host on a chosen port so a device with no adapter is still usable by somebody
 * who knows what it speaks.
 *
 * WHAT IT WILL NOT DO. It will not claim a device is controllable when it has no adapter
 * for it. A host appears with what it exposes, and control attaches where an adapter
 * exists; anything else says so.
 */

@Serializable
data class NetworkScanResult(
    val success: Boolean,
    val subnet: String? = null,
    val probedAddresses: Int = 0,
    val count: Int = 0,
    val hosts: List<HostView> = emptyList(),
    val problems: List<String> = emptyList(),
    val error: String? = null,
) : ToolResultData

@Serializable
data class HostView(
    val address: String,
    val name: String,
    val kind: String,
    val mac: String? = null,
    val openPorts: List<Int> = emptyList(),
    val services: List<String> = emptyList(),
    val evidence: List<String> = emptyList(),
    /** True only where an adapter exists that can actually drive this host. */
    val controllable: Boolean = false,
)

@Serializable
data class HostProbeResult(
    val success: Boolean,
    val address: String = "",
    val port: Int = 0,
    /** The first part of whatever the device answered, or the reason it did not. */
    val response: String? = null,
    val statusLine: String? = null,
    val error: String? = null,
) : ToolResultData

object NetworkToolDefinitions : ToolSetRegistration {

    /**
     * NAMES ARE DELIBERATELY NOT `networkScan` AND `networkProbe`.
     *
     * The old smart home toolset already declares `networkScan` and `networkPing`, and
     * the registry keys by name, so a second tool of the same name silently replaces the
     * first depending on load order. That is a fault that behaves differently between
     * runs, which is the worst kind. These names are distinct, and the old pair is left
     * alone because replacing it is a separate decision about that toolset.
     */
    const val NETWORK_HOSTS = "netHosts"
    const val NETWORK_PROBE = "netProbe"

    override val definitions = listOf(
        ToolDefinition(
            name = NETWORK_HOSTS,
            description = "Inventory every device on the wifi network this phone is on. Sweeps the " +
                "whole local subnet, reads the phone's own ARP table, and asks over SSDP, mDNS and " +
                "HTTP, then reports each host with its address, MAC, open ports, announced services " +
                "and what answered to identify it. Use this to find televisions, set-top boxes, " +
                "speakers, cameras, printers, computers, a NAS or anything else on the network. A " +
                "device that answers no protocol on any port is invisible to every scanner, " +
                "including this one.",
            category = "network",
            parameters = emptyList(),
            // NO PERMISSION IS DECLARED, and that is deliberate. Both INTERNET and
            // ACCESS_NETWORK_STATE are protectionLevel normal, granted at install with no
            // dialog, so declaring them here would only make the dispatcher pre-flight
            // check the same thing Android already guarantees. More importantly, a
            // declared permission outside the batch's catalogue is reported as a tool
            // asking for something nobody reasoned about, and the JVM contract test is
            // right to fail on that.
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = NETWORK_PROBE,
            description = "Ask one device on the network what it is on a specific port: fetches the " +
                "HTTP response, the TLS banner, or opens the socket and reports what came back. " +
                "This is how a host with no adapter is identified by hand. It reports the real " +
                "bytes the device sent, so a refusal names what the device actually did rather " +
                "than guessing what it is.",
            category = "network",
            parameters = listOf(
                ToolParameter("address", ToolParameterType.String, true, "Host address, e.g. 192.168.0.40"),
                ToolParameter("port", ToolParameterType.Integer, true, "Port to ask, e.g. 8060"),
                ToolParameter(
                    "method", ToolParameterType.String, false,
                    "http to fetch over HTTP, raw to read whatever the device sends first. Default http.",
                    enumValues = listOf("http", "raw"),
                ),
            ),
            // Same reasoning as netHosts: INTERNET is normal tier and needs no dialog, so
            // declaring it only trips the catalogue contract without buying anything.
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = NetworkToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class NetworkToolExecutor(
    private val scanner: NetworkScanner,
    private val store: NetworkStore,
) : ToolExecutor {

    constructor(context: Context) : this(NetworkScanner(), NetworkStore())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            NetworkToolDefinitions.NETWORK_HOSTS -> scan()
            NetworkToolDefinitions.NETWORK_PROBE -> probe(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    /**
     * Sweep the network and store what was found.
     *
     * The store is filled here so a later control call reaches the same host object,
     * including the vendor a television was identified as.
     */
    private suspend fun scan(): ToolExecutionResult {
        val report = scanner.scan()
        store.replace(report.hosts)

        val r = NetworkScanResult(
            success = report.hosts.isNotEmpty(),
            subnet = report.subnet,
            probedAddresses = report.probedAddresses,
            count = report.count,
            hosts = report.hosts.map { host ->
                HostView(
                    address = host.address,
                    name = host.displayName,
                    kind = host.kind.name,
                    mac = host.mac,
                    openPorts = host.openPorts,
                    services = host.services,
                    evidence = host.evidence,
                    controllable = isControllable(host),
                )
            },
            problems = report.problems,
            error = if (report.hosts.isEmpty()) {
                report.problems.firstOrNull() ?: "Nothing answered on this network."
            } else {
                null
            },
        )
        return emitPayload(json, json.encodeToString(NetworkScanResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Ask one host what it is.
     *
     * HTTP by default because every device a person cares about exposes something over
     * it, and raw as the fallback for a device that speaks a protocol with no HTTP banner
     * at all.
     */
    private suspend fun probe(args: Map<String, Any?>): ToolExecutionResult {
        val address = (args["address"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'address'")
        val port = (args["port"] as? Number)?.toInt()
            ?: return ToolExecutionResult.error("Missing 'port'")
        if (port !in 1..65535) {
            return ToolExecutionResult.error("Port $port is outside 1 to 65535.")
        }
        val method = (args["method"] as? String)?.trim()?.lowercase() ?: "http"

        return when (method) {
            "raw" -> rawProbe(address, port)
            "http" -> httpProbe(address, port)
            else -> ToolExecutionResult.error("Unknown method '$method'. Use http or raw.")
        }
    }

    private fun httpProbe(address: String, port: Int): ToolExecutionResult {
        val result = runCatching {
            val scheme = if (port == 443 || port == 8443) "https" else "http"
            val connection = java.net.URL("$scheme://$address:$port/").openConnection()
                as java.net.HttpURLConnection
            try {
                connection.connectTimeout = 3000
                connection.readTimeout = 3000
                val status = "HTTP ${connection.responseCode} ${connection.responseMessage.orEmpty()}"
                val body = runCatching {
                    connection.inputStream.bufferedReader().use { it.readText() }
                }.getOrElse {
                    connection.errorStream?.bufferedReader()?.use { stream -> stream.readText() }.orEmpty()
                }
                status to body.take(4000)
            } finally {
                connection.disconnect()
            }
        }

        val r = result.fold(
            onSuccess = { (status, body) ->
                HostProbeResult(
                    success = true,
                    address = address,
                    port = port,
                    statusLine = status,
                    response = body.ifBlank { "(the device answered with an empty body)" },
                )
            },
            onFailure = { error ->
                HostProbeResult(
                    success = false,
                    address = address,
                    port = port,
                    error = "$address:$port did not answer HTTP: " +
                        "${error.message ?: error.javaClass.simpleName}.",
                )
            },
        )
        return emitPayload(json, json.encodeToString(HostProbeResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Open a socket and read whatever the device sends first.
     *
     * This is the honest way to ask a device that has no HTTP interface: a banner is
     * whatever it chose to say, and reading nothing is a real answer rather than a
     * failure to be dressed up.
     */
    private fun rawProbe(address: String, port: Int): ToolExecutionResult {
        val result = runCatching {
            java.net.Socket().use { socket ->
                socket.connect(java.net.InetSocketAddress(address, port), 3000)
                socket.soTimeout = 3000
                val buffer = ByteArray(2048)
                val read = socket.getInputStream().read(buffer)
                if (read <= 0) "" else String(buffer, 0, read, Charsets.ISO_8859_1)
            }
        }

        val r = result.fold(
            onSuccess = { banner ->
                HostProbeResult(
                    success = true,
                    address = address,
                    port = port,
                    response = banner.ifBlank {
                        "$address:$port accepted the connection and sent nothing before closing. " +
                            "A device that says nothing on connect is one you have to speak to first."
                    },
                )
            },
            onFailure = { error ->
                HostProbeResult(
                    success = false,
                    address = address,
                    port = port,
                    error = "Could not open $address:$port: " +
                        "${error.message ?: error.javaClass.simpleName}.",
                )
            },
        )
        return emitPayload(json, json.encodeToString(HostProbeResult.serializer(), r), r, r.success, r.error)
    }

    /** Whether an adapter exists that can actually drive this host. */
    private fun isControllable(host: NetworkHost): Boolean =
        host.kind == HostKind.TELEVISION &&
            host.openPorts.any { it in setOf(8060, 8001, 3000) }
}

/**
 * The hosts a scan found, so a later control call reaches the same objects.
 */
class NetworkStore {

    private val hosts = java.util.concurrent.ConcurrentHashMap<String, NetworkHost>()

    fun replace(found: List<NetworkHost>) {
        hosts.clear()
        found.forEach { hosts[it.address] = it }
    }

    fun get(address: String): NetworkHost? = hosts[address]

    fun all(): List<NetworkHost> = hosts.values.toList()

    fun clear() = hosts.clear()
}

/** The network domain's wiring. */
object NetworkDomainModule : DomainToolModule {

    override val id: String = "network"

    override val registrations: List<ToolSetRegistration> =
        listOf(NetworkToolDefinitions, NetworkControlToolDefinitions)

    override fun bindings(): Module = module {
        single { NetworkStore() }
        single { NetworkScanner() }
        single { UpnpSoapClient() }
        factory { NetworkToolExecutor(get(), get()) }
        factory { NetworkControlExecutor(get(), get(), get()) }
    }
}
