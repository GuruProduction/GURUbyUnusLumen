package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.PortalResult
import kotlinx.coroutines.flow.Flow

interface AiRepository {

    suspend fun sendPrompt(prompt: String): PortalResult<String>

    fun sendMessage(messages: List<AiMessage>, conversationId: String? = null): Flow<AiMessage>

    /**
     * Rebuild the model wiring from current device settings. Called after a
     * BYO save (provider, endpoint, key, model name) so the change applies to
     * the very next send instead of requiring an app restart.
     */
    fun reinitialise()
}