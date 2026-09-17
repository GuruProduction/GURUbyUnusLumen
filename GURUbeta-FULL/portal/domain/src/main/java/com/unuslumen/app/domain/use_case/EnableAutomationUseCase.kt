package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.GuruAutomation
import com.unuslumen.app.domain.repository.AutomationRepository
import org.koin.core.annotation.Factory

/**
 * Enable an automation.
 */
@Factory
class EnableAutomationUseCase(
    private val automationRepository: AutomationRepository
) {
    suspend operator fun invoke(automationId: String): Result<GuruAutomation> = runCatching {
        automationRepository.enableAutomation(automationId)
    }
}
