// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.notification

import android.content.Context
import android.service.notification.StatusBarNotification
import com.unuslumen.app.data.tools.GuruNotificationListener
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The live notification domain: what has arrived, waiting for the next one, dismissing
 * and pressing a button.
 *
 * WHY THIS IS SEPARATE FROM THE `notification` TOOL SET. That set reads the shade, which
 * answers "what is on screen right now". These answer "what has arrived", which is a
 * different question with a different answer the moment a notification is dismissed. Both
 * are real; conflating them is how the listener looked wired while nothing used it.
 *
 * WHAT EVERY RESULT STATES. Whether the listener is bound. A read from a disabled
 * listener and a read from a working listener that caught nothing are different facts, and
 * a tool that returns an empty list for the first is the defect issue 337 names: a value a
 * genuine result could also produce is not a result.
 *
 * WHY NO TOOL DECLARES THE PERMISSION. `BIND_NOTIFICATION_LISTENER_SERVICE` is a signature
 * permission and reads as denied on every phone, so the dispatcher's pre-flight would
 * refuse every call including on a phone where the listener runs. The check reads
 * Settings.Secure instead, and [NotificationAccess] holds it.
 */

@Serializable
data class NotificationStreamResult(
    val success: Boolean,
    /** True only when the service is bound, so an empty list is a real answer. */
    val listenerRunning: Boolean,
    val listenerState: String,
    /** The notifications asked for, newest last. */
    val notifications: List<CapturedNotification> = emptyList(),
    val count: Int = 0,
    /** What this list actually covers, stated so nobody reads it as a stored history. */
    val scope: String,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class NotificationWaitResult(
    val success: Boolean,
    val listenerRunning: Boolean,
    val listenerState: String,
    /** The notification that matched, or null when the wait expired or the listener was off. */
    val notification: CapturedNotification? = null,
    val waitedMillis: Long = 0,
    val timeoutMillis: Long = 0,
    val packageNameFilter: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class NotificationDismissResult(
    val success: Boolean,
    val listenerRunning: Boolean,
    val listenerState: String,
    /** The key that was dismissed, echoed so a caller can tell which one landed. */
    val key: String? = null,
    val packageName: String? = null,
    /** True when the notification is gone from the shade afterwards, read back. */
    val goneFromShade: Boolean = false,
    val dismissedCount: Int = 0,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class NotificationActionResult(
    val success: Boolean,
    val listenerRunning: Boolean,
    val listenerState: String,
    val key: String? = null,
    val actionIndex: Int = -1,
    val actionTitle: String? = null,
    /** What the notification's buttons actually are, so a wrong index is answerable. */
    val availableActions: List<CapturedAction> = emptyList(),
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

object NotificationDomainDefinitions : ToolSetRegistration {
    const val STREAM = "notificationStream"
    const val WAIT = "notificationWait"
    const val DISMISS = "notificationDismiss"
    const val ACT = "notificationAction"

    override val definitions = listOf(
        ToolDefinition(
            name = STREAM,
            description = "List the notifications that have actually arrived on this phone since " +
                "GURU's process started, newest last, with the app name, title, body, category and " +
                "the buttons each one carries. This is the live stream rather than the shade: a " +
                "notification the person already swiped away is still here, which is exactly what " +
                "getActiveNotifications cannot tell you. It needs Notification access, a Settings " +
                "toggle, and the result says plainly whether the listener is bound. The list dies " +
                "with the process and is not a stored history.",
            category = "notifications",
            parameters = listOf(
                ToolParameter(
                    "limit", ToolParameterType.Integer, false,
                    "How many of the most recent to return, 1 to 200. Default 20.",
                ),
                ToolParameter(
                    "packageName", ToolParameterType.String, false,
                    "Only this app's notifications, such as com.whatsapp. Omit for every app.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = WAIT,
            description = "Wait for the next notification to arrive and return it, or return when " +
                "the timeout expires. Use this instead of polling notificationStream when the point " +
                "is to react to something as it happens: a message from one person, a code arriving, " +
                "a delivery notification. Filter by package name to wait for one app. A timeout " +
                "means nothing arrived in that window, which is not the same as the listener being " +
                "off, and the result says which one it is.",
            category = "notifications",
            parameters = listOf(
                ToolParameter(
                    "timeoutSeconds", ToolParameterType.Integer, false,
                    "How long to wait, 1 to 300 seconds. Default 30.",
                ),
                ToolParameter(
                    "packageName", ToolParameterType.String, false,
                    "Only count a notification from this app, such as com.whatsapp.",
                ),
                ToolParameter(
                    "titleContains", ToolParameterType.String, false,
                    "Only count a notification whose title contains this text, case-insensitive.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = DISMISS,
            description = "Dismiss a notification by the key notificationStream returned, or dismiss " +
                "every notification currently in the shade. The result reads the shade back rather " +
                "than reporting the call's return, because Android refuses to dismiss an ongoing or " +
                "system notification and a tool that claimed success there would be lying about the " +
                "person's screen.",
            category = "notifications",
            parameters = listOf(
                ToolParameter(
                    "key", ToolParameterType.String, false,
                    "The key exactly as notificationStream reported it. Omit with all=true to clear " +
                        "the shade instead.",
                ),
                ToolParameter(
                    "all", ToolParameterType.Boolean, false,
                    "Dismiss everything clearable in the shade. Default false.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = ACT,
            description = "Press one of a notification's buttons, such as Reply, Mark as read or " +
                "Archive. Get the key and the action index from notificationStream first: every " +
                "captured notification lists its actions with their index and title. This is how " +
                "GURU answers a message without opening the app, which is the whole reason a " +
                "notification listener is worth having.",
            category = "notifications",
            parameters = listOf(
                ToolParameter(
                    "key", ToolParameterType.String, true,
                    "The key exactly as notificationStream reported it.",
                ),
                ToolParameter(
                    "actionIndex", ToolParameterType.Integer, true,
                    "The index of the button to press, from the actions list on the notification.",
                ),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = NotificationDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class NotificationDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            NotificationDomainDefinitions.STREAM -> stream(args)
            NotificationDomainDefinitions.WAIT -> wait(args)
            NotificationDomainDefinitions.DISMISS -> dismiss(args)
            NotificationDomainDefinitions.ACT -> act(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // The stream
    // ------------------------------------------------------------------

    private suspend fun stream(args: Map<String, Any?>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, NotificationStream.CAPACITY)
            val filter = (args["packageName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

            NotificationAccess.guard(context)?.let { return@withContext it }

            val notifications = NotificationStream.recent(limit, filter)
            val state = NotificationAccess.state(context)

            val r = NotificationStreamResult(
                success = true,
                listenerRunning = state == NotificationAccess.State.RUNNING,
                listenerState = state.name,
                notifications = notifications,
                count = notifications.size,
                scope = "In-memory trace of this app run only (${NotificationStream.size()} " +
                    "notifications kept, newest last). It does not survive a restart and it is " +
                    "not a stored notification history.",
                note = buildString {
                    append(NotificationAccess.describe(state)).append(' ')
                    if (notifications.isEmpty()) {
                        append("The listener is bound and the ring is empty, so nothing has arrived ")
                        append("since GURU's process started")
                        if (filter != null) append(" that came from $filter")
                        append(". That is an empty result rather than a refused read.")
                    } else {
                        append("These are arrivals, which is a different set from what is still in ")
                        append("the shade: a swiped notification is here and not there.")
                    }
                }.trim(),
            )
            emit(json, NotificationStreamResult.serializer(), r, r.success, r.error)
        }

    // ------------------------------------------------------------------
    // Waiting for the next one
    // ------------------------------------------------------------------

    private suspend fun wait(args: Map<String, Any?>): ToolExecutionResult {
        val timeoutSeconds = ((args["timeoutSeconds"] as? Number)?.toInt() ?: 30).coerceIn(1, 300)
        val filter = (args["packageName"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
        val titleContains = (args["titleContains"] as? String)?.trim()?.takeIf { it.isNotEmpty() }

        NotificationAccess.guard(context)?.let { return it }

        val state = NotificationAccess.state(context)
        val timeoutMillis = timeoutSeconds * 1000L
        val startedAt = System.currentTimeMillis()

        val matched = withTimeoutOrNull(timeoutMillis) {
            // first() on the shared flow suspends until one arrives, and the predicate is
            // applied before it is returned, so a notification from the wrong app does not
            // consume the wait. A flow with no replay means only what arrives from now
            // counts, which is what "wait for the next one" means.
            NotificationStream.posted.first { candidate ->
                (filter == null || candidate.packageName == filter) &&
                    (titleContains == null || candidate.title.contains(titleContains, ignoreCase = true))
            }
        }

        val waited = System.currentTimeMillis() - startedAt

        val r = NotificationWaitResult(
            success = matched != null,
            listenerRunning = state == NotificationAccess.State.RUNNING,
            listenerState = state.name,
            notification = matched,
            waitedMillis = waited,
            timeoutMillis = timeoutMillis,
            packageNameFilter = filter,
            note = when {
                matched != null ->
                    "Arrived ${waited}ms into the wait, from ${matched.appLabel}."
                else ->
                    "Nothing matched in ${timeoutSeconds} seconds. The listener is bound, so this " +
                        "means no notification arrived in that window, which is not a refused " +
                        "read and not a missing grant."
            },
            error = if (matched == null) {
                "No notification arrived within ${timeoutSeconds} seconds" +
                    (filter?.let { " from $it" } ?: "") + "."
            } else {
                null
            },
        )
        return emit(json, NotificationWaitResult.serializer(), r, r.success, r.error)
    }

    // ------------------------------------------------------------------
    // Dismissing
    // ------------------------------------------------------------------

    private suspend fun dismiss(args: Map<String, Any?>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val key = (args["key"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val all = args["all"] as? Boolean ?: false

            NotificationAccess.guard(context)?.let { return@withContext it }

            val service = GuruNotificationListener.instance
                ?: return@withContext emit(
                    json,
                    NotificationDismissResult.serializer(),
                    NotificationDismissResult(
                        success = false,
                        listenerRunning = false,
                        listenerState = NotificationAccess.State.ENABLED_NOT_BOUND.name,
                        error = "The listener was enabled a moment ago and is not bound now, so " +
                            "nothing was dismissed. Ask again in a few seconds.",
                    ),
                    false,
                    "The listener is not bound, so nothing was dismissed.",
                )

            val state = NotificationAccess.state(context)

            if (key == null && !all) {
                return@withContext emit(
                    json,
                    NotificationDismissResult.serializer(),
                    NotificationDismissResult(
                        success = false,
                        listenerRunning = true,
                        listenerState = state.name,
                        error = "This call needs either a 'key' from notificationStream or all=true " +
                            "to clear the shade. Nothing was dismissed.",
                    ),
                    false,
                    "Nothing was dismissed because no target was named.",
                )
            }

            if (key != null) {
                val existedBefore = service.shadeSnapshot().any { it.key == key }
                service.dismissByKey(key)
                // Read the shade back rather than trusting the call. Android refuses to
                // dismiss ongoing and group-summary notifications, and the only honest
                // report is what the platform holds now.
                val stillThere = service.shadeSnapshot().any { it.key == key }

                val r = NotificationDismissResult(
                    success = !stillThere,
                    listenerRunning = true,
                    listenerState = state.name,
                    key = key,
                    goneFromShade = !stillThere,
                    dismissedCount = if (!stillThere) 1 else 0,
                    note = when {
                        !existedBefore ->
                            "That key was not in the shade, so there was nothing to dismiss. A key " +
                                "from notificationStream can outlive the notification, because the " +
                                "stream remembers what arrived and the shade holds what is left."
                        !stillThere -> "Dismissed, and the shade no longer lists it."
                        else ->
                            "The shade still lists it after the dismiss call, which is what Android " +
                                "does for an ongoing or system notification. Nothing here can remove " +
                                "one of those."
                    },
                    error = if (stillThere) "The notification is still in the shade after the dismiss call." else null,
                )
                return@withContext emit(json, NotificationDismissResult.serializer(), r, r.success, r.error)
            }

            val before = service.shadeSnapshot().count { it.isClearable }
            service.shadeSnapshot().filter { it.isClearable }.forEach { service.dismissByKey(it.key) }
            val remaining = service.shadeSnapshot().count { it.isClearable }

            val r = NotificationDismissResult(
                success = remaining == 0,
                listenerRunning = true,
                listenerState = state.name,
                goneFromShade = remaining == 0,
                dismissedCount = (before - remaining).coerceAtLeast(0),
                note = "Dismissed everything clearable and read the shade back: $remaining clearable " +
                    "notification(s) remain. Ongoing and system notifications refuse dismissal and are " +
                    "counted out of this, because no call removes them.",
                error = if (remaining > 0) "$remaining clearable notification(s) refused the dismiss call." else null,
            )
            emit(json, NotificationDismissResult.serializer(), r, r.success, r.error)
        }

    // ------------------------------------------------------------------
    // Pressing a button
    // ------------------------------------------------------------------

    private suspend fun act(args: Map<String, Any?>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val key = (args["key"] as? String)?.trim()?.takeIf { it.isNotEmpty() }
            val index = (args["actionIndex"] as? Number)?.toInt()

            NotificationAccess.guard(context)?.let { return@withContext it }

            val service = GuruNotificationListener.instance
                ?: return@withContext emit(
                    json,
                    NotificationActionResult.serializer(),
                    NotificationActionResult(
                        success = false,
                        listenerRunning = false,
                        listenerState = NotificationAccess.State.ENABLED_NOT_BOUND.name,
                        error = "The listener is not bound right now, so no action was pressed. Ask " +
                            "again in a few seconds.",
                    ),
                    false,
                    "The listener is not bound, so no action was pressed.",
                )

            val state = NotificationAccess.state(context)

            if (key == null || index == null) {
                return@withContext emit(
                    json,
                    NotificationActionResult.serializer(),
                    NotificationActionResult(
                        success = false,
                        listenerRunning = true,
                        listenerState = state.name,
                        error = "This call needs both a 'key' from notificationStream and an " +
                            "'actionIndex' from that notification's actions list.",
                    ),
                    false,
                    "Nothing was pressed because the target was incomplete.",
                )
            }

            // The live shade entry is the only source of the action's intent: it belongs to
            // the posting app and the stream's copy carries only what it can describe.
            val live = service.shadeSnapshot().firstOrNull { it.key == key }
            val available = live?.let { actionsOf(it) }
                ?: NotificationStream.recent(NotificationStream.CAPACITY)
                    .lastOrNull { it.key == key }
                    ?.actions
                    .orEmpty()

            if (live == null) {
                return@withContext emit(
                    json,
                    NotificationActionResult.serializer(),
                    NotificationActionResult(
                        success = false,
                        listenerRunning = true,
                        listenerState = state.name,
                        key = key,
                        actionIndex = index,
                        availableActions = available,
                        error = "That notification is no longer in the shade, so its buttons are gone " +
                            "with it. An action can only be pressed while the notification is live.",
                    ),
                    false,
                    "The notification is no longer in the shade, so there was no button to press.",
                )
            }

            val action = live.notification?.actions?.getOrNull(index)
            if (action == null) {
                return@withContext emit(
                    json,
                    NotificationActionResult.serializer(),
                    NotificationActionResult(
                        success = false,
                        listenerRunning = true,
                        listenerState = state.name,
                        key = key,
                        actionIndex = index,
                        availableActions = available,
                        error = "This notification has no action at index $index. Use one of the " +
                            "indices in availableActions.",
                    ),
                    false,
                    "There is no button at index $index on that notification.",
                )
            }

            val pressed = service.pressAction(key, index)

            val r = NotificationActionResult(
                success = pressed,
                listenerRunning = true,
                listenerState = state.name,
                key = key,
                actionIndex = index,
                actionTitle = action.title?.toString(),
                availableActions = available,
                note = if (pressed) {
                    "Pressed '${action.title}', and the intent was sent to the app that posted the " +
                        "notification. Whether the app did what the button says is the app's business " +
                        "and is not observable from here."
                } else {
                    "Android refused the action, which usually means the intent was already " +
                        "consumed or the notification was replaced between the read and the press."
                },
                error = if (pressed) null else "The action intent could not be sent.",
            )
            emit(json, NotificationActionResult.serializer(), r, r.success, r.error)
        }

    /**
     * The buttons on a shade entry, described the same way the listener describes them.
     *
     * Read from the platform here rather than from the stream's copy, because the stream's
     * copy is a snapshot from when it arrived and the action list on a live notification can
     * differ after an app updates it in place.
     */
    private fun actionsOf(sbn: StatusBarNotification): List<CapturedAction> {
        val notification = sbn.notification ?: return emptyList()
        val actions = notification.actions ?: return emptyList()
        return actions.mapIndexedNotNull { index, action ->
            if (action == null) return@mapIndexedNotNull null
            CapturedAction(
                index = index,
                title = action.title?.toString().orEmpty(),
                sendable = action.actionIntent != null,
                wantsText = action.remoteInputs?.isNotEmpty() == true,
            )
        }
    }

    private fun <T : ToolResultData> emit(
        json: Json,
        serializer: kotlinx.serialization.KSerializer<T>,
        payload: T,
        success: Boolean,
        error: String?,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, payload),
        resultData = payload,
        resultObject = null,
        success = success,
        error = error,
    )
}

object NotificationDomainModule : DomainToolModule {
    override val id: String = "notifications_live"

    override val registrations: List<ToolSetRegistration> = listOf(NotificationDomainDefinitions)

    override fun bindings(): Module = module {
        factory { NotificationDomainExecutor(get()) }
    }
}
