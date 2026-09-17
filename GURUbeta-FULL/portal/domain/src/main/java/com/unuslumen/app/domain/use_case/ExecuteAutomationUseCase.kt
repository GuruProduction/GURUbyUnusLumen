package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.AutomationExecutionResult
import com.unuslumen.app.domain.repository.AutomationRepository
import org.koin.core.annotation.Factory

/**
 * Execute an automation.
 */
@Factory
class ExecuteAutomationUseCase(
    private val automationRepository: AutomationRepository
) {
    suspend operator fun invoke(
        automationId: String,
        params: Map<String, Any?>
    ): Result<AutomationExecutionResult> = runCatching {
        automationRepository.executeAutomation(automationId, params)
    }
}
