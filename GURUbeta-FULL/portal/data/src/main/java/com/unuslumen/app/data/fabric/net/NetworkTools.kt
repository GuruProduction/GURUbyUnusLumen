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
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

/** What the full sweep found, one entry per device, with how each was identified. */
@Serializable
data class NetworkEverythingResult(
    val success: Boolean,
    val subnet: String? = null,
    val probedAddresses: Int = 0,
    /** How many addresses were present at all, which is not the same as how many were named. */
    val aliveAddresses: Int = 0,
    val count: Int = 0,
    val devices: List<DeviceView> = emptyList(),
    val problems: List<String> = emptyList(),
    val error: String? = null,
) : ToolResultData

/** One device as the tool reports it. */
@Serializable
data class DeviceView(
    val address: String,
    val name: String,
    val deviceClass: String,
    val vendor: String? = null,
    val model: String? = null,
    val location: String? = null,
    /** One line per fact, each naming the protocol and whether it was declared or inferred. */
    val evidence: List<String> = emptyList(),
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

    /**
     * The full sweep, which asks every protocol rather than only the ones the first two use.
     *
     * WHY THIS IS A SEPARATE TOOL RATHER THAN netHosts GROWING QUIETLY. `netHosts` is already wired
     * into three tools and its own tests, and its behaviour is what the owner has used and
     * reported on. Changing what it does underneath him would make every past description of it a
     * lie. This tool is the one that gains SNMP, NBNS and reverse DNS, and it says so.
     */
    const val NETWORK_EVERYTHING = "netEverything"

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
        ToolDefinition(
            name = NETWORK_EVERYTHING,
            description = "Find everything on the wifi network, by asking every protocol at once " +
                "rather than only the ones a television answers. Sweeps every address on the local " +
                "subnet and asks over SNMP, NetBIOS, reverse DNS through the router, and a TCP " +
                "connect on the ports real devices expose. This is what names a printer, a NAS, a " +
                "switch or a router that serves no web page, and what names a washing machine or " +
                "a fridge by the hostname the router gave it when it joined the wifi. Each device " +
                "comes back with what it is, who made it, and how that was worked out, so a guess " +
                "is never presented as a fact. A device that answers nothing at all on any " +
                "protocol is still invisible, and the result says so rather than reporting an " +
                "empty house.",
            category = "network",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = NetworkToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class NetworkToolExecutor(
    private val scanner: NetworkScanner,
    private val store: NetworkStore,
    /**
     * Where blocking socket work runs.
     *
     * A PARAMETER SO THE DISPATCH IS PROVABLE. Issue #217 was the probe running on the
     * calling thread, and a JVM test cannot reproduce that failure for free: Robolectric
     * runs the test body on the main looper but does not enforce Android's main-thread
     * socket ban, so a probe left on the wrong thread returns its bytes happily and the
     * test passes on broken code. Handing the dispatcher in lets a test record where the
     * work actually ran, which is the fact the bug was about. The default keeps every
     * existing wiring exactly as it was.
     */
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    /**
     * The full sweep, which is what netEverything runs.
     *
     * A SECOND PARAMETER RATHER THAN REPLACING THE FIRST. The existing scanner stays the thing
     * netHosts runs, because that behaviour is what the owner has used and reported on. The new
     * sweep is additive until it has proved itself on his own network.
     */
    private val fullSweep: FullNetworkSweep = FullNetworkSweep(),
) : ToolExecutor {

    constructor(context: Context) : this(NetworkScanner(), NetworkStore())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            NetworkToolDefinitions.NETWORK_HOSTS -> scan()
            NetworkToolDefinitions.NETWORK_PROBE -> probe(args)
            NetworkToolDefinitions.NETWORK_EVERYTHING -> everything()
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    /**
     * Run the full sweep and report every device it found.
     *
     * WHY THE RESULT KEEPS THE PROVENANCE. A list that says "printer" without saying whether the
     * device declared it or it was inferred from a port is a list nobody can trust, because the
     * two facts have different weight and only one of them is the device's own word. Each entry
     * carries its evidence, so a wrong label can be traced to the protocol that produced it.
     */
    private suspend fun everything(): ToolExecutionResult {
        val report = fullSweep.sweep()

        val r = NetworkEverythingResult(
            success = report.devices.isNotEmpty(),
            subnet = report.subnet,
            probedAddresses = report.probedAddresses,
            aliveAddresses = report.aliveAddresses.size,
            count = report.devices.size,
            devices = report.devices.map { device ->
                DeviceView(
                    address = device.address,
                    name = device.displayName,
                    deviceClass = device.deviceClass.name,
                    vendor = device.vendor,
                    model = device.model,
                    location = device.location,
                    evidence = device.evidence.map { "${it.source}: ${it.detail} (${it.kind.name.lowercase()})" },
                )
            },
            problems = report.problems,
            error = if (report.devices.isEmpty()) {
                report.problems.firstOrNull() ?: "Nothing answered on this network."
            } else {
                null
            },
        )
        return emitPayload(json, json.encodeToString(NetworkEverythingResult.serializer(), r), r, r.success, r.error)
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

        // OFF THE CALLING THREAD, WHICH IS THE WHOLE POINT OF THIS WRAPPER. Issue #217.
        //
        // Both probe paths are plain functions doing blocking socket work, and the tool
        // dispatcher calls the executor from the main thread. So netProbe threw
        // NetworkOnMainThreadException on every single call, before a byte was read, while
        // netHosts and netDeepScan on the same host and port succeeded because those paths
        // already leave the main thread. Steven found it on a real television: raw probes to
        // 8009 and 5555 both threw while the port scan of the same two ports returned real
        // banners.
        //
        // withContext on the IO dispatcher rather than a thread per call, so the work lands on
        // the pool the rest of the fabric already uses. It is injected so a test can prove
        // the work left the calling thread, which is the fact this bug was about.
        return withContext(ioDispatcher) {
            when (method) {
                "raw" -> rawProbe(address, port)
                "http" -> httpProbe(address, port)
                else -> ToolExecutionResult.error("Unknown method '$method'. Use http or raw.")
            }
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
                // A DEVICE THAT SPEAKS HTTP BADLY STILL SAID SOMETHING.
                //
                // responseCode throws on anything it cannot parse as a status line, which
                // includes the malformed or absent one a set-top box or a bare socket server
                // often sends. Reading it unguarded reports a device that plainly answered as
                // a failure, so it is taken defensively and the bytes are read from whichever
                // stream this connection actually opened.
                val status = runCatching {
                    "HTTP ${connection.responseCode} ${connection.responseMessage.orEmpty()}".trim()
                }.getOrElse { "HTTP (no status line)" }

                // The body lives on errorStream when the JDK classifies a response as an
                // error, and inputStream is then empty. Reading only inputStream reports
                // "empty body" for a server that has just sent one.
                //
                // BOTH READS ARE BOUNDED, WHICH IS NOT AN OPTIMISATION. `readText()` allocates
                // the whole body into one String with no ceiling, so one local port that
                // streams a large payload fills the heap and kills the process. That is the
                // crash [readTextBounded] was written for, and this probe reintroduced it:
                // the `take(4000)` after the read cuts the String after it has already been
                // allocated, which bounds what is shown and not what is allocated.
                val body = runCatching {
                    connection.inputStream.use { stream -> readTextBounded(stream, 64 * 1024) }
                }.getOrElse {
                    runCatching {
                        connection.errorStream?.use { stream -> readTextBounded(stream, 64 * 1024) }
                    }.getOrNull().orEmpty()
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

    override val registrations: List<ToolSetRegistration> = listOf(
        NetworkToolDefinitions,
        NetworkControlToolDefinitions,
        NetworkReconToolDefinitions,
        // The Xbox tools, so a console is reachable rather than only described by classes.
        XboxToolDefinitions,
    )

    override fun bindings(): Module = module {
        single { NetworkStore() }
        single { NetworkScanner() }
        // The full sweep, with its targets resolved from the platform rather than assumed.
        single { ScanTargets() }
        single { FullNetworkSweep(targets = get()) }
        single { UpnpSoapClient() }
        single { ShellNetworkRecon() }
        single { DeepPortScanner() }
        factory { NetworkToolExecutor(get(), get(), fullSweep = get()) }
        factory { NetworkControlExecutor(get(), get(), get()) }
        factory { NetworkReconExecutor(get(), get()) }
        single { XboxConsole() }
        factory { XboxToolExecutor(get<XboxConsole>()) }
    }
}
