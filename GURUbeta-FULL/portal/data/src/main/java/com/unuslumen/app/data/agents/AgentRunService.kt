// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.agents

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.app.NotificationCompat
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

/**
 * The process that holds every live agent run while a person can see it working.
 *
 * WHY A SERVICE AT ALL. Durability on disk is only half of it. Android kills background
 * processes, and a ledger cannot make a dead loop continue. A foreground service is the
 * only thing that keeps an agent actually running when the Portal is backgrounded or the
 * person switches app, and its ongoing notification is the contract that says GURU is still
 * doing something and offers the way to stop it.
 *
 * IT HOLDS MANY RUNS, NOT ONE. Several agents can be working at once and each one needs its
 * own foreground notification, its own heartbeat and its own stop action. A single
 * `currentRunId` made that impossible: a second start overwrote the first run's id, cancelled
 * its heartbeat, and left a run executing with no lease protection while the only stop
 * button on screen targeted whichever run had most recently arrived. So the state here is a
 * map keyed by run id and nothing is held in a single slot.
 *
 * EACH STOP ACTION CARRIES ITS OWN RUN ID. A PendingIntent's request code separates two
 * actions, but the extra is what makes the target unambiguous: the stop for run A names run
 * A, whatever else has started since. A PendingIntent built from mutable "the current run"
 * is exactly the defect this replaces.
 *
 * THE SERVICE RUNS NO WORK. It owns the notification and the heartbeat, and the heartbeat
 * asks [AgentRunHost] whether work is genuinely active rather than trusting a local job that
 * would always look finished. Execution lives on the host, whose scope nothing here can
 * cancel.
 *
 * THE SERVICE TYPE, CHOSEN FROM THE MANIFEST RATHER THAN GUESSED. `specialUse` with a
 * declared subtype, because agent execution is not data synchronisation, not media
 * processing and not any other listed type.
 *
 * NO PROMISE OF INDEFINITE EXECUTION. Android can stop a long-running service and job quotas
 * apply. The design leans on the checkpoint, not on the service: if this process is killed,
 * the run becomes `INTERRUPTED` on the next scan and resumes from the last checkpoint.
 */
class AgentRunService : Service(), KoinComponent {

    companion object {
        const val CHANNEL_ID = "guru_agent_runs"
        const val EXTRA_RUN_ID = "run_id"
        const val EXTRA_AGENT_NAME = "agent_name"
        const val ACTION_STOP = "com.unuslumen.app.action.STOP_AGENT_RUN"

        /**
         * Bring the service up for a run so it holds the foreground and shows the notification.
         *
         * IDEMPOTENT PER RUN. A second start for a run already held replaces that run's
         * notification rather than adding a second entry, and `EXTRA_RUN_ID` is how the
         * service knows which one it is looking at.
         */
        fun start(context: Context, runId: String, agentName: String) {
            val intent = Intent(context, AgentRunService::class.java).apply {
                putExtra(EXTRA_RUN_ID, runId)
                putExtra(EXTRA_AGENT_NAME, agentName)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        /** Stop holding every run. Does not cancel the executions, which live on the host. */
        fun stop(context: Context) {
            context.stopService(Intent(context, AgentRunService::class.java))
        }
    }

    private val coordinator: AgentRecoveryCoordinator by inject()
    private val ledger: AgentRunLedger by inject()

    /**
     * The only authority on whether work is genuinely executing.
     *
     * NOT A LOCAL JOB REFERENCE. This component does not run the work, so a job field here
     * would report "finished" the instant this scope moved on and the heartbeat would stop
     * renewing a lease for a run that is still going.
     */
    private val host: AgentRunHost by inject()

    /** One entry per run this service is currently holding. */
    private data class HeldRun(
        val runId: String,
        val agentName: String,
        val notificationId: Int,
        val heartbeat: Job,
    )

    private val runs = ConcurrentHashMap<String, HeldRun>()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    /**
     * The owner every heartbeat writes under.
     *
     * THE SAME ID THE RUNTIME CLAIMED THE LEASE WITH. A separate id here would mean the
     * heartbeat never matched the claim, so the run would read as stale while it was
     * working and the recovery scan would interrupt live work.
     */
    private val owner: String get() = AgentRuntime.leaseOwner()

    /**
     * The notification id for a run.
     *
     * DERIVED FROM THE RUN ID AND NOT A COUNTER. A counter would give the same run a
     * different id on a restart, so a notification left behind by a killed process would
     * never be replaced by the one for the same run. A hash is stable for the life of the id.
     */
    private fun notificationIdFor(runId: String): Int = runId.hashCode()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // THE STOP ACTION IS A REAL STOP, AND IT NAMES ITS OWN RUN. It records the
        // cancellation request, cancels the execution on the host, and releases this
        // service's hold on that one run. Every other run carries on untouched.
        if (intent?.action == ACTION_STOP) {
            val stoppedRunId = intent.getStringExtra(EXTRA_RUN_ID)
            if (stoppedRunId == null) {
                stopSelf()
                return START_NOT_STICKY
            }
            scope.launch {
                runCatching {
                    ledger.append(
                        stoppedRunId,
                        AgentRunLedger.EventDraft(
                            eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.RUN_CANCEL_REQUESTED,
                            actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_USER,
                            payload = "{\"reason\":\"stopped from the notification\"}",
                        )
                    )
                }
                host.cancel(stoppedRunId)
                release(stoppedRunId)
            }
            return START_NOT_STICKY
        }

        val runId = intent?.getStringExtra(EXTRA_RUN_ID) ?: return START_NOT_STICKY
        val agentName = intent.getStringExtra(EXTRA_AGENT_NAME) ?: "An agent"

        // A second start for a run already held replaces its heartbeat rather than
        // accumulating a second one, so a re-posted notification cannot double the beats.
        runs.remove(runId)?.heartbeat?.cancel()

        coordinator.registerLease(runId)
        startInForeground(runId, agentName)

        val heartbeat = beginHeartbeat(runId)
        runs[runId] = HeldRun(runId, agentName, notificationIdFor(runId), heartbeat)

        // NOT STICKY. A service restarted by the platform has no execution behind it: the
        // loop that was running is gone. Restarting it would produce a service holding
        // notifications for work nobody is doing. The recovery scan is the correct responder
        // to a death, not a sticky restart.
        return START_NOT_STICKY
    }

    /**
     * Heartbeat one run, and ONLY while its work is genuinely alive.
     *
     * A HEARTBEAT THAT OUTLIVES ITS WORK IS A LIE THE RECOVERY SCAN BELIEVES. Renewing a
     * lease for work that has already finished keeps a dead run looking healthy for ever,
     * which is worse than no heartbeat at all, because the scan will never interrupt it and
     * a person is never told.
     *
     * IT ASKS THE HOST, NOT A LOCAL JOB. Work runs under [AgentRunHost]'s application-lifetime
     * scope, so a job reference held here would say nothing about whether the agent is still
     * running.
     *
     * WHEN THE LOOP ENDS, THIS RUN IS RELEASED. The lease is left to go stale on purpose,
     * which is how the scan learns the work stopped, and the notification is withdrawn
     * because there is no longer anything to report.
     */
    private fun beginHeartbeat(runId: String): Job = scope.launch {
        while (host.isActive(runId)) {
            runCatching { ledger.heartbeat(runId, owner) }
            delay(AgentRecoveryCoordinator.HEARTBEAT_INTERVAL_MS)
        }
        release(runId)
    }

    /**
     * Let go of one run: drop its hold, retire its notification, and stop the service when
     * nothing is left.
     *
     * REMOVING THE ENTRY FIRST MAKES THIS IDEMPOTENT. A run can be released from its own
     * heartbeat loop and again from a stop action, and the second call must do nothing
     * rather than cancel a notification that now belongs to something else.
     */
    private fun release(runId: String) {
        val held = runs.remove(runId) ?: return
        held.heartbeat.cancel()
        coordinator.releaseLocalLease(runId)
        retireNotification(held.notificationId)

        if (runs.isEmpty()) {
            stopForegroundCompat()
            stopSelf()
        }
    }

    /**
     * Withdraw one run's notification without disturbing the others.
     *
     * A SERVICE WITH SEVERAL FOREGROUND NOTIFICATIONS CANNOT RETIRE ONE OF THEM. Android
     * offers `stopForeground` for all of them or none, so a finished run's notification is
     * cancelled through the notification manager. The service stays in the foreground on the
     * strength of the runs that are still held, and `stopForegroundCompat` runs only once the
     * map is empty.
     */
    private fun retireNotification(notificationId: Int) {
        runCatching {
            getSystemService(NotificationManager::class.java)?.cancel(notificationId)
        }
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                "Agent runs",
                // LOW, because an agent working is not an interruption. The notification is a
                // standing fact in the shade, not a tap on the shoulder.
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = "Shown while a GURU agent is working."
                setShowBadge(false)
            }
        )
    }

    private fun buildNotification(runId: String, agentName: String): Notification {
        val open = packageManager.getLaunchIntentForPackage(packageName)
        val openPending = open?.let {
            PendingIntent.getActivity(
                this,
                notificationIdFor(runId),
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        // THE RUN ID TRAVELS IN THE INTENT, NOT IN THE SERVICE'S CURRENT STATE. Two agents
        // working at once produce two stop actions, and each one must stop the run whose
        // notification carried it rather than whichever run started most recently.
        val stopIntent = Intent(this, AgentRunService::class.java).apply {
            action = ACTION_STOP
            putExtra(EXTRA_RUN_ID, runId)
        }
        val stopPending = PendingIntent.getService(
            this,
            notificationIdFor(runId) + 1,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("$agentName is working")
            .setContentText("GURU is running an agent. Tap to open, or stop it here.")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(openPending)
            // A REAL STOP, which records the request before stopping.
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Stop", stopPending)
            .build()
    }

    private fun startInForeground(runId: String, agentName: String) {
        val notification = buildNotification(runId, agentName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                notificationIdFor(runId),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(notificationIdFor(runId), notification)
        }
    }

    private fun stopForegroundCompat() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            @Suppress("DEPRECATION")
            stopForeground(true)
        }
    }

    override fun onDestroy() {
        // Every run is released, so nothing keeps a notification for work this component is
        // no longer watching. The executions themselves are untouched: they live on the host,
        // and a lease released here goes stale, which is how the scan learns the service is
        // gone.
        runs.keys.toList().forEach { runId ->
            runs.remove(runId)?.let { held ->
                held.heartbeat.cancel()
                coordinator.releaseLocalLease(runId)
            }
        }
        scope.cancel()
        super.onDestroy()
    }
}
