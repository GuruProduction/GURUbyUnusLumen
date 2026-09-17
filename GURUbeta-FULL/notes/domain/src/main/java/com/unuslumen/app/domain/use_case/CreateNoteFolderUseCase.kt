package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.NoteRepository
import org.koin.core.annotation.Factory

@Factory
class CreateNoteFolderUseCase(
    private val noteRepository: NoteRepository
) {
    suspend operator fun invoke(folderName: String) = noteRepository.insertNoteFolder(folderName)
}