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
    /** True when a prompt has been saved. False means there is no prompt at all. */
    val edited: Boolean = false,
    val saving: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
    /**
     * Whether the chain is switched on.
     *
     * THE OWNER'S SWITCH. Off means no alarm is booked and nothing fires. On means
     * the thirty-minute chain runs exactly as it always has.
     *
     * IT CANNOT BE ON WITHOUT A PROMPT. A prompt is a precondition, not a follow-up
     * warning: the switch is refused until one exists, and nothing switches it off
     * behind the owner's back either.
     */
    val enabled: Boolean = false,
    /** True while the switch is being written, so it cannot be double-tapped. */
    val toggling: Boolean = false,
    /**
     * True while the prompt-to-write panel is showing.
     *
     * THE SWITCH DID NOT MOVE. Pressing it with no prompt raises this panel, which
     * states that a heartbeat master prompt has to be written, and it is DISMISSIBLE:
     * the owner can read it, close it, and write the prompt when he chooses. A
     * permanent bar over the page was the wrong shape for exactly that reason.
     */
    val promptRequired: Boolean = false,
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
     * conversation, a beat with no prompt to send, and a beat that never fired at all
     * are three different faults that all look like "nothing happened".
     */
    val lastOutcome: String = "",
    /** True while a manual fire has been requested and not yet reported back. */
    val firing: Boolean = false,
    /**
     * When a manual fire successfully handed a beat to the portal, as epoch millis.
     *
     * The screen watches this and navigates. A timestamp rather than a boolean because
     * it changes on every fire, so two fires in a row each get their own navigation
     * instead of the second one finding the flag already true and doing nothing.
     */
    val firedAt: Long = 0L,
) {
    /** A save is worth offering only when there is text to save. */
    val canSave: Boolean get() = draft.isNotBlank()

    /** True when the chain has never been seen to deliver an alarm. */
    val neverFired: Boolean get() = lastFiredAt <= 0L

    /**
     * Whether the switch can be turned on right now.
     *
     * A PROMPT IS REQUIRED. This is the owner's rule, stated plainly: turning the
     * heartbeat on forces a prompt, because a chain with nothing to send is not a
     * chain. The switch is refused rather than moved and warned about.
     */
    val canEnable: Boolean get() = draft.isNotBlank() && !toggling
}

sealed interface HeartbeatEvent {
    data object Load : HeartbeatEvent
    data class Edit(val text: String) : HeartbeatEvent
    data object Save : HeartbeatEvent
    data object Reset : HeartbeatEvent
    data object ClearNotice : HeartbeatEvent
    /**
     * Turn the chain on or off.
     *
     * Off always works. On is refused with [HeartbeatState.promptRequired] when there
     * is no prompt, because a prompt is what a beat sends.
     */
    data class SetEnabled(val enabled: Boolean) : HeartbeatEvent
    /** Close the prompt-to-write panel without writing anything. */
    data object DismissPromptRequired : HeartbeatEvent
    /** Fire one beat now, so a dead chain can be told apart from a dead beat. */
    data object FireNow : HeartbeatEvent
    /** Re-read the chain's own state, after a fire or when the room is reopened. */
    data object RefreshChain : HeartbeatEvent
}

/**
 * The heartbeat room.
 *
 * WHAT THIS ROOM IS FOR. The beat fires every thirty minutes and injects a prompt
 * into the newest conversation. That prompt is the owner's, written here and stored
 * in the database; there is no shipped default any more.
 *
 * THE TWO CONTROLS ARE ONE RULE. The switch decides whether the chain runs, the prompt
 * is what a beat sends, and the switch cannot be on without a prompt. Turning it on
 * with nothing written is refused and sends the owner to the editor, so "on" always
 * means a chain that can actually run.
 *
 * THE PREVIEW IS THE POINT. A prompt with tokens in it is not what the model sees, so
 * the editor shows both: the text as written, and the text after substitution. The
 * preview calls the same function the beat calls, or it would be a second contract
 * that could disagree with the real one.
 */
@KoinViewModel
class HeartbeatViewModel(
    private val promptRepository: HeartbeatPromptRepository,
    private val getPreference: GetPreferenceUseCase,
    /**
     * The chain itself, so the room can report whether it is alive.
     *
     * Passed as narrow callbacks rather than the scheduler object, because the
     * scheduler is an Android AlarmManager authority and a view model has no business
     * holding one. These are only ever "read the switch", "set the switch and book or
     * cancel accordingly", and "fire once, now".
     */
    private val isEnabledNow: () -> Boolean = { false },
    private val applyEnabled: (Boolean) -> Unit = {},
    /**
     * Fire a beat, in the open, where its owner can see it.
     *
     * WHAT THIS IS AND IS NOT. It takes the fully substituted prompt, hands it to the
     * portal's own pending-prompt holder and opens the portal, so the beat arrives as a
     * REAL TURN in the chat: the same send path, the same streaming, the same tool calls
     * and the same reply a human gets when they type something.
     *
     * A background service start produced a beat nobody could see, which is exactly why
     * the button looked broken.
     *
     * The scheduled thirty-minute chain is untouched and still runs headless through
     * HeartbeatRunService. This is the button, not the clock.
     *
     * Returns false when the portal could not be opened, so the room can say so instead
     * of pretending a beat happened.
     */
    private val fireNow: (String) -> Boolean = { false },
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
                current.copy(
                    draft = event.text,
                    preview = previewFor(event.text),
                    // Writing a prompt answers the refusal, so the panel goes away.
                    promptRequired = if (event.text.isNotBlank()) false else current.promptRequired,
                )
            }
            HeartbeatEvent.Save -> save()
            HeartbeatEvent.Reset -> reset()
            // Dismissing the panel only closes it. Nothing is written, and the switch
            // stays exactly where it was, because a dismissal is not a decision.
            HeartbeatEvent.DismissPromptRequired -> _state.update {
                it.copy(promptRequired = false, error = null)
            }
            HeartbeatEvent.ClearNotice -> _state.update { it.copy(notice = null, error = null) }
            is HeartbeatEvent.SetEnabled -> setEnabled(event.enabled)
            HeartbeatEvent.FireNow -> fireOneNow()
            HeartbeatEvent.RefreshChain -> refreshChain()
        }
    }

    /**
     * Turn the chain on or off.
     *
     * ON REQUIRES A PROMPT. If there is none the switch does not move, nothing is
     * booked, and the room says what to do about it. That is the owner's rule and it is
     * a precondition rather than a warning: a switch that reads "on" beside a chain
     * with nothing to send is a lie on screen.
     *
     * OFF ALWAYS WORKS. Turning it off is never refused and nothing but the owner ever
     * turns it off.
     */
    private fun setEnabled(enabled: Boolean) {
        if (_state.value.toggling) return

        if (enabled && _state.value.draft.isBlank()) {
            // Refused, and nothing is written: the switch stays exactly where it was.
            _state.update {
                it.copy(
                    promptRequired = true,
                    notice = null,
                    error = "The heartbeat needs a prompt before it can be switched on. " +
                        "Write one below, save it, and then turn it on.",
                )
            }
            return
        }

        _state.update { it.copy(toggling = true, notice = null, error = null, promptRequired = false) }

        val applied = runCatching { applyEnabled(enabled) }
        if (applied.isFailure) {
            _state.update {
                it.copy(
                    toggling = false,
                    error = "The switch could not be saved: ${applied.exceptionOrNull()?.message}",
                )
            }
            return
        }

        _state.update {
            it.copy(
                toggling = false,
                enabled = enabled,
                notice = when {
                    enabled -> "The heartbeat is on. It will fire every thirty minutes."
                    else -> "The heartbeat is off. Nothing is scheduled and nothing will fire."
                },
            )
        }
        refreshChain()
    }

    /**
     * Read the chain's own pulse.
     *
     * Called on load and after every change. Both timestamps matter and neither is
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
     * Fire one beat by hand, IN THE CHAT.
     *
     * WHAT WAS WRONG. This started HeartbeatRunService, which sent the beat headlessly
     * into the newest conversation. The model really replied, and the log proved it,
     * but the human saw nothing: the portal had already loaded its history and does not
     * re-read the database, so a beat that arrived while the chat was open was
     * invisible. A button that fires something you cannot see reads as broken, and it
     * was.
     *
     * WHAT IT DOES NOW. The substituted prompt goes to
     * `com.unuslumen.app.domain.PendingPortalPrompt` and the screen opens the portal.
     * The portal's own watcher picks it up and sends it through the real chat path, so
     * the reply streams into the canvas like any other turn, with the tool calls and the
     * live progress.
     *
     * THE SAVED PROMPT IS WHAT FIRES, not the draft. A beat injects what the database
     * holds, so firing unsaved text would show a turn the scheduled chain could never
     * repeat.
     *
     * A beat with no prompt is refused for the same reason the switch is: there would
     * be nothing to send.
     */
    private fun fireOneNow() {
        if (_state.value.draft.isBlank()) {
            _state.update {
                it.copy(
                    promptRequired = true,
                    error = "There is no prompt to send. Write one below and save it first.",
                )
            }
            return
        }
        _state.update { it.copy(firing = true, notice = null, error = null) }

        viewModelScope.launch {
            // A beat injects what the DATABASE holds, so firing unsaved text would show
            // a turn the scheduled chain could never repeat.
            val saved = runCatching { promptRepository.current() }.getOrDefault("")
            if (saved.isBlank()) {
                _state.update {
                    it.copy(
                        firing = false,
                        promptRequired = true,
                        error = "The prompt in the editor has not been saved, so there is " +
                            "nothing to fire. Save it first and the beat will use it.",
                    )
                }
                return@launch
            }

            // Exactly what a scheduled beat would send: saved text, tokens substituted.
            val injected = previewFor(saved)
            lastFiredPrompt = injected

            // The callback takes the prompt, not nothing: the whole fault was a fire that
            // produced a beat nobody could see, so the text has to travel with it.
            val accepted = runCatching { fireNow(injected) }.getOrDefault(false)
            _state.update {
                it.copy(
                    firing = false,
                    // The handover is stamped only on success, so the screen navigates to
                    // the portal only when there is something waiting there.
                    firedAt = if (accepted) System.currentTimeMillis() else it.firedAt,
                    error = if (accepted) null else "The beat could not be handed to the portal.",
                    notice = if (accepted) {
                        "Firing now. The portal is opening and the beat streams in as a normal " +
                            "turn, so you can watch it work."
                    } else {
                        "The beat was not sent."
                    },
                )
            }
        }
    }

    /**
     * The text a manual fire handed to the portal.
     *
     * Not read by the UI. It exists because the fire path is the one place a test can
     * see what a beat would actually have sent, which is the whole difference between
     * "the button did something" and "the button sent the right words".
     */
    var lastFiredPrompt: String = ""
        private set

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
                val enabled = runCatching { isEnabledNow() }.getOrDefault(false)
                _state.update {
                    it.copy(
                        isLoading = false,
                        draft = current,
                        preview = previewFor(current),
                        edited = edited,
                        enabled = enabled,
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
                            promptRequired = false,
                            notice = "Saved. The heartbeat can now be switched on.",
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

    /**
     * Clear the prompt.
     *
     * THE SWITCH IS NOT TOUCHED. The owner is the only thing that turns the chain off.
     * If clearing leaves a chain that is on with nothing to send, the beat ends and
     * records why, and the room states that the switch now needs a prompt again. That
     * is the honest sequence: the owner's switch stays where he put it, and the missing
     * prompt is visible.
     */
    private fun reset() {
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            promptRepository.reset()
            val restored = promptRepository.current()
            val enabled = _state.value.enabled
            _state.update {
                it.copy(
                    saving = false,
                    draft = restored,
                    preview = previewFor(restored),
                    edited = false,
                    promptRequired = enabled,
                    notice = if (enabled) {
                        "Cleared. The heartbeat is still on, but it has no prompt now, " +
                            "so it will not run until you write one."
                    } else {
                        "Cleared. There is no heartbeat prompt now."
                    },
                )
            }
            refreshChain()
        }
    }

    /**
     * What a beat would inject for a given body of text, right now.
     *
     * THE SAME FUNCTION THE BEAT CALLS. A preview with its own substitution rules
     * would be a second contract, and the two would drift the first time a token was
     * added.
     */
    private fun previewFor(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching {
            com.unuslumen.app.data.heartbeat.HeartbeatPrompts.substitute(raw, humanName)
        }.getOrDefault(raw)
    }
}
