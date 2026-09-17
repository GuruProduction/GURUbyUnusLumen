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