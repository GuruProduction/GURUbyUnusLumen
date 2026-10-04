// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.widget.tasks

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.unuslumen.app.domain.use_case.GetTaskByIdUseCase
import com.unuslumen.app.domain.use_case.UpdateTaskCompletedUseCase
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import org.koin.core.qualifier.named

class CompleteTaskWidgetReceiver : BroadcastReceiver(), KoinComponent {

    private val completeTask: UpdateTaskCompletedUseCase by inject()
    private val getTaskById: GetTaskByIdUseCase by inject()
    private val applicationScope: CoroutineScope by inject(named("applicationScope"))
    private val ioDispatcher: CoroutineDispatcher by inject(named("ioDispatcher"))


    override fun onReceive(context: Context, intent: Intent) {
        val id = intent.getStringExtra("taskId") ?: return
        val completed = intent.getBooleanExtra("completed", true)
        val pendingResult = goAsync()
        applicationScope.launch(ioDispatcher) {
            try {
                val task = getTaskById(id) ?: return@launch
                completeTask(task, completed)
            } finally {
                pendingResult.finish()
            }
        }
    }
}