// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.ui.ItemView

sealed class NoteEvent {
    data class SearchNotes(val query: String) : NoteEvent()
    data class UpdateOrder(val order: Order) : NoteEvent()
    data class UpdateView(val view: ItemView) : NoteEvent()
    data class ShowAllNotes(val showAll: Boolean) : NoteEvent()
    data class CreateFolder(val name: String): NoteEvent()
}