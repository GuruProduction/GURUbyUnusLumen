package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.repository.GuruToolRepository
import org.koin.core.annotation.Factory

/**
 * Approve a pending Guru tool.
 */
@Factory
class ApproveGuruToolUseCase(
    private val guruToolRepository: GuruToolRepository
) {
    suspend operator fun invoke(toolId: String): Result<GuruDefinedTool> = runCatching {
        guruToolRepository.approveTool(toolId)
    }
}