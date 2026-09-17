package com.unuslumen.app.presentation

import com.unuslumen.app.preferences.domain.model.Order

sealed class JournalEvent {
    data class SearchEntries(val query: String) : JournalEvent()
    data class UpdateOrder(val order: Order) : JournalEvent()
    data class ChangeChartEntriesRange(val monthly: Boolean) : JournalEvent()
}