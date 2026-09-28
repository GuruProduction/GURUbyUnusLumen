package com.unuslumen.app.thoughts.domain.use_case

import com.unuslumen.app.thoughts.domain.model.GuruThoughtCycle
import com.unuslumen.app.thoughts.domain.repository.ThoughtCycleRepository
import org.koin.core.annotation.Factory

@Factory
class GetThoughtCyclesUseCase(
    private val thoughtCycleRepository: ThoughtCycleRepository
) {
    suspend operator fun invoke(): Result<List<GuruThoughtCycle>> = runCatching {
        thoughtCycleRepository.getAllCycles()
    }
}