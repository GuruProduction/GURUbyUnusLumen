// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.model.NoteFolder

sealed class NoteDetailsEvent {
    data class DeleteNote(val note: Note) : NoteDetailsEvent()
    data object ToggleReadingMode : NoteDetailsEvent()
    data class Summarize(val content: String): NoteDetailsEvent(), AiAction
    data class AutoFormat(val content: String): NoteDetailsEvent(), AiAction
    data class CorrectSpelling(val content: String): NoteDetailsEvent(), AiAction
    data object AiResultHandled: NoteDetailsEvent()
    data object ScreenOnStop: NoteDetailsEvent()
    data class UpdateTitle(val title: String): NoteDetailsEvent()
    data class UpdateContent(val content: String): NoteDetailsEvent()
    data class UpdateFolder(val folder: NoteFolder?): NoteDetailsEvent()
    data class UpdatePinned(val pinned: Boolean): NoteDetailsEvent()
}

sealed interface AiAction