package com.unuslumen.app.presentation

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.CalendarDay

@Composable
fun CalendarDayCell(
    day: CalendarDay,
    isSelected: Boolean,
    isToday: Boolean,
    onDaySelected: (CalendarDay) -> Unit
) {
    val backgroundColor = when {
        isSelected -> MaterialTheme.colorScheme.primaryContainer
        else -> Color.Transparent
    }
    val textColor = when {
        isSelected -> MaterialTheme.colorScheme.onPrimaryContainer
        !day.isCurrentMonth -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        else -> MaterialTheme.colorScheme.onSurface
    }
    val eventColors = remember(day.events) {
        day.events.map { it.color }.distinct()
    }

    CalendarDayCellContent(
        text = day.date.day.toString(),
        textColor = if (isToday && !isSelected) MaterialTheme.colorScheme.primary else textColor,
        backgroundColor = backgroundColor,
        fontWeight = if (isToday) FontWeight.Bold else FontWeight.Normal,
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
        fontWeight = FontWeight.Normal,
        eventColors = emptyList()
    )
}

@Composable
fun CalendarDayCellContent(
    text: String,
    textColor: Color,
    backgroundColor: Color,
    fontWeight: FontWeight,
    eventColors: List<Int>,
    onClick: (() -> Unit) = {}
) {
    Column(
        modifier = Modifier
            .padding(2.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(backgroundColor)
            .clickable { onClick() }
            .padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = text,
            style = MaterialTheme.typography.bodyMedium,
            color = textColor,
            fontWeight = fontWeight
        )
        Spacer(Modifier.height(5.dp))
        EventDots(colors = eventColors)
    }
}

@Composable
fun EventDots(colors: List<Int>) {
    if (colors.isEmpty()) {
        Spacer(Modifier.height(5.dp))
    } else {
        val indicators = if (colors.size <= 3) colors else colors.take(3)
        Row(
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            indicators.forEach { colorValue ->
                Box(
                    modifier = Modifier
                        .size(5.dp)
                        .clip(CircleShape)
                        .background(Color(colorValue))
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun CalendarDayCellContentPreview() {
    MaterialTheme {
        Box(
            Modifier.width(40.dp)
        ) {
            CalendarDayCellContent(
                text = "15",
                textColor = MaterialTheme.colorScheme.onPrimaryContainer,
                backgroundColor = MaterialTheme.colorScheme.primaryContainer,
                fontWeight = FontWeight.Bold,
                eventColors = listOf(0xFFEA4335.toInt(), 0xFF34A853.toInt(), 0xFF4285F4.toInt()),
                onClick = {}
            )
        }
    }
}
