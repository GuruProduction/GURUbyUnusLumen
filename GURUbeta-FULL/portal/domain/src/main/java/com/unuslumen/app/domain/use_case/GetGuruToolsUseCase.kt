package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.repository.GuruToolRepository
import org.koin.core.annotation.Factory

/**
 * Get all Guru-defined tools.
 */
@Factory
class GetGuruToolsUseCase(
    private val guruToolRepository: GuruToolRepository
) {
    suspend operator fun invoke(): Result<List<GuruDefinedTool>> = runCatching {
        guruToolRepository.getAllTools()
    }
}