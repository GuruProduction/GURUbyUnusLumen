package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.NoteRepository
import org.koin.core.annotation.Factory

@Factory
class SearchNotesUseCase(
    private val notesRepository: NoteRepository
) {
    suspend operator fun invoke(query: String) = notesRepository.searchNotes(query)
}