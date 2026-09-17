package com.unuslumen.app.widget.notes

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import com.unuslumen.app.util.Constants

class AddNoteAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            "${Constants.NOTE_DETAILS_URI}/null".toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

class NavigateToNotesAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        val intent = Intent(
            Intent.ACTION_VIEW,
            Constants.NOTES_SCREEN_URI.toUri()
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }
}

class NoteWidgetItemClickAction : ActionCallback {
    override suspend fun onAction(context: Context, glanceId: GlanceId, parameters: ActionParameters) {
        parameters[noteId]?.let {
            val intent = Intent(
                Intent.ACTION_VIEW,
                "${Constants.NOTE_DETAILS_URI}/$it".toUri()
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(intent)
        }
    }
}

val noteId = ActionParameters.Key<String>("noteId")

