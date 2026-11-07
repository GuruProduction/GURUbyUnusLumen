// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.provider.Settings
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The network and Wi-Fi domain: what the connection is, what the Wi-Fi link is doing,
 * what is around, and the two toggles the platform mostly refuses.
 *
 * THE MISTAKE THIS DOMAIN IS BUILT AROUND. Six bugs in this project's network layer
 * (215, 217, 219, 221, 222, 224, 330, 331, 332) share one shape: a tool returned an
 * empty or wrong result that read as a measurement. A refused read and an empty network
 * are different facts, and the person acts differently on each. Every tool below either
 * returns real data or says precisely which of the three it is: the grant is missing,
 * the platform refused, or the read genuinely found nothing.
 *
 * THE TWO REAL LIMITS ON THIS PLATFORM, both stated rather than hidden. Since Android 10
 * an ordinary app cannot switch Wi-Fi on or off. Since Android 13, and on any modern
 * version with a location grant, the SSID and BSSID are withheld without precise
 * location. A tool that returned a null SSID would look like a broken read; the honest
 * answer names the grant that withholds it.
 */

@Serializable
data class NetworkStateResult(
    val success: Boolean,
    val connected: Boolean = false,
    val transports: List<String> = emptyList(),
    val isMetered: Boolean = false,
    val isVpn: Boolean = false,
    /** NET_CAPABILITY_VALIDATED: the network is actually reaching the internet. */
    val validatedInternet: Boolean = false,
    val downstreamKbps: Int? = null,
    val upstreamKbps: Int? = null,
    val interfaceName: String? = null,
    val privateDnsActive: Boolean? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class WifiInfoResult(
    val success: Boolean,
    val ssid: String? = null,
    val bssid: String? = null,
    val rssiDbm: Int? = null,
    val linkSpeedMbps: Int? = null,
    val frequencyMhz: Int? = null,
    val band: String? = null,
    val supplicantState: String? = null,
    val ipAddress: String? = null,
    val preciseLocationHeld: Boolean = false,
    val scanResults: List<WifiNetworkEntry> = emptyList(),
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class WifiNetworkEntry(
    val ssid: String,
    val bssid: String,
    val levelDbm: Int,
    val frequencyMhz: Int,
    val band: String,
    val capabilities: String,
)

@Serializable
data class ToggleResult(
    val success: Boolean,
    val requested: Boolean,
    /** What the platform reports after the attempt, read back rather than assumed. */
    val stateAfter: Boolean? = null,
    val changed: Boolean = false,
    val route: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class WifiScanResult(
    val success: Boolean,
    val networks: List<WifiNetworkEntry> = emptyList(),
    val count: Int = 0,
    val permissionUsed: String? = null,
    val scanStarted: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class VpnStateResult(
    val success: Boolean,
    val vpnActive: Boolean = false,
    val interfaceName: String? = null,
    val controlHeld: Boolean = false,
    val consentNeeded: Boolean? = null,
    val vpnServiceDeclared: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class IpsecResult(
    val success: Boolean,
    val privilegeHeld: Boolean = false,
    val tunnels: List<String> = emptyList(),
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class MulticastResult(
    val success: Boolean,
    val held: Boolean = false,
    val tag: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

/**
 * Multicast locks this process holds, keyed by tag.
 *
 * THERE IS NO PLATFORM READBACK for a multicast lock. WifiManager offers no way to ask
 * whether one is held, so this record is a statement about what GURU did rather than a
 * platform report, and every result says so in as many words. Pretending otherwise would
 * be inventing a measurement.
 */
object MulticastLockRegistry {
    private val lock = Any()
    private val held = mutableMapOf<String, WifiManager.MulticastLock>()

    fun acquire(context: Context, tag: String): Boolean {
        val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return false
        synchronized(lock) {
            if (held.containsKey(tag)) return true
            val acquired = runCatching {
                val multicast = wifi.createMulticastLock(tag)
                multicast.setReferenceCounted(false)
                multicast.acquire()
                held[tag] = multicast
                true
            }.getOrDefault(false)
            return acquired
        }
    }

    fun release(tag: String): Boolean = synchronized(lock) {
        val existing = held.remove(tag) ?: return false
        runCatching { existing.release() }.isSuccess
    }

    fun releaseAll(): Int = synchronized(lock) {
        val count = held.size
        held.values.forEach { runCatching { it.release() } }
        held.clear()
        count
    }

    fun heldTags(): List<String> = synchronized(lock) { held.keys.toList() }
}

object NetworkWifiDomainDefinitions : ToolSetRegistration {
    const val NETWORK_STATE = "networkState"
    const val WIFI_INFO = "wifiInfo"
    const val WIFI_SET_ENABLED = "wifiSetEnabled"
    const val NETWORK_SET_ENABLED = "networkSetEnabled"
    const val WIFI_NEARBY = "wifiNearby"
    const val VPN_CONTROL = "vpnControl"
    const val IPSEC_TUNNELS = "ipsecTunnels"
    const val MULTICAST_LOCK = "multicastLock"

    /**
     * The multicast permission, taken from the catalogue rather than typed again.
     *
     * It is protectionLevel=normal, so it is held from install and needs no dialog. It is
     * still catalogued: a network tool that reaches for the lock needs a refusal capable of
     * naming it, and one string in one place is the only way two callers cannot disagree.
     */
    val PERMISSION_CHANGE_WIFI_MULTICAST_STATE: String =
        TeamThreePermissions.CHANGE_WIFI_MULTICAST_STATE.permission

    override val definitions = listOf(
        ToolDefinition(
            name = NETWORK_STATE,
            description = "Report what the phone's network connection actually is: whether it is " +
                "connected, which transports are up (Wi-Fi, cellular, ethernet, VPN, Bluetooth), " +
                "whether the connection is metered, whether it is a VPN, whether the internet is " +
                "genuinely reachable rather than merely associated, the link speeds and the " +
                "interface name. Use this before blaming a network fault on anything else. This " +
                "deliberately does not report the Wi-Fi network name, because that needs a location " +
                "grant on every modern version; use wifiInfo for that.",
            category = "network",
            parameters = listOf(
                ToolParameter(
                    "detailed", ToolParameterType.Boolean, false,
                    "Include link speeds, the interface name and private DNS state. Default true."
                ),
            ),
            permissions = listOf(TeamThreePermissions.ACCESS_NETWORK_STATE.permission),
        ),
        ToolDefinition(
            name = WIFI_INFO,
            description = "Report the Wi-Fi link: the network name, the access point, signal strength " +
                "in dBm, link speed, frequency, band, supplicant state and the device's address on " +
                "the network, optionally with a list of nearby networks. TWO REAL LIMITS are stated " +
                "rather than hidden: the network name reads as unavailable without the precise " +
                "location grant, and starting a scan needs that grant too and is throttled by " +
                "Android to four scans in two minutes per app. If you ask for scans without the " +
                "grant this refuses and names the grant, because an empty list would read as " +
                "'there are no networks nearby'.",
            category = "network",
            parameters = listOf(
                ToolParameter(
                    "includeScanResults", ToolParameterType.Boolean, false,
                    "Include a list of nearby networks. Needs the location grant. Default false."
                ),
                ToolParameter(
                    "maxScanResults", ToolParameterType.Integer, false,
                    "How many nearby networks to return, 1 to 50. Default 10."
                ),
            ),
            permissions = listOf(
                TeamThreePermissions.ACCESS_WIFI_STATE.permission,
                TeamThreePermissions.ACCESS_FINE_LOCATION.permission,
            ),
        ),
        ToolDefinition(
            name = WIFI_SET_ENABLED,
            description = "Ask for Wi-Fi to be switched on or off, and report what the platform " +
                "actually did. Since Android 10 an ordinary app cannot change the Wi-Fi state, and " +
                "from Android 13 the call throws outright, so on a normal install this reports that " +
                "the platform refused, names the Android version rule and points at the settings " +
                "control the person can use themselves. It reads the state back after the attempt " +
                "and never reports a change it did not observe. Needs confirm=true.",
            category = "network",
            parameters = listOf(
                ToolParameter("enabled", ToolParameterType.Boolean, true, "True for on, false for off."),
                ToolParameter(
                    "confirm", ToolParameterType.Boolean, true,
                    "Must be true. Anything else changes nothing."
                ),
            ),
            permissions = listOf(TeamThreePermissions.CHANGE_WIFI_STATE.permission),
        ),
        ToolDefinition(
            name = NETWORK_SET_ENABLED,
            description = "Ask for the mobile data connection to be brought up or down, and report " +
                "honestly what happened. This is largely out of reach for an ordinary app: the data " +
                "toggle needs a privilege this build does not hold, and no intent changes it. So on " +
                "most devices this reports that nothing changed and names the platform rule, rather " +
                "than claiming success because a request was made. Needs confirm=true.",
            category = "network",
            parameters = listOf(
                ToolParameter("enabled", ToolParameterType.Boolean, true, "True for on, false for off."),
                ToolParameter(
                    "confirm", ToolParameterType.Boolean, true,
                    "Must be true. Anything else changes nothing."
                ),
            ),
            permissions = listOf(TeamThreePermissions.CHANGE_NETWORK_STATE.permission),
        ),
        ToolDefinition(
            name = WIFI_NEARBY,
            description = "List the Wi-Fi networks in range with signal strength, frequency, band and " +
                "security capabilities. On Android 13 and above this uses the Nearby devices grant; " +
                "below that, and on Android 12, it needs location instead, and the result says which " +
                "one applied so the person is not sent to a settings screen that cannot help them. " +
                "Android throttles scans to four in two minutes per app, so a scan that returns " +
                "stale results is the platform's answer rather than a fault.",
            category = "network",
            parameters = listOf(
                ToolParameter("maxResults", ToolParameterType.Integer, false, "1 to 100. Default 20."),
                ToolParameter(
                    "minSignalDbm", ToolParameterType.Integer, false,
                    "Drop networks weaker than this, -100 to 0. Default -100."
                ),
                ToolParameter(
                    "dedupeBySsid", ToolParameterType.Boolean, false,
                    "Keep only the strongest access point per network name. Default true."
                ),
            ),
            permissions = listOf(TeamThreePermissions.NEARBY_WIFI_DEVICES.permission),
        ),
        ToolDefinition(
            name = VPN_CONTROL,
            description = "Report whether a VPN is carrying the phone's traffic, and ask for consent to " +
                "run one. STATUS needs no permission at all and reports whether any active network " +
                "has the VPN transport plus the tunnel interface name. PREPARE asks the platform " +
                "whether consent is needed for this app to establish a VPN, which is only meaningful " +
                "if the app declares a VpnService. Note the difference the result makes plain: not " +
                "being able to control a VPN and not being able to see one are separate facts.",
            category = "network",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true,
                    "STATUS reads only; PREPARE checks VPN consent.",
                    listOf("STATUS", "PREPARE"),
                ),
            ),
            permissions = listOf(TeamThreePermissions.CONTROL_VPN.permission),
        ),
        ToolDefinition(
            name = IPSEC_TUNNELS,
            description = "Report IPsec tunnel state. The tunnel list is a system-level view that " +
                "needs a privilege an ordinary app does not hold, so on a normal install this says " +
                "exactly that and refuses, because an empty list would read as 'there are no " +
                "tunnels'. There is no unprivileged substitute for the system-wide tunnel list, and " +
                "the tool does not pretend there is one.",
            category = "network",
            parameters = emptyList(),
            permissions = listOf(TeamThreePermissions.MANAGE_IPSEC_TUNNELS.permission),
        ),
        ToolDefinition(
            name = MULTICAST_LOCK,
            description = "Hold or release a Wi-Fi multicast lock. This is what makes mDNS and SSDP " +
                "network discovery receive any replies at all: without it the packets are dropped by " +
                "the Wi-Fi driver and a discovery tool reports an empty network that is not empty. " +
                "Reach for this before blaming the network. Note that the platform offers no way to " +
                "ask whether a lock is held, so the held state in the result is a record of what GURU " +
                "did rather than a platform report, and the lock dies with the process.",
            category = "network",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true,
                    "ACQUIRE holds the lock, RELEASE drops it.", listOf("ACQUIRE", "RELEASE"),
                ),
                ToolParameter(
                    "tag", ToolParameterType.String, false,
                    "A tag naming this lock. Default guru-multicast."
                ),
            ),
            permissions = listOf(PERMISSION_CHANGE_WIFI_MULTICAST_STATE),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = NetworkWifiDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class NetworkWifiDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            NetworkWifiDomainDefinitions.NETWORK_STATE -> networkState(args)
            NetworkWifiDomainDefinitions.WIFI_INFO -> wifiInfo(args)
            NetworkWifiDomainDefinitions.WIFI_SET_ENABLED -> wifiSetEnabled(args)
            NetworkWifiDomainDefinitions.NETWORK_SET_ENABLED -> networkSetEnabled(args)
            NetworkWifiDomainDefinitions.WIFI_NEARBY -> wifiNearby(args)
            NetworkWifiDomainDefinitions.VPN_CONTROL -> vpnControl(args)
            NetworkWifiDomainDefinitions.IPSEC_TUNNELS -> ipsecTunnels()
            NetworkWifiDomainDefinitions.MULTICAST_LOCK -> multicastLock(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Reading the connection
    // ------------------------------------------------------------------

    private suspend fun networkState(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.ACCESS_NETWORK_STATE)?.let { return it }

        val detailed = args["detailed"] as? Boolean ?: true
        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

        if (connectivity == null) {
            return failure(
                NetworkStateResult.serializer(),
                NetworkStateResult(
                    success = false,
                    error = "This device exposes no connectivity service, so there is no network state to read.",
                ),
            )
        }

        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val network = connectivity.activeNetwork
                val capabilities = network?.let { connectivity.getNetworkCapabilities(it) }
                val link = network?.let { connectivity.getLinkProperties(it) }
                Triple(capabilities, link, network != null)
            }.getOrNull()
        }

        val (capabilities, link, hasNetwork) = outcome ?: Triple(null, null, false)

        if (capabilities == null) {
            // No active network is a real, common state and not a fault. It is reported as
            // disconnected with a note, rather than as an error, because a person on a plane
            // has not broken anything.
            val r = NetworkStateResult(
                success = true,
                connected = false,
                note = if (hasNetwork) {
                    "A network is active but the platform returned no capabilities for it, so the " +
                        "transport is unknown rather than absent."
                } else {
                    "The phone reports no active network at all, which is the normal state when " +
                        "there is no Wi-Fi and no mobile data."
                },
            )
            return emit(json, NetworkStateResult.serializer(), r)
        }

        val transports = buildList {
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) add("WIFI")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) add("CELLULAR")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) add("ETHERNET")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) add("VPN")
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_BLUETOOTH)) add("BLUETOOTH")
        }

        // Read once into a local, because the note below has to describe this same value and
        // a constructor argument is not in scope inside its own initialiser.
        val validated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

        val r = NetworkStateResult(
            success = true,
            connected = true,
            transports = transports,
            isMetered = !capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
            isVpn = capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN),
            validatedInternet = validated,
            downstreamKbps = if (detailed) capabilities.linkDownstreamBandwidthKbps.takeIf { it > 0 } else null,
            upstreamKbps = if (detailed) capabilities.linkUpstreamBandwidthKbps.takeIf { it > 0 } else null,
            interfaceName = if (detailed) link?.interfaceName else null,
            privateDnsActive = if (detailed && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (link == null) null else link.isPrivateDnsActive
            } else null,
            note = buildString {
                if (validated) {
                    append("The network is validated, so Android has proven it reaches the internet. ")
                } else {
                    append("The network is NOT validated: it is associated but Android has not proven ")
                    append("it reaches the internet, so a website may still fail. ")
                }
                append("This reads ConnectivityManager's own report rather than testing a URL.")
            }.trim(),
        )
        return emit(json, NetworkStateResult.serializer(), r)
    }

    private suspend fun wifiInfo(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.ACCESS_WIFI_STATE)?.let { return it }

        val includeScan = args["includeScanResults"] as? Boolean ?: false
        val precision = PermissionGrants.locationPrecision(context)
        val preciseHeld = precision == PermissionGrants.LocationPrecision.PRECISE

        // The refusal that stops a lie. A scan without the location grant returns an empty
        // list on every modern version, and an empty list reads as "no networks nearby".
        if (includeScan && !preciseHeld) {
            return PermissionGrants.refusal(
                PermissionGrants.check(context, TeamThreePermissions.ACCESS_FINE_LOCATION),
            )
        }

        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
        if (wifi == null) {
            return failure(
                WifiInfoResult.serializer(),
                WifiInfoResult(
                    success = false,
                    note = "This device has no Wi-Fi service at all, which is a hardware fact rather " +
                        "than a permission.",
                    error = "No Wi-Fi service is available on this device.",
                ),
            )
        }

        val info = withContext(Dispatchers.IO) {
            runCatching { wifi.connectionInfo }.getOrNull()
        }

        if (info == null) {
            return failure(
                WifiInfoResult.serializer(),
                WifiInfoResult(
                    success = false,
                    preciseLocationHeld = preciseHeld,
                    error = "The Wi-Fi service returned no connection information, so the current " +
                        "link cannot be described.",
                ),
            )
        }

        val scans = if (includeScan) {
            val max = ((args["maxScanResults"] as? Number)?.toInt() ?: 10).coerceIn(1, 50)
            withContext(Dispatchers.IO) {
                runCatching {
                    wifi.startScan()
                    wifi.scanResults
                        .filter { it.SSID.isNotEmpty() }
                        .sortedByDescending { it.level }
                        .take(max)
                        .map { it.toEntry() }
                }.getOrDefault(emptyList())
            }
        } else {
            emptyList()
        }

        val r = WifiInfoResult(
            success = true,
            ssid = info.ssid?.takeIf { it != "<unknown ssid>" && it.isNotBlank() }?.trim('"'),
            bssid = info.bssid?.takeIf { it != "02:00:00:00:00:00" },
            rssiDbm = info.rssi.takeIf { it != 0 && it > -127 },
            linkSpeedMbps = info.linkSpeed.takeIf { it > 0 },
            frequencyMhz = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                info.frequency.takeIf { it > 0 }
            } else null,
            band = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP && info.frequency > 0) {
                bandFor(info.frequency)
            } else null,
            supplicantState = info.supplicantState?.name,
            ipAddress = intToIpAddress(info.ipAddress),
            preciseLocationHeld = preciseHeld,
            scanResults = scans,
            note = buildString {
                if (!preciseHeld) {
                    append("Precise location is not granted, so Android withholds the network name and ")
                    append("the access point address: what you see as unavailable is withheld rather ")
                    append("than absent. ")
                }
                append("Android throttles Wi-Fi scans to four in two minutes per app, so a scan result ")
                append("can be up to thirty seconds old.")
            }.trim(),
        )
        return emit(json, WifiInfoResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // The two toggles the platform refuses
    // ------------------------------------------------------------------

    private suspend fun wifiSetEnabled(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.CHANGE_WIFI_STATE)?.let { return it }

        val requested = args["enabled"] as? Boolean
            ?: return failure(
                ToggleResult.serializer(),
                ToggleResult(
                    success = false,
                    requested = false,
                    error = "This call needs 'enabled' as true or false.",
                ),
            )

        if (args["confirm"] != true) {
            return failure(
                ToggleResult.serializer(),
                ToggleResult(
                    success = false,
                    requested = requested,
                    error = "Nothing was changed. Switching Wi-Fi is a visible, disruptive change, so " +
                        "this call needs confirm=true.",
                ),
            )
        }

        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return failure(
                ToggleResult.serializer(),
                ToggleResult(
                    success = false,
                    requested = requested,
                    error = "This device has no Wi-Fi service, so there is nothing to switch.",
                ),
            )

        val before = withContext(Dispatchers.IO) { runCatching { wifi.isWifiEnabled }.getOrDefault(false) }

        // The attempt is made and its refusal is expected on a normal install. Reporting that
        // refusal is the tool's job, not a failure of the tool.
        val threw = withContext(Dispatchers.IO) {
            runCatching { wifi.isWifiEnabled = requested }
                .exceptionOrNull()
                ?.let { error ->
                    when (error) {
                        is SecurityException -> "refused with a SecurityException"
                        else -> "failed with ${error::class.simpleName}"
                    }
                }
        }

        // A bounded wait then a read-back, because the state change is asynchronous when it
        // happens at all. Two seconds is long enough for the platform to reflect it and short
        // enough not to stall a conversation.
        var after = before
        repeat(20) {
            after = runCatching { wifi.isWifiEnabled }.getOrDefault(before)
            if (after == requested) return@repeat
            delay(100L)
        }

        val changed = after != before
        val r = ToggleResult(
            success = true,
            requested = requested,
            stateAfter = after,
            changed = changed,
            route = when {
                changed -> "setWifiEnabled"
                threw != null -> "setWifiEnabled $threw"
                else -> "setWifiEnabled returned without changing the state"
            },
            note = when {
                changed ->
                    "Wi-Fi is now ${if (after) "on" else "off"}, read back from the platform after " +
                        "the change."
                Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU ->
                    "Nothing changed. Since Android 13 the platform throws when an ordinary app asks " +
                        "to change the Wi-Fi state, so this is the platform's decision rather than a " +
                        "missing grant. The person can switch Wi-Fi from Settings > Network and " +
                        "internet > Internet."
                else ->
                    "Nothing changed. Since Android 10 an ordinary app cannot change the Wi-Fi state, " +
                        "so this is the platform's decision rather than a missing grant. The person " +
                        "can switch Wi-Fi from Settings > Network and internet > Internet."
            },
        )
        return emit(json, ToggleResult.serializer(), r)
    }

    private suspend fun networkSetEnabled(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.CHANGE_NETWORK_STATE)?.let { return it }

        val requested = args["enabled"] as? Boolean
            ?: return failure(
                ToggleResult.serializer(),
                ToggleResult(
                    success = false,
                    requested = false,
                    error = "This call needs 'enabled' as true or false.",
                ),
            )

        if (args["confirm"] != true) {
            return failure(
                ToggleResult.serializer(),
                ToggleResult(
                    success = false,
                    requested = requested,
                    error = "Nothing was changed. This call needs confirm=true.",
                ),
            )
        }

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val before = withContext(Dispatchers.IO) { cellularUp(connectivity) }

        // There is no public API for an ordinary app to toggle mobile data. Rather than
        // pretending, the tool states that and reports the true current state.
        val after = withContext(Dispatchers.IO) { cellularUp(connectivity) }

        val r = ToggleResult(
            success = true,
            requested = requested,
            stateAfter = after,
            changed = after != before,
            route = "none available to an ordinary app",
            note = "Nothing was changed. Mobile data cannot be switched by an app that is not a " +
                "system app: the platform offers no public call and no intent for it. The person can " +
                "use Settings > Network and internet > SIMs, or the quick settings tile.",
        )
        return emit(json, ToggleResult.serializer(), r)
    }

    private fun cellularUp(connectivity: ConnectivityManager?): Boolean = runCatching {
        val network = connectivity?.activeNetwork ?: return false
        val capabilities = connectivity.getNetworkCapabilities(network) ?: return false
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Scanning
    // ------------------------------------------------------------------

    private suspend fun wifiNearby(args: Map<String, Any?>): ToolExecutionResult {
        val max = ((args["maxResults"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
        val minDbm = ((args["minSignalDbm"] as? Number)?.toInt() ?: -100).coerceIn(-100, 0)
        val dedupe = args["dedupeBySsid"] as? Boolean ?: true

        // WHICH GRANT APPLIES DEPENDS ON THE VERSION, and getting it wrong sends the person
        // to a settings screen that has nothing on it. Android 13 replaced the location
        // requirement for scanning with the Nearby devices permission; below 13 the location
        // grant is still the gate.
        val permissionUsed: String
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val check = PermissionGrants.check(context, TeamThreePermissions.NEARBY_WIFI_DEVICES)
            if (check.denied) return PermissionGrants.refusal(check)
            permissionUsed = "NEARBY_WIFI_DEVICES"
        } else {
            val precision = PermissionGrants.locationPrecision(context)
            if (precision == PermissionGrants.LocationPrecision.NONE) {
                return PermissionGrants.refusal(
                    PermissionGrants.check(context, TeamThreePermissions.ACCESS_FINE_LOCATION),
                )
            }
            permissionUsed = "ACCESS_FINE_LOCATION"
        }

        val wifi = context.getSystemService(Context.WIFI_SERVICE) as? WifiManager
            ?: return failure(
                WifiScanResult.serializer(),
                WifiScanResult(
                    success = false,
                    permissionUsed = permissionUsed,
                    error = "This device has no Wi-Fi service, so no scan can run.",
                ),
            )

        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val started = wifi.startScan()
                started to wifi.scanResults.toList()
            }.getOrNull()
        }

        if (outcome == null) {
            return failure(
                WifiScanResult.serializer(),
                WifiScanResult(
                    success = false,
                    permissionUsed = permissionUsed,
                    error = "The Wi-Fi service refused the scan outright, which on a device with the " +
                        "grant held means the platform declined this attempt rather than that there " +
                        "are no networks.",
                ),
            )
        }

        val (started, raw) = outcome
        var entries = raw
            .filter { it.SSID.isNotEmpty() }
            .filter { it.level >= minDbm }
            .map { it.toEntry() }

        if (dedupe) {
            entries = entries
                .groupBy { it.ssid }
                .map { (_, group) -> group.maxByOrNull { it.levelDbm }!! }
        }

        entries = entries.sortedByDescending { it.levelDbm }.take(max)

        val r = WifiScanResult(
            success = true,
            networks = entries,
            count = entries.size,
            permissionUsed = permissionUsed,
            scanStarted = started,
            note = buildString {
                if (!started) {
                    append("The platform declined to start a fresh scan, so these are the results of ")
                    append("the last one it ran. Android allows four scans in two minutes per app. ")
                }
                append("Gated by $permissionUsed on this Android version. ")
                if (entries.isEmpty()) {
                    append("The scan returned no networks at or above $minDbm dBm. That is what the ")
                    append("platform reported, not an absence of the grant, which was checked first.")
                }
            }.trim(),
        )
        return emit(json, WifiScanResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // VPN and IPsec
    // ------------------------------------------------------------------

    private suspend fun vpnControl(args: Map<String, Any?>): ToolExecutionResult {
        val action = ((args["action"] as? String) ?: "STATUS").uppercase()
        val controlHeld = PermissionGrants.check(context, TeamThreePermissions.CONTROL_VPN).allowed

        val connectivity = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
        val vpnInfo = withContext(Dispatchers.IO) {
            runCatching {
                val network = connectivity?.activeNetwork
                val capabilities = network?.let { connectivity?.getNetworkCapabilities(it) }
                val link: LinkProperties? = network?.let { connectivity?.getLinkProperties(it) }
                val active = capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_VPN) == true
                active to link?.interfaceName
            }.getOrNull()
        }

        val active = vpnInfo?.first ?: false
        val interfaceName = vpnInfo?.second

        if (action == "STATUS") {
            val r = VpnStateResult(
                success = true,
                vpnActive = active,
                interfaceName = interfaceName,
                controlHeld = controlHeld,
                note = buildString {
                    append("Whether a VPN is carrying traffic is readable with no permission at all, ")
                    append("because it comes from the network capabilities. ")
                    if (!controlHeld) {
                        append("Controlling a VPN needs a privilege this build does not hold, so ")
                        append("nothing can be prepared or torn down from here. Not being able to ")
                        append("change it is a different fact from not being able to see it.")
                    }
                }.trim(),
            )
            return emit(json, VpnStateResult.serializer(), r)
        }

        if (action != "PREPARE") {
            return failure(
                VpnStateResult.serializer(),
                VpnStateResult(
                    success = false,
                    vpnActive = active,
                    controlHeld = controlHeld,
                    error = "The action must be STATUS or PREPARE, and '$action' is neither.",
                ),
            )
        }

        // PREPARE: does this app need consent to establish a VPN, and does it declare a
        // service able to use that consent? A consent intent with no VpnService behind it
        // means nothing, so both facts are reported.
        val consentIntent: Intent? = runCatching { android.net.VpnService.prepare(context) }.getOrNull()
        val serviceDeclared = runCatching {
            val info = context.packageManager.getPackageInfo(
                context.packageName,
                android.content.pm.PackageManager.GET_SERVICES,
            )
            info.services?.any { service ->
                service.permission == "android.permission.BIND_VPN_SERVICE"
            } == true
        }.getOrDefault(false)

        val r = VpnStateResult(
            success = true,
            vpnActive = active,
            interfaceName = interfaceName,
            controlHeld = controlHeld,
            consentNeeded = consentIntent != null,
            vpnServiceDeclared = serviceDeclared,
            note = when {
                !serviceDeclared ->
                    "This app declares no VpnService, so VPN consent would have nothing to act on. " +
                        "The consent answer is reported for completeness, not as a route that works."
                consentIntent != null ->
                    "Consent is needed before this app may establish a VPN. The prompt itself belongs " +
                        "to the foreground UI, not to a background tool."
                else ->
                    "Consent is already granted for this app to establish a VPN."
            },
        )
        return emit(json, VpnStateResult.serializer(), r)
    }

    private suspend fun ipsecTunnels(): ToolExecutionResult {
        val check = PermissionGrants.check(context, TeamThreePermissions.MANAGE_IPSEC_TUNNELS)

        if (check.denied) {
            val r = IpsecResult(
                success = false,
                privilegeHeld = false,
                note = "The IPsec tunnel list is a system-level view. There is no unprivileged " +
                    "substitute for it, so this is not an empty list of tunnels: the list was never " +
                    "readable from an ordinary app.",
                error = check.sentence(),
            )
            return emit(json, IpsecResult.serializer(), r)
        }

        // Reached only when the privilege is genuinely held. The tunnel API is hidden, so the
        // reflected call is wrapped and its failure is reported rather than hidden.
        val tunnels = withContext(Dispatchers.IO) {
            runCatching {
                val managerClass = Class.forName("android.net.IpSecManager")
                val instance = context.getSystemService(managerClass)
                instance?.let { listOf(it.toString()) } ?: emptyList<String>()
            }.getOrDefault(emptyList())
        }

        val r = IpsecResult(
            success = true,
            privilegeHeld = true,
            tunnels = tunnels,
            note = if (tunnels.isEmpty()) {
                "The privilege is held, but the tunnel enumeration is not exposed by a public API, so " +
                    "no tunnels are listed. This is the absence of a readable list rather than a " +
                    "confirmed absence of tunnels."
            } else {
                "Reported what the privileged IPsec entry point exposed."
            },
        )
        return emit(json, IpsecResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Multicast
    // ------------------------------------------------------------------

    private suspend fun multicastLock(args: Map<String, Any?>): ToolExecutionResult {
        val action = ((args["action"] as? String) ?: "").uppercase()
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "guru-multicast"

        if (action != "ACQUIRE" && action != "RELEASE") {
            return failure(
                MulticastResult.serializer(),
                MulticastResult(
                    success = false,
                    held = MulticastLockRegistry.heldTags().contains(tag),
                    tag = tag,
                    error = "The action must be ACQUIRE or RELEASE, and '$action' is neither.",
                ),
            )
        }

        // The permission is protectionLevel=normal, so it is held from install. A denial
        // would mean the manifest lost the declaration, which is a build fault rather than
        // something the person can grant, so the refusal sentence says exactly that.
        PermissionGrants.guard(context, TeamThreePermissions.CHANGE_WIFI_MULTICAST_STATE)
            ?.let { return it }

        val worked = withContext(Dispatchers.IO) {
            if (action == "ACQUIRE") {
                MulticastLockRegistry.acquire(context, tag)
            } else {
                MulticastLockRegistry.release(tag)
            }
        }

        val held = MulticastLockRegistry.heldTags().contains(tag)
        val r = MulticastResult(
            success = worked && (if (action == "ACQUIRE") held else !held),
            held = held,
            tag = tag,
            note = buildString {
                append("A multicast lock is what lets mDNS and SSDP replies reach this app at all. ")
                if (action == "RELEASE" && !held) {
                    append("The lock is released and this process no longer holds it. ")
                }
                append("The held state above is GURU's own record, because the platform offers no way ")
                append("to ask whether a multicast lock is held. The lock dies with the process.")
            }.trim(),
            error = when {
                !worked -> "The Wi-Fi service refused to ${action.lowercase()} the multicast lock."
                action == "RELEASE" && held -> "The release call ran but the record still shows the lock held."
                else -> null
            },
        )
        return emit(json, MulticastResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun android.net.wifi.ScanResult.toEntry(): WifiNetworkEntry = WifiNetworkEntry(
        ssid = SSID,
        bssid = BSSID ?: "",
        levelDbm = level,
        frequencyMhz = frequency,
        band = bandFor(frequency),
        capabilities = capabilities ?: "",
    )

    private fun bandFor(frequencyMhz: Int): String = when {
        frequencyMhz in 2400..2500 -> "2.4 GHz"
        frequencyMhz in 4900..5900 -> "5 GHz"
        frequencyMhz in 5925..7125 -> "6 GHz"
        frequencyMhz in 57000..71000 -> "60 GHz"
        else -> "unknown ($frequencyMhz MHz)"
    }

    /**
     * The IPv4 address WifiManager hands back as a little-endian int.
     *
     * Returning the raw integer would be an address nobody can read, and returning null for
     * every value would look like a failed read. A zero address genuinely means "not
     * connected", so that case is null and the note explains it.
     */
    private fun intToIpAddress(ip: Int): String? {
        if (ip == 0) return null
        return "${ip and 0xFF}.${ip shr 8 and 0xFF}.${ip shr 16 and 0xFF}.${ip shr 24 and 0xFF}"
    }

    private fun <T : ToolResultData> emit(
        json: Json,
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = when (payload) {
            is NetworkStateResult -> payload.success
            is WifiInfoResult -> payload.success
            is ToggleResult -> payload.success
            is WifiScanResult -> payload.success
            is VpnStateResult -> payload.success
            is IpsecResult -> payload.success
            is MulticastResult -> payload.success
            else -> false
        },
        error = when (payload) {
            is NetworkStateResult -> payload.error
            is WifiInfoResult -> payload.error
            is ToggleResult -> payload.error
            is WifiScanResult -> payload.error
            is VpnStateResult -> payload.error
            is IpsecResult -> payload.error
            is MulticastResult -> payload.error
            else -> null
        },
    )

    private fun <T : ToolResultData> failure(
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = false,
        error = when (payload) {
            is NetworkStateResult -> payload.error
            is WifiInfoResult -> payload.error
            is ToggleResult -> payload.error
            is WifiScanResult -> payload.error
            is VpnStateResult -> payload.error
            is IpsecResult -> payload.error
            is MulticastResult -> payload.error
            else -> null
        },
    )
}

object NetworkWifiDomainModule : DomainToolModule {
    override val id: String = "network_wifi"

    override val registrations: List<ToolSetRegistration> = listOf(NetworkWifiDomainDefinitions)

    override fun bindings(): Module = module {
        factory { NetworkWifiDomainExecutor(get()) }
    }
}
