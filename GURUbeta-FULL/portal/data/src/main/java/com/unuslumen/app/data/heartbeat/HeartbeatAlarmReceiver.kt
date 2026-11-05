// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
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
 *  a) Booking FIRST, through [HeartbeatScheduler.schedule], which is the single booking
 *     authority. If every later step failed, the grid would still hold from that one
 *     synchronous call.
 *  b) Payload kickoff via startForegroundService to HeartbeatRunService —
 *     UNCONDITIONAL. Every fire runs the beat. The old booked-at identity check
 *     (payload only when the carry matched a DataStore value) is retired: when
 *     the two records ever drifted, every future payload was silently dropped
 *     forever, which on a real device reads as "the heartbeat never fires".
 *     One booking authority (the scheduler), one payload rule (always run),
 *     zero drift-prone second sources.
 *
 * WHY THE BOOKING NOW GOES THROUGH THE SCHEDULER RATHER THAN BEING WRITTEN HERE AGAIN.
 * This file used to build its own AlarmManager call, which meant two copies of the
 * booking rule and — worse — a copy that did not ask whether the owner had switched the
 * chain off. A fire that arrived from a booking made before the switch was turned off
 * would rebook itself from here and the chain would carry on regardless. Routing through
 * the scheduler gives the switch one place to speak, and gives the diary one place to be
 * reconciled on every rebook.
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
                /*
                 * (a) REBOOK FIRST, ALWAYS, BEFORE ANY PAYLOAD WORK.
                 *
                 * Through the scheduler, so the booking rule lives in one place, the
                 * switch is honoured here exactly as it is on boot, and the diary is
                 * reconciled on the same call. The scheduler wraps its own AlarmManager
                 * work in runCatching, so a refused rebook cannot cost this fire.
                 */
                runCatching {
                    HeartbeatScheduler.schedule(appContext)
                }.onFailure { Log.w(TAG, "Fire rebook failed: ${it.message}") }

                // (b) payload kick-off, ALWAYS. The book keeping of the fire
                // rides the carried extra purely as telemetry now.
                val carried = intent.getLongExtra(
                    HeartbeatScheduler.HEARTBEAT_EXTRA_BOOKED_AT, -1L
                )

                // THE FIRE IS RECORDED BEFORE ANYTHING CAN FAIL.
                //
                // A booking proves only that an alarm was written; this proves one
                // arrived. Without it, "booked" and "firing" are indistinguishable from
                // the room, which is exactly why a dead heartbeat can look scheduled
                // while nothing happens. Written here, first, so a refused start below
                // still leaves the evidence that the alarm itself is healthy.
                runCatching {
                    HeartbeatScheduler.recordFired(appContext, System.currentTimeMillis())
                }.onFailure { Log.w(TAG, "Fire record failed: ${it.message}") }

                runCatching {
                    appContext.startForegroundService(
                        Intent(appContext, HeartbeatRunService::class.java).apply {
                            putExtra(HeartbeatScheduler.HEARTBEAT_EXTRA_BOOKED_AT, carried)
                        }
                    )
                }.onFailure { e ->
                    // OneUI FGS start refusals keep the grid alive
                    // (already rebooked in (a)); the next fire re-kicks. The reason is
                    // recorded as an outcome so the room can name it rather than leave
                    // a fire that produced nothing with no explanation.
                    Log.w(TAG, "Run service start failed this fire: ${e.message}")
                    HeartbeatScheduler.recordOutcome(
                        appContext,
                        "Alarm fired but the run service was refused: ${e.javaClass.simpleName}. " +
                            "Usually a background-start restriction; the next fire tries again.",
                    )
                }
            } finally {
                pendingResult.finish()
            }
        }
    }
}