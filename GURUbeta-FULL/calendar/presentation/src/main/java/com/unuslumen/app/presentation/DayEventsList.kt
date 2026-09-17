package com.unuslumen.app.presentation

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.CalendarDay
import com.unuslumen.app.domain.model.CalendarEvent
import com.unuslumen.app.ui.R
import com.unuslumen.app.util.date.formattedEventsDayName

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
            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            item {
                Text(
                    text = selectedDate.date.formattedEventsDayName,
                    style = MaterialTheme.typography.headlineSmall
                )
            }
            if (selectedDate.events.isEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.no_events),
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
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
