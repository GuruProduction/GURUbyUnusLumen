package com.unuslumen.app.domain.use_case.`interface`
 
interface ExportMarkdownDataUseCase {
    suspend operator fun invoke(
        directoryUri: String,
        exportNotes: Boolean,
        exportTasks: Boolean,
        exportJournal: Boolean,
        exportBookmarks: Boolean,
        encrypted: Boolean,
        password: String?,
    )
}
