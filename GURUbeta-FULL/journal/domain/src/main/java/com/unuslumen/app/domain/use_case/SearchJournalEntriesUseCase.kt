package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.JournalRepository
import org.koin.core.annotation.Single

@Single
class SearchJournalEntriesUseCase(
    private val repository: JournalRepository
) {
    suspend operator fun invoke(query: String) = repository.searchEntries(query)
}