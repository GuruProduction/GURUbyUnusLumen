package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.JournalRepository
import org.koin.core.annotation.Single

@Single
class GetJournalEntryUseCase(
    private val journalRepository: JournalRepository
) {
    suspend operator fun invoke(id: String) = journalRepository.getEntry(id)
}