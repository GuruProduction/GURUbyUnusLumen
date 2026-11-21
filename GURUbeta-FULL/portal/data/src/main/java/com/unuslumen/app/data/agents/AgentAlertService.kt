// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import com.unuslumen.app.domain.model.AgentNotificationPolicy
import com.unuslumen.app.domain.model.AgentNotifications
import com.unuslumen.app.domain.model.AgentUrgency
import java.util.Calendar
import org.koin.core.annotation.Single

/**
 * Tells the owner something an agent did, when that agent's preferences say it may.
 *
 * THE GAP THIS CLOSES. `AgentNotifications` has carried an enabled flag, an urgency threshold and a
 * quiet window since Phase 2, and nothing has ever read any of it. An agent that finished overnight or
 * stopped because it needed a decision told nobody, so the only way to discover either was to open its
 * room and look. That makes the whole transparency surface a thing you must remember to check rather
 * than a thing that reaches you.
 *
 * THE DECISION IS NOT MADE HERE. [AgentNotificationPolicy] decides, as a pure function of the agent's
 * settings, the message's urgency and the minute of the day. This class supplies the clock and the
 * channel and posts, which is the part that genuinely needs Android.
 *
 * IT IS BEST-EFFORT AND NEVER THROWS. A notification that cannot be posted must not fail the run that
 * produced it: the work is already done and recorded, and a refused POST_NOTIFICATIONS permission
 * costs the alert rather than the result. The failure is logged, and the room still shows everything.
 *
 * IT HAS ITS OWN CHANNEL, SEPARATE FROM THE RUNNING ONE. "An agent is working" is a standing fact in
 * the shade that should stay silent; "an agent finished" or "an agent is blocked on you" is an
 * event the owner may well want to hear. Two kinds of message with different importance belong in two
 * channels, because a person can then silence one without silencing the other.
 */
@Single
class AgentAlertService(
    private val context: Context,
) {

    companion object {
        const val CHANNEL_ID = "guru_agent_outcomes"

        /** Where the agent's own message carries its urgency, when the caller knows it. */
        const val EXTRA_URGENCY = "urgency"
    }

    /**
     * Tell the owner a run needs them, if that agent may say so.
     *
     * WAITING ON THE OWNER IS THE ONE STATE THAT IS ACTIVELY COSTING THEM TIME. A run blocked on a
     * decision does nothing until somebody decides, so this is the highest-urgency thing an agent
     * can produce: the request's own severity, which the caller passes, governs whether it interrupts.
     */
    fun alertBlocked(
        runId: String,
        agentName: String,
        assignment: String,
        notifications: AgentNotifications,
        reason: String,
        urgency: AgentUrgency = AgentUrgency.HIGH,
    ): AgentNotificationPolicy.Verdict = post(
        runId = runId,
        agentName = agentName,
        title = "$agentName needs you",
        body = reason.ifBlank { "It is waiting on a decision before it can carry on." },
        notifications = notifications,
        urgency = urgency,
    )

    /**
     * Tell the owner a run finished, if that agent may say so.
     *
     * A COMPLETION IS NORMAL URGENCY. It is good to know and it is not worth waking anybody for, so
     * an agent with a quiet window will hold it until morning, which is the correct behaviour and the
     * reason the threshold exists.
     */
    fun alertFinished(
        runId: String,
        agentName: String,
        assignment: String,
        notifications: AgentNotifications,
        succeeded: Boolean,
        report: String,
        urgency: AgentUrgency = AgentUrgency.NORMAL,
    ): AgentNotificationPolicy.Verdict = post(
        runId = runId,
        agentName = agentName,
        title = if (succeeded) "$agentName finished" else "$agentName could not finish",
        body = report.ifBlank {
            if (succeeded) "Its report is in its room." else "The reason is in its room."
        },
        notifications = notifications,
        urgency = urgency,
    )

    /**
     * Gate, then post.
     *
     * THE GATE RUNS FIRST AND ALWAYS. A suppressed message produces no notification and no channel
     * work at all, so an agent the owner has silenced costs nothing but the decision. The verdict is
     * returned either way, because a surface may want to say what it did not send.
     */
    private fun post(
        runId: String,
        agentName: String,
        title: String,
        body: String,
        notifications: AgentNotifications,
        urgency: AgentUrgency,
    ): AgentNotificationPolicy.Verdict {
        val verdict = AgentNotificationPolicy.evaluate(
            notifications = notifications,
            urgency = urgency,
            minuteOfDay = minuteOfDayNow(),
        )
        if (verdict.isSuppressed) return verdict

        runCatching {
            createChannel()
            val manager = context.getSystemService(NotificationManager::class.java)
            manager?.notify(notificationIdFor(runId, title), build(runId, agentName, title, body))
        }.onFailure {
            /*
             * A MISSING POST_NOTIFICATIONS GRANT LANDS HERE and is not a defect in the run. The
             * work is done and recorded; only the interruption was lost. Reported rather than
             * swallowed so a person wondering why nothing arrived can see the reason.
             */
            android.util.Log.w("guru_agents", "Agent alert not posted: ${it.message}")
        }
        return verdict
    }

    /**
     * A stable id per run and kind, so a second alert for the same run replaces the first.
     *
     * NOT A COUNTER. A counter would give the same event a different id each time and the shade would
     * fill with duplicates for one run.
     */
    private fun notificationIdFor(runId: String, title: String): Int =
        (runId + "|" + title).hashCode()

    private fun minuteOfDayNow(): Int {
        val now = Calendar.getInstance()
        return now.get(Calendar.HOUR_OF_DAY) * 60 + now.get(Calendar.MINUTE)
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent outcomes",
                /*
                 * DEFAULT, WHERE THE RUNNING CHANNEL IS LOW. This one exists to be noticed: an agent
                 * that finished or that is blocked on its owner is the message the person asked for
                 * by enabling notifications at all. The agent's own settings and quiet window are
                 * what decide whether it is actually posted, not the channel's importance.
                 */
                NotificationManager.IMPORTANCE_DEFAULT,
            ).apply {
                description = "When a GURU agent finishes or needs a decision."
                setShowBadge(true)
            }
        )
    }

    private fun build(
        runId: String,
        agentName: String,
        title: String,
        body: String,
    ): Notification {
        val open = context.packageManager.getLaunchIntentForPackage(context.packageName)
        val openPending = open?.let {
            PendingIntent.getActivity(
                context,
                notificationIdFor(runId, title),
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body.take(180))
            /*
             * THE FULL REPORT IS THE BIG TEXT, NOT A TRUNCATION. An agent's finding condensed to one
             * line in the shade is a headline the owner cannot act on without opening the room, and
             * the point of the alert is that they may not open it.
             */
            .setStyle(NotificationCompat.BigTextStyle().bigText(body.take(1_500)))
            .setSmallIcon(android.R.drawable.stat_notify_chat)
            .setAutoCancel(true)
            .setContentIntent(openPending)
            .setSubText(agentName)
            .build()
    }
}
