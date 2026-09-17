package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.NoteFolder
import com.unuslumen.app.domain.repository.NoteRepository
import org.koin.core.annotation.Factory

@Factory
class UpdateNoteFolderUseCase(
private val noteRepository: NoteRepository
) {
    suspend operator fun invoke(folder: NoteFolder) = noteRepository.updateNoteFolder(folder)
}