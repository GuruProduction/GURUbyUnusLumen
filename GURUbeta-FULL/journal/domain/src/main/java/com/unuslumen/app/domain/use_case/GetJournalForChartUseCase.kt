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