// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.permissions

/**
 * The outcome of walking a grant path.
 *
 * Issue 172 is explicit that a grant which was requested is not a grant that
 * landed, so this carries what was observed as well as what was attempted. The
 * two are separate fields because a caller that reads only `held` cannot tell
 * "the user said no" from "the settings screen opened and they backed out".
 *
 * @param permission the platform string, android.permission.*
 * @param mechanism the path that was walked
 * @param held true only when the grant was read back and found present
 * @param observed what the platform reported afterwards, in plain words
 * @param instruction one sentence telling a person exactly what to do, or null when held
 */
data class GrantOutcome(
    val permission: String,
    val mechanism: GrantMechanism,
    val held: Boolean,
    val observed: String,
    val instruction: String?
) {
    /**
     * The single sentence a denying tool must return.
     *
     * Issue 172's honest-failure contract: what is missing, why it matters, and
     * exactly how to grant it. Never an empty result, never a bare permission
     * string, never a success that was not observed.
     */
    fun denial(): String = buildString {
        append(permission.substringAfterLast('.'))
        append(" is not granted. ")
        append(observed)
        instruction?.let {
            append(' ')
            append(it)
        }
    }
}
