// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.widget.tasks

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.unuslumen.app.util.Constants

class AddTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            "${Constants.TASKS_SCREEN_URI}?${Constants.ADD_TASK_ARG}=true".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

class NavigateToTasksAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Constants.TASKS_SCREEN_URI.toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

class TaskWidgetItemClickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[taskId]?.let {
            val intent = Intent(
                Intent.ACTION_VIEW,
                "${Constants.TASK_DETAILS_URI}/${it}".toUri()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

class CompleteTaskAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[taskId]?.let { id ->
            parameters[completed].let { completed ->
                val intent = Intent(context, CompleteTaskWidgetReceiver::class.java)
                intent.putExtra("taskId", id)
                intent.putExtra("completed", completed)
                context.sendBroadcast(intent)
            }
        }
    }
}

val taskId = ActionParameters.Key<String>("taskId")
val completed = ActionParameters.Key<Boolean>("completed")