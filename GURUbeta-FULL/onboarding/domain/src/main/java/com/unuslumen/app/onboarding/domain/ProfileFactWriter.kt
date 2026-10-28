// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.onboarding.domain

/**
 * ProfileFactWriter — the write seam into the brain.
 *
 * The onboarding flow stores answers as facts in Cerebrum through this
 * interface, so the onboarding data module never depends on portal:data and
 * the brain stays the single memory store.
 *
 * The implementation lives in the portal data module and wraps BrainService.
 * That is deliberate: the brain's write gate, deterministic ids and engine
 * side effects all belong to the existing memory lane, and onboarding is just
 * another trusted writer onto it.
 *
 * Contract the implementation must honour:
 *  - [writeFact] returns the REAL brain memory id, never a synthetic one
 *  - a brain that is not up yet returns null rather than throwing; the caller
 *    records the key as pending and retries at boot
 *  - [archiveFact] is the no-delete path: the brain archives, never erases
 */
interface ProfileFactWriter {

    /**
     * Store one profile answer as a brain fact.
     *
     * @param key the catalogue question key, used as the fact's topic tag
     * @param factText the prose sentence built from the answer
     * @param category brain category, normally user_profile
     * @return the real 64-hex brain id, or null when the brain is not running
     */
    suspend fun writeFact(key: String, factText: String, category: String): String?

    /** Archive a fact that has been superseded by a newer answer. */
    suspend fun archiveFact(idHex: String, reason: String)
}
