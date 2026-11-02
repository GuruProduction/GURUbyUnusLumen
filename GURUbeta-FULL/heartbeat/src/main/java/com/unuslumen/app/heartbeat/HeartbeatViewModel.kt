// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.domain.repository.HeartbeatPromptRepository
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import org.koin.android.annotation.KoinViewModel

data class HeartbeatState(
    val isLoading: Boolean = true,
    /** What is in the editor right now. */
    val draft: String = "",
    /** What a beat would inject at this moment, tokens substituted. */
    val preview: String = "",
    /** True when the owner has saved a prompt, false when the shipped one is in force. */
    val edited: Boolean = false,
    val saving: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    /** A save is worth offering only when the text has actually moved. */
    val canSave: Boolean get() = draft.isNotBlank()
}

sealed interface HeartbeatEvent {
    data object Load : HeartbeatEvent
    data class Edit(val text: String) : HeartbeatEvent
    data object Save : HeartbeatEvent
    data object Reset : HeartbeatEvent
    data object ClearNotice : HeartbeatEvent
}

/**
 * The heartbeat room.
 *
 * WHAT THIS ROOM IS FOR. The beat fires every thirty minutes and injects a prompt
 * into the newest conversation. That prompt used to be a compiled asset, so what
 * GURU said to itself could only change with a release. This is where its owner
 * reads it, edits it, and sees exactly what will be injected.
 *
 * THE PREVIEW IS THE POINT. A prompt with tokens in it is not what the model sees,
 * so the editor shows both: the text as written, and the text after substitution.
 * A preview that substituted tokens its own way would be a second contract that
 * could disagree with the real one, so it calls the same function the beat does.
 */
@KoinViewModel
class HeartbeatViewModel(
    private val promptRepository: HeartbeatPromptRepository,
    private val getPreference: GetPreferenceUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(HeartbeatState())
    val state: StateFlow<HeartbeatState> = _state.asStateFlow()

    init {
        load()
    }

    fun onEvent(event: HeartbeatEvent) {
        when (event) {
            HeartbeatEvent.Load -> load()
            is HeartbeatEvent.Edit -> _state.update { current ->
                current.copy(draft = event.text, preview = previewFor(event.text))
            }
            HeartbeatEvent.Save -> save()
            HeartbeatEvent.Reset -> reset()
            HeartbeatEvent.ClearNotice -> _state.update { it.copy(notice = null, error = null) }
        }
    }

    /**
     * The owner's name, read once and kept.
     *
     * CACHED BECAUSE THE PREVIEW RUNS ON EVERY KEYSTROKE. A preference read per
     * character would put a datastore round trip in the typing path, and the name does
     * not change while this room is open. The beat reads the same key at beat time, so
     * the preview and the real injection agree on who is being addressed.
     */
    private var humanName: String = ""

    private fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                humanName = runCatching {
                    getPreference(stringPreferencesKey(PrefsConstants.USER_NAME_KEY), "").first()
                }.getOrDefault("")

                val current = promptRepository.current()
                val edited = promptRepository.isEdited()
                _state.update {
                    it.copy(
                        isLoading = false,
                        draft = current,
                        preview = previewFor(current),
                        edited = edited,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(isLoading = false, error = e.message ?: "Could not read the heartbeat prompt")
                }
            }
        }
    }

    private fun save() {
        val draft = _state.value.draft
        if (draft.isBlank()) {
            _state.update {
                it.copy(error = "The prompt cannot be empty: a beat would inject nothing.")
            }
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val result = promptRepository.save(draft)
            _state.update { current ->
                result.fold(
                    onSuccess = {
                        current.copy(
                            saving = false,
                            edited = true,
                            preview = previewFor(draft),
                            notice = "Saved. The next beat uses this.",
                        )
                    },
                    onFailure = { failure ->
                        current.copy(
                            saving = false,
                            error = failure.message ?: "Could not save the prompt",
                        )
                    },
                )
            }
        }
    }

    private fun reset() {
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            promptRepository.reset()
            // Re-read rather than assume: after a reset the answer comes from the
            // bundled asset, and only the repository knows what that currently says.
            val restored = promptRepository.current()
            _state.update {
                it.copy(
                    saving = false,
                    draft = restored,
                    preview = previewFor(restored),
                    edited = false,
                    notice = "Back to the shipped prompt.",
                )
            }
        }
    }

    /**
     * What a beat would inject for a given body of text, right now.
     *
     * THE SAME FUNCTION THE BEAT CALLS. A preview with its own substitution rules
     * would be a second contract, and the two would drift the first time a token was
     * added. This is why [HeartbeatPrompts.substitute] is public.
     */
    private fun previewFor(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            com.unuslumen.app.data.heartbeat.HeartbeatPrompts.substitute(raw, humanName)
        }.getOrDefault(raw)
    }
}
