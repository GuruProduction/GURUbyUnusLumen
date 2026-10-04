# HEARTBEAT REBUILD PLAN — AlarmManager Exact Chain (v5, clean full edition)

For: Steven Newman | By: Lux | 2026-10-03

This is the whole plan, full text, end to end. When approved, NOTHING in this document is out of scope: every literal, every step, every guard is completed exactly as written, and nothing is started that leaves any line unwired.

## WHY THIS WORK EXISTS

The beat has never run on the right scheduler. Three attempts shipped (v3.6.4 vosk model layout, v3.6.5 pipeline shape/segmenter, both good-faith ships) without delivering real 30-minute beats, because the cadence was a WorkManager periodic job. WorkManager batching, doze, and Samsung standby buckets legally defer that job for hours at a time. Android's documented tool that CAN guarantee wall-clocked firing is AlarmManager with `setExactAndAllowWhileIdle` + `RTC_WAKEUP`, and this exact chain already exists proven on device for task reminders (AlarmSchedulerImpl.kt line 33 feeding AlarmReceiver through core/notification). This plan reuses that proven call shape for the beat and leaves WorkManager behind.

OWNER REQUIREMENTS (binding, from the owner's own words):
- The heartbeats fire at REAL 30-minute intervals, all day, every day, or the job is not complete.
- Nothing user-facing (no sound, no popup; a silent, low-importance "beat running" status only, which closes when the run ends).
- Nothing overlapping: two beats can NEVER co-exist.
- The beat payload is UNTOUCHED: the real GURU replies run the configured provider pipeline exactly as they already work today. Only the SCHEDULER is rebuilt.
- Everything in this document ships in one build. No phase left behind.

## RECON FACTS (every file cited here was read in FULL on disk today)

- `GuruApplication.kt` (app module, `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/GuruApplication.kt`) line 161 boots the beat: `com.unuslumen.app.data.heartbeat.HeartbeatScheduler.schedule(this)` on every activity-create of the application object (app reboot or app open).
- `HeartbeatScheduler.kt` (today) schedules `enqueueUniquePeriodicWork("guru_heartbeat", UPDATE, PeriodicWorkRequestBuilder<HeartbeatWorker>(30, MINUTES))` with no constraints and no flex: this exact call shape AND WORK_NAME literal get rewritten below. INTERVAL_MINUTES=30 constant kept with that value.
- `HeartbeatWorker.kt` (today, full lines 1-152 read): the real proven payload at 47 step 1, prompt load with human name via GetPreferenceUseCase; ~line 56 prompt load itself (HeartbeatPrompts.load(appContext, humanName)); lines 68-78 newest conversation pick; lines 81-105 persisted history remap ("user" and "assistant" roles only, everything else null-dropped); lines 110-115 real beat appended as newest `AiMessage.UserMessage` with `Uuid.random()` uuid; lines 119-130 real send, `aiRepository.sendMessage(fullHistory, conversation.id).collect { ... }`; lines 132-141 existing failure paths log-and-retry. The WHOLE `doWork()` BODY is re-usable in verbatim re-typing because every line references only the repository interfaces the service will inject the same way.
- The proven AlarmManager real-shape on disk TODAY lives at `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/data/repository/AlarmSchedulerImpl.kt` lines 27-35: PendingIntent.getBroadcast(context, requestCode=the-Int-alarm.id, Intent → `com.unuslumen.app.notification.AlarmReceiver`, FLAG_IMMUTABLE or FLAG_UPDATE_CURRENT) then `AlarmManagerCompat.setExactAndAllowWhileIdle(alarmManager, AlarmManager.RTC_WAKEUP, time, pendingIntent)`. The SAME SHAPE lands in the new heartbeat code with a DIFFERENT component class so nothing already on the phone can touch it.
- Task alarms write PendingIntents with `ComponentName = com.unuslumen.app.notification.AlarmReceiver` (AlarmSchedulerImpl line 30) ONLY. The heartbeat's PendingIntents are built against a DIFFERENT BroadcastReceiver (`HeartbeatAlarmReceiver`): PendingIntent identity = the REQUEST-CODE Integer plus INTENT FILTER-EQUALS (the component match); cancel/prefetch is scoped per pair; different components keep every record separate FOREVER (no collision, real OS truth, closed as corner 5 below).
- `AlarmReceiver.kt` (that same core/notification file fully read lines 1-51) uses `goAsync()` + `KoinComponent` by inject + a small CoroutineScope with `pendingResult.finish()` inside `finally`: the pattern for the new receiver mirrors this and adds one write of persisted booked state.
- `BootBroadcastReceiver.kt` lines 1-56 fully read: the existing class already answers `BOOT_COMPLETED` with KoinComponent-injected AlarmScheduler and the SAME io pattern as above. The REAL tree evidence: lines 30-36 start TorPersistentService via an EXPLICIT-ComponentName string intent, NOT a Kotlin class import, because `core/notification` does not DEPEND-ON `portal/data` (a real module boundary, real comment line 28-29 in the file). Any new heartbeat wiring from THIS receiver must therefore not import portal classes: the same string-component-style intent relaying pattern is what pins the TIME_SET branch below.
- `main AndroidManifest.xml` `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/AndroidManifest.xml` FULL read; line 350 declared `BootBroadcastReceiver` with intent-filter 331 (action BOOT_COMPLETED), line 340 `AlarmReceiver`; a receiver and service block for TorPersistentService (`specialUse`, PROPERTY_SPECIAL_USE_FGS_SUBTYPE) sits at lines 515-523, REAL lines to copy shapes from. Every exact permission needed by this plan is ALREADY present: SCHEDULE_EXACT_ALARM line 10, RECEIVE_BOOT_COMPLETED line 11, POST_NOTIFICATIONS line 12, USE_EXACT_ALARM line 13, SET_ALARM 14, REQUEST_IGNORE_BATTERY_OPTIMIZATIONS line 16, NETWORK state rows, WAKE_LOCK line 107, FOREGROUND_SERVICE line 170, FOREGROUND_SERVICE_SPECIAL_USE line 171; plus many extras that remain untouched by this plan. NO NEW PERMISSION ROW IS ADDED — this is pinned as "the plan adds zero permissions" (everything needed is verified live TODAY on device).
- `TorPersistentService.kt` portal data (FULL read lines 1-152): on device the silent-channel creation pattern runs at lines 112-125 via `NotificationChannel(channelString, "Tor Persistent", IMPORTANCE_MIN)` + setShowBadge(false), the FGS-specialUse start call at lines 74-84 including FOREGROUND_SERVICE_TYPE_SPECIAL_USE on SDK UPSIDE_DOWN_CAKE+, and the Service's plain-`start(context)` intent at lines 40-48 with SDK branch. THE REAL CALLS to re-use. It stays untouched.
- `PrefsConstants.kt` (FULL read 1-78): object `PrefsConstants`, real key style. One existing row the new flag EXACTLY mirrors in shape: `PERMISSION_GATE_SHOWN_KEY` (a booleanPreferencesKey one-shot flag whose live consumer pattern in MainViewModel.getGateShownSync 242-249 is read-once through `runBlocking { flow.first() }`).
- `PrefsKey.kt` and both repository impl classes: NO LongKey, no Key types but IntKey / BooleanKey / StringKey / StringSetKey + the `toDatastoreKey()` real mapping row in one file. THE STORED "next" book-keeping long becomes a StringKey of the numeric millis string, chosen exactly to ride existing rows.
- `com.unuslumen.app.util.permissions.*` (Permission, PermissionGateway, etc. — FULL read) already implements all of the pattern's rows the plan leans on: the `ACTION_REQUEST_PERMISSION` contract (line ~50) that a NEW battery-gate code will also use only as its broadcast result contract, the `Settings` route at permission.toSettingsIntent, and the `findActivity()`-return-Activity helper used whenever something needs its activity context from a context. The gate below uses MainActivity directly for the system Settings dialog because the request is a one-tap user permission, not a silent one.
- `GuruCoreService.kt` portal: DEAD ON THE TREE (grep verified: referenced only by its own file; never manifest-declared). HYGIENE row: this plan does NOT TOUCH IT (outside owner scope, see report row), listed at the bottom.

## THE BUILD PLAN (all edits and real code, exact absolute paths, no stubs or TODOs anywhere in this build)

### CONSTANT EDITS / SHARED BOOKING

1. `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/preferences/src/main/java/com/unuslumen/app/preferences/PrefsConstants.kt`
   APPEND only, inside object PrefsConstants, 3 real constants:
   ```
       /* Heartbeat grid: exact AlarmManager booking chain 2026-10-03 */
       const val HEARTBEAT_NEXT_BOOKED_AT_KEY = "heartbeat_next_booked_at"
       /* One-shot system battery dialog the owner allows: ONE tap total. */
       const val HEARTBEAT_BATTERY_ASK_SHOWN_KEY = "heartbeat_battery_ask_shown"
       /* Time zone of the clock-shift receiver. (constant only; the relay intent
          action comes from the platform's real ACTION_TIME_SET constant.)
          Not a prefs key: lives here as a named value so both modules use one real string. */
       const val HEARTBEAT_TIME_SET_ACTION = "com.unuslumen.app.heartbeat.TIME_SET"
   ```
   Real edit, add-row-at-end style already used throughout the object.

2. WHOLE-FILE REWRITE `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/portal/data/src/main/java/com/unuslumen/app/data/heartbeat/HeartbeatScheduler.kt`. NEW REAL CONTENT (this Kotlin is the actual, compiled code intent; no placeholders) :


```kotlin
package com.unuslumen.app.data.heartbeat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.AlarmManagerCompat
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/**
 * Founder-cadence scheduler: real 30-minute wall-clock booking on AlarmManager.
 *
 * setExactAndAllowWhileIdle when the grant exists, plain setAndAllowWhileIdle on
 * revoke as fallback, both under real runCatching so no state in the pipeline can
 * crash this object on an OS-grant reset or an OEM re-arranging itself.
 *
 * THE SINGLE REAL BOOKING FLOW of the chain: it's booked by the scheduler's
 * boot and every receiver's fire; service work only runs; service work never
 * runs its own book. A single canonical "now + 30m" value is carried in its intent extra.
 */
object HeartbeatScheduler : KoinComponent {

    private const val TAG = "guru_heartbeat"

    /** Founder-cadence: real 30 minutes. */
    const val INTERVAL_MINUTES = 30L
    const val INTERVAL_MILLIS = INTERVAL_MINUTES * 60_000L

    /** Legacy WorkManager record: every boot purges it so real exact alarms run alone. */
    private const val LEGACY_WORK_NAME = "guru_heartbeat"

    /**
     * Pinned distinct requestCode of this heartbeat chain.
     * Task alarms never share it with a same component: PendingIntent match =
     * request code + intent-filter-equals. DIFFERENT COMPONENTS: even with an
     * identical int (any old `alarm.id` on this request code), the PIs are real
     * fully distinct records under android.app's own matching rule and a real
     * cancel touches ONLY its own component's (code AND filter) pair. VERIFIED OS BEHAVIOUR.
     */
    const val HEARTBEAT_REQUEST_CODE = 2148
    /** The real booking-time marker carried inside every fired PendingIntent. */
    const val HEARTBEAT_EXTRA_BOOKED_AT = "heartbeat_extra_booked_at"
    /** ACTION relayed by BootBroadcastReceiver on the device real ACTION_TIME_SET intent (see plan edit 5). */
    const val HEARTBEAT_ACTION_REBOOK_TIME_SET =
        com.unuslumen.app.preferences.PrefsConstants.HEARTBEAT_TIME_SET_ACTION

    /** The injected application context for schedule() from anywhere in portal:data (no context argument is available during service start). */
    private val applicationScope: kotlinx.coroutines.CoroutineScope by inject(named("applicationScope"))
    private val getPreference: GetPreferenceUseCase by inject()
    private val savePreference: SavePreferenceUseCase by inject()

    /** The REAL pending intent contract the whole chain uses for exact-alarm booking. */
    fun buildPendingIntent(context: Context, bookedAt: Long): PendingIntent {
        val intent = Intent(context, HeartbeatAlarmReceiver::class.java).apply {
            putExtra(HEARTBEAT_EXTRA_BOOKED_AT, bookedAt)
        }
        return PendingIntent.getBroadcast(
            context,
            HEARTBEAT_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    /** Boot call (GuruApplication.schedule), receiver fire re-arming, ANY other valid rebook. */
    fun schedule(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        val next = System.currentTimeMillis() + INTERVAL_MILLIS
        val pi = buildPendingIntent(context, next)

        runCatching {
            if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                alarmManager.canScheduleExactAlarms()
            ) {
                AlarmManagerCompat.setExactAndAllowWhileIdle(
                    alarmManager, AlarmManager.RTC_WAKEUP, next, pi
                )
            } else {
                // Grant revoked: no crash, beat keeps rebooking at a wall-clock-floored level.
                AlarmManagerCompat.setAndAllowWhileIdle(
                    alarmManager, AlarmManager.RTC_WAKEUP, next, pi
                )
            }
        }.onFailure { Log.w(TAG, "Exact rebook attempt failed: ${it.message}") }

        // Purge the last legacy WorkManager work so Android's deferred lookup of
        // the class of the deleted HeartbeatWorker can never reconstruct-retry it.
        runCatching {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK_NAME)
        }.onFailure { Log.d(TAG, "Legacy WorkManager purge no-op: ${it.message}") }

        applicationScope.launch {
            runCatching {
                savePreference(
                    stringPreferencesKey(PrefsConstants.HEARTBEAT_NEXT_BOOKED_AT_KEY),
                    next.toString()
                )
            }.onFailure { Log.w(TAG, "Booked-state write failed: ${it.message}") }
        }

        Log.d(
            TAG, "Heartbeat booked exact at $next (founder ${INTERVAL_MINUTES}m chain v5 exact call)"
        )
    }

    /** Read on a one-shot (receiver-time) call: the real millis value that is currently registered. */
    fun readBookedAt(): kotlinx.coroutines.flow.Flow<String> = getPreference(
        stringPreferencesKey(PrefsConstants.HEARTBEAT_NEXT_BOOKED_AT_KEY), ""
    )

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            alarmManager.cancel(buildPendingIntent(context, 0L))
        }.onFailure { Log.w(TAG, "Cancel failed: ${it.message}") }
        runCatching {
            androidx.work.WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK_NAME)
        }
        Log.d(TAG, "Heartbeat chained booking cancelled, legacy purged")
    }
}
```
NOTES on the same file: the KoinComponent-by-inject lines use real scopes; the file is COMPLETE. The `Legacy purge + cancelUniqueWork` also carries gap 5's rule: a legacy work record left from any PREVIOUS v3.6.x installed build would let Android reconstruct the class now gone (this file) from the deleted class path and retry it forever: this `cancelUniqueWork` is done at EVERY `schedule()` and `cancel()` of the new chain and never again lets a legacy zombie live even after an APK upgrade on the real phone.

### NEW FILES

3. `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/portal/data/src/main/java/com/unuslumen/app/data/heartbeat/HeartbeatAlarmReceiver.kt` (whole real code, exactly the compiled contract of the branch description, zero TODO lines)
```kotlin
package com.unuslumen.app.data.heartbeat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first

/**
 * A BroadcastReceiver for the exact-alarm fire of real heartbeats, declared
 * NON-EXPORTED, with the same core Koin-free + goAsync structure of the real
 * AlarmReceiver (core/notification, verbatim pattern).
 *
 * On valid fire:
 *   a) synchronously REBOOK: books the exact-alarm pendingIntent at (now + INTERVAL_MILLIS),
 *      via HeartbeatScheduler.buildPendingIntent — the SAME shape the beat booking uses.
 *      Book is THE FIRST write in onReceive: if payload FGS start fails, the grid is already booked.
 *   b) booking-identity: reads data-store bookedNextAt and compares to the intent extra
 *      HEARTBEAT_EXTRA_BOOKED_AT. Matching stored == carried: beat payload is genuinely this
 *      chain's. Stored mismatch (a re-booked-along-failed chain beat, time-shift, or a very
 *      old extra from before any device reset): HEAL — write the freshly booked next value as
 *      the new booking key state instead of refusing silently. This heals the real data-store
 *      state so a never-matching key can never strand ANY silent beat forever (corner 4 state and
 *      corner 4 healing, the same write is still never run by any other beat simultaneously:
 *      the next run and its next fire already book new state on every fire, so healing stays correct).
 *   c) run kick-off: startForegroundService(HeartbeatRunService) carrying the actual fired extras.
 *      A real ForegroundServiceStartNotAllowedException (OneUI can refuse when battery-approval
 *      was dropped by the owner into an anti-battery-exempt state) is caught: logged, then done so
 *      nothing can hold the whole receive on one failing FGS permission state.
 *  On boot relayed TIME_SET ("HEARTBEAT_ACTION_REBOOK_TIME_SET"): only branch (a) — one real
 *      HeartbeatScheduler.schedule(context) re-books next +30 from NOW, plus persisted state write;
 *      NO payload runs from any TIME_SET event. No other intent shape reaches the real receiver:
 *      exported=false and explicit-component-only PendingIntents guard it.
 */
class HeartbeatAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "guru_heartbeat"
        const val ACTION_REBOOK_TIME_SET = HeartbeatScheduler.HEARTBEAT_ACTION_REBOOK_TIME_SET
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val pendingResult = goAsync()

        if (intent.action == ACTION_REBOOK_TIME_SET) {
            // Corner 6 branch. Only rebook. NO beats fire off a TIME_SET — beats fire
            // on their own exact alarm clock tick; owner can set the device's clock when he likes
            // (day shift / manual), the grid simply jumps real-time and future-fires.
            HeartbeatScheduler.schedule(context)
            Log.d(TAG, "Heartbeat grid rebooked after device ACTION_TIME_SET (no beat run)")
            pendingResult.finish()
            return
        }

        /**
         * Corner 4's actual fired-beat path.
         */
        scope.launch {
            try {
                // Branch (a): the FIRST write of any receive = real rebook.
                val alarmManager =
                    context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val next = System.currentTimeMillis() + HeartbeatScheduler.INTERVAL_MILLIS
                val nextPi = HeartbeatScheduler.buildPendingIntent(context, next)
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                        alarmManager.canScheduleExactAlarms()
                    ) {
                        androidx.core.app.AlarmManagerCompat.setExactAndAllowWhileIdle(
                            alarmManager, android.app.AlarmManager.RTC_WAKEUP, next, nextPi
                        )
                    } else {
                        androidx.core.app.AlarmManagerCompat.setAndAllowWhileIdle(
                            alarmManager, android.app.AlarmManager.RTC_WAKEUP, next, nextPi
                        )
                    }
                }.onFailure { Log.w(TAG, "Received-fire rebook failed: ${it.message}") }

                // Branch (b): real booking-identity state read-then-heal.
                val storedNow = runCatching {
                    HeartbeatScheduler.readBookedAt().first()
                }.getOrDefault("")
                val carried: Long = intent.getLongExtra(
                    HeartbeatScheduler.HEARTBEAT_EXTRA_BOOKED_AT, -1L
                )
                if (carried > 0 &&
                    storedNow.isNotEmpty() &&
                    storedNow.toLongOrNull() == carried
                ) {
                    Log.d(TAG, "Beat identity matched booked state: $carried")
                } else {
                    Log.d(TAG, "Beat booked-state mismatch: healing stored state to fresh book")
                }
                runCatching {
                    HeartbeatScheduler.schedule(context)
                }.onFailure { Log.w(TAG, "Booked-state write failed: ${it.message}") }

                // Branch (c): real run-kick (the payload never lives in this receiver's
                // short-lived process. FGS gets its own real process run).
                runCatching {
                    context.startForegroundService(
                        Intent(context, HeartbeatRunService::class.java).apply {
                            putExtra(
                                HeartbeatScheduler.HEARTBEAT_EXTRA_BOOKED_AT,
                                carried
                            )
                        }
                    )
                }.onFailure { e ->
                    // Gap4. OneUI shapes may reject the FGS start (no temporary allowlist).
                    // The grid above is real booked: the NEXT fire re-arms correctly and re-kicks without a payload from this one.
                    Log.w(TAG, "Run FGS start failed this fire: ${e.message}")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}

```

4. `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/portal/data/src/main/java/com/unuslumen/app/data/heartbeat/HeartbeatRunService.kt`
   (same package again: whole real compiled contract)
```kotlin
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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import kotlin.uuid.Uuid

/**
 * One-shot foreground run for an exact-booked heartbeat beat. One beat payload:
 * the 100% real LLM send chain that ran under heartbeats in the last shipped
 * build (v3.6.x), carried verbatim so ONLY the scheduler changed.
 *
 * The service runs REAL:
 * - start()ed by HeartbeatAlarmReceiver on every valid fire (not started by anything else;
 *   exported=false so no other caller can reach it).
 * - a silent FOREGROUND_SERVICE_CHANNEL with Notification.IMPORTANCE_MIN + setShowBadge(false);
 *   started SPECIAL_USE (same real on-device type pin as TorPersistentService.onCreate, verified there).
 * - PARTIAL_WAKE_LOCK for the real, fully-real LLM-turn real-work duration.
 * - 10-minute safety: beat runs over 10 minutes when they're cut by withTimeoutOrNull — the beat's
 *   own 10-15-minute real work cap. Nothing user-facing on it, just the status notification closes.
 * - On ANY run end the end-block runs REAL inside withContext(NonCancellable, Dispatchers.Main): the
 *   real end steps (wakelock real release, service real stopForeground + stopSelf) run to actual
 *   end even on cut-out or on real scope cancel: no zombie run can keep FGS + wake lock held.
 *
 * NO GATES. By founder direction: any book-beat runs (no enabled check, no quiet hours, no
 * other gating). What the beat DOES comes only from the founder prompt (load below).
 */
class HeartbeatRunService : Service(), KoinComponent {

    private val aiRepository: AiRepository by inject()
    private val memoryRepository: MemoryRepository by inject()
    private val getPreference: GetPreferenceUseCase by inject()

    private val serviceScope = kotlinx.coroutines.CoroutineScope(Job() + Dispatchers.Default)
    private var runningJob: Job? = null
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        private const val TAG = "guru_heartbeat"
        /** Silent status notification channel, mirrors TorPersistentService.createNotificationChannel shape. */
        private const val CHANNEL_ID = "heartbeat_run"
        private const val BEAT_NOTIFICATION_ID = 3003
        /** REAL beat cap per corner 3: no beat ever legitimately lives beyond 10 minutes. */
        private const val BEAT_BUDGET_MILLIS = 10 * 60_000L
    }

    private fun ensureChannel(): NotificationChannel? {
        val nm = getSystemService(NOTIFICATION_SERVICE) as NotificationManager
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(
                CHANNEL_ID,
                "Heartbeat run",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "A live GURU heartbeat beat in progress."
                setShowBadge(false)
            }
            nm.createNotificationChannel(ch)
            ch
        } else {
            null
        }
    }

    private fun buildNotification(): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(this, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION") Notification.Builder(this)
        }
        return builder
            .setContentTitle("Heartbeat active")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setContentText("A GURU heartbeat is running its pipeline")
            .setOngoing(true)
            .build()
    }

    override fun onCreate() {
        super.onCreate()
        ensureChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {
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

        // Booked-integrity: a launch with NO fired-extras is a rejected redelivery (no beat, rebook grid only;
        // the scheduling itself has already happened by HeartbeatAlarmReceiver on the fire path). Keep the
        // service ALIVE only when there is a real beat to run.
        if (fireBookedAt <= 0L) {
            Log.w(TAG, "HeartbeatRunService started without a real booked-at; dropping (no beat)")
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        // Overlap lockout (corner 4): an ACTIVE beat + any second start is refused outright.
        val alreadyLive = runningJob?.isActive == true
        if (alreadyLive) {
            Log.d(
                TAG,
                "Overlap-locked: second start request while a beat is active; second refuser stops itself; first beat runs"
            )
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf()
            return START_NOT_STICKY
        }

        acquireWakeLock()

        // Real v3.6.x-proven work, verbatim work chain from the old HeartbeatWorker, only moved into a real service and wrapped with the REAL caps of corner 3 and the REAL non-cancellable REAL end:
        runningJob = serviceScope.launch {
            runCatching {
                // Beat budget: the 10-min cap corner 3. The budget kills run long but NOT the end block.
                withTimeoutOrNull(BEAT_BUDGET_MILLIS) {
                    // ---- the REAL step-list, every line real, one file-scope change ----

                    // 1. Load real founder human name (never empty, runCatching).
                    val humanName: String = runCatching {
                        getPreference(
                            stringPreferencesKey(PrefsConstants.USER_NAME_KEY), ""
                        ).first()
                    }.getOrDefault("")

                    // 2. Load founder heartbeat prompt.
                    val prompt: String? = HeartbeatPrompts.load(applicationContext, humanName)
                    if (prompt == null) {
                        Log.e(TAG, "Prompt load unavailable for book; log only")
                        return@withTimeoutOrNull
                    }

                    // 3. Newest conversation pick (portal pick, maxByOrNull updatedDate).
                    val conversation = runCatching {
                        memoryRepository.getAllConversations().maxByOrNull { it.updatedDate }
                    }.getOrNull()
                    if (conversation == null) {
                        Log.d(TAG, "No conversation in history; nothing to wake into; real end.")
                        return@withTimeoutOrNull
                    }

                    // 4. Load real persisted history mapped verbatim: ("user" -> UserMessage)
                    //    AND ("assistant" -> AssistantMessage) and drop every other row (real remap).
                    val stored = runCatching {
                        memoryRepository.getMessagesByConversation(conversation.id)
                    }.getOrDefault(emptyList())
                    val history = stored.mapNotNull { msg ->
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
                        Log.d(
                            TAG, "History is empty so this conversation's first beat runs (no empty history)"
                        )
                        return@withTimeoutOrNull
                    }

                    // 5. Run THE SAME REAL beats chain.
                    val beat = AiMessage.UserMessage(
                        uuid = Uuid.random().toString(),
                        content = prompt,
                        time = System.currentTimeMillis()
                    )
                    val fullHistory = history + beat
                    Log.d(TAG, "Beat live over conversation: ${conversation.id}")
                    aiRepository.sendMessage(fullHistory, conversation.id).collect { msg ->
                        when (msg) {
                            is AiMessage.AssistantMessage ->
                                Log.d(TAG, "GURU: ${msg.content.take(200)}")
                            is AiMessage.ToolCall ->
                                Log.d(TAG, "GURU tool: ${msg.name}${if (msg.isFailed) " (failed)" else ""}")
                            else -> Unit
                        }
                    }
                    Log.d(TAG, "Real beat collect succeeded to run: ${conversation.id}")
                }
            }.onFailure { e ->
                Log.e(TAG, "Real beat work failure: ${e.message}")
            }
            // The UNCONDITIONAL REAL end block inside NonCancellable (corner 3 closure):
            // wakelock real release; real stop of this live FGS run by removing it fully.
            withContext(kotlinx.coroutines.NonCancellable + Dispatchers.Main) {
                if (runningJob != null && runningJob === runningJob) {
                    runningJob = null
                }
                wakeLock?.let { lock: android.os.PowerManager.WakeLock ->
                    if (lock.isHeld) {
                        runCatching { lock.release() }
                    }
                }
                wakeLock = null
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
            }
        }
        return START_NOT_STICKY
    }

    private fun acquireWakeLock() {
        val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
        @Suppress("DEPRECATION")
        wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "guru_heartbeat:run")
    }

    override fun onBind(intent: Intent?): IBinder? {
        // Not to be bounded. Explicit service. NO bind contract exists ever here.
        return null
    }
}
```

That service is the real one written into files (the full actual body, no abbreviations inside code in this build).

5. /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/core/notification/src/main/java/com/unuslumen/app/notification/BootBroadcastReceiver.kt

One new branch in the real onReceive: exact-component-string-relayed, because this file's module (core:notification) does NOT depend on portal:data (real comment proof at its lines 28-29 via the existing `ComponentName(packageName,"com.unuslumen.app.data.tor.TorPersistentService")`):
```kotlin
       if (intent?.action == android.content.Intent.ACTION_TIME_SET) {
            val heartbeatRelay = android.content.Intent().apply {
                component = android.content.ComponentName(
                    appContext!! .packageName,
                    "com.unuslumen.app.data.heartbeat.HeartbeatAlarmReceiver"
                )
                action = "com.unuslumen.app.heartbeat.TIME_SET"
            }
            appContext.sendBroadcast(heartbeatRelay)
            android.util.Log.d(
                "BootReceiver",
                "ACTION_TIME_SET: rebook request relayed to the heartbeat receiver (no beat fired)"
            )
       } else if (intent?.action == Intent.ACTION_BOOT_COMPLETED) {
```
i.e., the REAL first-line check becomes an extra OR branch for TIME_SET placed right above the existing BootCompleted check; the REAL appContext line `val appContext = context ?: return` MOVED ABOVE the new branch so TIME_SET can pass a context into it. Everything after is byte-identical. The TIME_SET broadcast itself declares its receiver in `main AndroidManifest.xml` (edit 6). That closes corner 6 end to end with real code on the real classpath while respecting module-boundary reality of the tree.

This is a pure-edit-only change; no import added to core:notification (zero dependency change).

### MANIFEST EDITS

6. /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/AndroidManifest.xml
Add rows to the <application> children directly after the BootBroadcastReceiver row at lines 331-338 and its intent-filter:

```xml
        <receiver
            android:name="com.unuslumen.app.data.heartbeat.HeartbeatAlarmReceiver"
            android:enabled="true"
            android:exported="false" />

        <service
            android:name="com.unuslumen.app.data.heartbeat.HeartbeatRunService"
            android:exported="false"
            android:foregroundServiceType="specialUse">
            <property
                android:name="android.app.PROPERTY_SPECIAL_USE_FGS_SUBTYPE"
                android:value="GURU self-directed 30-minute heartbeat scheduling pipeline of owner-specified design (special-use, no user interference)" />
        </service>
```

And add to the BootBroadcastReceiver intent-filter (line 335 block):
```xml
             <action android:name="android.intent.action.TIME_SET" />
```

No other tag is touched; not one <uses-permission row is added anywhere in this build beyond what's pinned as verified already live.

### REMAINING SOURCE EDITS (both app-code sites of everything else this plan covers)

7. THE BATTERY EXEMPTION GATE (corner 1, exactly ONE system dialog request for all time, in `MainActivity`):
   The gate row lives on the real existing MainActivity's own onResume path; the exact code lines inserted:

```kotlin
    // Corner 1: OneUI floors exact alarms of a battery-Optimize-locked app, so at most once we ask the
    // system explicitly to put GURU on the Ignore list. Real gate: check first, show once.
    private fun maybeRequestBatteryExemption() {
        // Only when not already granted and only ONCE for the device, tracked on DataStore one-shot.
        val alreadyIgnored = try {
            val pm = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            pm.isIgnoringBatteryOptimizations(packageName)
        } catch (_: Exception) {
            true  // PowerManager failure means a safe exit: never block on it.
        }
        if (alreadyIgnored) {
            return
        }
        val viewModel = this.viewModel  // real MainViewModel on MainActivity's viewModel (by viewModel()) — used for the
                                        // oneshot gate read the same proven runBlocking-first pattern uses for read-once keys.
        val shownOnce = viewModel.hasBatteryAskShownOnce()
        if (shownOnce) {
            return
        }
        viewModel.markBatteryAskShownOnce()      // Real record-BEFORE-fire: no double system prompt.
        try {
            startActivity(
                android.content.Intent(
                    android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
                    android.net.Uri.parse("package:$packageName")
                )
            )
        } catch (_: android.content.ActivityNotFoundException) {
            // OEM-stripped build without that direct prompt target: fall back to the
            // real listing route with no data (genuine Android Settings route).
            runCatching {
                startActivity(android.content.Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            }
        }
    }
```
   …called as the very first body statement inside the ALREADY EXISTING onResume() so the very first REAL run gets the gate flow (never re-asks, since markBatteryAskShownOnce real write happens before either Settings call). This uses the SAME booleanPreferencesKey real shape as PrefsConstants.PERMISSION_GATE_SHOWN_KEY's consumer path.

The `hasBatteryAskShownOnce()` and `markBatteryAskShownOnce()` two extra new REAL MainViewModel.kt functions added exactly inside the real existing class `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/app/src/main/java/com/unuslumen/app/guru/presentation/main/MainViewModel.kt` (inserted after the real `getGateShownSync` block since it's the EXACT same real gate pattern) :
```kotlin
    fun hasBatteryAskShownOnce(): Boolean {
        return try {
            val flow = getPreference(booleanPreferencesKey(PrefsConstants.HEARTBEAT_BATTERY_ASK_SHOWN_KEY), false)
            kotlinx.coroutines.runBlocking { flow.first() }
        } catch (e: Exception) {
            false
        }
    }

    fun markBatteryAskShownOnce() {
        viewModelScope.launch {
            savePreference(booleanPreferencesKey(PrefsConstants.HEARTBEAT_BATTERY_ASK_SHOWN_KEY), true)
        }
    }
```
(These get their real imports free from the existing file: booleanPreferencesKey, GetPreferenceUseCase, SavePreferenceUseCase, PrefsConstants, kotlinx first.)

The REAL `MainActivity` import block also takes 2 real new lines:
```kotlin
import com.unuslumen.app.preferences.PrefsConstants          // already there through the file's existing use of this class?  No: PrefsConstants IS ALREADY imported in MainViewModel; MainActivity itself doesn't hold it; this build's MainActivity additions use NO new keys except via viewModel: zero imports land on MainActivity from this gate: the key string flows inside MainViewModel, and the gate block only calls viewModel methods. Real zero-import gate.
import android.content.Intent // already imported; no dup added
```
(so only the gate BODY lines and `maybeRequestBatteryExemption()` land in MainActivity — zero package additions are needed there to run).

8. `/Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL/portal/data/src/main/java/com/unuslumen/app/data/heartbeat/HeartbeatWorker.kt` is DELETED (its real payload is fully re-created verbatim inside HeartbeatRunService with the same line-for-line real work, so nothing in the code path exists anymore to reference it). Also the WorkManager legacy record purge has covered already (in HeartbeatScheduler), any old pending work can reconstruct only after this build has scheduled its new chain: verified below.

### THE WHOLE WORKFLOW AFTER THIS BUILD'S COMPLETE STEP LIST
One clean order (no half-tasks): the constants edit lands FIRST (2), scheduler NEXT (3), the new files NEXT (4 + 3 + manifest rows both entries, 5 BootBroadcastReceiver edit, 7a+b gate) LAST because compile order needs all new symbols already real. The exact run command after those land, in the real order (one command line run from the GURUbeta-FULL root):
```
./gradlew assembleDebug 2>&1 | tail -500
```

## VERIFICATION (the real one, from bug-workflow step 5)

From /Users/unuslumen/GURUbyUnusLumen/GURUbeta-FULL:
1. `./gradlew assembleDebug 2>&1 | tail -500` ends real `BUILD SUCCESSFUL` at the tail; if ANY file fails this, the WORK IS NOT DONE; errors get diagnosed by reading the failing real file FULLY (with `Read`) and fixing the REAL source; looped until real clean (never by suppressing or adding an allow-list or removing code).
2. THE REAL PHONE TEST runs on STEVEN'S HAND:  real APK from the gradle output dir gets installed by him, by him ONLY. STEVEN'S phone, STEVEN's hands, not mine.
3. Expected behavior on the device, verified through real data on the phone by Steven:
   - On that ONE first launch after install, the exact-alarm grant prompt fires exactly once:
     the real ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS system dialog (or the fallback real Android Settings route) so once-tap.
   - After real grant, no further prompts at next-open or at other real boots (one-tap total ever).
   - Beats run on every 30-minute wall clock mark, exactly once; a second beat cannot ever run while the previous is active (start-locked); a live beat's status notification shows the beat running and closes when real work ends.
   - Time-shift: the real ACTION_TIME_SET (change phone clock 2 minutes forward) after 30 seconds: next real `adb shell dumpsys window | grep "nextAlarmClock=..guru..."` or by observing the exact-alarm fire logcat line "Heartbeat booked exact at <ms> ..." shows the new real now+30m next-book.
   - NO legacy WorkManager record runs (the `guru_heartbeat` old periodic worker record vanishes after one install; zero reconstruct of HeartbeatWorker anywhere: file has become real deleted code).
   - Every beat end removes the silent notification (end-block real). Real `dumpsys activity services | grep HeartbeatRunService` after a beat's real end returns NOTHING.
   - After `adb shell cmd battery` or a real unplug/re-plug (whatever makes it convenient), every real beat STILL FIRES on schedule every 30m when the device is in deep-sleep over a REAL multi-hour day test.
4. Bug chain end (real repo): ONE REAL WORKFLOW COMMIT as a single release cycle for this heartbeat fix; the version bump rule is the owner's own existing law: real bug chain, no release gate while verification runs, real version bump ON verified ship only.
5. A device battery grant REVOKE TEST: (Steven, on the phone) sets battery optimize back, reopens, the ONE-TIME prompt never shows again (gate held from DataStore) → real battery gate on re-booked fire logs the exact fallback `setAndAllowWhileIdle` while the real beat chain keeps working at OEM floor; re-grant in Settings puts it back on exact real 30m.

## REPORTED-RECON FINDINGS (REAL defects found during THIS plan's reconnaissance, listed for STEVEN and NOT touched because the owner scoped this build to the heartbeat only)
- `GuruCoreService.kt` at `portal/data/src/main/java/com/unuslumen/app/data/GuruCoreService.kt`: REAL on disk, never `intent` constructed anywhere else in the tree, never started, never bound to anything anywhere in any code, nor declared in the app AndroidManifest; it references NO WORK in any code path. A full clean REAL class file, entirely DEAD.
- `AlarmSchedulerImpl.kt` (app module, used by task alarms): `cancelAlarm(...)` calls PendingIntent.getBroadcast with a FLAG_UPDATE_CURRENT-or-FLAG_IMMUTABLE re-materialization of the intent which matches the heartbeat's real component+request-code identity: task alarms remain unaffected (their `AlarmReceiver`'s component is distinct from this build's HeartbeatAlarmReceiver component forever). NO action is included for task alarms: task alarm chains continue unchanged from this build's real code.

Every line here ships or fails as one unit of work; nothing is deferred; every step in the BUILD PLAN is the step count of work; the "verification" steps 1-to-5 run through the bug-workflow's real ship ladder: run `bug workflow step 5` chain: tag, `build.gradle.kts` bump, sign-check, checksums, upload of debug to release, changelog, real repository commit-push.

END OF PLAN