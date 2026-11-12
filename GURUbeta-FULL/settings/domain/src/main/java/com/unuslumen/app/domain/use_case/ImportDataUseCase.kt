// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.exception.BackupDataException
import com.unuslumen.app.domain.model.BackupFormat
import com.unuslumen.app.domain.use_case.`interface`.ImportJsonDataUseCase
import org.koin.core.annotation.Factory

@Factory
class ImportDataUseCase(
    private val importJsonData: ImportJsonDataUseCase
) {
    suspend operator fun invoke(
        fileUri: String,
        format: BackupFormat,
    ) = when (format) {
        BackupFormat.JSON -> importJsonData(fileUri)

        // Named as its own refusal rather than a generic error. The import picker offers only
        // JSON, so reaching this is a programming mistake rather than something the owner did,
        // and the message says which format was asked for so the mistake is visible.
        BackupFormat.MARKDOWN -> throw BackupDataException.ImportFormatNotSupported(
            format = BackupFormat.MARKDOWN.name,
        )
    }
}
