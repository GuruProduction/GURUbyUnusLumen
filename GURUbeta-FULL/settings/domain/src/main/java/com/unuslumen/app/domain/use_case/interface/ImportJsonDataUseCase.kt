package com.unuslumen.app.domain.use_case.`interface`

interface ImportJsonDataUseCase {
    suspend operator fun invoke(
        fileUri: String,
        encrypted: Boolean,
        password: String?
    )
}
