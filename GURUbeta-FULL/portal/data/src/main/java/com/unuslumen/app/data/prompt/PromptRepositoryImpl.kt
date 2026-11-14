// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.prompt

import android.content.Context
import com.unuslumen.app.data.PromptFetcher
import com.unuslumen.app.domain.prompt.NameTokens
import com.unuslumen.app.domain.repository.PromptRepository
import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withContext
import org.koin.core.annotation.Single

/**
 * Serves the assembled system prompt with per-user substitution applied.
 *
 * Prompts authored on the server (and notes to self authored on device) use
 * the ${human} token wherever the human's name belongs. The name itself lives
 * in the settings store under USER_NAME_KEY, the same key the Settings screen
 * writes, read here through the standard preference pipeline (GetPreferenceUseCase
 * / PreferenceRepository). Substitution happens at read time on every send, so
 * renaming the user in Settings takes effect on the next message with no app
 * restart and no prompt re-fetch.
 */
@Single(binds = [PromptRepository::class])
class PromptRepositoryImpl(
    private val context: Context,
    private val getPreference: GetPreferenceUseCase
) : PromptRepository {

    companion object {
        /** The token prompts use for the human's name. */
        const val HUMAN_TOKEN = "\${human}"
        /** Fallback when the user has not saved a name yet. */
        const val FALLBACK_NAME = "your human"
    }

    /**
     * The assembled prompt with BOTH name tokens substituted (issue 340).
     *
     * `${human}` was the only token, so GURU called itself "GURU" everywhere forever even when the
     * owner wanted it called something else. `${guru}` is substituted here through the same rules,
     * and both are read fresh on every send so renaming either one takes effect on the next message
     * with no restart and no prompt re-fetch.
     */
    override suspend fun getSystemPrompt(): String = withContext(Dispatchers.IO) {
        val raw = PromptFetcher.getCachedPrompt(context) ?: ""
        if (!NameTokens.hasUnsubstitutedToken(raw)) return@withContext raw
        NameTokens.substitute(raw, humanName = readUserName(), guruName = readGuruName())
    }

    /**
     * Replace every name token with the saved names.
     *
     * Kept as a named function because existing callers use it, but the rules themselves live in
     * [NameTokens] so the prompt path and any other surface cannot drift apart on what a blank name
     * becomes.
     */
    suspend fun substituteHumanToken(prompt: String, savedName: String? = null): String {
        if (!NameTokens.hasUnsubstitutedToken(prompt)) return prompt
        return NameTokens.substitute(
            prompt,
            humanName = savedName ?: readUserName(),
            guruName = readGuruName(),
        )
    }

    private suspend fun readUserName(): String = readPreference(PrefsConstants.USER_NAME_KEY, "user name")

    /** The owner's chosen name for GURU, or blank when he has not chosen one (issue 340). */
    private suspend fun readGuruName(): String = readPreference(PrefsConstants.GURU_NAME_KEY, "GURU name")

    /**
     * One read, for both names.
     *
     * A FAILED READ IS A BLANK, which the substitution turns into the default. That is deliberate:
     * a preference read that throws must not leave a literal token in the prompt, because a model
     * reading `${guru}` does exactly what the literal text says.
     */
    private suspend fun readPreference(key: String, what: String): String = withContext(Dispatchers.IO) {
        try {
            getPreference(stringPreferencesKey(key), "").first()
        } catch (e: Exception) {
            android.util.Log.w("guru", "Reading $what for prompt substitution failed: ${e.message}")
            ""
        }
    }
}