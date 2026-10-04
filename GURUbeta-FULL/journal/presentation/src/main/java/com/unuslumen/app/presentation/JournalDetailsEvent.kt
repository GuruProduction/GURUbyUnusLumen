// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import com.unuslumen.app.domain.model.JournalEntry

sealed class JournalDetailsEvent {
    data object DeleteEntry : JournalDetailsEvent()
    data object ToggleReadingMode : JournalDetailsEvent()
    data class ScreenOnStop(val currentEntry: JournalEntry) : JournalDetailsEvent()
}