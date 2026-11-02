// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.model.AgentSlug
import com.unuslumen.app.domain.model.AgentSummary
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.model.SaveAgentRequest
import com.unuslumen.app.domain.repository.AgentCatalogue
import com.unuslumen.app.domain.repository.GrantableTool
import com.unuslumen.app.domain.repository.GuruAgentRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

data class AgentsState(
    val isLoading: Boolean = true,
    val agents: List<GuruAgent> = emptyList(),
    val summary: AgentSummary? = null,
    val error: String? = null,
    /** Non-null while the creator form is open. Null id means creating. */
    val editing: EditableAgent? = null,
    /** The tools an agent may be granted, loaded once with the list. */
    val availableTools: List<GrantableTool> = emptyList(),
    val saving: Boolean = false,
    val notice: String? = null
)

/**
 * The creator form's own copy of a definition. Kept out of GuruAgent because a
 * half-typed agent is not a stored agent and must never be confused for one.
 */
data class EditableAgent(
    val id: String? = null,
    val name: String = "",
    val description: String = "",
    val systemPrompt: String = "",
    val toolPermissions: Set<String> = emptySet(),
    val source: String = GuruAgent.SOURCE_USER
)

sealed interface AgentsEvent {
    data object Load : AgentsEvent
    data object StartCreate : AgentsEvent
    data class StartEdit(val agent: GuruAgent) : AgentsEvent
    data object CancelEdit : AgentsEvent
    data class UpdateDraft(val draft: EditableAgent) : AgentsEvent
    data class ToggleTool(val toolName: String, val selected: Boolean) : AgentsEvent
    data object Save : AgentsEvent
    data class ToggleEnabled(val id: String, val enabled: Boolean) : AgentsEvent
    data class Delete(val agent: GuruAgent) : AgentsEvent
    data class Publish(val agent: GuruAgent) : AgentsEvent
    data object ClearNotice : AgentsEvent
}

@KoinViewModel
class AgentsViewModel(
    private val agentRepository: GuruAgentRepository,
    private val agentCatalogue: AgentCatalogue
) : ViewModel() {

    private val _state = MutableStateFlow(AgentsState())
    val state: StateFlow<AgentsState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: AgentsEvent) {
        when (event) {
            AgentsEvent.Load -> load()
            AgentsEvent.StartCreate -> _state.update {
                it.copy(editing = EditableAgent(), error = null, notice = null)
            }
            is AgentsEvent.StartEdit -> _state.update {
                it.copy(
                    editing = EditableAgent(
                        id = event.agent.id,
                        name = event.agent.name,
                        description = event.agent.description,
                        systemPrompt = event.agent.systemPrompt,
                        toolPermissions = event.agent.toolPermissions.toSet(),
                        source = event.agent.source
                    ),
                    error = null,
                    notice = null
                )
            }
            AgentsEvent.CancelEdit -> _state.update { it.copy(editing = null, error = null) }
            is AgentsEvent.UpdateDraft -> _state.update {
                it.copy(editing = event.draft)
            }
            is AgentsEvent.ToggleTool -> _state.update { current ->
                val draft = current.editing ?: return@update current
                val next = if (event.selected) {
                    draft.toolPermissions + event.toolName
                } else {
                    draft.toolPermissions - event.toolName
                }
                current.copy(editing = draft.copy(toolPermissions = next))
            }
            AgentsEvent.Save -> save()
            is AgentsEvent.ToggleEnabled -> toggle(event.id, event.enabled)
            is AgentsEvent.Delete -> delete(event.agent)
            is AgentsEvent.Publish -> publish(event.agent)
            AgentsEvent.ClearNotice -> _state.update { it.copy(notice = null) }
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val agents = agentRepository.getAllAgents()
                val summary = agentRepository.getSummary()
                // Read once here rather than on every keystroke in the creator:
                // the registry does not change while a form is open.
                val tools = runCatching { agentRepository.grantableTools() }.getOrDefault(emptyList())
                _state.update {
                    it.copy(
                        isLoading = false,
                        agents = agents,
                        summary = summary,
                        availableTools = tools
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Could not load your agents")
                }
            }
        }
    }

    private fun save() {
        val draft = _state.value.editing ?: return
        if (draft.name.isBlank()) {
            _state.update { it.copy(error = "Give the agent a name.") }
            return
        }
        if (draft.systemPrompt.isBlank()) {
            _state.update { it.copy(error = "The agent needs instructions, otherwise it has nothing to be.") }
            return
        }

        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            try {
                val request = SaveAgentRequest(
                    name = draft.name,
                    description = draft.description,
                    systemPrompt = draft.systemPrompt,
                    toolPermissions = draft.toolPermissions.toList().sorted()
                )
                if (draft.id == null) {
                    agentRepository.createAgent(request)
                } else {
                    agentRepository.updateAgent(draft.id, request)
                }
                val agents = agentRepository.getAllAgents()
                _state.update {
                    it.copy(
                        saving = false,
                        editing = null,
                        agents = agents,
                        summary = agentRepository.getSummary(),
                        notice = if (draft.id == null) "Agent saved." else "Agent updated."
                    )
                }
            } catch (e: Exception) {
                _state.update { it.copy(saving = false, error = e.message ?: "Could not save the agent") }
            }
        }
    }

    private fun toggle(id: String, enabled: Boolean) {
        viewModelScope.launch {
            try {
                if (enabled) agentRepository.enableAgent(id) else agentRepository.disableAgent(id)
                val agents = agentRepository.getAllAgents()
                _state.update {
                    it.copy(agents = agents, summary = agentRepository.getSummary())
                }
            } catch (e: Exception) {
                _state.update { it.copy(error = e.message ?: "Could not change that agent") }
            }
        }
    }

    private fun delete(agent: GuruAgent) {
        viewModelScope.launch {
            try {
                agentRepository.deleteAgent(agent.id)
                val agents = agentRepository.getAllAgents()
                _state.update {
                    it.copy(
                        agents = agents,
                        summary = agentRepository.getSummary(),
                        notice = "${agent.name} deleted."
                    )
                }
            } catch (e: Exception) {
                // The repository refuses a catalogue agent by design; that
                // sentence is written for a person, so it is shown as-is.
                _state.update { it.copy(error = e.message ?: "Could not delete that agent") }
            }
        }
    }

    private fun publish(agent: GuruAgent) {
        if (agent.source != GuruAgent.SOURCE_USER) {
            _state.update {
                it.copy(error = "Only an agent you made yourself can be published.")
            }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            // THE AGENT'S OWN SLUG, NOT A FRESH ONE DERIVED HERE.
            //
            // Issue #233: the local row and the publish path each derived their own, which
            // is how one could be empty while the other was correct. Publishing under the
            // slug the agent already carries means the identity a person sees on this phone
            // is the identity the catalogue knows it by, and a rename does not silently
            // publish a second copy under a new slug.
            //
            // The fallback covers an agent created before slugs were derived, whose stored
            // slug is empty: it is slugified on the way out rather than refused.
            val slug = agent.slug.ifBlank { AgentSlug.from(agent.name) }
            val outcome = agentCatalogue.submitForReview(
                slug = slug,
                name = agent.name,
                roleDescription = agent.description,
                systemPrompt = agent.systemPrompt,
                toolPermissions = agent.toolPermissions,
                authorLabel = null
            )
            _state.update {
                it.copy(
                    saving = false,
                    notice = if (outcome.success) outcome.message else null,
                    error = if (outcome.success) null else outcome.message
                )
            }
        }
    }

    /**
     * The slug for a name.
     *
     * DELEGATED, NOT DUPLICATED. This used to hold its own copy of the rule, and a second
     * copy is how it drifted from the one the save path used. [AgentSlug] is the single
     * definition, kept where the domain can reach it and tested on its own.
     */
    fun slugify(name: String): String = AgentSlug.from(name)
}
