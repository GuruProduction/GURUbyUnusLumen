// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unuslumen.app.alarm.use_case.DeleteAlarmUseCase
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.use_case.GetTaskByAlarmUseCase
import com.unuslumen.app.util.Constants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject

class AlarmReceiver : BroadcastReceiver(), KoinComponent {

    private val deleteAlarmUseCase: DeleteAlarmUseCase by inject()
    private val getTaskByAlarm: GetTaskByAlarmUseCase by inject()

    private val scope = CoroutineScope(Dispatchers.IO)

    override fun onReceive(context: Context?, intent: Intent?) {
        val pendingResult = goAsync()

        scope.launch {
            val task = intent?.getTaskBackwardsCompat() ?: run {
                pendingResult.finish()
                return@launch
            }

            val manager =
                context?.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.sendNotification(task, context, task.alarmId ?: return@launch)
            deleteAlarmUseCase(task.alarmId ?: return@launch)

            pendingResult.finish()
        }
    }


    // Newly used name is alarm id but previous versions use task id name
    private suspend fun Intent.getTaskBackwardsCompat(): Task? {
        val alarmId =
            getIntExtra(Constants.ALARM_ID_EXTRA, -1).takeIf { it != -1 }
                ?: getIntExtra(Constants.TASK_ID_EXTRA, -1).takeIf { it != -1 }
        return alarmId?.let { getTaskByAlarm(it) }
    }

}
