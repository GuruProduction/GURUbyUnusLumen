package com.unuslumen.app.data.prompt

import android.content.Context
import com.unuslumen.app.data.PromptFetcher
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

    override suspend fun getSystemPrompt(): String = withContext(Dispatchers.IO) {
        val raw = PromptFetcher.getCachedPrompt(context) ?: ""
        substituteHumanToken(raw)
    }

    /**
     * Replace every ${human} occurrence with the saved user name.
     * Reads the name fresh through the preference flow each time. Blank or
     * missing names fall back to "your human" so prompts never ship the raw
     * token to the model.
     */
    suspend fun substituteHumanToken(prompt: String, savedName: String? = null): String {
        if (!prompt.contains(HUMAN_TOKEN)) return prompt
        val name = savedName ?: readUserName()
        return prompt.replace(HUMAN_TOKEN, name.ifBlank { FALLBACK_NAME })
    }

    private suspend fun readUserName(): String = withContext(Dispatchers.IO) {
        try {
            getPreference(
                stringPreferencesKey(PrefsConstants.USER_NAME_KEY),
                ""
            ).first()
        } catch (e: Exception) {
            android.util.Log.w("guru", "Reading user name for prompt substitution failed: ${e.message}")
            ""
        }
    }
}