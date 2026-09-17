package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.domain.repository.ProjectRepository

class GetProjectUseCase(
    private val repository: ProjectRepository
) {
    suspend operator fun invoke(projectId: String): Project? {
        return repository.getProject(projectId)
    }
}