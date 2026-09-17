package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.Note
import com.unuslumen.app.domain.repository.NoteRepository
import org.koin.core.annotation.Factory

@Factory
class UpsertNoteUseCase(
    private val notesRepository: NoteRepository
) {
    suspend operator fun invoke(note: Note, currentFolderId: String? = null) = notesRepository.upsertNote(note, currentFolderId)
}