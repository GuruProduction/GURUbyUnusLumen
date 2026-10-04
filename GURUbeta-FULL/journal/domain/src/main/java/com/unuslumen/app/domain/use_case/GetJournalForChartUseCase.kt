// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.JournalRepository
import com.unuslumen.app.domain.model.JournalEntry
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Named
import org.koin.core.annotation.Single

@Single
class GetJournalForChartUseCase(
    private val journalRepository: JournalRepository,
    @Named("defaultDispatcher") private val defaultDispatcher: CoroutineDispatcher

) {
    suspend operator fun invoke(filterSelector: (JournalEntry) -> Boolean) : List<JournalEntry>{
        return withContext(defaultDispatcher) {
            journalRepository
                .getAllEntries()
                .first()
                .filter(filterSelector)
                .sortedBy { it.createdDate }
        }
    }
}