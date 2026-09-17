package com.unuslumen.app.presentation

import android.content.res.Configuration.UI_MODE_NIGHT_YES
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.theme.guruTheme
import com.unuslumen.app.util.date.formatEventStartEnd

@Composable
fun LazyItemScope.CalendarEventItem(
    event: CalendarEvent,
    modifier: Modifier = Modifier,
    onClick: (CalendarEvent) -> Unit
) {
    val context = LocalContext.current
    Card(
        modifier = modifier
            .animateItem(),
        shape = RoundedCornerShape(20.dp),
        elevation = CardDefaults.elevatedCardElevation(4.dp)
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(start = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .width(8.dp)
                    .height(34.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(Color(event.color)),
            )
            Column(
                modifier = Modifier
                    .clickable { onClick(event) }
                    .padding(start = 12.dp, top = 12.dp, bottom = 12.dp, end = 6.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    event.title,
                    style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    context.formatEventStartEnd(
                        start = event.start,
                        end = event.end,
                        allDayString = stringResource(R.string.all_day),
                        eventTimeAtRes = R.string.event_time_at,
                        eventTimeRes = R.string.event_time,
                        location = event.location,
                        allDay = event.allDay,
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )

            }
        }
    }
}

@Preview
@Preview(uiMode = UI_MODE_NIGHT_YES)
@Composable
fun CalendarEventItemPreview() {
    val sampleEvent = CalendarEvent(
        title = "Sample Event",
        start = System.currentTimeMillis(),
        end = System.currentTimeMillis() + 60 * 60 * 1000L,
        color = Color.Red.toArgb(),
        location = "Office",
        allDay = false,
        id = 2,
        calendarId = 1
    )

    guruTheme {
        LazyColumn(
            contentPadding = PaddingValues(8.dp)
        ) {
            item {
                CalendarEventItem(
                    event = sampleEvent,
                    onClick = {}
                )
            }
        }
    }
}
