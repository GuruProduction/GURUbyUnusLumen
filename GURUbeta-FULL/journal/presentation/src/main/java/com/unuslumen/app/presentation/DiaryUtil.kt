// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import androidx.compose.ui.graphics.Color
import com.unuslumen.app.domain.model.Mood
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.theme.*

val Mood.iconRes: Int
    get() = when (this) {
        Mood.AWESOME -> R.drawable.ic_very_happy
        Mood.GOOD -> R.drawable.ic_happy
        Mood.OKAY -> R.drawable.ic_ok_face
        Mood.BAD -> R.drawable.ic_sad
        Mood.TERRIBLE -> R.drawable.ic_very_sad
    }

val Mood.color: Color
    get() = when (this) {
        Mood.AWESOME -> Green
        Mood.GOOD -> Blue
        Mood.OKAY -> Purple
        Mood.BAD -> Orange
        Mood.TERRIBLE -> Color.Red
    }

val Mood.titleRes: Int
    get() = when (this) {
        Mood.AWESOME -> R.string.awesome
        Mood.GOOD -> R.string.good
        Mood.OKAY -> R.string.okay
        Mood.BAD -> R.string.bad
        Mood.TERRIBLE -> R.string.terrible
    }