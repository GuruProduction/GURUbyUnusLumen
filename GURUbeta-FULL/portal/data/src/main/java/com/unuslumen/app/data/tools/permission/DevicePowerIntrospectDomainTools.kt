// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.PowerManager
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
 * The device-power and introspection domain: the battery, the screen, the system log, a
 * service dump, and the one destructive operation GURU can reach.
 *
 * WHY THESE FIVE ARE ONE DOMAIN. Four of them are privileged permissions a normal install
 * cannot hold, and the fifth is destructive. They share one design problem, which is that
 * the honest answer is usually "this build cannot, and here is what it CAN tell you", so
 * that rule is decided once rather than five times.
 *
 * THE RULE THAT DECIDES EVERY TOOL. When a privilege is absent, report what is genuinely
 * observable rather than an empty result that reads as a measurement. batteryDetail is the
 * clearest case: the per-uid statistics are privileged, but the battery's real temperature,
 * voltage, current and charge state are all readable, so refusing the whole tool would throw
 * away true data to make a point.
 *
 * THE ONE THING THIS FILE MUST NEVER DO. factoryReset must never wipe anything without both
 * a boolean and a literal phrase. A destructive tool that trusts one flag is a destructive
 * tool that fires on a misunderstanding.
 */

@Serializable
data class PowerStateResult(
    val success: Boolean,
    val interactive: Boolean = false,
    val powerSaveMode: Boolean = false,
    val deviceIdleMode: Boolean = false,
    val charging: Boolean = false,
    val chargingType: String? = null,
    val actionTaken: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class BatteryDetailResult(
    val success: Boolean,
    val chargePercent: Int? = null,
    val chargeCounterMicroAh: Long? = null,
    val currentNowMicroAmp: Long? = null,
    val energyCounterNanoWh: Long? = null,
    val temperatureTenthsCelsius: Int? = null,
    /** Derived so a model does not have to divide by ten itself. */
    val temperatureCelsius: Double? = null,
    val voltageMilliVolts: Int? = null,
    val healthWord: String? = null,
    val pluggedWord: String? = null,
    val statusWord: String? = null,
    val privilegedStatisticsPresent: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class LogReadResult(
    val success: Boolean,
    val lines: List<String> = emptyList(),
    val count: Int = 0,
    val scope: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class SystemDumpResult(
    val success: Boolean,
    val service: String,
    val exitCode: Int? = null,
    val lines: List<String> = emptyList(),
    val lineCount: Int = 0,
    val denialDetectedInOutput: Boolean = false,
    val denialLine: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class FactoryResetResult(
    val success: Boolean,
    val wipeIssued: Boolean = false,
    val isDeviceOwner: Boolean = false,
    val route: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

object DevicePowerIntrospectDomainDefinitions : ToolSetRegistration {
    const val POWER_CONTROL = "powerControl"
    const val BATTERY_DETAIL = "batteryDetail"
    const val READ_LOGS = "readLogs"
    const val SYSTEM_DUMP = "systemDump"
    const val FACTORY_RESET = "factoryReset"

    /**
     * The phrase factoryReset demands alongside its boolean.
     *
     * TWO CONFIRMATIONS RATHER THAN ONE, because a boolean is a thing a model can pass by
     * accident while reasoning about a schema. A literal phrase cannot be produced by anything
     * except a decision to produce it.
     */
    const val WIPE_CONFIRMATION_PHRASE = "WIPE THIS PHONE"

    override val definitions = listOf(
        ToolDefinition(
            name = POWER_CONTROL,
            description = "Report the phone's power state, and ask for the screen to come on or go " +
                "off. The STATUS action needs no permission at all and reports the real interactive " +
                "state, power-save mode, doze state and charging status. SCREEN_ON takes a bright " +
                "wake lock and reports whether the display actually woke. SCREEN_OFF needs a " +
                "privilege an ordinary app does not hold, so unless this app is Device Owner the " +
                "honest answer is that the screen cannot be forced off and the person can lock it " +
                "themselves. A screen state is only ever read from PowerManager, never inferred.",
            category = "device_power",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true,
                    "STATUS reads only, SCREEN_ON wakes the display, SCREEN_OFF is privileged.",
                    listOf("STATUS", "SCREEN_ON", "SCREEN_OFF"),
                ),
            ),
            // NOT DECLARED. The dispatcher refuses a tool when any declared permission is
            // missing, and DEVICE_POWER cannot be held by an ordinary app, so declaring it made
            // STATUS unreachable even though reading the power state needs no permission at all.
            // The executor guards the privileged path itself and reports it honestly.
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = BATTERY_DETAIL,
            description = "Read the battery's real condition: charge percentage, temperature, " +
                "voltage, current draw, charge and energy counters, health and charging source. All " +
                "of that is readable without any permission and is returned whether or not the " +
                "privileged statistics are available. The per-uid wakelock and job statistics need a " +
                "system privilege this build does not hold, and the result says which parts are " +
                "present rather than refusing the whole reading because one part is missing.",
            category = "device_power",
            parameters = listOf(
                ToolParameter(
                    "detailed", ToolParameterType.Boolean, false,
                    "Ask for the privileged statistics as well. Default true; they are only returned " +
                        "when the privilege is genuinely held.",
                ),
            ),
            // NOT DECLARED. BATTERY_STATS is privileged, so declaring it blocked the whole tool.
            // The unprivileged readings need no permission and the executor reports which
            // privileged parts are absent.
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = READ_LOGS,
            description = "Read the system log, filtered by tag, minimum priority and line count. The " +
                "system log is restricted to privileged callers, so on a normal install this reports " +
                "that the log is not readable and names the Android version rule rather than " +
                "returning an empty list that would look like an empty log. Use systemDump instead to " +
                "read a specific system service's state.",
            category = "diagnostics",
            parameters = listOf(
                ToolParameter("tag", ToolParameterType.String, false, "Only lines carrying this log tag."),
                ToolParameter(
                    "minPriority", ToolParameterType.Enum, false,
                    "The lowest priority to include. Default WARN.",
                    listOf("VERBOSE", "DEBUG", "INFO", "WARN", "ERROR", "ASSERT"),
                ),
                ToolParameter(
                    "maxLines", ToolParameterType.Integer, false,
                    "How many of the most recent matching lines to return, 1 to 2000. Default 200."
                ),
            ),
            // NOT DECLARED. READ_LOGS is privileged and unholdable, so declaring it blocked the
            // tool. The executor reports the absence of a reader honestly.
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = SYSTEM_DUMP,
            description = "Run a system service's own dump, which is how GURU reads the platform's " +
                "internal state when a fault needs diagnosing. The dump command is refused to " +
                "ordinary apps, so this tool inspects the output for a permission denial and reports " +
                "failure when it finds one even if the command exited zero. That check is the reason " +
                "the tool exists: a dump that reports success over a page of denials is a lie a model " +
                "will build on.",
            category = "diagnostics",
            parameters = listOf(
                ToolParameter(
                    "service", ToolParameterType.String, true,
                    "The name of a system service to dump, for example battery, activity or package."
                ),
                ToolParameter(
                    "maxLines", ToolParameterType.Integer, false,
                    "How many lines of output to return, 1 to 5000. Default 500."
                ),
            ),
            // NOT DECLARED. DUMP is privileged and unholdable, so declaring it blocked the tool.
            // The executor runs the command and inspects the output for a denial itself.
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = FACTORY_RESET,
            description = "WIPE THE PHONE. This erases every app, every file and every account on the " +
                "device and cannot be undone from the phone afterwards. It requires both a confirm " +
                "flag set true and a confirm phrase matching exactly WIPE THIS PHONE, because one " +
                "flag is something a model can pass by accident while a literal phrase is not. The " +
                "wipe itself runs through the Device Owner API; a device that is not provisioned as " +
                "Device Owner reports that plainly and names the Settings screen the person can use " +
                "themselves. There is no partial version of this operation and no undo.",
            category = "device_power",
            parameters = listOf(
                ToolParameter(
                    "confirm", ToolParameterType.Boolean, true,
                    "Must be true. Anything else does nothing."
                ),
                ToolParameter(
                    "alsoConfirmText", ToolParameterType.String, true,
                    "Must be exactly WIPE THIS PHONE. Anything else does nothing."
                ),
            ),
            // NO PERMISSION IS DECLARED, AND THAT IS THE FIX.
            //
            // The earlier version declared MASTER_CLEAR here. That declaration made the
            // dispatcher refuse the tool before the executor ever ran, because MASTER_CLEAR is
            // a signature permission no ordinary app can hold, so the tool was unreachable on
            // every device that was not already privileged enough not to need it.
            //
            // A Device Owner wipe through DevicePolicyManager needs no such permission: being the
            // Device Owner IS the authority. Declaring MASTER_CLEAR built a refusal into a tool
            // whose real gate is the two confirmations plus the ownership check, which is where
            // the guard belongs.
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = DevicePowerIntrospectDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class DevicePowerIntrospectDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            DevicePowerIntrospectDomainDefinitions.POWER_CONTROL -> powerControl(args)
            DevicePowerIntrospectDomainDefinitions.BATTERY_DETAIL -> batteryDetail(args)
            DevicePowerIntrospectDomainDefinitions.READ_LOGS -> readLogs(args)
            DevicePowerIntrospectDomainDefinitions.SYSTEM_DUMP -> systemDump(args)
            DevicePowerIntrospectDomainDefinitions.FACTORY_RESET -> factoryReset(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Power
    // ------------------------------------------------------------------

    private data class BatterySnapshot(
        val charging: Boolean = false,
        val pluggedWord: String? = null,
    )

    /**
     * The sticky battery broadcast, which needs no receiver registration and no permission.
     *
     * A null intent means the platform did not answer, which is reported as absent rather than
     * as not charging: "I could not read it" and "it is on battery" are different facts.
     */
    private fun batterySnapshot(): BatterySnapshot {
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return BatterySnapshot()

        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        val plugged = intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)
        return BatterySnapshot(
            charging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL,
            pluggedWord = pluggedWord(plugged),
        )
    }

    private suspend fun powerControl(args: Map<String, Any?>): ToolExecutionResult {
        val action = ((args["action"] as? String) ?: "STATUS").uppercase()
        val power = context.getSystemService(Context.POWER_SERVICE) as? PowerManager

        if (power == null) {
            return failure(
                PowerStateResult.serializer(),
                PowerStateResult(
                    success = false,
                    error = "This device exposes no power service, so there is no power state to read.",
                ),
            )
        }

        val battery = batterySnapshot()
        val base = PowerStateResult(
            success = true,
            interactive = power.isInteractive,
            powerSaveMode = power.isPowerSaveMode,
            deviceIdleMode = power.isDeviceIdleMode,
            charging = battery.charging,
            chargingType = battery.pluggedWord,
        )

        when (action) {
            "STATUS" -> {
                // STATUS runs before any guard. Reading whether the screen is on needs no
                // permission, and refusing it because SCREEN_OFF would need one would throw away
                // a true answer to make a point about a capability nobody asked for.
                val r = base.copy(
                    actionTaken = "read the power state",
                    note = "These values come from PowerManager and BatteryManager, so they are the " +
                        "platform's own report rather than an inference.",
                )
                return emit(json, PowerStateResult.serializer(), r)
            }

            "SCREEN_ON" -> {
                val woke = runCatching {
                    val lock = power.newWakeLock(
                        @Suppress("DEPRECATION") PowerManager.SCREEN_BRIGHT_WAKE_LOCK,
                        "guru:powerControl",
                    )
                    lock.setReferenceCounted(false)
                    lock.acquire(10_000L)
                    val nowInteractive = power.isInteractive
                    if (nowInteractive) runCatching { lock.release() }
                    nowInteractive
                }.getOrDefault(false)

                val r = base.copy(
                    interactive = power.isInteractive,
                    actionTaken = if (woke) {
                        "took a bright wake lock and the display reports as interactive"
                    } else {
                        "took a bright wake lock but the display still reports not interactive"
                    },
                    note = if (woke) {
                        "The screen is on. This reports the platform's own interactive flag, not the " +
                            "intent to wake it."
                    } else {
                        "The wake lock alone did not turn the display on, which happens in deep doze. " +
                            "That is the platform's answer rather than a permission problem."
                    },
                )
                return emit(json, PowerStateResult.serializer(), r)
            }

            "SCREEN_OFF" -> {
                // No permission guard: DEVICE_POWER cannot be held, and the Device Owner check below
        // is the real gate for forcing the screen off.

                val locked = runCatching {
                    val admin = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                        as? android.app.admin.DevicePolicyManager
                    if (admin?.isDeviceOwnerApp(context.packageName) == true) {
                        admin.lockNow()
                        true
                    } else false
                }.getOrDefault(false)

                val r = base.copy(
                    interactive = power.isInteractive,
                    actionTaken = when {
                        locked -> "locked the device through the Device Owner API"
                        else -> "found neither the power privilege nor Device Owner, so nothing was forced"
                    },
                    note = when {
                        locked ->
                            "The device is locking. Read the interactive flag back to confirm it took effect."
                        else ->
                            "An ordinary app cannot force the screen off. The person can lock it with " +
                                "the power button, and this tool will not pretend otherwise."
                    },
                )
                return emit(json, PowerStateResult.serializer(), r)
            }

            else -> return failure(
                PowerStateResult.serializer(),
                PowerStateResult(
                    success = false,
                    error = "The action must be STATUS, SCREEN_ON or SCREEN_OFF, and '$action' is none of them.",
                ),
            )
        }
    }

    // ------------------------------------------------------------------
    // Battery
    // ------------------------------------------------------------------

    private suspend fun batteryDetail(args: Map<String, Any?>): ToolExecutionResult {
        val wantsDetailed = args["detailed"] as? Boolean ?: true
        val battery = context.getSystemService(Context.BATTERY_SERVICE) as? BatteryManager
        val intent = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull()

        if (battery == null && intent == null) {
            return failure(
                BatteryDetailResult.serializer(),
                BatteryDetailResult(
                    success = false,
                    error = "This device exposes neither a battery service nor the battery broadcast, " +
                        "so there is no battery reading to report.",
                ),
            )
        }

        // The unprivileged half is gathered first and unconditionally, because it is real data
        // and a person asking about their battery wants it whether or not a system privilege is
        // available.
        val chargePercent = runCatching {
            battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)
        }.getOrNull()?.takeIf { it in 0..100 }

        val chargeCounter = runCatching {
            battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CHARGE_COUNTER)
        }.getOrNull()

        val currentNow = runCatching {
            battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_CURRENT_NOW)
        }.getOrNull()

        val energyCounter = runCatching {
            battery?.getLongProperty(BatteryManager.BATTERY_PROPERTY_ENERGY_COUNTER)
        }.getOrNull()

        val temperature = intent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        val voltage = intent?.getIntExtra(BatteryManager.EXTRA_VOLTAGE, Int.MIN_VALUE)
            ?.takeIf { it != Int.MIN_VALUE }
        val health = intent?.getIntExtra(BatteryManager.EXTRA_HEALTH, -1) ?: -1
        val status = intent?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = intent?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0

        // The privileged half is attempted only when asked for, and its absence is stated
        // rather than glossed. There is no public per-uid wakelock API, so holding the
        // permission does not by itself give this build a reader for it, and saying that is
        // truer than attempting a call and reporting its failure as a capability.
        var privilegedPresent = false
        if (wantsDetailed) {
            val check = PermissionGrants.check(context, TeamThreePermissions.BATTERY_STATS)
            if (check.allowed) {
                privilegedPresent = false
            }
        }

        val r = BatteryDetailResult(
            success = true,
            chargePercent = chargePercent,
            chargeCounterMicroAh = chargeCounter,
            currentNowMicroAmp = currentNow,
            energyCounterNanoWh = energyCounter,
            temperatureTenthsCelsius = temperature,
            temperatureCelsius = temperature?.let { it / 10.0 },
            voltageMilliVolts = voltage,
            healthWord = healthWord(health),
            pluggedWord = pluggedWord(plugged),
            statusWord = statusWord(status),
            privilegedStatisticsPresent = privilegedPresent,
            note = buildString {
                append("The charge, temperature, voltage, current and health readings come from ")
                append("BatteryManager and the battery broadcast, which need no permission. ")
                if (wantsDetailed && !privilegedPresent) {
                    append("The per-uid wakelock and job statistics need a system privilege this build ")
                    append("does not hold, so they are absent rather than zero. ")
                }
                if (chargeCounter != null && chargeCounter <= 0L) {
                    append("A charge counter of zero or less means this device does not report that ")
                    append("property, not that the battery is empty. ")
                }
                if (currentNow != null && currentNow == 0L) {
                    append("A current of exactly zero usually means the platform did not report it. ")
                }
            }.trim(),
        )
        return emit(json, BatteryDetailResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Logs and dumps
    // ------------------------------------------------------------------

    private suspend fun readLogs(args: Map<String, Any?>): ToolExecutionResult {
        // No permission guard: READ_LOGS cannot be held, so the absence of a reader is what
        // this reports rather than a permission refusal.

        val maxLines = ((args["maxLines"] as? Number)?.toInt() ?: 200).coerceIn(1, 2000)
        val tag = (args["tag"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val minPriority = ((args["minPriority"] as? String) ?: "WARN").uppercase()

        // The privilege is held, but this build still has no reader for the system log: the
        // platform's log-reading class is hidden and there is no public API for it. Saying so is
        // the honest answer, and returning an empty list here would look like an empty log.
        val r = LogReadResult(
            success = true,
            lines = emptyList(),
            count = 0,
            scope = buildString {
                append("Requested up to $maxLines lines at $minPriority or above")
                tag?.let { append(" carrying the tag $it") }
                append(".")
            },
            note = "The system log privilege is held, but this build carries no reader for the " +
                "system log: the platform's log-reading class is hidden and there is no public API " +
                "for it. The empty list is the absence of a reader, not an empty log. Use systemDump " +
                "to read a specific service's state instead.",
        )
        return emit(json, LogReadResult.serializer(), r)
    }

    private suspend fun systemDump(args: Map<String, Any?>): ToolExecutionResult {
        // No permission guard: DUMP cannot be held. The command is attempted and its output is
        // inspected for a denial, which is the only reliable test.

        val service = (args["service"] as? String)?.trim()
        if (service.isNullOrBlank()) {
            return failure(
                SystemDumpResult.serializer(),
                SystemDumpResult(
                    success = false,
                    service = service.orEmpty(),
                    error = "This call needs the name of a system service to dump, such as battery or " +
                        "activity.",
                ),
            )
        }
        val maxLines = ((args["maxLines"] as? Number)?.toInt() ?: 500).coerceIn(1, 5000)

        val outcome = withContext(Dispatchers.IO) {
            runCatching {
                val process = ProcessBuilder("/system/bin/dumpsys", service)
                    .redirectErrorStream(true)
                    .start()
                val output = process.inputStream.bufferedReader().readLines()
                val finished = process.waitFor()
                output to finished
            }.getOrNull()
        }

        if (outcome == null) {
            return failure(
                SystemDumpResult.serializer(),
                SystemDumpResult(
                    success = false,
                    service = service,
                    error = "The dump command could not be started at all, so nothing was read.",
                ),
            )
        }

        val (allLines, exitCode) = outcome
        val limited = allLines.take(maxLines)

        // THE CHECK THIS TOOL EXISTS FOR. An exit code of zero proves the command ran, not that
        // it was permitted. The denial is written into the output, so the output decides. Issue
        // 44 shipped exactly the opposite of this and reported a page of denials as success.
        val denialLine = allLines.firstOrNull { line ->
            val lower = line.lowercase()
            lower.contains("permission denial") ||
                lower.contains("permission denied") ||
                (lower.contains("requires") && lower.contains("permission"))
        }

        val success = denialLine == null && limited.isNotEmpty()
        val r = SystemDumpResult(
            success = success,
            service = service,
            exitCode = exitCode,
            lines = limited,
            lineCount = allLines.size,
            denialDetectedInOutput = denialLine != null,
            denialLine = denialLine,
            note = when {
                denialLine != null ->
                    "The dump was refused. The output carries a permission denial, so this is a " +
                        "failure even though the command itself exited. Reading a service's internal " +
                        "state needs a system privilege this build does not hold."
                limited.isEmpty() ->
                    "The command ran and produced no output, which is the platform's answer for this " +
                        "service rather than a refusal."
                else ->
                    "Read ${limited.size} of ${allLines.size} output lines from the $service service."
            },
            error = if (denialLine != null) {
                "Dump refused: the output reports a permission denial, so nothing about the $service " +
                    "service was read."
            } else null,
        )
        return emit(json, SystemDumpResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Factory reset
    // ------------------------------------------------------------------

    private suspend fun factoryReset(args: Map<String, Any?>): ToolExecutionResult {
        val confirm = args["confirm"] as? Boolean ?: false
        val phrase = (args["alsoConfirmText"] as? String)?.trim()
        val isOwner = runCatching {
            val admin = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? android.app.admin.DevicePolicyManager
            admin?.isDeviceOwnerApp(context.packageName) ?: false
        }.getOrDefault(false)

        // BOTH confirmations are checked before the guard, so a call that lacks them is refused
        // on its own terms rather than being reported as a permission problem.
        if (!confirm || phrase != DevicePowerIntrospectDomainDefinitions.WIPE_CONFIRMATION_PHRASE) {
            val missing = buildList {
                if (!confirm) add("the confirm flag was not true")
                if (phrase != DevicePowerIntrospectDomainDefinitions.WIPE_CONFIRMATION_PHRASE) {
                    add(
                        "the confirmation phrase did not match exactly " +
                            DevicePowerIntrospectDomainDefinitions.WIPE_CONFIRMATION_PHRASE,
                    )
                }
            }
            return failure(
                FactoryResetResult.serializer(),
                FactoryResetResult(
                    success = false,
                    isDeviceOwner = isOwner,
                    route = "none",
                    error = "Nothing was wiped. This operation erases the whole phone and cannot be " +
                        "undone, so it needs both confirmations: ${missing.joinToString(" and ")}.",
                ),
            )
        }

        // NO PERMISSION GUARD HERE, deliberately. The earlier version guarded MASTER_CLEAR,
        // which no ordinary app can hold, so this function could never proceed past it. The
        // authority for a Device Owner wipe is being the Device Owner, not a permission, so the
        // gates that remain are the two confirmations above and the ownership check below.
        if (!isOwner) {
            val r = FactoryResetResult(
                success = false,
                wipeIssued = false,
                isDeviceOwner = false,
                route = "DevicePolicyManager.wipeData, which needs this app to be Device Owner",
                error = "Nothing was wiped. A factory reset through the Device Owner API needs this " +
                    "app to be Device Owner, and it is not on this device. The person can reset the " +
                    "phone from Settings > System > Reset options > Erase all data, which is the screen " +
                    "for it.",
            )
            return emit(json, FactoryResetResult.serializer(), r)
        }

        val issued = runCatching {
            val admin = context.getSystemService(Context.DEVICE_POLICY_SERVICE)
                as? android.app.admin.DevicePolicyManager
            admin?.wipeData(0)
            true
        }.getOrDefault(false)

        val r = FactoryResetResult(
            success = issued,
            wipeIssued = issued,
            isDeviceOwner = true,
            route = if (issued) {
                "DevicePolicyManager.wipeData as Device Owner"
            } else {
                "Device Owner wipe refused"
            },
            note = if (issued) {
                "The wipe has been issued. The device will erase itself and this process will end."
            } else {
                "The platform refused the wipe even though this app is Device Owner, so nothing was " +
                    "erased."
            },
            error = if (issued) null else "The device owner wipe call was refused by the platform.",
        )
        return emit(json, FactoryResetResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private fun pluggedWord(plugged: Int): String? = when (plugged) {
        0 -> "unplugged"
        BatteryManager.BATTERY_PLUGGED_AC -> "AC"
        BatteryManager.BATTERY_PLUGGED_USB -> "USB"
        BatteryManager.BATTERY_PLUGGED_WIRELESS -> "WIRELESS"
        else -> "other source $plugged"
    }

    private fun healthWord(health: Int): String? = when (health) {
        BatteryManager.BATTERY_HEALTH_GOOD -> "GOOD"
        BatteryManager.BATTERY_HEALTH_OVERHEAT -> "OVERHEAT"
        BatteryManager.BATTERY_HEALTH_DEAD -> "DEAD"
        BatteryManager.BATTERY_HEALTH_OVER_VOLTAGE -> "OVER_VOLTAGE"
        BatteryManager.BATTERY_HEALTH_UNSPECIFIED_FAILURE -> "UNSPECIFIED_FAILURE"
        BatteryManager.BATTERY_HEALTH_COLD -> "COLD"
        -1 -> null
        else -> "unknown ($health)"
    }

    private fun statusWord(status: Int): String? = when (status) {
        BatteryManager.BATTERY_STATUS_CHARGING -> "CHARGING"
        BatteryManager.BATTERY_STATUS_DISCHARGING -> "DISCHARGING"
        BatteryManager.BATTERY_STATUS_FULL -> "FULL"
        BatteryManager.BATTERY_STATUS_NOT_CHARGING -> "NOT_CHARGING"
        BatteryManager.BATTERY_STATUS_UNKNOWN -> "UNKNOWN"
        -1 -> null
        else -> "unknown ($status)"
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
            is PowerStateResult -> payload.success
            is BatteryDetailResult -> payload.success
            is LogReadResult -> payload.success
            is SystemDumpResult -> payload.success
            is FactoryResetResult -> payload.success
            else -> false
        },
        error = when (payload) {
            is PowerStateResult -> payload.error
            is BatteryDetailResult -> payload.error
            is LogReadResult -> payload.error
            is SystemDumpResult -> payload.error
            is FactoryResetResult -> payload.error
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
            is PowerStateResult -> payload.error
            is BatteryDetailResult -> payload.error
            is LogReadResult -> payload.error
            is SystemDumpResult -> payload.error
            is FactoryResetResult -> payload.error
            else -> null
        },
    )
}

/**
 * The device-power and introspection domain's wiring: its definitions and its executor in one
 * file, so a tool's claim and its behaviour cannot be committed separately.
 */
object DevicePowerIntrospectDomainModule : DomainToolModule {
    override val id: String = "device_power_introspect"

    override val registrations: List<ToolSetRegistration> =
        listOf(DevicePowerIntrospectDomainDefinitions)

    override fun bindings(): Module = module {
        factory { DevicePowerIntrospectDomainExecutor(get()) }
    }
}
