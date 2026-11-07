// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Build
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
 * The app-usage and notification domain: what has been in the foreground, whether a
 * notification actually posted, and the phone's Do Not Disturb state.
 *
 * THE THREE FACTS THIS DOMAIN KEEPS APART. "The grant is missing", "the window had no rows",
 * and "the platform restricted this list" are different answers and a person acts
 * differently on each. Every result below says which one it is, because the alternative is
 * an empty list that reads as a measurement.
 *
 * WHY dndControl GUARDS HALF OF ITSELF. Reading the interruption filter needs no permission
 * at all, and writing it needs the notification policy. A tool that refused the whole thing
 * would deny a person a reading they are entitled to; a tool that reported the write as done
 * would be lying. So the read runs unguarded, the write is guarded, and both facts are
 * reported separately.
 */

@Serializable
data class AppUsageEntry(
    val packageName: String,
    val totalTimeInForegroundMillis: Long,
    val lastTimeUsedMillis: Long,
    val firstTimeStampMillis: Long,
)

@Serializable
data class AppUsageResult(
    val success: Boolean,
    val entries: List<AppUsageEntry> = emptyList(),
    val count: Int = 0,
    val windowFromMillis: Long = 0,
    val windowToMillis: Long = 0,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class NotifyResult(
    val success: Boolean,
    val channelId: String,
    val notificationsEnabled: Boolean = false,
    val channelExistsAfterwards: Boolean = false,
    val posted: Boolean = false,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class DndResult(
    val success: Boolean,
    val action: String,
    /** The current interruption filter as a word, read from the platform. */
    val filterWord: String? = null,
    /** Whether this app holds the policy access that a WRITE needs. */
    val policyAccessGranted: Boolean = false,
    val written: Boolean? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

object AppUsageNotifyDomainDefinitions : ToolSetRegistration {
    const val APP_USAGE = "appUsage"
    const val NOTIFY = "notify"
    const val DND_CONTROL = "dndControl"

    override val definitions = listOf(
        ToolDefinition(
            name = APP_USAGE,
            description = "Report which apps have been in the foreground and for how long, over a " +
                "time window. This is the correct unprivileged way to answer 'what have I been " +
                "using': the platform's task list cannot be read for other apps without a system " +
                "privilege, and usage statistics cover the practical case. It needs the Usage access " +
                "grant, which is a Settings toggle rather than a dialog, and an empty result means " +
                "the window had no recorded activity rather than a refused read.",
            category = "app_usage",
            parameters = listOf(
                ToolParameter(
                    "fromMillis", ToolParameterType.Long, false,
                    "Start of the window, epoch milliseconds. Default 24 hours ago."
                ),
                ToolParameter(
                    "toMillis", ToolParameterType.Long, false,
                    "End of the window, epoch milliseconds. Default now."
                ),
                ToolParameter("packageName", ToolParameterType.String, false, "One app to report."),
                ToolParameter("limit", ToolParameterType.Integer, false, "1 to 500. Default 50."),
            ),
            permissions = listOf(TeamThreePermissions.PACKAGE_USAGE_STATS.permission),
        ),
        ToolDefinition(
            name = NOTIFY,
            description = "Post a notification and then report the truth about whether it can be " +
                "seen: whether the app's notifications are enabled, and whether the channel exists " +
                "afterwards. On modern Android a notification posted without the notifications grant " +
                "fails silently, so a tool that reported success on the post call alone would be " +
                "claiming something a person cannot see. This reports the platform's own enabled " +
                "state rather than the intent.",
            category = "notifications",
            parameters = listOf(
                ToolParameter("title", ToolParameterType.String, true, "The notification title."),
                ToolParameter("body", ToolParameterType.String, true, "The notification body."),
                ToolParameter("channelId", ToolParameterType.String, false, "Default guru-default."),
                ToolParameter(
                    "importance", ToolParameterType.Enum, false,
                    "Default DEFAULT.", listOf("LOW", "DEFAULT", "HIGH"),
                ),
                ToolParameter("ongoing", ToolParameterType.Boolean, false, "Default false."),
            ),
            permissions = listOf(TeamThreePermissions.POST_NOTIFICATIONS.permission),
        ),
        ToolDefinition(
            name = DND_CONTROL,
            description = "Read or change the phone's Do Not Disturb state. Reading the current " +
                "interruption filter needs NO permission at all and always works, which is why the " +
                "read path is not guarded. Changing it needs the Do Not Disturb access toggle, so a " +
                "write without it is refused on its own terms. The result reports both facts " +
                "separately: not being able to change the state and not being able to see it are " +
                "different problems and conflating them makes a working read look broken.",
            category = "notifications",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true, "GET reads, SET changes.",
                    listOf("GET", "SET"),
                ),
                ToolParameter(
                    "mode", ToolParameterType.Enum, false,
                    "For SET: OFF silences nothing, PRIORITY allows priority only, ALARMS allows " +
                        "alarms only, NONE blocks everything, ALL allows everything.",
                    listOf("OFF", "PRIORITY", "ALARMS", "NONE", "ALL"),
                ),
            ),
            // GET needs nothing. SET needs this. The dispatcher's pre-flight refuses when ANY
            // declared permission is missing, so declaring it means a person without the toggle
            // cannot even read, which is the wrong trade: a true reading is worth more than a
            // uniform declaration. So it is NOT declared, and the write path guards itself.
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = AppUsageNotifyDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class AppUsageNotifyDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            AppUsageNotifyDomainDefinitions.APP_USAGE -> appUsage(args)
            AppUsageNotifyDomainDefinitions.NOTIFY -> notify(args)
            AppUsageNotifyDomainDefinitions.DND_CONTROL -> dndControl(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Usage
    // ------------------------------------------------------------------

    private suspend fun appUsage(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.PACKAGE_USAGE_STATS)?.let { return it }

        val to = (args["toMillis"] as? Number)?.toLong() ?: System.currentTimeMillis()
        val from = (args["fromMillis"] as? Number)?.toLong()
            ?: (to - 24L * 60L * 60L * 1000L)
        val packageName = (args["packageName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val limit = ((args["limit"] as? Number)?.toInt() ?: 50).coerceIn(1, 500)

        if (to <= from) {
            return failure(
                AppUsageResult.serializer(),
                AppUsageResult(
                    success = false,
                    windowFromMillis = from,
                    windowToMillis = to,
                    error = "The window ends before it starts: from $from is not before $to. Nothing " +
                        "was queried, which is different from finding no activity.",
                ),
            )
        }

        val entries = withContext(Dispatchers.IO) {
            runCatching {
                val manager = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
                val stats = manager.queryUsageStats(
                    UsageStatsManager.INTERVAL_DAILY,
                    from,
                    to,
                ).orEmpty()

                stats
                    .filter { packageName == null || it.packageName == packageName }
                    // A total of zero means the app was installed but never brought forward in
                    // the window, which is not activity worth reporting.
                    .filter { it.totalTimeInForeground > 0L }
                    .sortedByDescending { it.totalTimeInForeground }
                    .take(limit)
                    .map { stat ->
                        AppUsageEntry(
                            packageName = stat.packageName,
                            totalTimeInForegroundMillis = stat.totalTimeInForeground,
                            lastTimeUsedMillis = stat.lastTimeUsed,
                            firstTimeStampMillis = stat.firstTimeStamp,
                        )
                    }
            }.getOrNull()
        }

        if (entries == null) {
            return failure(
                AppUsageResult.serializer(),
                AppUsageResult(
                    success = false,
                    windowFromMillis = from,
                    windowToMillis = to,
                    error = "The usage statistics service refused the query outright, so nothing was " +
                        "read. That is different from a window with no activity.",
                ),
            )
        }

        val r = AppUsageResult(
            success = true,
            entries = entries,
            count = entries.size,
            windowFromMillis = from,
            windowToMillis = to,
            note = buildString {
                append("Foreground time per app between $from and $to. ")
                if (entries.isEmpty()) {
                    append("The grant is held and the query ran, so an empty list means Android ")
                    append("recorded no foreground use in that window. That can also happen on a ")
                    append("freshly installed build, because the statistics store needs a little ")
                    append("history before it reports anything. ")
                }
                append("This is the unprivileged route: the platform's task list is not readable for ")
                append("other apps without a system privilege, and usage statistics cover the case.")
            }.trim(),
        )
        return emit(json, AppUsageResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Notifications
    // ------------------------------------------------------------------

    private suspend fun notify(args: Map<String, Any?>): ToolExecutionResult {
        PermissionGrants.guard(context, TeamThreePermissions.POST_NOTIFICATIONS)?.let { return it }

        val title = args["title"] as? String
        val body = args["body"] as? String
        if (title.isNullOrBlank() || body.isNullOrBlank()) {
            return failure(
                NotifyResult.serializer(),
                NotifyResult(
                    success = false,
                    channelId = (args["channelId"] as? String) ?: "guru-default",
                    error = "This call needs both a non-blank 'title' and a non-blank 'body'.",
                ),
            )
        }

        val channelId = (args["channelId"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            ?: "guru-default"
        val importanceWord = ((args["importance"] as? String) ?: "DEFAULT").uppercase()
        val ongoing = args["ongoing"] as? Boolean ?: false

        val importance = when (importanceWord) {
            "LOW" -> NotificationManager.IMPORTANCE_LOW
            "HIGH" -> NotificationManager.IMPORTANCE_HIGH
            else -> NotificationManager.IMPORTANCE_DEFAULT
        }

        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return failure(
                NotifyResult.serializer(),
                NotifyResult(
                    success = false,
                    channelId = channelId,
                    error = "This device exposes no notification service, so nothing can be posted.",
                ),
            )

        // Creating a channel twice with the same id is a no-op on the platform, which is the
        // correct idempotent pattern. Below Android 8 there are no channels at all.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                manager.createNotificationChannel(
                    NotificationChannel(channelId, "GURU", importance),
                )
            }
        }

        val enabled = runCatching { manager.areNotificationsEnabled() }.getOrDefault(false)

        val posted = runCatching {
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                android.app.Notification.Builder(context, channelId)
            } else {
                @Suppress("DEPRECATION")
                android.app.Notification.Builder(context)
            }
            builder
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(body)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)

            manager.notify(
                (System.currentTimeMillis() and 0xFFFFFF).toInt(),
                builder.build(),
            )
            true
        }.getOrDefault(false)

        val channelExists = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching { manager.getNotificationChannel(channelId) != null }.getOrDefault(false)
        } else {
            null
        }

        val r = NotifyResult(
            success = posted,
            channelId = channelId,
            notificationsEnabled = enabled,
            channelExistsAfterwards = channelExists ?: false,
            posted = posted,
            note = buildString {
                when {
                    !enabled ->
                        append("The notification was handed to the platform but notifications for this " +
                            "app are DISABLED, so the person will not see it. That is the notifications " +
                            "grant or the app's own notification setting, and no post call overrides it. ")
                    posted ->
                        append("Posted on channel $channelId, and notifications are enabled, which is " +
                            "the platform's own report rather than the post call's return. ")
                    else ->
                        append("The platform refused the post, so nothing was shown. ")
                }
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    append("The channel ${if (channelExists == true) "exists" else "is missing"} " +
                        "afterwards, read back from the platform.")
                }
            }.trim(),
            error = if (!posted) "The platform refused the notification post." else null,
        )
        return emit(json, NotifyResult.serializer(), r)
    }

    // ------------------------------------------------------------------
    // Do Not Disturb
    // ------------------------------------------------------------------

    private suspend fun dndControl(args: Map<String, Any?>): ToolExecutionResult {
        val action = ((args["action"] as? String) ?: "").uppercase()
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            ?: return failure(
                DndResult.serializer(),
                DndResult(
                    success = false,
                    action = action,
                    error = "This device exposes no notification service, so Do Not Disturb cannot be " +
                        "read or changed.",
                ),
            )

        // The READ runs before any guard: reading the filter needs no permission at all, and
        // refusing it because a write would need one would deny a person a true answer.
        val currentFilter = runCatching { manager.currentInterruptionFilter }.getOrNull()
        val filterWord = filterWord(currentFilter)
        val policyGranted = runCatching { manager.isNotificationPolicyAccessGranted }
            .getOrDefault(false)

        if (action == "GET") {
            val r = DndResult(
                success = true,
                action = action,
                filterWord = filterWord,
                policyAccessGranted = policyGranted,
                note = buildString {
                    append("The current interruption filter is ${filterWord ?: "unreadable"}. ")
                    append("Reading it needs no permission, which is why this works whether or not ")
                    append("the access toggle is on. ")
                    if (!policyGranted) {
                        append("Changing it needs the Do Not Disturb access grant, which is off, so a " +
                            "SET would be refused. Grant it in Settings > Apps > GURU > Do Not " +
                            "Disturb access.")
                    }
                }.trim(),
            )
            return emit(json, DndResult.serializer(), r)
        }

        if (action != "SET") {
            return failure(
                DndResult.serializer(),
                DndResult(
                    success = false,
                    action = action,
                    filterWord = filterWord,
                    policyAccessGranted = policyGranted,
                    error = "The action must be GET or SET, and '$action' is neither.",
                ),
            )
        }

        // Only the write is guarded, and the guard's refusal is returned as-is so the sentence
        // naming the access screen is the catalogue's, not a second invention.
        PermissionGrants.guard(context, TeamThreePermissions.ACCESS_NOTIFICATION_POLICY)
            ?.let { return it }

        val mode = ((args["mode"] as? String) ?: "").uppercase()
        val target = when (mode) {
            "OFF", "ALL" -> NotificationManager.INTERRUPTION_FILTER_ALL
            "PRIORITY" -> NotificationManager.INTERRUPTION_FILTER_PRIORITY
            "ALARMS" -> NotificationManager.INTERRUPTION_FILTER_ALARMS
            "NONE" -> NotificationManager.INTERRUPTION_FILTER_NONE
            else -> return failure(
                DndResult.serializer(),
                DndResult(
                    success = false,
                    action = action,
                    filterWord = filterWord,
                    policyAccessGranted = policyGranted,
                    error = "SET needs 'mode' as one of OFF, PRIORITY, ALARMS, NONE or ALL, and " +
                        "'$mode' is none of them.",
                ),
            )
        }

        val written = runCatching {
            manager.setInterruptionFilter(target)
            true
        }.getOrDefault(false)

        // Read the filter back rather than reporting the mode that was sent.
        val afterWord = runCatching { filterWord(manager.currentInterruptionFilter) }.getOrNull()

        val r = DndResult(
            success = written,
            action = action,
            filterWord = afterWord,
            policyAccessGranted = policyGranted,
            written = written,
            note = if (written) {
                "Set the filter to $mode and read it back as ${afterWord ?: "unreadable"}. The value " +
                    "above is what the platform holds now, not what was requested."
            } else {
                "The platform refused the change, so the filter is unchanged."
            },
            error = if (written) null else "The platform refused the interruption filter change.",
        )
        return emit(json, DndResult.serializer(), r)
    }

    private fun filterWord(filter: Int?): String? = when (filter) {
        NotificationManager.INTERRUPTION_FILTER_ALL -> "ALL"
        NotificationManager.INTERRUPTION_FILTER_PRIORITY -> "PRIORITY"
        NotificationManager.INTERRUPTION_FILTER_ALARMS -> "ALARMS"
        NotificationManager.INTERRUPTION_FILTER_NONE -> "NONE"
        NotificationManager.INTERRUPTION_FILTER_UNKNOWN -> "UNKNOWN"
        else -> null
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
            is AppUsageResult -> payload.success
            is NotifyResult -> payload.success
            is DndResult -> payload.success
            else -> false
        },
        error = when (payload) {
            is AppUsageResult -> payload.error
            is NotifyResult -> payload.error
            is DndResult -> payload.error
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
            is AppUsageResult -> payload.error
            is NotifyResult -> payload.error
            is DndResult -> payload.error
            else -> null
        },
    )
}

object AppUsageNotifyDomainModule : DomainToolModule {
    override val id: String = "app_usage_notify"

    override val registrations: List<ToolSetRegistration> = listOf(AppUsageNotifyDomainDefinitions)

    override fun bindings(): Module = module {
        factory { AppUsageNotifyDomainExecutor(get()) }
    }
}
