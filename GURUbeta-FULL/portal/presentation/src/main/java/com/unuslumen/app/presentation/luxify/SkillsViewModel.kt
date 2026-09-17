package com.unuslumen.app.presentation.luxify

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.LuxifySkill
import com.unuslumen.app.domain.repository.LuxifyRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

data class SkillsState(
    val isLoading: Boolean = true,
    val skills: List<LuxifySkill> = emptyList(),
    val error: String? = null
)

sealed interface SkillsEvent {
    data class ToggleSkill(val id: String, val enabled: Boolean) : SkillsEvent
    data class DeleteSkill(val id: String) : SkillsEvent
    data object LoadSkills : SkillsEvent
}

@KoinViewModel
class SkillsViewModel(
    private val luxifyRepository: LuxifyRepository
) : ViewModel() {

    private val _state = MutableStateFlow(SkillsState())
    val state: StateFlow<SkillsState> = _state.asStateFlow()

    init {
        loadSkills()
    }

    fun onEvent(event: SkillsEvent) {
        when (event) {
            is SkillsEvent.ToggleSkill -> toggleSkill(event.id, event.enabled)
            is SkillsEvent.DeleteSkill -> deleteSkill(event.id)
            SkillsEvent.LoadSkills -> loadSkills()
        }
    }

    private fun loadSkills() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val skills = luxifyRepository.getAllSkills()
                _state.update { it.copy(isLoading = false, skills = skills) }
            } catch (e: Exception) {
                _state.update { it.copy(isLoading = false, error = e.message ?: "Failed to load skills") }
            }
        }
    }

    private fun toggleSkill(id: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                if (enabled) {
                    luxifyRepository.enableSkill(id)
                } else {
                    luxifyRepository.disableSkill(id)
                }
                val skills = luxifyRepository.getAllSkills()
                _state.update { it.copy(skills = skills) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to toggle skill") }
            }
        }
    }

    private fun deleteSkill(id: String) {
        viewModelScope.launch {
            try {
                luxifyRepository.deleteSkill(id)
                val skills = luxifyRepository.getAllSkills()
                _state.update { it.copy(skills = skills) }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Failed to delete skill") }
            }
        }
    }
}