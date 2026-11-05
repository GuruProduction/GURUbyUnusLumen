// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.repository

/**
 * The heartbeat prompt, as its owner writes it.
 *
 * ONE SOURCE: THE DATABASE. There is no shipped prompt any more. It used to be an
 * asset inside the APK, and [current] used to fall back to it, which meant the app
 * carried an opinion about what GURU should say to itself every thirty minutes. That
 * opinion is its owner's, so the asset and the fallback are gone.
 *
 * A FRESH INSTALL THEREFORE HAS NO PROMPT, and [current] answers an empty string. A
 * beat that finds nothing ends, records why, and the room asks for one. That is the
 * honest state, and it is visible rather than papered over with words nobody chose.
 *
 * [reset] deletes the row. "Cleared" and "set to these words" are different states
 * and only one of them is true after a reset.
 */
interface HeartbeatPromptRepository {

    /** The prompt in force, or an empty string when the owner has written none. */
    suspend fun current(): String

    /** True when a prompt has been saved, false when there is none at all. */
    suspend fun isEdited(): Boolean

    /** Save a prompt. An empty string is refused rather than stored. */
    suspend fun save(content: String): Result<Unit>

    /** Delete the prompt. Leaves no prompt, which is what a fresh install has. */
    suspend fun reset()
}
