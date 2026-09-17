package com.unuslumen.app.notification

import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unuslumen.app.domain.model.Task
import com.unuslumen.app.domain.use_case.GetTaskByAlarmUseCase
import com.unuslumen.app.domain.use_case.GetTaskByIdUseCase
import com.unuslumen.app.domain.use_case.UpdateTaskCompletedUseCase
import com.unuslumen.app.util.Constants
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

class TaskActionButtonBroadcastReceiver : BroadcastReceiver(), KoinComponent {

    private val updateTaskCompleted: UpdateTaskCompletedUseCase by inject()
    private val getTaskById: GetTaskByIdUseCase by inject()
    private val getTaskByAlarm: GetTaskByAlarmUseCase by inject()
    private val ioDispatcher: CoroutineDispatcher by inject(named("ioDispatcher"))
    private val scope = CoroutineScope(ioDispatcher)

    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action == Constants.ACTION_COMPLETE) {
            val pendingResult = goAsync()
            scope.launch(ioDispatcher) {
                try {
                    val task = intent.getTaskBackwardsCompat() ?: return@launch
                    updateTaskCompleted(task, true)
                    val manager =
                        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
                    manager.cancel(task.alarmId ?: return@launch)
                } finally {
                    pendingResult.finish()
                }
            }
        }
    }

    // The new is uuid string but previously it was an int which is same as alarm id
    private suspend fun Intent.getTaskBackwardsCompat(): Task? {
        val taskId = getStringExtra(Constants.TASK_ID_EXTRA) // uuid
        taskId?.let { return getTaskById(it) }
        val alarmId = getIntExtra(Constants.TASK_ID_EXTRA, -1).takeIf { it != -1 }
        return alarmId?.let { getTaskByAlarm(it) }
    }
}