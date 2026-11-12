// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case

import com.unuslumen.app.domain.model.BackupFormat
import com.unuslumen.app.domain.use_case.`interface`.ExportJsonDataUseCase
import com.unuslumen.app.domain.use_case.`interface`.ExportMarkdownDataUseCase
import org.koin.core.annotation.Factory

/**
 * Route a content export to the format the owner chose.
 *
 * The parameters are named at every call site rather than passed positionally. Five booleans in
 * a row can be transposed without the compiler noticing, and a transposition here writes the
 * wrong tables to a file the owner believes is their whole journal.
 */
@Factory
class ExportDataUseCase(
    private val exportJsonData: ExportJsonDataUseCase,
    private val exportMarkdownData: ExportMarkdownDataUseCase,
) {
    suspend operator fun invoke(
        directoryUri: String,
        exportNotes: Boolean,
        exportTasks: Boolean,
        exportJournal: Boolean,
        exportBookmarks: Boolean,
        format: BackupFormat,
    ) = when (format) {
        BackupFormat.JSON -> exportJsonData(
            directoryUri = directoryUri,
            exportNotes = exportNotes,
            exportTasks = exportTasks,
            exportJournal = exportJournal,
            exportBookmarks = exportBookmarks,
        )

        BackupFormat.MARKDOWN -> exportMarkdownData(
            directoryUri = directoryUri,
            exportNotes = exportNotes,
            exportTasks = exportTasks,
            exportJournal = exportJournal,
            exportBookmarks = exportBookmarks,
        )
    }
}