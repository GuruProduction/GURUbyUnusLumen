// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tor

import com.unuslumen.app.preferences.domain.model.EgressMode

/**
 * The full answer for one request: where it goes, and whether that is a compromise.
 *
 * WHY A ROUTE ALONE WAS NOT ENOUGH. A caller that receives DIRECT cannot tell a loopback
 * address from a public host that was allowed out unproxied, so it reports "fetched" for
 * both and the second one is false privacy. Carrying [unproxied] and [reason] means every
 * layer can state what actually happened.
 *
 * [unproxied] is true ONLY for a public host sent direct. A LAN or loopback address is
 * direct by arithmetic and is never an exposure, so it never sets the flag. That is what
 * makes the flag worth honouring rather than noise a caller learns to ignore.
 */
data class EgressDecision(
    /** Where the request must be sent. Meaningless when [refusal] is set. */
    val route: EgressClassifier.Route,
    /** The posture that produced this answer. */
    val mode: EgressMode,
    /** True only when a public host is being sent direct, which is the exposure. */
    val unproxied: Boolean,
    /** Why this route, in one clause a caller can put in a result. */
    val reason: String,
    /** The sentence to report instead of running, or null when the request may run. */
    val refusal: String? = null,
) {
    /** True when the request must not proceed because no acceptable route exists. */
    val refused: Boolean get() = refusal != null

    companion object {
        /** A direct route for a private or loopback host, which is never an exposure. */
        fun direct(mode: EgressMode, reason: String): EgressDecision = EgressDecision(
            route = EgressClassifier.Route.DIRECT,
            mode = mode,
            unproxied = false,
            reason = reason,
        )

        /** A proxied route for a public host. */
        fun tor(mode: EgressMode, reason: String): EgressDecision = EgressDecision(
            route = EgressClassifier.Route.TOR,
            mode = mode,
            unproxied = false,
            reason = reason,
        )

        /**
         * A public host allowed out direct because the owner overrode the default.
         *
         * The only constructor that sets [unproxied], so there is exactly one place the
         * exposure can be created and it is reachable only from [EgressMode.CLEARNET].
         */
        fun clearnetOverride(reason: String): EgressDecision = EgressDecision(
            route = EgressClassifier.Route.DIRECT,
            mode = EgressMode.CLEARNET,
            unproxied = true,
            reason = reason,
        )

        /** A request the policy will not run, with the sentence that explains why. */
        fun refuse(mode: EgressMode, sentence: String): EgressDecision = EgressDecision(
            route = EgressClassifier.Route.TOR,
            mode = mode,
            unproxied = false,
            reason = sentence,
            refusal = sentence,
        )
    }
}
