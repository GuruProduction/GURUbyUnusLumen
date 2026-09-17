package com.unuslumen.app.domain.repository

import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.PortalResult
import kotlinx.coroutines.flow.Flow

interface AiRepository {

    suspend fun sendPrompt(prompt: String): PortalResult<String>

    fun sendMessage(messages: List<AiMessage>, conversationId: String? = null): Flow<AiMessage>
}