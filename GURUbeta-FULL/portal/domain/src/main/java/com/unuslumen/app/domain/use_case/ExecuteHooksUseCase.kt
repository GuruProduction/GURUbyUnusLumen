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