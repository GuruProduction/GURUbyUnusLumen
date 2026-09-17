package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.JournalRepository
import com.unuslumen.app.domain.model.JournalEntry
import org.koin.core.annotation.Single

@Single
class DeleteJournalEntryUseCase(
    private val journalRepository: JournalRepository
) {
    suspend operator fun invoke(entry: JournalEntry) = journalRepository.deleteEntry(entry)
}