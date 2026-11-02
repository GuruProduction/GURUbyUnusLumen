// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

/**
 * The heartbeat prompt, as its owner edits it.
 *
 * THE SHIPPED TEXT IS THE FLOOR. A fresh install runs exactly as it did when this
 * was a compiled asset: [current] answers with the bundled prompt until somebody
 * saves one, so nothing about a new phone changes. From the first save, the stored
 * row is what the beat injects.
 *
 * [reset] deletes the override rather than writing the bundled text into the table.
 * That matters because the bundled text can still be corrected in a release, and a
 * copy sitting in the database would never pick the correction up.
 */
interface HeartbeatPromptRepository {

    /** The prompt in force: the saved one, or the bundled text when nothing is saved. */
    suspend fun current(): String

    /** True when the owner has edited it, false when the bundled text is in force. */
    suspend fun isEdited(): Boolean

    /** Save an edited prompt. An empty string is refused rather than stored. */
    suspend fun save(content: String): Result<Unit>

    /** Throw away the edit and fall back to the bundled text. */
    suspend fun reset()
}
