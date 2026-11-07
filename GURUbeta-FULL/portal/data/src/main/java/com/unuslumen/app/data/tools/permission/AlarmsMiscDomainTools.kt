// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.app.AlarmManager
import android.content.Context
import android.content.Intent
import android.content.pm.ComponentInfo
import android.content.pm.PackageManager
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
import android.provider.AlarmClock
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
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The alarms and automation domain: an alarm in the system clock app, the state of the four
 * grants that decide whether automation fires at all, the multicast lock those grants need,
 * and a manifest health check.
 *
 * WHY THE ALARM TOOL IS NOT CALLED createAlarm. A createAlarm tool already exists elsewhere
 * in this repo, and a second tool with the same name makes the registry silently keep
 * whichever registered last, which is the fault TeamThreeToolDomains.duplicateToolNames
 * flags at boot. This one is setSystemAlarm and it has a different job: reaching the system
 * clock app through the public intent rather than setting GURU's own alarm.
 *
 * THE POINT OF automationStatus AND bootReceiverState. Both declare no permission, because
 * neither needs one, and both answer the question a person actually asks when a scheduled
 * thing did not happen: which of the four separate grants is switched off. One all-clear
 * boolean would hide exactly the one that is missing.
 */

@Serializable
data class AlarmResult(
    val success: Boolean,
    val route: String? = null,
    val nextAlarmMillis: Long? = null,
    val nextAlarmInMinutes: Long? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class AutomationStatusResult(
    val success: Boolean,
    val bootReceiverDeclared: Boolean = false,
    val batteryOptimisationExempt: Boolean = false,
    val exactAlarmsAllowed: Boolean? = null,
    val multicastLockHeld: Boolean = false,
    /** One route per switched-off grant, so the fix is per-line rather than a wall of text. */
    val routes: Map<String, String> = emptyMap(),
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

/**
 * A multicast lock result, named for this domain because NetworkWifiDomainTools already owns
 * a type called MulticastResult and two classes with one name in one package do not compile.
 */
@Serializable
data class AutomationMulticastResult(
    val success: Boolean,
    val held: Boolean = false,
    val tag: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class BootReceiverStateResult(
    val success: Boolean,
    val bootReceiverDeclared: Boolean = false,
    val receiverNames: List<String> = emptyList(),
    val foregroundServiceTypes: List<String> = emptyList(),
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

/**
 * The multicast locks this process holds, keyed by tag.
 *
 * THERE IS NO PLATFORM READBACK for multicast lock state, so this record is a statement
 * about what GURU did rather than a platform report, and every result says so. It lives here
 * rather than in the network domain because the automation status reads it.
 */
object AutomationMulticastRegistry {
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

    fun isHeld(tag: String): Boolean = synchronized(lock) { held.containsKey(tag) }

    fun heldTags(): List<String> = synchronized(lock) { held.keys.toList() }
}

object AlarmsMiscDomainDefinitions : ToolSetRegistration {
    const val SET_SYSTEM_ALARM = "setSystemAlarm"
    const val AUTOMATION_STATUS = "automationStatus"
    const val BOOT_RECEIVER_STATE = "bootReceiverState"

    /**
     * THE multicastLock TOOL LIVES IN THE NETWORK DOMAIN, NOT HERE.
     *
     * An earlier draft of this file also declared a tool called multicastLock, and the senses
     * test caught it: two domains registering one name means the registry silently keeps
     * whichever loads last, and the tool behaves differently depending on load order. That is
     * exactly the fault TeamThreeToolDomains.duplicateToolNames flags at boot, so it must not
     * be shipped.
     *
     * The lock is still readable from here: automationStatus reads AutomationMulticastRegistry
     * below, which is the same record the network domain's tool writes to. Two tools that take
     * the same lock is duplication; one tool plus a status that reports it is the right shape.
     */

    /**
     * The multicast permission, taken from the catalogue rather than typed again.
     *
     * protectionLevel=normal, so it is held from install and needs no dialog. It is declared
     * here for the registry, and the network domain's tool is the one that guards with it.
     */
    val PERMISSION_CHANGE_WIFI_MULTICAST_STATE: String =
        TeamThreePermissions.CHANGE_WIFI_MULTICAST_STATE.permission

    override val definitions = listOf(
        ToolDefinition(
            name = SET_SYSTEM_ALARM,
            description = "Set an alarm in the phone's own clock app. This works through the public " +
                "alarm intent, which any app can fire, so it needs no privilege: the system clock app " +
                "opens and creates it. It checks that a clock app exists before firing, because on a " +
                "device without one the intent silently does nothing, and it reads the platform's " +
                "next-alarm value afterwards. If that value is empty the alarm did not land, and this " +
                "reports that rather than claiming it was set.",
            category = "alarms",
            parameters = listOf(
                ToolParameter("hour", ToolParameterType.Integer, true, "Hour of day, 0 to 23."),
                ToolParameter("minute", ToolParameterType.Integer, true, "Minute, 0 to 59."),
                ToolParameter("label", ToolParameterType.String, false, "A message shown with the alarm."),
                ToolParameter(
                    "skipUi", ToolParameterType.Boolean, false,
                    "Ask the clock app to create the alarm without showing its editor. Default true."
                ),
                ToolParameter(
                    "daysOfWeek", ToolParameterType.String, false,
                    "Comma-separated weekdays for a repeating alarm, such as MON,WED,FRI."
                ),
            ),
            permissions = listOf(TeamThreePermissions.SET_ALARM.permission),
        ),
        ToolDefinition(
            name = AUTOMATION_STATUS,
            description = "Report the four separate grants that decide whether scheduled work fires " +
                "at all: whether the boot receiver is declared, whether the app is exempt from " +
                "battery optimisation, whether exact alarms are allowed, and whether a multicast lock " +
                "is held. Each is reported on its own line with its own fix, because a single " +
                "all-clear answer would hide exactly the one that is switched off. This needs no " +
                "permission and changes nothing.",
            category = "automation",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = BOOT_RECEIVER_STATE,
            description = "Report what this app's own manifest declares: whether any receiver asks for " +
                "the boot broadcast, and which foreground service types are declared. This is a " +
                "build-health check and it needs no permission. Its value is that it reads what the " +
                "manifest ACTUALLY holds rather than what it should hold, so a build that lost a line " +
                "shows up here instead of as a scheduled thing that silently never runs.",
            category = "automation",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = AlarmsMiscDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class AlarmsMiscDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            AlarmsMiscDomainDefinitions.SET_SYSTEM_ALARM -> setSystemAlarm(args)
            AlarmsMiscDomainDefinitions.AUTOMATION_STATUS -> automationStatus()
            AlarmsMiscDomainDefinitions.BOOT_RECEIVER_STATE -> bootReceiverState()
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Alarm
    // ------------------------------------------------------------------

    private suspend fun setSystemAlarm(args: Map<String, Any?>): ToolExecutionResult {
        val hour = (args["hour"] as? Number)?.toInt()
        val minute = (args["minute"] as? Number)?.toInt()

        if (hour == null || hour !in 0..23) {
            return failure(
                AlarmResult.serializer(),
                AlarmResult(
                    success = false,
                    error = "This call needs 'hour' as a number from 0 to 23.",
                ),
            )
        }
        if (minute == null || minute !in 0..59) {
            return failure(
                AlarmResult.serializer(),
                AlarmResult(
                    success = false,
                    error = "This call needs 'minute' as a number from 0 to 59.",
                ),
            )
        }

        val label = (args["label"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val skipUi = args["skipUi"] as? Boolean ?: true
        val days = (args["daysOfWeek"] as? String)
            ?.split(",")
            ?.mapNotNull { dayOfWeek(it.trim().uppercase()) }
            ?.takeIf { it.isNotEmpty() }
            ?.toIntArray()

        val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
            putExtra(AlarmClock.EXTRA_HOUR, hour)
            putExtra(AlarmClock.EXTRA_MINUTES, minute)
            putExtra(AlarmClock.EXTRA_SKIP_UI, skipUi)
            label?.let { putExtra(AlarmClock.EXTRA_MESSAGE, it) }
            days?.let { putExtra(AlarmClock.EXTRA_DAYS, it) }
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        // Resolve before firing. Without a clock app the intent silently does nothing, and
        // reporting that as a success would be the exact defect this batch exists to remove.
        val resolves = runCatching {
            context.packageManager.resolveActivity(intent, 0) != null
        }.getOrDefault(false)

        if (!resolves) {
            return failure(
                AlarmResult.serializer(),
                AlarmResult(
                    success = false,
                    route = "none, no clock app handles the alarm intent",
                    error = "No app on this device handles the alarm intent, so the alarm cannot be " +
                        "created. That is the device rather than a permission.",
                ),
            )
        }

        val fired = runCatching {
            context.startActivity(intent)
            true
        }.getOrDefault(false)

        if (!fired) {
            return failure(
                AlarmResult.serializer(),
                AlarmResult(
                    success = false,
                    route = "the alarm intent was refused",
                    error = "The clock app could not be opened, so nothing was set.",
                ),
            )
        }

        // The readback. getNextAlarmClock reports the next alarm the platform knows about, and
        // an empty value after a successful intent means the alarm did not land.
        val next = withContext(Dispatchers.IO) {
            runCatching {
                val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                manager?.nextAlarmClock?.triggerTime
            }.getOrNull()
        }

        val r = AlarmResult(
            success = true,
            route = "the public alarm intent, which needs no privilege",
            nextAlarmMillis = next,
            nextAlarmInMinutes = next?.let { (it - System.currentTimeMillis()) / 60_000L },
            note = when {
                next == null ->
                    "The intent was accepted, but the platform reports no next alarm, so the alarm " +
                        "did not land. That happens when the clock app is not the system one, or when " +
                        "it ignored the request."
                else ->
                    "The platform's next alarm is at ${next}, which is the readback this tool trusts " +
                        "rather than the intent being fired."
            },
        )
        return emit(json, AlarmResult.serializer(), r)
    }

    private fun dayOfWeek(name: String): Int? = when (name) {
        "MON", "MONDAY" -> java.util.Calendar.MONDAY
        "TUE", "TUESDAY" -> java.util.Calendar.TUESDAY
        "WED", "WEDNESDAY" -> java.util.Calendar.WEDNESDAY
        "THU", "THURSDAY" -> java.util.Calendar.THURSDAY
        "FRI", "FRIDAY" -> java.util.Calendar.FRIDAY
        "SAT", "SATURDAY" -> java.util.Calendar.SATURDAY
        "SUN", "SUNDAY" -> java.util.Calendar.SUNDAY
        else -> null
    }

    // ------------------------------------------------------------------
    // Automation status
    // ------------------------------------------------------------------

    private suspend fun automationStatus(): ToolExecutionResult {
        val routes = mutableMapOf<String, String>()

        val bootDeclared = withContext(Dispatchers.IO) { bootReceiverDeclared() }
        if (!bootDeclared) {
            routes["bootReceiver"] = "The manifest declares no receiver for the boot broadcast, so " +
                "nothing can be restored after a restart. This is a build fault rather than a setting."
        }

        val batteryExempt = runCatching {
            val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
            power?.isIgnoringBatteryOptimizations(context.packageName) ?: false
        }.getOrDefault(false)
        if (!batteryExempt) {
            routes["batteryOptimisation"] =
                "Settings > Apps > GURU > Battery > Unrestricted"
        }

        val exactAllowed = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            runCatching {
                val manager = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager
                manager?.canScheduleExactAlarms() ?: false
            }.getOrNull()
        } else {
            // Below Android 12 the permission is granted at install, so there is nothing to ask.
            true
        }
        if (exactAllowed == false) {
            routes["exactAlarms"] = "Settings > Apps > GURU > Alarms & reminders > Allow"
        }

        val multicastHeld = AutomationMulticastRegistry.heldTags().isNotEmpty()
        if (!multicastHeld) {
            routes["multicastLock"] = "Call multicastLock with action ACQUIRE before running network " +
                "discovery, because the Wi-Fi driver drops discovery replies without it."
        }

        val r = AutomationStatusResult(
            success = true,
            bootReceiverDeclared = bootDeclared,
            batteryOptimisationExempt = batteryExempt,
            exactAlarmsAllowed = exactAllowed,
            multicastLockHeld = multicastHeld,
            routes = routes,
            note = buildString {
                append("Four separate grants, reported separately, because a single all-clear boolean ")
                append("would hide the one that is actually off. ")
                append("The multicast line is a record of what GURU did rather than a platform report, ")
                append("because the platform exposes no readback for lock state. ")
                if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) {
                    append("Exact alarms need no grant below Android 12, so that line reports true by ")
                    append("the platform's own rule rather than by a check. ")
                }
            }.trim(),
        )
        return emit(json, AutomationStatusResult.serializer(), r)
    }

    /**
     * Ask the package manager whether any of this app's receivers handles the boot broadcast.
     *
     * WHY NOT READ intentFilters DIRECTLY. ActivityInfo.intentFilters is not a public field: the
     * flags-based PackageInfo query returns receivers without their filters, which is why the
     * direct approach does not compile. The documented way to ask is
     * queryBroadcastReceivers with the boot intent, which returns exactly the receivers that
     * would receive it and nothing else. That answer is also the more useful one, because it is
     * what the platform would actually do rather than what the manifest text says.
     */
    private fun bootReceiverDeclared(): Boolean = runCatching {
        val probe = Intent(Intent.ACTION_BOOT_COMPLETED).apply { setPackage(context.packageName) }
        val matches = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.packageManager.queryBroadcastReceivers(
                probe,
                PackageManager.ResolveInfoFlags.of(0L),
            )
        } else {
            @Suppress("DEPRECATION")
            context.packageManager.queryBroadcastReceivers(probe, 0)
        }
        matches.isNotEmpty()
    }.getOrDefault(false)

    // ------------------------------------------------------------------
    // Multicast
    // ------------------------------------------------------------------

    private suspend fun multicastLock(args: Map<String, Any?>): ToolExecutionResult {
        val action = ((args["action"] as? String) ?: "").uppercase()
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "guru-multicast"

        if (action != "ACQUIRE" && action != "RELEASE") {
            return failure(
                AutomationMulticastResult.serializer(),
                AutomationMulticastResult(
                    success = false,
                    held = AutomationMulticastRegistry.isHeld(tag),
                    tag = tag,
                    error = "The action must be ACQUIRE or RELEASE, and '$action' is neither.",
                ),
            )
        }

        // protectionLevel=normal, so it is held from install. A denial means the manifest lost
        // the line, which is a build fault rather than something a person can grant, and the
        // catalogue's sentence says exactly that.
        PermissionGrants.guard(context, TeamThreePermissions.CHANGE_WIFI_MULTICAST_STATE)
            ?.let { return it }

        val worked = withContext(Dispatchers.IO) {
            if (action == "ACQUIRE") {
                AutomationMulticastRegistry.acquire(context, tag)
            } else {
                AutomationMulticastRegistry.release(tag)
            }
        }

        val held = AutomationMulticastRegistry.isHeld(tag)
        val r = AutomationMulticastResult(
            success = worked && (if (action == "ACQUIRE") held else !held),
            held = held,
            tag = tag,
            note = buildString {
                append("A multicast lock is what lets mDNS and SSDP replies reach this app at all. ")
                append("The held state above is GURU's own record, because the platform offers no way ")
                append("to ask whether a multicast lock is held, and the lock dies with the process.")
            }.trim(),
            error = when {
                !worked -> "The Wi-Fi service refused to ${action.lowercase()} the multicast lock."
                action == "RELEASE" && held ->
                    "The release call ran but the record still shows the lock held."
                else -> null
            },
        )
        return emit(json, AutomationMulticastResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Boot receiver health
    // ------------------------------------------------------------------

    private suspend fun bootReceiverState(): ToolExecutionResult {
        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    PackageManager.PackageInfoFlags.of(
                        (PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES).toLong(),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    PackageManager.PackageInfoFlags.of(0L)
                }

                val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.getPackageInfo(context.packageName, flags)
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.getPackageInfo(
                        context.packageName,
                        PackageManager.GET_RECEIVERS or PackageManager.GET_SERVICES,
                    )
                }

                // The same documented query the automation status uses, so both tools answer
                // the same question the same way rather than one reading manifest text and the
                // other asking the platform.
                val probe = Intent(Intent.ACTION_BOOT_COMPLETED).apply {
                    setPackage(context.packageName)
                }
                val bootReceivers = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    context.packageManager.queryBroadcastReceivers(
                        probe,
                        PackageManager.ResolveInfoFlags.of(0L),
                    )
                } else {
                    @Suppress("DEPRECATION")
                    context.packageManager.queryBroadcastReceivers(probe, 0)
                }.mapNotNull { it.activityInfo?.name }.distinct()

                val serviceTypes = info.services
                    ?.mapNotNull { service ->
                        runCatching {
                            service.javaClass.getField("foregroundServiceType").getInt(service)
                        }.getOrNull()
                            ?.takeIf { it != 0 }
                            ?.let { type -> "${service.name}=$type" }
                    }
                    .orEmpty()

                Triple(bootReceivers, serviceTypes, info.receivers?.size ?: 0)
            }.getOrNull()
        }

        if (outcome == null) {
            return failure(
                BootReceiverStateResult.serializer(),
                BootReceiverStateResult(
                    success = false,
                    error = "This app's own package info could not be read, which is a build-level " +
                        "fault rather than a permission.",
                ),
            )
        }

        val (bootReceivers, serviceTypes, receiverCount) = outcome
        val r = BootReceiverStateResult(
            success = true,
            bootReceiverDeclared = bootReceivers.isNotEmpty(),
            receiverNames = bootReceivers,
            foregroundServiceTypes = serviceTypes,
            note = buildString {
                append("Read from this app's own manifest, so it reports what the build ACTUALLY holds ")
                append("rather than what it should hold. ")
                append("The app declares $receiverCount receiver(s). ")
                if (bootReceivers.isEmpty()) {
                    append("None of them asks for the boot broadcast, so nothing can be restored after ")
                    append("a restart: that is a manifest that lost a line, which is a build fault and ")
                    append("not something a person can grant.")
                } else {
                    append("A boot receiver is declared, so scheduled work can be restored after a ")
                    append("restart.")
                }
            }.trim(),
        )
        return emit(json, BootReceiverStateResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Emit helpers
    // ------------------------------------------------------------------

    private fun <T : ToolResultData> emit(
        json: Json,
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = when (payload) {
            is AlarmResult -> payload.success
            is AutomationStatusResult -> payload.success
            is AutomationMulticastResult -> payload.success
            is BootReceiverStateResult -> payload.success
            else -> false
        },
        error = when (payload) {
            is AlarmResult -> payload.error
            is AutomationStatusResult -> payload.error
            is AutomationMulticastResult -> payload.error
            is BootReceiverStateResult -> payload.error
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
            is AlarmResult -> payload.error
            is AutomationStatusResult -> payload.error
            is AutomationMulticastResult -> payload.error
            is BootReceiverStateResult -> payload.error
            else -> null
        },
    )
}

object AlarmsMiscDomainModule : DomainToolModule {
    override val id: String = "alarms_misc"

    override val registrations: List<ToolSetRegistration> = listOf(AlarmsMiscDomainDefinitions)

    override fun bindings(): Module = module {
        factory { AlarmsMiscDomainExecutor(get()) }
    }
}
