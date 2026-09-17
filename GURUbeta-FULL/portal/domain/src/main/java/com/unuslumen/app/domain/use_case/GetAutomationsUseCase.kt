package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.GuruAutomation
import com.unuslumen.app.domain.repository.AutomationRepository
import org.koin.core.annotation.Factory

/**
 * Get all automations.
 */
@Factory
class GetAutomationsUseCase(
    private val automationRepository: AutomationRepository
) {
    suspend operator fun invoke(): Result<List<GuruAutomation>> = runCatching {
        automationRepository.getAllAutomations()
    }
}
