package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.repository.ThoughtCycleRepository
import org.koin.core.annotation.Factory

@Factory
class DeleteThoughtCycleUseCase(
    private val thoughtCycleRepository: ThoughtCycleRepository
) {
    suspend operator fun invoke(cycleId: String): Result<Unit> = runCatching {
        thoughtCycleRepository.deleteCycle(cycleId)
    }
}