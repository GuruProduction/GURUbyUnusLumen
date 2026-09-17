package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.NoteRepository
import org.koin.core.annotation.Factory

@Factory
class GetNoteFolderUseCase(
    private val repository: NoteRepository
) {
    suspend operator fun invoke(folderId: String) = repository.getNoteFolder(folderId)
}