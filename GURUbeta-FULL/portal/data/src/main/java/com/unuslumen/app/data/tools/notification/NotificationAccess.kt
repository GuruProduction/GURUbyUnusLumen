// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.notification

import android.content.ComponentName
import android.content.Context
import android.provider.Settings
import com.unuslumen.app.data.tools.GuruNotificationListener
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.permission.GrantCheck
import com.unuslumen.app.data.tools.registry.permission.PermissionGrants
import com.unuslumen.app.data.tools.registry.permission.TeamThreePermissions
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.serialization.Serializable

/**
 * NotificationAccess — the three facts about the notification listener, and the
 * stream of what actually arrived.
 *
 * WHY THREE STATES AND NOT ONE BOOLEAN. The listener has the same shape as the
 * accessibility service, for the same reason: "the person has not switched it on"
 * and "it is switched on but not connected right now" need opposite responses. The
 * first needs a trip to a Settings screen and no amount of waiting helps. The
 * second clears by itself, and sending somebody to Settings for it wastes their
 * time. A status field that collapses both into `false` cannot tell them apart,
 * which is the defect issue 36 fixed on the accessibility service.
 *
 * WHY THE GRANT IS NOT A DECLARED TOOL PERMISSION. `BIND_NOTIFICATION_LISTENER_SERVICE`
 * is a signature permission: the app declares it so the platform will bind the
 * service, and `checkSelfPermission` reports it as denied on every phone because a
 * normal app is never granted it. The dispatcher's pre-flight refuses on any
 * declared permission it reads as missing, so declaring this on a notification tool
 * would refuse every call everywhere, including on a phone where the listener is
 * enabled and working. The grant that decides this capability lives in
 * `Settings.Secure.ENABLED_NOTIFICATION_LISTENERS`, so the check reads that and the
 * tools guard themselves. dndControl's write path makes the same trade for the same
 * reason.
 */
object NotificationAccess {

    /** The three states, reported as words because a boolean cannot carry them. */
    enum class State {
        /** Not switched on in Settings. Waiting never helps; the person must act. */
        DISABLED,

        /** Switched on, but the platform has not bound the service right now. */
        ENABLED_NOT_BOUND,

        /** Bound and delivering. The stream is real and so is the shade. */
        RUNNING,
    }

    /** This app's listener, as the platform names it in the enabled-services list. */
    fun component(context: Context): ComponentName =
        ComponentName(context.packageName, GuruNotificationListener::class.java.name)

    /**
     * Whether the person has switched this app's listener on in Settings.
     *
     * THE READ IS THE SECURE SETTING, not a permission check, because that is where
     * Android records the answer. The value is a colon-separated list of flattened
     * component names, and the spelling differs between builds: some write the long
     * form, some the short one. Both are tested and the package prefix is the
     * fallback, because a mismatch here would report a working listener as off and
     * send somebody to a screen that already says enabled.
     */
    fun isEnabledInSettings(context: Context): Boolean = runCatching {
        val enabled = Settings.Secure.getString(
            context.contentResolver,
            "enabled_notification_listeners",
        ).orEmpty()
        if (enabled.isEmpty()) return false

        val component = component(context)
        enabled.contains(component.flattenToString()) ||
            enabled.contains(component.flattenToShortString()) ||
            enabled.split(':').any { it.startsWith(context.packageName) }
    }.getOrDefault(false)

    /** Whether the service is bound right now. */
    fun isBound(): Boolean = GuruNotificationListener.instance != null

    fun state(context: Context): State = when {
        isBound() -> State.RUNNING
        isEnabledInSettings(context) -> State.ENABLED_NOT_BOUND
        else -> State.DISABLED
    }

    /**
     * The refusal for a notification tool, or null when the listener may be used.
     *
     * Two of the three states produce a refusal and they are built separately on
     * purpose. A disabled listener is a grant problem, so its sentence comes from the
     * catalogue and names the exact Settings screen in the same words every other
     * refusal uses. A listener that is switched on but not bound is not a grant
     * problem at all: there is nothing for the person to grant and nowhere to send
     * them, so telling them the permission is missing would be a lie about their own
     * phone.
     *
     * Both are `success = false`, because in neither state did the tool do the thing,
     * and a success flag on a call that read nothing is the defect issue 337 names.
     */
    fun guard(context: Context): ToolExecutionResult? = when (val now = state(context)) {
        State.RUNNING -> null

        State.DISABLED -> PermissionGrants.refusal(
            GrantCheck.of(TeamThreePermissions.NOTIFICATION_ACCESS, granted = false)
        )

        State.ENABLED_NOT_BOUND -> ToolExecutionResult(
            rawJson = PermissionGrants.refusalBodyJson(
                GrantCheck.of(TeamThreePermissions.NOTIFICATION_ACCESS, granted = false)
            ),
            resultData = null,
            resultObject = null,
            success = false,
            error = "Permission held but the listener is not connected: GURU is switched on for " +
                "notification access, and the platform has not bound the service right now. That " +
                "usually clears within a few seconds; until it does, nothing new is being captured. " +
                "This is not a grant problem, so there is nothing to allow.",
        )
    }

    /**
     * One sentence naming the state, for a result that ran and wants to be explicit
     * about what it read.
     */
    fun describe(state: State): String = when (state) {
        State.RUNNING ->
            "The listener is bound, so this reads notifications as they arrive rather than " +
                "whatever is left in the shade."
        State.ENABLED_NOT_BOUND ->
            "The listener is switched on but not connected to the platform right now, which " +
                "usually clears within a few seconds; nothing new is being captured until it does."
        State.DISABLED ->
            "The listener is switched off, so nothing has been captured and nothing is being " +
                "captured now."
    }
}

/**
 * One notification as it arrived.
 *
 * PURE KOTLIN WITH NO ANDROID TYPES. That is deliberate: the capture, the ring and
 * the filtering are where this can be wrong in ways a device test would hide, so
 * they live in objects a JVM test can drive directly. The listener converts
 * `StatusBarNotification` into this and does nothing else.
 */
@Serializable
data class CapturedNotification(
    /** The platform's own key. Dismiss and act need it, so it is never rebuilt. */
    val key: String,
    val packageName: String,
    /** The app's display label, so a model reads "WhatsApp" rather than a package. */
    val appLabel: String,
    val notificationId: Int,
    val title: String,
    val text: String,
    val subText: String,
    val bigText: String,
    val category: String,
    /** When the app posted it, epoch milliseconds. */
    val postTimeMillis: Long,
    /** When this process saw it, epoch milliseconds. Later than postTime when the phone was busy. */
    val receivedAtMillis: Long,
    val isOngoing: Boolean,
    val isClearable: Boolean,
    val actions: List<CapturedAction> = emptyList(),
) {
    /** True when this notification carries buttons that can be pressed. */
    val hasActions: Boolean get() = actions.isNotEmpty()
}

/** One button on a notification, as it can be pressed later. */
@Serializable
data class CapturedAction(
    /** The position in the notification's action list, which is what act uses. */
    val index: Int,
    val title: String,
    /** False when the action carries no intent, which some apps ship. */
    val sendable: Boolean,
    /** True when pressing this button needs text typed into it, such as a reply. */
    val wantsText: Boolean = false,
)

/**
 * NotificationStream — what has arrived during this app run, plus a live feed.
 *
 * WHY IN MEMORY. The project has no notification store, and inventing a database to
 * satisfy a tool name would be a decorative layer. The ring is bounded and every
 * result states plainly that it dies with the process, which is the same honesty the
 * location trace uses.
 *
 * WHY A RING AND A FLOW. "What arrived while you were working" and "tell me when the
 * next one arrives" are different questions. The ring answers the first without
 * blocking and the flow answers the second without polling. Both are fed from one
 * record call, so they cannot disagree about what arrived.
 */
object NotificationStream {

    /** How many arrivals are kept. Old enough to be useful, small enough for a phone. */
    const val CAPACITY = 200

    private val lock = Any()
    private val ring = ArrayDeque<CapturedNotification>()

    /**
     * The live feed of arrivals.
     *
     * No replay: a watcher wants what arrives next, and replaying the ring into a late
     * subscriber would make a watch return old notifications as though they were new.
     * The buffer is bounded and drops the oldest under pressure, which the watch
     * result states rather than hides.
     */
    private val _posted = MutableSharedFlow<CapturedNotification>(
        replay = 0,
        extraBufferCapacity = 64,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    val posted: SharedFlow<CapturedNotification> = _posted

    /** Record one arrival. Called from the listener; safe from any thread. */
    fun record(notification: CapturedNotification) {
        synchronized(lock) {
            ring.addLast(notification)
            while (ring.size > CAPACITY) ring.removeFirst()
        }
        _posted.tryEmit(notification)
    }

    /** The most recent arrivals, newest last, matching [packageName] when given. */
    fun recent(limit: Int, packageName: String? = null): List<CapturedNotification> =
        synchronized(lock) {
            val matching = if (packageName == null) {
                ring.toList()
            } else {
                ring.filter { it.packageName == packageName }
            }
            matching.takeLast(limit.coerceIn(1, CAPACITY))
        }

    fun size(): Int = synchronized(lock) { ring.size }

    /** For a test that needs a clean slate. Never called by product code. */
    fun clear() {
        synchronized(lock) { ring.clear() }
    }
}
