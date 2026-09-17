package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.ProjectRepository

class DeleteProjectUseCase(
    private val repository: ProjectRepository
) {
    suspend operator fun invoke(projectId: String) {
        repository.deleteProject(projectId)
    }
}