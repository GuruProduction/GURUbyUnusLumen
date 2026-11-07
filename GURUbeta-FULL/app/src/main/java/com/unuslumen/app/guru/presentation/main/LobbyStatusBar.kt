// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.presentation.main

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.unuslumen.app.data.metadata.DeviceStatus
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.hardShadow
import com.unuslumen.app.ui.theme.posterStyle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * The top bar: the status line of the machine GURU is running on.
 *
 * WHY THIS IS NOT A HEADER. A header says where you are. This says what the phone is
 * doing, because the lobby is a dashboard into the device as well as the front door of the
 * app, and those two facts belong on the same line at the top.
 *
 * FIVE THINGS, IN THE ORDER THEY EARN THE SPACE:
 *
 * 1. WHETHER GURU IS AWAKE. The single most important fact on the screen, because a phone
 *    with the chain off is a different machine from one with it on. It is drawn as a
 *    filled block when awake and an outlined one when not, so it reads before any word.
 * 2. THE BATTERY, with how long is left when the system will say. It goes coral under
 *    twenty percent and pulses in the type weight rather than in animation, because
 *    nothing on this page moves.
 * 3. THE NETWORK, in the same vocabulary the metadata block uses, so what a person sees is
 *    the fact GURU is being told rather than a second opinion about it.
 * 4. WHERE THE PHONE IS, at the precision GURU actually has, with the age of the reading.
 * 5. THE CLOCK, because a dashboard is a thing you glance at.
 *
 * THE HOLDING COUNT IS NOT HERE. It belongs beside the rooms it counts, in the band above
 * the grid, so the number and the thing it measures are never two screens apart.
 */
@Composable
fun LobbyStatusBar(
    reading: DeviceStatus.Reading?,
    gurusAwake: Boolean,
    modifier: Modifier = Modifier,
) {
    val clock = SimpleDateFormat("HH:mm", Locale.UK).format(Date())

    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // ONE: awake or asleep. The block is the word, so there is nothing to read.
            StatusBlock(
                text = if (gurusAwake) "AWAKE" else "ASLEEP",
                fill = if (gurusAwake) Memphis.Teal else Memphis.Paper,
                ink = if (gurusAwake) Memphis.Paper else Memphis.Ink.copy(alpha = 0.55f),
            )

            // TWO: the battery. Coral when it is a problem, ink otherwise.
            BatteryBlock(reading)

            // THREE: the network.
            if (reading != null) {
                StatusBlock(
                    text = reading.network.uppercase(),
                    fill = Memphis.Paper,
                    ink = Memphis.Ink.copy(alpha = 0.7f),
                )
            }

            Spacer(Modifier.weight(1f))

            // FIVE: the clock, at poster size because it is the thing the eye lands on.
            Text(
                text = clock,
                style = posterStyle(20f),
            )
        }

        // FOUR: the place, on its own line because a town name is long and the row above
        // is already carrying four blocks.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 7.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "LOCATION",
                style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.45f)),
            )
            Spacer(Modifier.width(7.dp))
            // Read into locals first: `reading` is a nullable parameter and its fields are
            // not smart-cast across the safe calls in a `when` branch.
            val place = reading?.place
            val placeAge = reading?.placeAgeMinutes
            Text(
                text = when {
                    place == null -> "not resolved yet"
                    placeAge == null -> place.uppercase()
                    else -> "${place.uppercase()} · ${ageLabel(placeAge)}"
                },
                style = captionStyle(8.5f, Memphis.Ink.copy(alpha = if (place == null) 0.4f else 0.75f)),
            )
        }
    }
}

/**
 * The battery block.
 *
 * IT SAYS THE MINUTES WHEN THE SYSTEM WILL SAY THEM. "18% · 40m" is the fact that makes
 * somebody plug in; "18%" alone is the fact that makes them keep scrolling. When the
 * forecast is unavailable the block shows the level alone rather than a duration nobody
 * measured.
 *
 * IT GOES CORAL UNDER TWENTY and ink under that going paler, so the warning is a colour
 * the page already uses for a problem rather than a new one invented here.
 */
@Composable
private fun BatteryBlock(reading: DeviceStatus.Reading?) {
    if (reading == null || reading.levelPercent < 0) {
        // The battery could not be read at all. Say so rather than printing a zero, which
        // would be a measurement nobody made.
        StatusBlock(
            text = "BATTERY UNKNOWN",
            fill = Memphis.Paper,
            ink = Memphis.Ink.copy(alpha = 0.4f),
        )
        return
    }

    val minutes = DeviceStatus.minutesLabel(reading.minutesLeft)
    val text = buildString {
        append(reading.levelPercent)
        append("%")
        if (reading.charging) append(" · CHARGING")
        if (!reading.charging && minutes != null) {
            append(" · ")
            append(minutes)
        }
    }

    val fill = when {
        reading.isEmergency -> Memphis.Coral
        reading.isCritical -> Memphis.Coral
        reading.isLow -> Memphis.Sun
        reading.charging -> Memphis.Teal
        else -> Memphis.Paper
    }
    val ink = when {
        reading.isCritical || reading.charging -> Memphis.Paper
        else -> Memphis.Ink
    }

    StatusBlock(text = text, fill = fill, ink = ink)
}

/** One small printed block in the status row. */
@Composable
private fun StatusBlock(text: String, fill: Color, ink: Color) {
    Box(
        Modifier
            .height(24.dp)
            .hardShadow(offset = 2.dp)
            .background(fill)
            .padding(horizontal = 7.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(text = text, style = captionStyle(8f, ink))
    }
}

/**
 * "2 min", "1 h", "3 d": the age of a reading, short enough for a status line.
 *
 * Its whole job is to stop a stale reading from looking like a live one. A place name with
 * no age reads as "you are here now", which is a claim nobody made.
 */
private fun ageLabel(minutes: Long): String = when {
    minutes < 1 -> "just now"
    minutes < 60 -> "${minutes}m ago"
    minutes < 60 * 24 -> "${minutes / 60}h ago"
    else -> "${minutes / (60 * 24)}d ago"
}
