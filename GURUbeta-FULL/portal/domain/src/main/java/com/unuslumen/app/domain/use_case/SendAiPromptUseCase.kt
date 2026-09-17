package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.PortalResult
import com.unuslumen.app.domain.repository.AiRepository
import org.koin.core.annotation.Factory
import java.io.IOException

@Factory
class SendAiPromptUseCase(private val aiRepository: AiRepository) {
    suspend operator fun invoke(prompt: String): PortalResult<String> {
        return try {
            aiRepository.sendPrompt(prompt)
        } catch (e: IOException) {
            e.printStackTrace()
            PortalResult.InternetError
        } catch (e: Exception) {
            e.printStackTrace()
            PortalResult.OtherError()
        }
    }
}