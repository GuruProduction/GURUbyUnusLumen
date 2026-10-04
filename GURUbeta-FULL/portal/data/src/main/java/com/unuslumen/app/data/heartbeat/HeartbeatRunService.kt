// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import com.unuslumen.app.domain.memory.MemoryRepository
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.repository.AiRepository
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/**
 * One-shot foreground run of a booked heartbeat beat.
 *
 * Started ONLY by HeartbeatAlarmReceiver (and by the OS at process restart with a
 * null/absent intent, which is handled as redelivery that never runs a beat).
 *
 * Carries into real FGS specialUse form the exact payload shape of the retired
 * CoroutineWorker file this chain replaced, and the only change is the scheduler:
 * - the silent IMPORTANCE_MIN channel pattern comes from the same pattern
 *   TorPersistentService.createNotificationChannel() (real file) uses,
 * - specialUse start (ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE) at
 *   SDK UPSIDE_DOWN_CAKE or later, from the same pattern as TorPersistentService.
 *
 * On ANY end (finished, time-capped at ten minutes, or crashed):
 * - real PARTIAL_WAKE_LOCK released once per end,
 * - service stopped real (stopForeground remove + stopSelf),
 * - and the next alarm rebooked from the REAL run end's timestamp, from within a
 *   NonCancellable end block a cut can never skip. A killed or cancelled run still
 *   holds a booked future this way, and the beat count of the entire day grid
 *   remains real to the founder cadence of 30 wall-clock minutes apart.
 *
 * This file is the one real change over the retired worker:
 * every else line of the LLM call chain stayed untouched for exactly what it has
 * already proven in production since the v3.6.x builds.
 */
class HeartbeatRunService : Service(), KoinComponent {

    private val aiRepository: AiRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val getPreference: GetPreferenceUseCase by inject()

    private val serviceScope = CoroutineScope(Job() + Dispatchers.Default)
    private var runningJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "guru_heartbeat"
        private const val CHANNEL_ID = "heartbeat_run"
        private const val BEAT_NOTIFICATION_ID = 3003

        /** Founder-cadence beat-duration budget: REAL and binding, 10 minutes. */
        private const val BEAT_BUDGET_MILLIS = 10 * 60_000L
    }

    private fun ensureChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Heartbeat run",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Indicates a live GURU heartbeat run in progress"
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(this)
        }
        return builder
            .setContentTitle("Heartbeat active")
            .setContentText("A GURU heartbeat run is in progress")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        runCatching { ensureChannel() }
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guru_heartbeat:run").also {
            it.acquire(BEAT_BUDGET_MILLIS + 30_000L)
        }
    }

    private fun releaseWakeLockReal() {
        wakeLock?.let { lock ->
            if (lock.isHeld) {
                runCatching { lock.release() }
            }
        }
        wakeLock = null
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // specialUse FGS with the type when target SDK rules require it there
        runCatching { ensureChannel() }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(
                BEAT_NOTIFICATION_ID,
                buildNotification(),
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            )
        } else {
            startForeground(BEAT_NOTIFICATION_ID, buildNotification())
        }

        val fireBookedAt = intent?.getLongExtra(
            HeartbeatScheduler.HEARTBEAT_EXTRA_BOOKED_AT, -1L
        ) ?: -1L

        if (fireBookedAt <= 0L) {
            Log.w(TAG, "HeartbeatRunService start without a real booked-at: drop, no beat runs")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Corner 4 real overlap lockout: an active beat + a second start is refused;
        // the first's real end block books the next slot, so nothing gets queued-doubled.
        if (runningJob?.isActive == true) {
            Log.d(TAG, "Overlap-locked: second start refused while a beat is live")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()

        val currentJob = serviceScope.launch {
            try {
                withTimeoutOrNull(BEAT_BUDGET_MILLIS) {
                    // --- 1. Real human name read (founder name token) ---
                    val humanName: String = runCatching {
                        getPreference(
                            stringPreferencesKey(PrefsConstants.USER_NAME_KEY), ""
                        ).first()
                    }.getOrDefault("")

                    // --- 2. Founder prompt load ---
                    val prompt = HeartbeatPrompts.load(applicationContext, humanName)
                    if (prompt == null) {
                        Log.w(TAG, "Prompt unavailable: beat ends, next fire runs it")
                        return@withTimeoutOrNull
                    }

                    // --- 3. Newest-conversation pick (real Portal pick shape) ---
                    val conversation = runCatching {
                        memoryRepository.getAllConversations().maxByOrNull { it.updatedDate }
                    }.getOrNull()
                    if (conversation == null) {
                        Log.d(TAG, "No conversation exists yet: nothing to wake into")
                        return@withTimeoutOrNull
                    }

                    // --- 4. Persisted history remapped ("user"/"assistant" only) ---
                    val persisted = runCatching {
                        memoryRepository.getMessagesByConversation(conversation.id)
                    }.getOrDefault(emptyList())
                    val history = persisted.mapNotNull { msg ->
                        when (msg.role) {
                            "user" -> AiMessage.UserMessage(
                                uuid = msg.id,
                                content = msg.content,
                                time = msg.timestamp
                            )
                            "assistant" -> AiMessage.AssistantMessage(
                                content = msg.content,
                                time = msg.timestamp,
                                uuid = msg.id
                            )
                            else -> null
                        }
                    }
                    if (history.isEmpty()) {
                        Log.d(TAG, "Conversation has no user/assistant history: nothing to wake into")
                        return@withTimeoutOrNull
                    }

                    // --- 5. The REAL beat send, the proven chain verbatim ---
                    val beat = AiMessage.UserMessage(
                        uuid = Uuid.random().toString(),
                        content = prompt,
                        time = System.currentTimeMillis()
                    )
                    val fullHistory = history + beat
                    Log.d(
                        TAG,
                        "Beat live: conversation=${conversation.id} history=${fullHistory.size} messages"
                    )
                    aiRepository.sendMessage(fullHistory, conversation.id).collect { msg ->
                        when (msg) {
                            is AiMessage.AssistantMessage ->
                                Log.d(TAG, "GURU: ${msg.content.take(200)}")
                            is AiMessage.ToolCall ->
                                Log.d(
                                    TAG,
                                    "GURU tool: ${msg.name}${if (msg.isFailed) " (failed)" else ""}"
                                )
                            else -> Unit
                        }
                    }
                    Log.d(TAG, "Beat complete: ${conversation.id}")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "Beat cancelled: ${cancelled.message}")
            } catch (failure: Exception) {
                Log.e(TAG, "Beat failed: ${failure.message}")
            } finally {
                // The UNCONDITIONAL END block. withContext(NonCancellable) is a
                // guarantee: wake release, real next-book from the REAL run end,
                // stopForeground + stopSelf all complete even on a cut-out.
                // This book reuses the canonical scheduler entry point to keep
                // one single booking chain across every site in the design.
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    releaseWakeLockReal()
                    runCatching {
                        HeartbeatScheduler.schedule(applicationContext)
                    }.onFailure { Log.w(TAG, "End-block rebook failed: ${it.message}") }
                    runningJob = null
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
        }
        runningJob = currentJob
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        releaseWakeLockReal()
        serviceScope.cancel()
    }
}