package com.unuslumen.app.presentation

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.domain.model.ProjectDocument
import com.unuslumen.app.domain.model.ProjectMessage
import com.unuslumen.app.domain.repository.ProjectRepository
import com.unuslumen.app.domain.model.ProjectAgentConfig
import com.unuslumen.app.domain.model.ProjectAgentRepository
import com.unuslumen.app.domain.model.AiMessage
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@OptIn(ExperimentalUuidApi::class)
@KoinViewModel
class ProjectDetailViewModel(
    private val projectRepository: ProjectRepository,
    private val projectAgentRepository: ProjectAgentRepository
) : ViewModel() {

    private val _uiState = MutableStateFlow(ProjectDetailUiState())
    val uiState: StateFlow<ProjectDetailUiState> = _uiState.asStateFlow()

    fun loadProject(projectId: String) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            val project = projectRepository.getProject(projectId)
            if (project != null) {
                val messages = projectRepository.getMessages(projectId)
                val documents = projectRepository.getDocuments(projectId)
                
                _uiState.value = _uiState.value.copy(
                    project = project,
                    messages = messages,
                    documents = documents,
                    isLoading = false
                )
            } else {
                _uiState.value = _uiState.value.copy(
                    error = "Project not found",
                    isLoading = false
                )
            }
        }
    }

    fun sendMessage(content: String) {
        val project = _uiState.value.project ?: return
        
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            
            // Add user message
            val userMessage = ProjectMessage(
                id = Uuid.random().toString(),
                projectId = project.id,
                role = "user",
                content = content,
                timestamp = System.currentTimeMillis()
            )
            projectRepository.addMessage(userMessage)
            
            // Update UI with user message
            val currentMessages = _uiState.value.messages + userMessage
            _uiState.value = _uiState.value.copy(messages = currentMessages)
            
            try {
                // Get agent config
                val config = projectRepository.getProject(project.id)?.let {
                    ProjectAgentConfig(
                        projectId = it.id,
                        systemPromptOverlay = it.promptOverlay
                    )
                }
                
                // Send to project agent
                val aiMessages = currentMessages.map { msg ->
                    when (msg.role) {
                        "user" -> AiMessage.UserMessage(
                            uuid = msg.id,
                            content = msg.content,
                            time = msg.timestamp
                        )
                        "assistant" -> AiMessage.AssistantMessage(
                            content = msg.content,
                            time = msg.timestamp,
                            uuid = msg.id
                        )
                        else -> AiMessage.UserMessage(
                            uuid = msg.id,
                            content = msg.content,
                            time = msg.timestamp
                        )
                    }
                }
                
                projectAgentRepository.sendMessage(project.id, aiMessages, config).collect { response ->
                    when (response) {
                        is AiMessage.AssistantMessage -> {
                            // Add assistant message
                            val assistantMessage = ProjectMessage(
                                id = response.uuid,
                                projectId = project.id,
                                role = "assistant",
                                content = response.content,
                                timestamp = response.time
                            )
                            projectRepository.addMessage(assistantMessage)
                            
                            _uiState.value = _uiState.value.copy(
                                messages = _uiState.value.messages + assistantMessage,
                                isLoading = false
                            )
                        }
                        else -> {
                            // Handle other message types if needed
                        }
                    }
                }
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(
                    error = e.message ?: "Failed to send message",
                    isLoading = false
                )
            }
        }
    }

    fun addDocument(title: String, content: String) {
        val project = _uiState.value.project ?: return
        
        viewModelScope.launch {
            val document = ProjectDocument(
                id = Uuid.random().toString(),
                projectId = project.id,
                title = title,
                content = content,
                createdDate = System.currentTimeMillis(),
                updatedDate = System.currentTimeMillis()
            )
            projectRepository.addDocument(document)
            
            // Refresh documents
            val documents = projectRepository.getDocuments(project.id)
            _uiState.value = _uiState.value.copy(documents = documents)
        }
    }

    fun clearError() {
        _uiState.value = _uiState.value.copy(error = null)
    }
}

data class ProjectDetailUiState(
    val project: Project? = null,
    val messages: List<ProjectMessage> = emptyList(),
    val documents: List<ProjectDocument> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)