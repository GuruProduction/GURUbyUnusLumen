package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.NoteRepository
import org.koin.core.annotation.Factory

@Factory
class GetNoteUseCase(
    private val notesRepository: NoteRepository
) {
    suspend operator fun invoke(id: String) = notesRepository.getNote(id)
}