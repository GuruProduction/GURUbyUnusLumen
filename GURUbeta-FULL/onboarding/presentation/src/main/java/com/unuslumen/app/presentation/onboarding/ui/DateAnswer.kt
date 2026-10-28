// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.onboarding.ui

import android.app.DatePickerDialog
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import com.unuslumen.app.onboarding.domain.OnboardingChapter
import java.time.LocalDate
import java.util.Locale

/**
 * DateAnswer — a real date field, not a text box that hopes.
 *
 * WHY A PICKER. A birthday typed by hand arrives as "31/12/94", "31-12-1994",
 * "12/31/1994" or "Dec 31 1994". All four are the same fact, and a model handed
 * any of them has to guess which number is the day. The picker removes the guess
 * entirely: the stored answer is always an ISO day, YYYY-MM-DD, with no time and
 * no timezone, because a birthday has neither.
 *
 * WHY THE SYSTEM DIALOG. It is the one control on the platform that everybody
 * already knows how to use, it handles leap years and month lengths for free, and
 * it is accessible without any work from this screen. A hand-rolled wheel here
 * would be prettier and worse.
 *
 * It opens from a tappable panel in the chapter colour, so the field still belongs
 * to the same visual language as every other answer even though the picking happens
 * in a system surface.
 */
@Composable
fun DateAnswer(
    chapter: OnboardingChapter,
    answer: String,
    onLaunch: (Offset) -> Unit,
    onAnswer: (String) -> Unit,
) {
    val context = LocalContext.current
    val accent = OnboardingSkin.colourOf(chapter)
    val ink = OnboardingSkin.inkOf(chapter)
    var box by remember { mutableStateOf(Rect.Zero) }

    // Start where the answer already is, so re-opening the picker on a birthday
    // they already gave lands on that date instead of jumping to today.
    val existing: LocalDate? = remember(answer) {
        runCatching { LocalDate.parse(answer.trim()) }.getOrNull()
    }
    val initial = existing ?: LocalDate.of(1995, 1, 1)

    val open: (Offset) -> Unit = { origin ->
        onLaunch(origin)
        DatePickerDialog(
            context,
            { _, year, month, day ->
                onAnswer(LocalDate.of(year, month + 1, day).toString())
            },
            initial.year,
            initial.monthValue - 1,
            initial.dayOfMonth
        ).apply {
            // The picker defaults to a wide century window. A birthday belongs in
            // the last hundred years, so the range opens where the answer lives
            // rather than in 1926.
            datePicker.maxDate = System.currentTimeMillis()
        }.show()
    }

    val shown = remember(answer) {
        runCatching {
            LocalDate.parse(answer.trim()).format(
                java.time.format.DateTimeFormatter.ofPattern("d MMMM yyyy", Locale.UK)
            )
        }.getOrDefault("")
    }

    Column(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .fillMaxWidth()
                .onGloballyPositioned { box = it.boundsInWindow() }
                .clip(OnboardingTileShape)
                .background(if (shown.isBlank()) OnboardingPalette.washOf(accent) else accent)
                .border(width = 2.dp, color = accent, shape = OnboardingTileShape)
                .clickable { open(Offset(box.center.x, box.center.y)) }
                .padding(horizontal = 20.dp, vertical = 22.dp)
        ) {
            Text(
                text = shown.ifBlank { "Tap to pick your birthday" },
                style = MaterialTheme.typography.titleLarge.copy(
                    color = if (shown.isBlank()) ink else OnboardingSkin.onColour(accent),
                    fontWeight = if (shown.isBlank()) FontWeight.Normal else FontWeight.SemiBold,
                    fontFamily = OnboardingSkin.Display,
                    lineHeight = 1.25.em
                )
            )
        }
    }
}
