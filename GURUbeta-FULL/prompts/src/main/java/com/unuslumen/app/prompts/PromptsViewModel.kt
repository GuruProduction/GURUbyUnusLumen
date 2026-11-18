// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.prompts

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.data.PromptFetcher
import com.unuslumen.app.domain.prompt.NameTokens
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.android.annotation.KoinViewModel

/**
 * What the owner can see and change about the instructions GURU runs on.
 *
 * THE OVERRIDE IS THE POINT. The assembled prompt arrives from the publisher and is
 * cached on the device. Until this room existed the owner could read it only by
 * finding the cache, and could not change it at all, which is the opposite of a
 * product whose premise is that the owner is in charge.
 *
 * THREE PIECES OF TEXT, AND THEY ARE NOT THE SAME THING.
 *
 * [published] is what the server sent, verbatim, including the name tokens. It is the
 * record of what Unus Lumen is telling every install, and it is read-only because it is
 * not the owner's to edit.
 *
 * [override] is what this owner wrote instead. When it is non-blank it replaces the
 * published text entirely, which is a real and consequential choice, so the screen
 * states the consequence rather than hiding it behind a switch.
 *
 * [preview] is what the model actually receives on the next send, tokens substituted,
 * which is the only one of the three that answers "what is it being told".
 */
data class PromptsState(
    val isLoading: Boolean = true,
    /** What the server last sent, with tokens unsubstituted. Read-only. */
    val published: String = "",
    /** The version the publisher stamped on it, or 0 when nothing is cached. */
    val publishedVersion: Int = 0,
    /** True when the cache is under 24 hours old, so the owner knows how current it is. */
    val publishedFresh: Boolean = false,
    /** What the owner wrote to replace it. Blank means the published text is in force. */
    val override: String = "",
    /** The saved override, so the screen can tell an edit from a no-op. */
    val storedOverride: String = "",
    /** What the model receives after substitution. */
    val preview: String = "",
    /** True when a saved override is in force right now. */
    val overrideActive: Boolean = false,
    val saving: Boolean = false,
    val notice: String? = null,
    val error: String? = null,
) {
    /**
     * A save is worth offering when the editor differs from what is stored.
     *
     * WRITING THE SAME TEXT AGAIN IS NOT A SAVE. A button that lights up on a keystroke
     * and then does nothing reads as broken, so the comparison is against the stored
     * value rather than against emptiness.
     */
    val canSave: Boolean get() = override.trim() != storedOverride.trim()

    /** True when there is anything at all to show. */
    val hasAnything: Boolean get() = published.isNotBlank() || override.isNotBlank()

    /** The text the model will actually be sent, published or overridden. */
    val effectiveSource: String get() = if (overrideActive) override else published
}

sealed interface PromptsEvent {
    data object Load : PromptsEvent
    /** Edit the override. Blank means "go back to the published text". */
    data class EditOverride(val text: String) : PromptsEvent
    data object Save : PromptsEvent
    /** Drop the override and return to whatever the publisher is serving. */
    data object ClearOverride : PromptsEvent
    /** Fetch a fresh copy from the publisher now, rather than waiting for the next boot. */
    data object Refresh : PromptsEvent
    data object ClearNotice : PromptsEvent
}

/**
 * The prompts room.
 *
 * WHAT THIS ROOM EXISTS TO FIX. The README told people they could read the prompts in
 * the app and write their own. Neither was true: the assembled prompt was fetched,
 * cached and used, with no viewer and no editor. This is the missing half, and the
 * README line stays because this is what makes it true.
 *
 * WHY THE OVERRIDE IS STORED SEPARATELY FROM THE CACHE. The publisher's text is
 * refreshed on every boot and would overwrite anything written into the same slot, so
 * an override kept there would vanish after a day with no warning. It lives in its own
 * preference file, which nothing else writes.
 *
 * THE PREVIEW GOES THROUGH THE SAME SUBSTITUTION THE SEND PATH USES, [NameTokens.substitute],
 * rather than a copy of the rules. A preview with its own substitution would be a
 * second contract and the two would drift the first time a token was added.
 */
@KoinViewModel
class PromptsViewModel(
    private val context: Context,
    private val getPreference: GetPreferenceUseCase,
) : ViewModel() {

    private val _state = MutableStateFlow(PromptsState())
    val state: StateFlow<PromptsState> = _state.asStateFlow()

    /**
     * The owner's names, read once per load.
     *
     * CACHED BECAUSE THE PREVIEW RUNS ON EVERY KEYSTROKE. A preference read per
     * character would put a datastore round trip in the typing path, and neither name
     * changes while this room is open.
     */
    private var humanName: String = ""
    private var guruName: String = ""

    init {
        load()
    }

    fun onEvent(event: PromptsEvent) {
        when (event) {
            PromptsEvent.Load -> load()
            is PromptsEvent.EditOverride -> _state.update { current ->
                current.copy(
                    override = event.text,
                    preview = previewFor(if (event.text.isBlank()) current.published else event.text),
                    error = null,
                )
            }
            PromptsEvent.Save -> save()
            PromptsEvent.ClearOverride -> clearOverride()
            PromptsEvent.Refresh -> refresh()
            PromptsEvent.ClearNotice -> _state.update { it.copy(notice = null, error = null) }
        }
    }

    private fun load() {
        _state.update { it.copy(isLoading = true, error = null) }
        viewModelScope.launch {
            readNames()

            val published = withContext(Dispatchers.IO) {
                runCatching { PromptFetcher.getCachedPrompt(context) }.getOrNull() ?: ""
            }
            val version = withContext(Dispatchers.IO) {
                runCatching { PromptFetcher.getCachedVersion(context) }.getOrDefault(0)
            }
            val fresh = withContext(Dispatchers.IO) {
                runCatching { PromptFetcher.hasRecentCache(context) }.getOrDefault(false)
            }
            val stored = withContext(Dispatchers.IO) { readOverride() }

            _state.update {
                it.copy(
                    isLoading = false,
                    published = published,
                    publishedVersion = version,
                    publishedFresh = fresh,
                    override = stored,
                    storedOverride = stored,
                    overrideActive = stored.isNotBlank(),
                    preview = previewFor(stored.ifBlank { published }),
                )
            }
        }
    }

    private fun save() {
        val text = _state.value.override
        if (text.isBlank()) {
            clearOverride()
            return
        }
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val wrote = withContext(Dispatchers.IO) { writeOverride(text) }
            _state.update {
                if (wrote) {
                    it.copy(
                        saving = false,
                        overrideActive = true,
                        storedOverride = text,
                        preview = previewFor(text),
                        notice = "Saved. GURU runs on your text from the next message.",
                    )
                } else {
                    it.copy(saving = false, error = "The override could not be written to this device.")
                }
            }
        }
    }

    private fun clearOverride() {
        _state.update { it.copy(saving = true, error = null) }
        viewModelScope.launch {
            val cleared = withContext(Dispatchers.IO) { writeOverride("") }
            val published = _state.value.published
            _state.update {
                if (cleared) {
                    it.copy(
                        saving = false,
                        override = "",
                        storedOverride = "",
                        overrideActive = false,
                        preview = previewFor(published),
                        notice = if (published.isBlank()) {
                            "Cleared. There is no published prompt on this device either, " +
                                "so GURU is running on nothing until it reaches the server."
                        } else {
                            "Cleared. GURU runs on the published prompt again."
                        },
                    )
                } else {
                    it.copy(saving = false, error = "The override could not be removed.")
                }
            }
        }
    }

    private fun refresh() {
        _state.update { it.copy(saving = true, notice = null, error = null) }
        viewModelScope.launch {
            val fetched = runCatching {
                withContext(Dispatchers.IO) { PromptFetcher.fetchAndCache(context) }
                true
            }.getOrDefault(false)

            if (!fetched) {
                _state.update {
                    it.copy(
                        saving = false,
                        error = "Could not reach the publisher. The copy on this device is unchanged.",
                    )
                }
                return@launch
            }

            val published = withContext(Dispatchers.IO) {
                runCatching { PromptFetcher.getCachedPrompt(context) }.getOrNull() ?: ""
            }
            val version = withContext(Dispatchers.IO) {
                runCatching { PromptFetcher.getCachedVersion(context) }.getOrDefault(0)
            }
            _state.update {
                it.copy(
                    saving = false,
                    published = published,
                    publishedVersion = version,
                    publishedFresh = true,
                    preview = previewFor(if (it.overrideActive) it.override else published),
                    notice = "Fetched version $version from the publisher.",
                )
            }
        }
    }

    /** Both names, from the same keys the Settings screen writes and the send path reads. */
    private suspend fun readNames() {
        humanName = runCatching {
            getPreference(stringPreferencesKey(PrefsConstants.USER_NAME_KEY), "").first()
        }.getOrDefault("")
        guruName = runCatching {
            getPreference(stringPreferencesKey(PrefsConstants.GURU_NAME_KEY), "").first()
        }.getOrDefault("")
    }

    /**
     * What the model receives for a given body of text.
     *
     * THE SAME FUNCTION THE SEND PATH CALLS. A preview with its own rules would be a
     * second contract, and the two would disagree the first time a token was added.
     */
    private fun previewFor(raw: String): String {
        if (raw.isBlank()) return ""
        return runCatching { NameTokens.substitute(raw, humanName, guruName) }.getOrDefault(raw)
    }

    private fun readOverride(): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString(KEY_OVERRIDE, "") ?: ""

    /**
     * Write the override, committing rather than applying.
     *
     * COMMIT, NOT APPLY, and the difference is visible to the owner. apply() returns
     * immediately and writes in the background, so a save that failed would already
     * have reported success. This is one short string on a button press, so the
     * synchronous write costs nothing and lets the room say honestly whether it landed.
     */
    private fun writeOverride(text: String): Boolean = runCatching {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_OVERRIDE, text)
            .commit()
    }.getOrDefault(false)

    companion object {
        /**
         * The override's own store.
         *
         * A SEPARATE FILE ON PURPOSE. PromptFetcher writes `guru_prompts` on every boot
         * and every refresh, so an override living there would be overwritten by the
         * next fetch and vanish without a word.
         */
        const val PREFS = "guru_prompt_override"
        const val KEY_OVERRIDE = "override_text"
    }
}
