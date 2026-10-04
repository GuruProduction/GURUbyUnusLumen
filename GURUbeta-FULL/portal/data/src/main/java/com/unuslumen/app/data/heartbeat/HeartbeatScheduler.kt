// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.AlarmManagerCompat
import androidx.work.WorkManager
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

/**
 * Founder-cadence scheduler: real 30-minute wall-clock booking on AlarmManager.
 *
 * The beat is booked with setExactAndAllowWhileIdle while the SCHEDULE_EXACT_ALARM
 * grant exists, drops to setAndAllowWhileIdle when it has been revoked, and every
 * booking is wrapped in runCatching so no grant reset or OEM oddity can crash a
 * boot, a receiver, or a service on the booking path.
 *
 * This object is the single booking authority of the chain: GuruApplication calls
 * schedule() on boot, the alarm receiver rebooks on every fire before running the
 * payload, and the relayed time-set rebook comes through schedule() too. The
 * legacy WorkManager periodic record ("guru_heartbeat", from builds before the
 * AlarmManager chain) is cancelled on every schedule() and cancel() so an upgraded
 * install can never have Android reconstruct the retired HeartbeatWorker class.
 */
object HeartbeatScheduler : KoinComponent {

    private const val TAG = "guru_heartbeat"

    /** Founder-specified cadence: every 30 minutes. */
    const val INTERVAL_MINUTES = 30L
    const val INTERVAL_MILLIS = INTERVAL_MINUTES * 60_000L

    /** The retired periodic WorkManager record this chain purges on every boot. */
    private const val LEGACY_WORK_NAME = "guru_heartbeat"

    /**
     * Distinct request code of the heartbeat PendingIntent chain.
     *
     * PendingIntents match by (requestCode, intent filterEquals). Task alarms
     * carry component com.unuslumen.app.notification.AlarmReceiver exclusively;
     * every PendingIntent this booking writes carries component
     * com.unuslumen.app.data.heartbeat.HeartbeatAlarmReceiver exclusively, so
     * even where the int coincides with some task-alarm id, the two records stay
     * distinct for cancel() and for every other AlarmManager lookup, forever.
     */
    const val HEARTBEAT_REQUEST_CODE = 2148

    /** Booking-time marker, carried into the fired alarm and compared with DataStore. */
    const val HEARTBEAT_EXTRA_BOOKED_AT = "heartbeat_extra_booked_at"

    /** Relay action for the boot receiver's time-shift branch. */
    const val HEARTBEAT_ACTION_REBOOK_TIME_SET = PrefsConstants.HEARTBEAT_TIME_SET_ACTION

    private val applicationScope: CoroutineScope by inject(named("applicationScope"))
    private val getPreference: GetPreferenceUseCase by inject()
    private val savePreference: SavePreferenceUseCase by inject()

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
                AlarmManagerCompat.setAndAllowWhileIdle(
                    alarmManager, AlarmManager.RTC_WAKEUP, next, pi
                )
            }
        }.onFailure { Log.w(TAG, "Exact rebook attempt failed: ${it.message}") }

        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK_NAME)
        }.onFailure { Log.d(TAG, "Legacy WorkManager purge no-op: ${it.message}") }

        applicationScope.launch {
            runCatching {
                savePreference(
                    stringPreferencesKey(PrefsConstants.HEARTBEAT_NEXT_BOOKED_AT_KEY),
                    next.toString()
                )
            }.onFailure { Log.w(TAG, "Booked-state write failed: ${it.message}") }
        }

        Log.d(TAG, "Heartbeat booked exact at $next (founder ${INTERVAL_MINUTES}m AlarmManager chain)")
    }

    fun readBookedAt(): Flow<String> = getPreference(
        stringPreferencesKey(PrefsConstants.HEARTBEAT_NEXT_BOOKED_AT_KEY), ""
    )

    fun cancel(context: Context) {
        val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
        runCatching {
            alarmManager.cancel(buildPendingIntent(context, 0L))
        }.onFailure { Log.w(TAG, "Cancel failed: ${it.message}") }
        runCatching {
            WorkManager.getInstance(context).cancelUniqueWork(LEGACY_WORK_NAME)
        }
        Log.d(TAG, "Heartbeat AlarmManager booking cancelled, legacy record purged")
    }
}