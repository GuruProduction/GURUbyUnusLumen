package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.repository.NoteRepository
import org.koin.core.annotation.Factory

@Factory
class UpsertNotesUseCase(
    private val notesRepository: NoteRepository
) {
    suspend operator fun invoke(notes: List<Note>) = notesRepository.upsertNotes(notes)
}
