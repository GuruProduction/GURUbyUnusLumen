// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.onboarding.domain

/**
 * UserProfile — everything the human told GURU about themselves at first boot.
 *
 * This is the domain shape. The Room row and the profile block both derive
 * from it. Free text fields stay free text: GURU reads them as prose, so a
 * human can answer in their own words instead of picking from a taxonomy.
 *
 * [factsById] maps a question key to the brain memory id that currently holds
 * the answer. Brain ids are content-derived, so a changed answer mints a new
 * id; the map is what lets the old row be archived instead of left as a ghost.
 *
 * [pendingBrainSync] lists question keys whose answer has not reached the
 * brain yet, because Cerebrum had not finished booting when the answer landed.
 * The boot pass flushes it. Nothing is ever silently lost.
 */
data class UserProfile(
    val id: String = SINGLETON_ID,
    /** How the human wants to be addressed. Drives the ${human} prompt token. */
    val preferredName: String = "",
    /** Full name, if they offered one. */
    val fullName: String = "",
    /** Pronouns, free text so people can answer in their own words. */
    val pronouns: String = "",
    /** Where they live, written however they want. */
    val location: String = "",
    /** Age as a number. See ageIsStale for the freshness caveat. */
    val age: Int? = null,
    /** How they identify. */
    val identity: String = "",
    /** Neurodivergence, in their own words. */
    val neurodivergence: String = "",
    /** Religious or spiritual beliefs. */
    val beliefs: String = "",
    /** Sexuality, in their own words. */
    val sexuality: String = "",
    /** Political views. */
    val politics: String = "",
    /** Relationship status and family. */
    val family: String = "",
    /** What they do for work, or a plain description of their days. */
    val work: String = "",
    /** What they are building or focused on right now. */
    val currentFocus: String = "",
    /** Adjustments GURU should make for them. */
    val adjustments: String = "",
    /** Health or legal context GURU must know. */
    val healthOrLegal: String = "",
    /** Topics they care about. */
    val interests: String = "",
    /** Where they get their news. */
    val newsSources: String = "",
    /** Social accounts and handles. */
    val socials: String = "",
    /** The one-liner that means GURU never asks twice. */
    val neverAskAgain: String = "",
    /** Free-text answers for any catalogue question without a typed column. */
    val extraAnswersJson: String = "{}",
    /** Question key -> brain memory id for the answer currently stored. */
    val factsByIdJson: String = "{}",
    /** Question keys waiting for the brain to come up. */
    val pendingBrainSyncJson: String = "[]",
    /** When the deal was accepted, epoch millis. 0 means never. */
    val dealAcceptedAt: Long = 0L,
    /** Catalogue version the human agreed to, so a rewrite can re-prompt. */
    val dealVersion: Int = 0,
    /** Whether the flow finished. The app gate reads this. */
    val completed: Boolean = false,
    val createdAt: Long = 0L,
    val updatedAt: Long = 0L
) {
    companion object {
        /** One profile row, always. Upsert semantics, no history table. */
        const val SINGLETON_ID = "user_profile"

        /**
         * Age drifts. A stored integer is only true for the year it was given,
         * so the profile block renders it with the year it was captured and
         * the model is told to treat it as approximate after twelve months.
         * The alternative, storing a birth year, is more durable but asks a
         * question people often decline. This keeps the ask natural and the
         * honesty explicit.
         */
        const val AGE_FRESHNESS_MILLIS = 365L * 24 * 60 * 60 * 1000
    }
}
