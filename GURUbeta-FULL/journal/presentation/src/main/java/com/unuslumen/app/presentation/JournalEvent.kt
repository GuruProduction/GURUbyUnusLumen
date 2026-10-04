// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import com.unuslumen.app.preferences.domain.model.Order

sealed class JournalEvent {
    data class SearchEntries(val query: String) : JournalEvent()
    data class UpdateOrder(val order: Order) : JournalEvent()
    data class ChangeChartEntriesRange(val monthly: Boolean) : JournalEvent()
}