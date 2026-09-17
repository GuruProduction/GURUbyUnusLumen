package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.NoteFolder
import com.unuslumen.app.domain.repository.NoteRepository
import org.koin.core.annotation.Factory

@Factory
class SearchNoteFoldersByNameUseCase(
    private val notesRepository: NoteRepository
) {
    suspend operator fun invoke(name: String): List<NoteFolder> = notesRepository.searchFoldersByName(name)
}
