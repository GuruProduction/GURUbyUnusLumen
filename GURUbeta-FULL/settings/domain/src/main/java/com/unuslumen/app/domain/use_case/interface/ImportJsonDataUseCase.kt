// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.use_case.`interface`

/**
 * Read a JSON content export back into the database.
 *
 * NO PASSPHRASE. This reader accepts exactly what the JSON export writes, which is plaintext,
 * and it used to accept a flag and a password it never consulted. The honest statement of what
 * it can read is now the whole contract.
 *
 * A file it cannot read is named rather than reported as a generic failure, because the picker
 * does not filter by type: choosing a sealed brain snapshot here used to say only that
 * something went wrong.
 */
interface ImportJsonDataUseCase {
    suspend operator fun invoke(fileUri: String)
}
