// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.CalendarDay
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.captionStyle

/**
 * One day in the month grid, in the house language.
 *
 * WHAT CHANGED AND WHY. The cell was drawn from Material's colour scheme: a soft
 * rounded lavender fill for the selected day, a tinted primary for today, and a
 * muted variant colour for days outside the month. On a printed page those read as
 * three shades of nothing. The selected day is now a flat ink block with paper type,
 * today is an outlined block, and days outside the month are faded ink. The event
 * dots became small squares, because nothing on this page is round.
 */
@Composable
fun CalendarDayCell(
    day: CalendarDay,
    isSelected: Boolean,
    isToday: Boolean,
    onDaySelected: (CalendarDay) -> Unit
) {
    val background = when {
        isSelected -> Memphis.Ink
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> Memphis.Paper
        !day.isCurrentMonth -> Memphis.Ink.copy(alpha = 0.3f)
        else -> Memphis.Ink
    }
    val borderColor = when {
        isSelected -> Memphis.Ink
        isToday -> Memphis.Ink
        else -> Color.Transparent
    }
    val eventColors = remember(day.events) {
        day.events.map { it.color }.distinct()
    }

    CalendarDayCellContent(
        text = day.date.day.toString(),
        textColor = textColor,
        backgroundColor = background,
        borderColor = borderColor,
        eventColors = eventColors,
        onClick = { onDaySelected(day) }
    )
}

@Composable
fun EmptyCalendarDayCell() {
    CalendarDayCellContent(
        text = "",
        textColor = Color.Transparent,
        backgroundColor = Color.Transparent,
        borderColor = Color.Transparent,
        eventColors = emptyList()
    )
}

@Composable
fun CalendarDayCellContent(
    text: String,
    textColor: Color,
    backgroundColor: Color,
    borderColor: Color = Color.Transparent,
    eventColors: List<Int>,
    onClick: (() -> Unit) = {}
) {
    Column(
        modifier = Modifier
            .padding(1.5.dp)
            .fillMaxWidth()
            .background(backgroundColor)
            .border(1.5.dp, borderColor)
            .clickable { onClick() }
            .padding(vertical = 5.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = captionStyle(10f, textColor),
        )
        Spacer(Modifier.height(4.dp))
        EventDots(colors = eventColors)
    }
}

/**
 * The marks under a day's number.
 *
 * Squares rather than dots: the page has no rounded shapes anywhere else, and a row
 * of circles beside hard-edged blocks was the one place the language slipped.
 */
@Composable
fun EventDots(colors: List<Int>) {
    if (colors.isEmpty()) {
        Spacer(Modifier.height(5.dp))
    } else {
        val indicators = if (colors.size <= 3) colors else colors.take(3)
        Row(
            horizontalArrangement = Arrangement.spacedBy(3.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            indicators.forEach { colorValue ->
                Box(
                    modifier = Modifier
                        .size(4.dp)
                        .background(Color(colorValue))
                )
            }
        }
    }
}
