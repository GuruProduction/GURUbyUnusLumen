// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.onboarding.domain

/**
 * UserProfileProvider — the read seam the send path uses.
 *
 * AiRepositoryImpl must not depend on the onboarding data module, and it must
 * not know how the profile is stored. It depends on this one interface, which
 * the onboarding data module binds to the real Room row. The send path calls
 * [profileBlock] on every send; when onboarding has never run it returns an
 * empty string and the system message is exactly as it was before.
 *
 * This seam is the whole reason the profile is deterministic. The memory
 * preamble is retrieval and can miss. This cannot: it is a direct read of the
 * row, formatted the same way every time, injected into the system message
 * before the model ever sees the conversation.
 */
interface UserProfileProvider {

    /**
     * The short always-injected block, or "" when there is nothing to say.
     * Read fresh per send so a later edit lands on the next message.
     * Never throws: a storage failure returns "" and the send carries on.
     */
    suspend fun profileBlock(): String

    /** The preferred name, or "" when unset. Used to keep names consistent. */
    suspend fun preferredName(): String

    /** True when onboarding finished. The app gate reads this. */
    suspend fun isComplete(): Boolean
}
