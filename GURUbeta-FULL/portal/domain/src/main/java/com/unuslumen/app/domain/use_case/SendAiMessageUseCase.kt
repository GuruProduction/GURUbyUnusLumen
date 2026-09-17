package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.repository.AiRepository
import kotlinx.coroutines.flow.Flow
import org.koin.core.annotation.Factory

@Factory
class SendAiMessageUseCase(private val aiRepository: AiRepository) {
    operator fun invoke(messages: List<AiMessage>, conversationId: String? = null): Flow<AiMessage> {
        return aiRepository.sendMessage(messages, conversationId)
    }
}