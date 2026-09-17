package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.repository.GuruToolRepository
import org.koin.core.annotation.Factory

/**
 * Get pending Guru tools awaiting approval.
 */
@Factory
class GetPendingGuruToolsUseCase(
    private val guruToolRepository: GuruToolRepository
) {
    suspend operator fun invoke(): Result<List<GuruDefinedTool>> = runCatching {
        guruToolRepository.getPendingTools()
    }
}