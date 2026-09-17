package com.unuslumen.app.widget.notes

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.*
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.lazy.LazyColumn
import androidx.glance.appwidget.lazy.items
import androidx.glance.layout.*
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextAlign
import androidx.glance.text.TextStyle
import com.unuslumen.app.ui.R
import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.widget.largeBackgroundBasedOnVersion
import com.unuslumen.app.widget.largeInnerBackgroundBasedOnVersion

@Composable
fun NotesHomeScreenWidget(
    notes: List<Note>
) {
    val context = LocalContext.current
    Box(
        modifier = GlanceModifier
            .fillMaxWidth()
            .largeBackgroundBasedOnVersion()
    ) {
        Column(
            modifier = GlanceModifier.padding(8.dp)
        ) {
            Row(
                GlanceModifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    context.getString(R.string.notes),
                    style = TextStyle(
                        color = GlanceTheme.colors.onSecondaryContainer,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    ),
                    modifier = GlanceModifier
                        .padding(horizontal = 8.dp)
                        .clickable(actionRunCallback<NavigateToNotesAction>()),
                )
                Row(
                    modifier = GlanceModifier
                        .fillMaxWidth()
                        .padding(horizontal = 8.dp)
                        .clickable(actionRunCallback<NavigateToNotesAction>()),
                    horizontalAlignment = Alignment.End
                ) {
                    Image(
                        provider = ImageProvider(R.drawable.ic_add),
                        modifier = GlanceModifier
                            .size(22.dp)
                            .clickable(actionRunCallback<AddNoteAction>())
                        ,
                        contentDescription = "Add note",
                        colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer)
                    )
                }
            }
            Spacer(GlanceModifier.height(8.dp))
            LazyColumn(
                modifier = GlanceModifier
                    .fillMaxSize()
                    .padding(horizontal = 6.dp)
                    .largeInnerBackgroundBasedOnVersion(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                    if (notes.isEmpty()) {
                        item {
                            Text(
                                text = context.getString(R.string.no_notes_message),
                                modifier = GlanceModifier.padding(16.dp),
                                style = TextStyle(
                                    color = GlanceTheme.colors.secondary,
                                    fontWeight = FontWeight.Normal,
                                    fontSize = 16.sp,
                                    textAlign = TextAlign.Center
                                )
                            )
                        }
                    } else {
                        item { Spacer(GlanceModifier.height(6.dp)) }
                        items(notes) { note ->
                            NoteWidgetItem(note)
                        }
                    }
            }
        }
    }
}

