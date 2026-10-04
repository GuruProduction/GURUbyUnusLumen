// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.AlarmManagerCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * BroadcastReceiver for the exact-alarm fire of the heartbeat chain.
 *
 * Exported=false. PendingIntent identity of every booking is
 * (HEARTBEAT_REQUEST_CODE, component=HeartbeatAlarmReceiver), so only this
 * chain's own alarms can land here.
 *
 * On an alarm fire the receiver does, in strict order:
 *  a) Booking FIRST: book the next slot at now + INTERVAL_MILLIS with the same
 *     gated exact call shape used everywhere in this chain. If every later step
 *     failed, the grid would still hold from this single synchronous booking.
 *  b) Booking identity: carry the extra HEARTBEAT_EXTRA_BOOKED_AT from the fired
 *     alarms compare against the DataStore booked key. A match says this beat was
 *     the actually-booked one and its payload runs. A mismatch (very stale extra
 *     redelivered, or a DataStore read that came back blank) drops THIS payload
 *     and heals the stored value to the freshly booked next, so one diverged row
 *     can never mute the chain forever.
 *  c) Payload kickoff via startForegroundService to HeartbeatRunService. A
 *     ForegroundServiceStartNotAllowedException on OEM shapes is caught-and-logged:
 *     the grid is already rebooked from step (a) and the next fire re-kicks.
 *
 * On the RELAYED time-set action only (a) runs: book-from-now, never a payload.
 */
class HeartbeatAlarmReceiver : BroadcastReceiver() {

    companion object {
        private const val TAG = "guru_heartbeat"

        /** Action relayed by BootBroadcastReceiver on ACTION_TIME_SET. Reboot-only book. */
        const val ACTION_REBOOK_TIME_SET = HeartbeatScheduler.HEARTBEAT_ACTION_REBOOK_TIME_SET
    }

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        if (context == null || intent == null) return
        val appContext = context.applicationContext ?: return

        if (intent.action == ACTION_REBOOK_TIME_SET) {
            // Corner 6: the phone's wall clock was reset; jump the whole grid
            // from NOW without ever running a payload on this receive.
            HeartbeatScheduler.schedule(appContext)
            Log.d(TAG, "Time-set rebook: grid rebooked from now, no beat fired")
            return
        }

        val pendingResult = goAsync()
        scope.launch {
            try {
                // (a) rebook FIRST, always, before any payload work. The gated
                // exact call shape and the runCatching are the booking of record.
                val alarmManager =
                    appContext.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                val next = System.currentTimeMillis() + HeartbeatScheduler.INTERVAL_MILLIS
                val nextPi = HeartbeatScheduler.buildPendingIntent(appContext, next)
                runCatching {
                    if (android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S ||
                        alarmManager.canScheduleExactAlarms()
                    ) {
                        AlarmManagerCompat.setExactAndAllowWhileIdle(
                            alarmManager, android.app.AlarmManager.RTC_WAKEUP, next, nextPi
                        )
                    } else {
                        AlarmManagerCompat.setAndAllowWhileIdle(
                            alarmManager, android.app.AlarmManager.RTC_WAKEUP, next, nextPi
                        )
                    }
                }.onFailure { Log.w(TAG, "Fire rebook attempt failed: ${it.message}") }

                // (b) booking identity read against DataStore, with healing.
                val storedNow = runCatching {
                    HeartbeatScheduler.readBookedAt().first()
                }.getOrDefault("")
                val carried = intent.getLongExtra(
                    HeartbeatScheduler.HEARTBEAT_EXTRA_BOOKED_AT, -1L
                )

                val identityMatched = (
                    carried > 0L &&
                        storedNow.toLongOrNull() == carried
                    )

                if (!identityMatched) {
                    // Healing: overwrite the stored value with the freshly booked
                    // `next` before dropping this beat's payload, so one diverged
                    // DataStore row can never mute the grid forever.
                    runCatching {
                        heartbeatSaveBookedAt(appContext, next)
                    }.onFailure { Log.w(TAG, "Booked-state heal write failed: ${it.message}") }
                    Log.w(
                        TAG,
                        "Booking id mismatch (stored=$storedNow, carried=$carried): payload dropped, state healed, grid alive"
                    )
                    return@launch
                }

                // (c) payload kick-off (only for an identity-matched fire).
                runCatching {
                    appContext.startForegroundService(
                        Intent(appContext, HeartbeatRunService::class.java).apply {
                            putExtra(HeartbeatScheduler.HEARTBEAT_EXTRA_BOOKED_AT, carried)
                        }
                    )
                }.onFailure { e ->
                    // Gap 4 pinned: OneUI FGS start refusals keep the grid alive
                    // (already rebooked in (a)); nothing further on this fire.
                    Log.w(TAG, "Run service start failed this fire: ${e.message}")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }

    /**
     * Heal-write path of the booked grid value: SavePreferenceUseCase by Koin fetch +
     * inject in the exact same DataStore chain every prefs writes.
     */
    private suspend fun heartbeatSaveBookedAt(context: Context, next: Long) {
        val savePreference: com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase =
            org.koin.core.context.GlobalContext.get().get()
        savePreference(
            com.unuslumen.app.preferences.domain.model.stringPreferencesKey(
                com.unuslumen.app.preferences.PrefsConstants.HEARTBEAT_NEXT_BOOKED_AT_KEY
            ),
            next.toString()
        )
    }
}