// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.HookEventType
import com.unuslumen.app.domain.model.HookExecutionResult
import com.unuslumen.app.domain.model.TriggerTiming
import com.unuslumen.app.domain.repository.HookRepository
import org.koin.core.annotation.Factory

@Factory
class ExecuteHooksUseCase(
    private val hookRepository: HookRepository
) {
    suspend operator fun invoke(
        eventType: HookEventType,
        timing: TriggerTiming,
        eventData: Map<String, Any?>
    ): Result<List<HookExecutionResult>> = runCatching {
        hookRepository.executeHooks(eventType, timing, eventData)
    }
}