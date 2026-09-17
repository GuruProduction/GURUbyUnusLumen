package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.AutomationRepository
import org.koin.core.annotation.Factory

/**
 * Delete an automation.
 */
@Factory
class DeleteAutomationUseCase(
    private val automationRepository: AutomationRepository
) {
    suspend operator fun invoke(automationId: String): Result<Unit> = runCatching {
        automationRepository.deleteAutomation(automationId)
    }
}
