// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case.`interface`
 
/**
 * Write the chosen content as Markdown files, one per note, task, entry and bookmark.
 *
 * NO PASSPHRASE, for the same reason as the JSON export: a directory of Markdown that cannot be
 * read without a key has failed at the only thing it is for. The ignored `encrypted` and
 * `password` parameters this interface used to carry were removed rather than wired, because
 * wiring them would have duplicated the sealed snapshot's job and destroyed this one's.
 */
interface ExportMarkdownDataUseCase {
    suspend operator fun invoke(
        directoryUri: String,
        exportNotes: Boolean,
        exportTasks: Boolean,
        exportJournal: Boolean,
        exportBookmarks: Boolean,
    )
}
