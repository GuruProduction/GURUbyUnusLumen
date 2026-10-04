// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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