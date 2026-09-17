package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.NoteRepository
import org.koin.core.annotation.Factory

@Factory
class GetAllNoteFoldersUseCase(
    private val repository: NoteRepository
) {
    operator fun invoke() = repository.getAllNoteFolders()
}