// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
