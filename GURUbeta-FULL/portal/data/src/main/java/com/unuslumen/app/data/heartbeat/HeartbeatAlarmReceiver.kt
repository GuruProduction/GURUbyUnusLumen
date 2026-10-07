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
 *  b) Payload kickoff via startForegroundService to HeartbeatRunService —
 *     UNCONDITIONAL. Every fire runs the beat. The old booked-at identity check
 *     (payload only when the carry matched a DataStore value) is retired: when
 *     the two records ever drifted, every future payload was silently dropped
 *     forever, which on a real device reads as "the heartbeat never fires".
 *     One booking authority (the scheduler), one payload rule (always run),
 *     zero drift-prone second sources.
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
            // the phone's wall clock was reset; jump the whole grid
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

                // (b) payload kick-off, ALWAYS. The book keeping of the fire
                // rides the carried extra purely as telemetry now.
                val carried = intent.getLongExtra(
                    HeartbeatScheduler.HEARTBEAT_EXTRA_BOOKED_AT, -1L
                )
                runCatching {
                    appContext.startForegroundService(
                        Intent(appContext, HeartbeatRunService::class.java).apply {
                            putExtra(HeartbeatScheduler.HEARTBEAT_EXTRA_BOOKED_AT, carried)
                        }
                    )
                }.onFailure { e ->
                    // OneUI FGS start refusals keep the grid alive
                    // (already rebooked in (a)); the next fire re-kicks.
                    Log.w(TAG, "Run service start failed this fire: ${e.message}")
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}