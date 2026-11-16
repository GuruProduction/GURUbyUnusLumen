// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.permission

/**
 * What a caller is allowed to see of an auth token, decided in one place.
 *
 * WHY A POLICY OBJECT RATHER THAN RETURNING THE TOKEN. An auth token is a bearer credential: it
 * is as good as the password for whatever it grants, and it is long-lived. A tool result is
 * stored, shown in the conversation, written to the observation table and possibly exported in
 * a brain snapshot. A token in any of those is a credential leak that the person cannot undo,
 * because the token stays valid until it is revoked.
 *
 * So the token never goes into a result body by default. What a result carries is whether one
 * was obtained, its shape, and nothing a replay could use. A caller that genuinely needs the
 * token to make a further call gets it into memory only, and that path is explicit rather than
 * the default.
 *
 * THE REDACTION IS HERE, NOT AT EACH CALL SITE. Four call sites redacting differently is how one
 * of them ends up logging the thing.
 */
object AccountTokenPolicy {

    /**
     * How much of a token is ever shown.
     *
     * A fingerprint, not a prefix. An auth token's first characters are often a fixed prefix for
     * the provider, so showing them proves nothing about which token this is and gives away a
     * little of the secret for free. The last four characters plus a length is enough for a
     * person to tell two tokens apart and useless for anything else.
     */
    private const val VISIBLE_TAIL = 4

    /** A token shorter than this is not fingerprinted at all, because the tail is too much of it. */
    private const val MIN_LENGTH_TO_FINGERPRINT = 12

    /**
     * The shape of a token, safe to report.
     *
     * [length] is a real fact about the credential and helps a person confirm they got the one
     * they expected. [fingerprint] is the tail, or null when the token is too short for a tail to
     * be safe. Neither can be replayed.
     */
    data class TokenShape(
        val length: Int,
        val fingerprint: String?,
        /** True when this token was obtained, which is not the same as it being usable. */
        val obtained: Boolean,
    )

    /** The shape of a token, or null when no token was obtained. */
    fun shapeOf(token: String?): TokenShape? {
        if (token.isNullOrEmpty()) return null
        val fingerprint = if (token.length >= MIN_LENGTH_TO_FINGERPRINT) {
            token.takeLast(VISIBLE_TAIL)
        } else {
            null
        }
        return TokenShape(length = token.length, fingerprint = fingerprint, obtained = true)
    }

    /**
     * The sentence a result uses, which must never contain the token.
     *
     * Written here rather than at the call site so every path that reports a token says the same
     * thing about what it is and is not showing.
     */
    fun sentence(shape: TokenShape?): String = when {
        shape == null ->
            "No token was obtained. That means the account has none cached for this type right " +
                "now, or it refused to issue one, which is different from a permission problem."
        shape.fingerprint == null ->
            "A token of ${shape.length} characters was obtained. It is not shown here in any form: " +
                "an auth token is a bearer credential, so a copy in a result is a credential leak " +
                "that stays valid until it is revoked."
        else ->
            "A token of ${shape.length} characters was obtained, ending ${shape.fingerprint}. Only " +
                "that tail is shown: an auth token is a bearer credential, and the rest of it would " +
                "let anything that read this result act as this account."
    }

    /**
     * Whether a token that was invalidated should be reported as removed.
     *
     * Invalidation is the one token operation that changes state, and it is the one a person can
     * safely be told succeeded without exposing anything. A failure here usually means the token
     * was already gone, which is a true and useful answer rather than an error.
     */
    fun invalidationSentence(invalidated: Boolean): String = if (invalidated) {
        "The token was invalidated on the device, so it cannot be used again. A service that " +
            "already cached it may hold it until it expires, which is the account's own policy " +
            "rather than something GURU can reach."
    } else {
        "The token was not invalidated. That usually means it was already gone, which is a fine " +
            "state to be in, or that the account provider declined, which shows nothing about why."
    }
}
