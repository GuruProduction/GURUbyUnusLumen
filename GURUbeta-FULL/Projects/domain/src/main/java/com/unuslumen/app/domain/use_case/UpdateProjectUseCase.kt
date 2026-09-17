package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.domain.repository.ProjectRepository

class UpdateProjectUseCase(
    private val repository: ProjectRepository
) {
    suspend operator fun invoke(project: Project) {
        repository.updateProject(project)
    }
}