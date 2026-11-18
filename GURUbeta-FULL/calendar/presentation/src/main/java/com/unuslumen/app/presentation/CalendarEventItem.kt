// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyItemScope
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import com.unuslumen.app.util.date.formatEventStartEnd

/**
 * One event as a ruled row, in the house language.
 *
 * The card keeps the calendar's own colour, but it carries it as a solid spine down
 * the left edge rather than a rounded pill, and the whole thing sits in a hard ink
 * frame with a printed drop. The title is in the poster face, the time is a caption.
 */
@Composable
fun LazyItemScope.CalendarEventItem(
    event: CalendarEvent,
    modifier: Modifier = Modifier,
    onClick: (CalendarEvent) -> Unit
) {
    val context = LocalContext.current

    MemphisSurface(
        modifier = modifier
            .fillMaxWidth()
            .animateItem()
            .clickable { onClick(event) },
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 3.dp,
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().height(58.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier
                    .width(7.dp)
                    .fillMaxHeight()
                    .background(Color(event.color))
            )
            Column(
                Modifier
                    .padding(start = 11.dp, end = 9.dp)
                    .fillMaxWidth()
            ) {
                Text(
                    text = event.title,
                    style = posterStyle(12.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = context.formatEventStartEnd(
                        start = event.start,
                        end = event.end,
                        allDayString = stringResource(R.string.all_day),
                        eventTimeAtRes = R.string.event_time_at,
                        eventTimeRes = R.string.event_time,
                        location = event.location,
                        allDay = event.allDay,
                    ),
                    style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}
