package com.unuslumen.app.presentation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.domain.use_case.CreateProjectUseCase
import com.unuslumen.app.domain.use_case.DeleteProjectUseCase
import com.unuslumen.app.domain.use_case.GetAllProjectsUseCase
import com.unuslumen.app.domain.use_case.GetProjectUseCase
import com.unuslumen.app.domain.use_case.SearchProjectsUseCase
import com.unuslumen.app.domain.use_case.UpdateProjectUseCase
import com.unuslumen.app.domain.repository.ProjectRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

@KoinViewModel
class ProjectsViewModel(
    private val createProject: CreateProjectUseCase,
    private val getAllProjects: GetAllProjectsUseCase,
    private val searchProjects: SearchProjectsUseCase,
    private val updateProject: UpdateProjectUseCase,
    private val deleteProject: DeleteProjectUseCase,
    private val getProject: GetProjectUseCase,
    private val projectRepository: ProjectRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    init {
        loadProjects()
    }

    private fun loadProjects() {
        viewModelScope.launch {
            getAllProjects().collectLatest { projects ->
                _uiState.value = _uiState.value.copy(projects = projects)
            }
        }
    }

    fun onEvent(event: ProjectsEvent) {
        when (event) {
            is ProjectsEvent.CreateProject -> viewModelScope.launch {
                val project = Project(
                    id = kotlin.uuid.Uuid.random().toString(),
                    title = event.title,
                    description = event.description,
                    promptOverlay = event.promptOverlay,
                    color = event.color,
                    icon = event.icon,
                    createdDate = System.currentTimeMillis(),
                    updatedDate = System.currentTimeMillis()
                )
                createProject(project)
            }

            is ProjectsEvent.UpdateProject -> viewModelScope.launch {
                updateProject(event.project.copy(updatedDate = System.currentTimeMillis()))
            }

            is ProjectsEvent.DeleteProject -> viewModelScope.launch {
                deleteProject(event.projectId)
            }

            is ProjectsEvent.SearchProjects -> viewModelScope.launch {
                val results = searchProjects(event.query)
                _uiState.value = _uiState.value.copy(searchResults = results)
            }

            is ProjectsEvent.SelectProject -> viewModelScope.launch {
                val project = getProject(event.projectId)
                _uiState.value = _uiState.value.copy(selectedProject = project)
            }

            ProjectsEvent.ClearSearch -> {
                _uiState.value = _uiState.value.copy(searchResults = emptyList())
            }

            ProjectsEvent.ErrorDisplayed -> {
                _uiState.value = _uiState.value.copy(error = null)
            }
        }
    }

    data class UiState(
        val projects: List<Project> = emptyList(),
        val selectedProject: Project? = null,
        val searchResults: List<Project> = emptyList(),
        val error: String? = null,
        val isLoading: Boolean = false
    )
}

sealed class ProjectsEvent {
    data class CreateProject(
        val title: String,
        val description: String = "",
        val promptOverlay: String = "",
        val color: String = "#6366f1",
        val icon: String = "folder"
    ) : ProjectsEvent()

    data class UpdateProject(val project: Project) : ProjectsEvent()
    data class DeleteProject(val projectId: String) : ProjectsEvent()
    data class SearchProjects(val query: String) : ProjectsEvent()
    data class SelectProject(val projectId: String) : ProjectsEvent()
    object ClearSearch : ProjectsEvent()
    object ErrorDisplayed : ProjectsEvent()
}