// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case.`interface`

/**
 * Write a readable JSON copy of the chosen content into a folder the owner picked.
 *
 * THERE IS NO PASSPHRASE, AND THAT IS DELIBERATE. This export exists to be opened in any
 * editor on any machine, which is the opposite of a sealed file. It used to accept an
 * `encrypted` flag and a `password`, forward both through two layers, and ignore them: every
 * implementation wrote plaintext. A parameter that nothing reads is a latent lie, because the
 * day somebody adds a checkbox labelled Encrypted it produces a readable file that a person
 * has been told is sealed.
 *
 * A sealed copy is a different feature with a different job, and it exists: the brain snapshot
 * sealed with the owner's own passphrase, verified before it is kept, and restorable. That one
 * carries the whole database. This one carries the words a person typed, in a form they can
 * grep.
 */
interface ExportJsonDataUseCase {
    suspend operator fun invoke(
        directoryUri: String,
        exportNotes: Boolean,
        exportTasks: Boolean,
        exportJournal: Boolean,
        exportBookmarks: Boolean,
    )
}
