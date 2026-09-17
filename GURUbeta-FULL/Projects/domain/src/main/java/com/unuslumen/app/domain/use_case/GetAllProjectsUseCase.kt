package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.Flow

class GetAllProjectsUseCase(
    private val repository: ProjectRepository
) {
    operator fun invoke(): Flow<List<Project>> {
        return repository.getAllProjects()
    }
}