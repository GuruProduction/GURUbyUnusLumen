package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.GuruHook
import com.unuslumen.app.domain.repository.HookRepository
import org.koin.core.annotation.Factory

@Factory
class GetHooksUseCase(
    private val hookRepository: HookRepository
) {
    suspend operator fun invoke(): Result<List<GuruHook>> = runCatching {
        hookRepository.getAllHooks()
    }
}