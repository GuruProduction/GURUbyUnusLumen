package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.ToolExecutionResult
import com.unuslumen.app.domain.repository.GuruToolRepository
import org.koin.core.annotation.Factory

/**
 * Execute a Guru-defined tool.
 */
@Factory
class ExecuteGuruToolUseCase(
    private val guruToolRepository: GuruToolRepository
) {
    suspend operator fun invoke(
        toolId: String,
        params: Map<String, Any?>
    ): Result<ToolExecutionResult> = runCatching {
        guruToolRepository.executeTool(toolId, params)
    }
}