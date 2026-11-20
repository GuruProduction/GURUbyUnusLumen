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
 * The process that owns a live agent run while a person can see it working.
 *
 * WHY A SERVICE AT ALL. Durability on disk is only half of it. Android kills background
 * processes, and a ledger cannot make a dead loop continue. A foreground service is the
 * only thing that keeps an agent actually running when the Portal is backgrounded or the
 * person switches app, and its ongoing notification is the contract that says GURU is still
 * doing something and offers the way to stop it.
 *
 * THE SERVICE TYPE, CHOSEN FROM THE MANIFEST RATHER THAN GUESSED. `specialUse` with a declared
 * subtype, because agent execution is not data synchronisation, not media processing and not
 * any other listed type. Choosing `dataSync` to borrow its foreground status would be a
 * misdeclaration with a review consequence later.
 *
 * NO PROMISE OF INDEFINITE EXECUTION. Android can stop a long-running service and job quotas
 * apply to work started from one. The design leans on the checkpoint, not on the service: if
 * this process is killed, the run becomes `INTERRUPTED` on the next scan and resumes from the
 * last checkpoint. The notification says what is happening rather than promising it for ever.
 *
 * THE HEARTBEAT LIVES HERE. Every [AgentRecoveryCoordinator.HEARTBEAT_INTERVAL_MS] it touches
 * the lease of each run this process owns, so a scan can tell "still working" from "gone".
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
         * THIS SERVICE DOES NOT RUN WORK, WHICH IS THE POINT. Execution lives on
         * [AgentRunHost]'s application-lifetime scope, and the caller that wants an answer
         * awaits it there. The service exists for the two things only a foreground service
         * can do: keep the process alive while an agent is visibly working, and give the
         * person a standing notification with a stop action.
         *
         * EVERY WAY A RUN STARTS CALLS THIS, spawn and resume alike. An earlier version
         * started it from spawn only, so a resumed run worked with no notification and no
         * foreground protection, and a variant of that version executed the resume here as
         * well, which would have run the same turn twice. One service, one responsibility.
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

        fun stop(context: Context) {
            context.stopService(Intent(context, AgentRunService::class.java))
        }
    }

    private val coordinator: AgentRecoveryCoordinator by inject()
    private val ledger: AgentRunLedger by inject()

    /**
     * The only authority on whether work is genuinely executing.
     *
     * NOT A LOCAL JOB REFERENCE. This component does not run the work any more, so a job
     * field here would report "finished" the instant this scope moved on and the heartbeat
     * would stop renewing a lease for a run that is still going.
     */
    private val host: AgentRunHost by inject()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var heartbeat: Job? = null
    private var currentRunId: String? = null

    /**
     * The owner this service heartbeats under.
     *
     * THE SAME ID THE RUNTIME CLAIMED THE LEASE WITH. A separate id here would mean the
     * heartbeat never matched the claim, so the run would read as stale while it was
     * working and the recovery scan would interrupt live work.
     */
    private val owner: String get() = AgentRuntime.leaseOwner()

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // THE STOP ACTION IS A REAL STOP, NOT A PROCESS KILL. It records the cancellation
        // request before anything else, because a stop that only kills the process leaves a
        // run whose last recorded state is RUNNING, which is the same lie the interruption
        // gap tells. A person pressing stop deserves a row that says they pressed it.
        if (intent?.action == ACTION_STOP) {
            val runId = currentRunId
            if (runId != null) {
                scope.launch {
                    runCatching {
                        ledger.append(
                            runId,
                            AgentRunLedger.EventDraft(
                                eventType = com.unuslumen.app.database.entity.AgentRunEventEntity.RUN_CANCEL_REQUESTED,
                                actorType = com.unuslumen.app.database.entity.AgentRunEventEntity.ACTOR_USER,
                                payload = "{\"reason\":\"stopped from the notification\"}",
                            )
                        )
                    }
                    stopForegroundCompat()
                    stopSelf()
                }
            } else {
                stopSelf()
            }
            return START_NOT_STICKY
        }

        val runId = intent?.getStringExtra(EXTRA_RUN_ID) ?: return START_NOT_STICKY
        val agentName = intent.getStringExtra(EXTRA_AGENT_NAME) ?: "An agent"
        currentRunId = runId
        coordinator.registerLease(runId)

        startInForeground(runId, agentName)

        /*
         * THIS SERVICE DOES NOT RUN THE WORK. IT HOLDS THE PROCESS AND HEARTBEATS.
         *
         * A previous version launched a coroutine here to execute a resume, which meant the
         * same turn could be executed twice: once by whatever caller had already started it
         * on the host, and once by this component. Two executions of one assignment is the
         * worst defect in this whole subsystem, because a resume carries a real answer into
         * a real job and running it twice repeats every side effect behind it.
         *
         * Execution lives in [AgentRunHost], and callers start it there. This component owns
         * exactly two jobs: the foreground notification, and the heartbeat that tells the
         * recovery scan the work is genuinely alive. The heartbeat asks the host rather than
         * trusting a local job reference, because a local reference would always look
         * finished the moment this component's own scope moved on.
         */
        beginHeartbeat(runId)

        // NOT STICKY. A service restarted by the platform has no execution behind it: the
        // loop that was running is gone. Restarting it would produce a service holding a
        // notification for work nobody is doing. The recovery scan is the correct responder
        // to a death, not a sticky restart.
        return START_NOT_STICKY
    }

    /**
     * Heartbeat, and ONLY while the work is genuinely alive.
     *
     * A HEARTBEAT THAT OUTLIVES ITS WORK IS A LIE THE RECOVERY SCAN BELIEVES. Renewing a
     * lease for work that has already finished keeps a dead run looking healthy for ever,
     * which is worse than no heartbeat at all, because the scan will never interrupt it and
     * a person is never told.
     *
     * IT ASKS THE HOST, NOT A LOCAL JOB. Work now runs under [AgentRunHost]'s
     * application-lifetime scope, so this service's own job reference says nothing about
     * whether the agent is still running. The host is the one authority on that.
     */
    private fun beginHeartbeat(runId: String) {
        heartbeat?.cancel()
        heartbeat = scope.launch {
            while (true) {
                if (!host.isActive(runId)) {
                    // Nothing is running, so nothing is renewed. The lease is left to go
                    // stale on purpose: that is how the scan learns the work stopped.
                    break
                }
                runCatching { ledger.heartbeat(runId, owner) }
                delay(AgentRecoveryCoordinator.HEARTBEAT_INTERVAL_MS)
            }
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
                runId.hashCode(),
                it,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
        }

        val stopIntent = Intent(this, AgentRunService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPending = PendingIntent.getService(
            this,
            runId.hashCode() + 1,
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
                runId.hashCode(),
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
            )
        } else {
            startForeground(runId.hashCode(), notification)
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
        heartbeat?.cancel()
        // The execution is cancelled with the scope, and the heartbeat stops with it, so
        // the lease is left to go stale. A run whose service has been destroyed is a run
        // whose owner is gone, and the scan must be able to see that.
        currentRunId?.let { coordinator.releaseLocalLease(it) }
        scope.cancel()
        super.onDestroy()
    }
}
