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
    /**
     * When an alarm last actually arrived, as epoch millis, or 0 when none has.
     *
     * THIS IS THE CHAIN'S PULSE. A booking in the future proves only that an alarm
     * was written; this proves one was delivered. Without both numbers on screen,
     * "scheduled and dead" and "scheduled and working" look the same to the person
     * holding the phone.
     */
    val lastFiredAt: Long = 0L,
    /** When the next beat is booked for, as epoch millis, or 0 when unknown. */
    val nextBookedAt: Long = 0L,
    /**
     * Why the last beat ended without reaching a model, or empty when it ran.
     *
     * The distinction this carries: a beat that fires and ends on an empty
     * conversation, a beat whose prompt is unavailable, and a beat that never fired
     * at all are three different faults that all look like "nothing happened".
     */
    val lastOutcome: String = "",
    /** True while a manual fire has been requested and not yet reported back. */
    val firing: Boolean = false,
) {
    /** A save is worth offering only when the text has actually moved. */
    val canSave: Boolean get() = draft.isNotBlank()

    /** True when the chain has never been seen to deliver an alarm. */
    val neverFired: Boolean get() = lastFiredAt <= 0L
}

sealed interface HeartbeatEvent {
    data object Load : HeartbeatEvent
    data class Edit(val text: String) : HeartbeatEvent
    data object Save : HeartbeatEvent
    data object Reset : HeartbeatEvent
    data object ClearNotice : HeartbeatEvent
    /** Fire one beat now, so a dead chain can be told apart from a dead beat. */
    data object FireNow : HeartbeatEvent
    /** Re-read the chain's own state, after a fire or when the room is reopened. */
    data object RefreshChain : HeartbeatEvent
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
    /**
     * The chain itself, so the room can report whether it is alive.
     *
     * Passed as a narrow callback rather than the scheduler object, because the
     * scheduler is an Android AlarmManager authority and a view model has no business
     * holding one. This is only ever "fire once, now".
     */
    private val fireNow: () -> Boolean = { false },
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
            HeartbeatEvent.FireNow -> fireOneNow()
            HeartbeatEvent.RefreshChain -> refreshChain()
        }
    }

    /**
     * Read the chain's own pulse.
     *
     * Called on load and after a manual fire. Both numbers matter and neither is
     * enough alone: a next booking with no last fire is a chain that has been written
     * but never delivered, which is the exact state a dead heartbeat sits in.
     */
    private fun refreshChain() {
        viewModelScope.launch {
            val fired = runCatching {
                getPreference(
                    stringPreferencesKey(PrefsConstants.HEARTBEAT_LAST_FIRED_AT_KEY), ""
                ).first().toLongOrNull() ?: 0L
            }.getOrDefault(0L)

            val booked = runCatching {
                getPreference(
                    stringPreferencesKey(PrefsConstants.HEARTBEAT_NEXT_BOOKED_AT_KEY), ""
                ).first().toLongOrNull() ?: 0L
            }.getOrDefault(0L)

            val outcome = runCatching {
                getPreference(
                    stringPreferencesKey(PrefsConstants.HEARTBEAT_LAST_OUTCOME_KEY), ""
                ).first()
            }.getOrDefault("")

            _state.update {
                it.copy(
                    lastFiredAt = fired,
                    nextBookedAt = booked,
                    lastOutcome = outcome,
                )
            }
        }
    }

    /**
     * Fire one beat by hand.
     *
     * THE DIAGNOSTIC THIS ANSWERS. If nothing has fired for hours there are two
     * possibilities with the same symptom: the alarm chain is dead, or the beat itself
     * dies when it runs. Pressing this goes straight to the run service, past the
     * alarm entirely, so a beat that works here and nowhere else names the chain as
     * the fault and clears the beat itself.
     */
    private fun fireOneNow() {
        _state.update { it.copy(firing = true, notice = null, error = null) }
        val accepted = runCatching { fireNow() }.getOrDefault(false)
        if (!accepted) {
            _state.update {
                it.copy(
                    firing = false,
                    error = "Android refused to start a beat from here. The reason is recorded " +
                        "in the chain state below.",
                )
            }
            refreshChain()
            return
        }
        _state.update {
            it.copy(
                firing = false,
                notice = "A beat was started by hand. Open the portal in a moment to see what " +
                    "GURU said, and the chain state below will report how it ended.",
            )
        }
        refreshChain()
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
                refreshChain()
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
