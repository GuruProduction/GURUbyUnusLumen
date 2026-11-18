// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.CalendarDay
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import com.unuslumen.app.util.date.formattedEventsDayName

/**
 * The events for the chosen day, in the house language.
 *
 * The day's name is set in the poster face as the page's own heading, and an empty
 * day says so in the caption voice rather than as grey Material body text. The
 * slide animation between days is unchanged.
 */
@Composable
fun DayEventsList(
    modifier: Modifier = Modifier,
    state: LazyListState,
    selectedDate: CalendarDay,
    onEventClick: (CalendarEvent) -> Unit
) {
    AnimatedContent(
        targetState = selectedDate,
        contentKey = { it.date.dayOfYear },
        transitionSpec = {
            if (targetState.date.dayOfYear < initialState.date.dayOfYear) {
                slideInHorizontally { -it } + fadeIn() togetherWith slideOutHorizontally { it } + fadeOut()
            } else {
                slideInHorizontally { it } + fadeIn() togetherWith slideOutHorizontally { -it } + fadeOut()
            }
        },
    ) { selectedDate ->
        LazyColumn(
            modifier = modifier,
            state = state,
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 18.dp),
            verticalArrangement = Arrangement.spacedBy(9.dp)
        ) {
            item {
                Text(
                    text = selectedDate.date.formattedEventsDayName.uppercase(),
                    style = posterStyle(15f),
                    modifier = Modifier.padding(bottom = 2.dp),
                )
            }
            if (selectedDate.events.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_events).uppercase(),
                        style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.5f)),
                    )
                }
            } else {
                items(
                    items = selectedDate.events,
                    key = { it.id }
                ) { event ->
                    CalendarEventItem(
                        event = event,
                        onClick = onEventClick
                    )
                }
            }
        }
    }
}
