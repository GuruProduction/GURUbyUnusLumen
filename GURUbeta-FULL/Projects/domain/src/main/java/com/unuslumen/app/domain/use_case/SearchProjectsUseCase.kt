package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.domain.repository.ProjectRepository

class SearchProjectsUseCase(
    private val repository: ProjectRepository
) {
    suspend operator fun invoke(query: String): List<Project> {
        return repository.searchProjects(query)
    }
}