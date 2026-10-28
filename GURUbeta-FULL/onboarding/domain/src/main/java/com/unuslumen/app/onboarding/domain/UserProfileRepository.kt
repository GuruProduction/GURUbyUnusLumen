// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.onboarding.domain

/**
 * UserProfileRepository — the profile row's only doorway.
 *
 * The data module implements this against Room; the portal send path reads
 * through the UserProfileProvider seam so the AI module never depends on the
 * onboarding data module. Small interface, one row, upsert semantics.
 */
interface UserProfileRepository {

    /** The single profile row, or a blank default when onboarding never ran. */
    suspend fun getProfile(): UserProfile

    /** Upsert the row and stamp updatedAt. */
    suspend fun saveProfile(profile: UserProfile)

    /** True only when the flow finished and the deal was accepted. */
    suspend fun isOnboardingComplete(): Boolean

    /**
     * Mark the deal accepted at the current catalogue version. Separate from
     * [saveProfile] so the gate can open the moment the deal is agreed, even
     * if a later answer is abandoned.
     */
    suspend fun acceptDeal(version: Int)

    /** Flip completed once every answer is persisted. */
    suspend fun markComplete()

    /** Clear the row. Used by a deliberate "start over" only, never on boot. */
    suspend fun reset()
}
