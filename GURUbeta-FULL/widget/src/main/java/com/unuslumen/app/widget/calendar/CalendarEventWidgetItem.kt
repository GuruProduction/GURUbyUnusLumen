package com.unuslumen.app.widget.calendar

import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.ColorFilter
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.actionParametersOf
import androidx.glance.action.clickable
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.cornerRadius
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Box
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.size
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.ui.R
import com.unuslumen.app.util.date.formatTime
import com.unuslumen.app.widget.smallBackgroundBasedOnVersion

@Composable
fun CalendarEventWidgetItem(
    event: CalendarEvent,
) {
    val context = LocalContext.current
    Box(
        GlanceModifier
            .padding(vertical = 4.dp)
    ) {
        Box(
            modifier = GlanceModifier.smallBackgroundBasedOnVersion()
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = GlanceModifier.padding(horizontal = 8.dp),
                horizontalAlignment = Alignment.Start
            ) {
                Box(
                    modifier = GlanceModifier
                        .width(6.dp)
                        .height(32.dp)
                        .cornerRadius(6.dp)
                        .background(Color(event.color)),
                ) {}
                Spacer(GlanceModifier.width(4.dp))
                Column(
                    modifier = GlanceModifier
                        .padding(8.dp)
                        .fillMaxWidth()
                ) {
                    Text(
                        event.title,
                        style = TextStyle(
                            color = GlanceTheme.colors.onSecondaryContainer,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        ),
                        maxLines = 1
                    )
                    Spacer(GlanceModifier.height(4.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            text = "${event.start.formatTime(context)} - ${event.end.formatTime(context)}",
                            style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer)
                            )
                        Spacer(GlanceModifier.width(4.dp))
                        if (!event.location.isNullOrBlank()) {
                            Image(
                                modifier = GlanceModifier.size(12.dp),
                                provider = ImageProvider(R.drawable.ic_location),
                                contentDescription = "",
                                colorFilter = ColorFilter.tint(GlanceTheme.colors.onSecondaryContainer)
                            )
                            Spacer(GlanceModifier.width(3.dp))
                            Text(
                                text = event.location!!,
                                style = TextStyle(color = GlanceTheme.colors.onSecondaryContainer),
                                maxLines = 1
                            )
                        }
                    }
                }
            }
            Box(
                GlanceModifier.fillMaxSize().clickable(
                    actionRunCallback<CalendarWidgetItemClick>(
                        parameters = actionParametersOf(
                            eventIdKey to event.id
                        )
                    )
                )
            ) {}
        }
    }
}