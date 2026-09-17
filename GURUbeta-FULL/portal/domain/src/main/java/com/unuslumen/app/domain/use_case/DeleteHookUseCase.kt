package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.HookRepository
import org.koin.core.annotation.Factory

@Factory
class DeleteHookUseCase(
    private val hookRepository: HookRepository
) {
    suspend operator fun invoke(hookId: String): Result<Unit> = runCatching {
        hookRepository.deleteHook(hookId)
    }
}