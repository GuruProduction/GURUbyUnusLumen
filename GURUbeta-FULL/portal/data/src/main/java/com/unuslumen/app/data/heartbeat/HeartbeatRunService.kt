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
import com.unuslumen.app.domain.model.EntryState
import com.unuslumen.app.domain.model.HeartbeatBooking
import com.unuslumen.app.domain.model.HeartbeatEntry
import com.unuslumen.app.domain.model.HeartbeatSlot
import com.unuslumen.app.domain.model.composeBeatInstruction
import com.unuslumen.app.domain.repository.AiRepository
import com.unuslumen.app.domain.repository.HeartbeatBookingRepository
import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
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
    /** The prompt in force: the owner's saved one. There is no other source. */
    private val promptRepository: HeartbeatPromptRepository by inject()
    /**
     * The diary.
     *
     * A beat does not just send the master prompt any more: it asks the diary what is
     * booked in the half hour it woke in, sends that booking's instruction (alone or
     * beside the master prompt), and writes the result back against that occurrence. The
     * repository is the same one the room draws from, so what the diary says happened is
     * what actually happened.
     */
    private val bookingRepository: HeartbeatBookingRepository by inject()

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
            HeartbeatScheduler.recordOutcome(
                applicationContext,
                "The run service was started by the system with no booking, so no beat ran.",
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Corner 4 real overlap lockout: an active beat + a second start is refused;
        // the first's real end block books the next slot, so nothing gets queued-doubled.
        if (runningJob?.isActive == true) {
            Log.d(TAG, "Overlap-locked: second start refused while a beat is live")
            /*
             * THE DIARY RECORDS THE REFUSAL, AND THAT IS NOT BOOKKEEPING.
             *
             * A half hour that a booking claimed and a running beat swallowed would
             * otherwise still read "booked" forever, and a person looking at their diary
             * would see a slot that neither ran nor said why. SKIPPED is its own state,
             * separate from MISSED, because the cause is different: this one is GURU busy
             * with its own previous instruction, not the phone being asleep.
             *
             * THE STOP HAPPENS INSIDE THE WRITE, NOT BEFORE IT. Stopping first tears the
             * service down and cancels its scope, so the sentence explaining the skip
             * would never reach the database and the diary would keep the lie.
             */
            serviceScope.launch {
                runCatching {
                    val slot = HeartbeatSlot.floor(System.currentTimeMillis())
                    val entry = bookingRepository.entryAt(slot)
                    if (entry != null && entry.state == EntryState.PENDING) {
                        bookingRepository.recordOutcome(
                            entryId = entry.id,
                            state = EntryState.SKIPPED,
                            firedAt = 0L,
                            finishedAt = 0L,
                            outcome = "The beat before this one was still running, so this " +
                                "half hour was skipped rather than stacked on top of it.",
                        )
                    }
                }.onFailure { Log.w(TAG, "Skip record failed: ${it.message}") }
                withContext(Dispatchers.Main) {
                    stopForeground(STOP_FOREGROUND_REMOVE)
                    stopSelf()
                }
            }
            return START_NOT_STICKY
        }

        /*
         * THE SWITCH IS CHECKED AGAIN HERE, BEFORE THE WAKE LOCK.
         *
         * The scheduler refuses to book while the chain is off, so in a healthy
         * install this branch is never reached by an alarm. It exists because an
         * alarm booked before the switch was turned off can still be delivered, and
         * on that one delivery this must not hold a ten-minute wake lock and run a
         * full agent turn the owner has switched off.
         *
         * It is checked BEFORE acquireWakeLock so a refused beat costs a notification
         * and nothing else.
         */
        if (!HeartbeatChainState.isEnabled()) {
            Log.d(TAG, "Heartbeat is switched off: beat refused, no wake lock taken")
            HeartbeatScheduler.recordOutcome(
                applicationContext,
                "A beat arrived but the heartbeat is switched off, so nothing ran. " +
                    "Turn it on in the Heartbeat room when you want it running again.",
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()

        val currentJob = serviceScope.launch {
            // THE HALF HOUR THIS BEAT BELONGS TO, AND WHAT IS BOOKED IN IT.
            //
            // Resolved before the timed block so both the success path and every early
            // return can write against the same occurrence. A diary entry that is not
            // resolved here would have no id to report against, and the whole point of
            // the diary is that it says what actually happened rather than what was
            // planned.
            val slotAt = HeartbeatSlot.floor(System.currentTimeMillis())
            var bookedEntry: HeartbeatEntry? = null
            var bookedBooking: HeartbeatBooking? = null
            runCatching {
                bookedEntry = bookingRepository.entryAt(slotAt)
                bookedBooking = bookedEntry?.let { bookingRepository.booking(it.bookingId) }
            }.onFailure { Log.w(TAG, "Diary lookup failed: ${it.message}") }

            /*
             * WHAT THIS HALF HOUR ENDED AS, WRITTEN ONCE, FROM ANY PATH.
             *
             * A beat has five ways to end: it reaches the model, it finds nothing to send,
             * it finds no conversation, it is cut off at ten minutes, or it throws. All
             * five are a real outcome for a booked half hour, and the diary must say which
             * one happened rather than leaving the slot reading "booked" forever.
             *
             * The default is CUT_SHORT because that is the only ending that reaches here
             * without setting anything: a beaten timeout returns null from
             * withTimeoutOrNull and falls straight through, which is exactly the case a
             * person most needs named.
             */
            var endedAs: EntryState? = null
            var endedBecause = ""

            // A booked slot is not still pending once a beat is working in it, so anything
            // that reads the diary right now sees RUNNING rather than a stale "booked".
            bookedEntry?.let { entry ->
                runCatching {
                    bookingRepository.recordOutcome(
                        entryId = entry.id,
                        state = EntryState.RUNNING,
                        firedAt = System.currentTimeMillis(),
                        finishedAt = 0L,
                        outcome = "",
                    )
                }.onFailure { Log.w(TAG, "Running mark failed: ${it.message}") }
            }

            try {
                withTimeoutOrNull(BEAT_BUDGET_MILLIS) {
                    // --- 1. Real human name read (founder name token) ---
                    val humanName: String = runCatching {
                        getPreference(
                            stringPreferencesKey(PrefsConstants.USER_NAME_KEY), ""
                        ).first()
                    }.getOrDefault("")

                    // --- 2. The prompt in force ---
                    //
                    // ONE SOURCE: THE DATABASE. The prompt is the owner's, written in
                    // the Heartbeat room, and there is no shipped fallback any more.
                    // A beat with no saved prompt ends and says so rather than
                    // injecting words nobody chose.
                    //
                    // The tokens are substituted through HeartbeatPrompts.substitute so
                    // the room's preview and the real injection honour one contract: a
                    // second substitution rule here would let the two drift apart.
                    val rawPrompt = promptRepository.current()
                    val masterPrompt = if (rawPrompt.isBlank()) {
                        ""
                    } else {
                        HeartbeatPrompts.substitute(rawPrompt, humanName)
                    }

                    /*
                     * THE BOOKING IS COMPOSED IN, AND THE COMPOSITION IS NOT DECIDED HERE.
                     *
                     * composeBeatInstruction is the single definition of what a beat sends
                     * when a half hour is booked: the master prompt with the booking beside
                     * it, or the booking alone when its owner chose "instead of master".
                     * The room's preview calls the same function, so what a person is shown
                     * in the diary is exactly what this line injects.
                     *
                     * A BOOKING ALONE IS A VALID BEAT. The rule that a beat needs a prompt
                     * was written when the master prompt was the only thing a beat could
                     * say; a booked half hour with an instruction of its own has something
                     * to send, so requiring the master prompt as well would refuse a beat
                     * that is fully specified.
                     */
                    val prompt = composeBeatInstruction(
                        masterPrompt = masterPrompt,
                        booking = bookedBooking,
                        entry = bookedEntry,
                    )
                    if (prompt.isBlank()) {
                        Log.w(TAG, "No prompt is saved and nothing is booked: beat ends without sending")
                        endedAs = EntryState.SKIPPED
                        endedBecause = "Nothing was written to send in this half hour, so the " +
                            "beat had no instruction."
                        HeartbeatScheduler.recordOutcome(
                            applicationContext,
                            "The beat fired but no heartbeat prompt has been written yet, so " +
                                "there was nothing to send. Write one in the Heartbeat room.",
                        )
                        return@withTimeoutOrNull
                    }

                    // --- 3. Newest-conversation pick (real Portal pick shape) ---
                    val conversation = runCatching {
                        memoryRepository.getAllConversations().maxByOrNull { it.updatedDate }
                    }.getOrNull()
                    if (conversation == null) {
                        Log.d(TAG, "No conversation exists yet: nothing to wake into")
                        endedAs = EntryState.SKIPPED
                        endedBecause = "There was no conversation to wake into, so the beat " +
                            "had nowhere to send."
                        HeartbeatScheduler.recordOutcome(
                            applicationContext,
                            "The beat fired but there is no conversation to wake into yet, so " +
                                "nothing was sent. Talk to GURU once and the next beat has a home.",
                        )
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
                        endedAs = EntryState.SKIPPED
                        endedBecause = "The conversation held no history to continue, so the " +
                            "beat had nothing to build on."
                        HeartbeatScheduler.recordOutcome(
                            applicationContext,
                            "The beat fired but the newest conversation holds no user or " +
                                "assistant messages, so there was no history to continue.",
                        )
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
                    endedAs = EntryState.RAN
                    endedBecause = ""
                    // A beat that reached the model clears the previous failure, so the
                    // room reports the current state of the chain rather than its worst
                    // moment. Written inside the timed block, so only a real completion
                    // clears it.
                    HeartbeatScheduler.recordOutcome(applicationContext, "")
                }
            } catch (cancelled: kotlinx.coroutines.CancellationException) {
                Log.w(TAG, "Beat cancelled: ${cancelled.message}")
                endedAs = EntryState.CUT_SHORT
                endedBecause = "The beat was cancelled before it finished: " +
                    "${cancelled.message ?: "no reason given"}."
                HeartbeatScheduler.recordOutcome(
                    applicationContext,
                    "The beat was cancelled before it finished: ${cancelled.message ?: "no reason given"}.",
                )
            } catch (failure: Exception) {
                Log.e(TAG, "Beat failed: ${failure.message}")
                endedAs = EntryState.MISSED
                endedBecause = "The beat started and then failed: " +
                    "${failure.message ?: failure.javaClass.simpleName}."
                // THE SENTENCE A PERSON NEEDS. A thrown beat used to leave only a log
                // line under a tag nobody reads, which on screen is identical to a beat
                // that never fired. This names the fault in the room.
                HeartbeatScheduler.recordOutcome(
                    applicationContext,
                    "The beat started and then failed: ${failure.message ?: failure.javaClass.simpleName}.",
                )
            } finally {
                // The UNCONDITIONAL END block. withContext(NonCancellable) is a
                // guarantee: wake release, real next-book from the REAL run end,
                // stopForeground + stopSelf all complete even on a cut-out.
                // This book reuses the canonical scheduler entry point to keep
                // one single booking chain across every site in the design.
                withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                    // THE DIARY'S OWN END, AND IT IS PART OF THE SAME GUARANTEE.
                    //
                    // A booked half hour gets its real ending written here, inside the
                    // block a cancellation cannot skip, for the same reason the wake lock
                    // and the rebook are: an outcome that only lands on the happy path is
                    // an outcome that is missing exactly when something went wrong.
                    //
                    // A beat that reached its timeout returns null rather than throwing,
                    // so it leaves endedAs unset — and that null IS the cut-short case.
                    // It is named as such rather than as ran or missed, because both of
                    // those would be untrue.
                    bookedEntry?.let { entry ->
                        val state = endedAs
                            ?: EntryState.CUT_SHORT
                        val reason = when {
                            endedAs != null -> endedBecause
                            else -> "The beat was still working when its ten-minute budget " +
                                "ran out, so it was cut short rather than finished."
                        }
                        runCatching {
                            bookingRepository.recordOutcome(
                                entryId = entry.id,
                                state = state,
                                firedAt = entry.firedAt,
                                finishedAt = System.currentTimeMillis(),
                                outcome = reason,
                            )
                        }.onFailure { Log.w(TAG, "Diary outcome write failed: ${it.message}") }
                    }

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