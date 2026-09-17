package com.unuslumen.app.widget.notes

import android.annotation.SuppressLint
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.theme.Orange
import com.unuslumen.app.widget.smallBackgroundBasedOnVersion

@SuppressLint("RestrictedApi")
@Composable
fun NoteWidgetItem(
    note: Note
) {
    Box(
        GlanceModifier.padding(bottom = 3.dp)
    ) {
        Column(
            GlanceModifier
                .smallBackgroundBasedOnVersion()
                .padding(10.dp)
                .clickable(
                    actionRunCallback<NoteWidgetItemClickAction>(
                        parameters = actionParametersOf(
                            noteId to note.id
                        )
                    )
                )
        ) {
            Row(
                GlanceModifier
                    .fillMaxWidth()
                    .clickable(
                        actionRunCallback<NoteWidgetItemClickAction>(
                            parameters = actionParametersOf(
                                noteId to note.id
                            )
                        )
                    ), 
                verticalAlignment = Alignment.CenterVertically
            ) {
                if (note.pinned) {
                    Image(
                        modifier = GlanceModifier.size(16.dp),
                        provider = ImageProvider(R.drawable.ic_pin_filled),
                        contentDescription = null,
                        colorFilter = ColorFilter.tint(ColorProvider(Orange))
                    )
                    Spacer(GlanceModifier.width(6.dp))
                }
                Text(
                    note.title.ifBlank { "Untitled" },
                    style = TextStyle(
                        color = GlanceTheme.colors.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp
                    ),
                    maxLines = 1,
                    modifier = GlanceModifier.defaultWeight()
                )
            }
            if (note.content.isNotBlank()) {
                Spacer(GlanceModifier.height(4.dp))
                Text(
                    note.content,
                    style = TextStyle(
                        color = GlanceTheme.colors.onSecondaryContainer,
                        fontWeight = FontWeight.Normal,
                        fontSize = 13.sp
                    ),
                    maxLines = 2
                )
            }
        }
    }
}

