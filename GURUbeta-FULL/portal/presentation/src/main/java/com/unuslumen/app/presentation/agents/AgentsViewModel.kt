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
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val notice: String? = null,
    /**
     * Whether the "what is an agent" panel is open right now.
     *
     * Deliberately separate from the stored "has been read" flag. This is what is
     * on screen; the flag is whether it should ever open by itself again. A person
     * can reopen it from the room at any time without the stored flag mattering.
     */
    val showIntro: Boolean = false
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
    /** Open the intro panel by hand, for somebody who wants to read it again. */
    data object ShowIntro : AgentsEvent
    /** Close it, and remember that it has been read so it stops opening by itself. */
    data object DismissIntro : AgentsEvent
}

@KoinViewModel
class AgentsViewModel(
    private val agentRepository: GuruAgentRepository,
    private val agentCatalogue: AgentCatalogue,
    /**
     * The app's own preference lane, which is how every other screen remembers a
     * one-time thing. The intro flag is one of those, so it uses that lane rather
     * than inventing storage inside the agent table.
     */
    private val getPreference: GetPreferenceUseCase,
    private val savePreference: SavePreferenceUseCase
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
            AgentsEvent.ShowIntro -> _state.update { it.copy(showIntro = true) }
            AgentsEvent.DismissIntro -> {
                _state.update { it.copy(showIntro = false) }
                viewModelScope.launch {
                    // Recording the read is what stops it opening by itself. It is
                    // written on dismiss rather than on open, so a person who sees
                    // the panel and kills the app before reading it still gets it.
                    runCatching {
                        savePreference(booleanPreferencesKey(PrefsConstants.AGENTS_INTRO_SEEN_KEY), true)
                    }
                }
            }
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

                // THE PANEL OPENS ITSELF ONCE, AND ONLY ONCE.
                //
                // A fresh install has no flag, so the explanation appears the first
                // time the room is opened. After the person dismisses it the flag is
                // set and it never interrupts again; they can still reopen it from the
                // room whenever they want the reminder.
                //
                // A read failure is treated as "not seen", because showing the
                // explanation twice is a small annoyance and hiding it from a first-time
                // user is a person staring at a screen with no idea what it is for.
                val seenIntro = runCatching {
                    getPreference(booleanPreferencesKey(PrefsConstants.AGENTS_INTRO_SEEN_KEY), false).first()
                }.getOrDefault(false)

                _state.update {
                    it.copy(
                        isLoading = false,
                        agents = agents,
                        summary = summary,
                        availableTools = tools,
                        showIntro = !seenIntro && agents.isEmpty()
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
