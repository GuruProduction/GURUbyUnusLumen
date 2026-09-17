package com.unuslumen.app.presentation

import com.unuslumen.app.domain.model.JournalEntry

sealed class JournalDetailsEvent {
    data object DeleteEntry : JournalDetailsEvent()
    data object ToggleReadingMode : JournalDetailsEvent()
    data class ScreenOnStop(val currentEntry: JournalEntry) : JournalDetailsEvent()
}