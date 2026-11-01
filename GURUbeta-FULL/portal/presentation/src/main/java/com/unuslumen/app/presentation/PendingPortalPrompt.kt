// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One sentence on its way from an ask bar somewhere else into the portal.
 *
 * The app now carries an ask bar on every screen, so a human can start typing
 * in the middle of Notes and finish in the chat. That means text has to survive
 * a navigation: the bar is on one screen, the thing that actually sends it is
 * the portal.
 *
 * A holder rather than a navigation argument, for one reason: the portal is also
 * reachable from the bottom bar, from a deep link and from the lobby, and none
 * of those should have to know that a prompt might be waiting. The portal asks
 * once on entry and takes whatever is there.
 *
 * Only ONE prompt is ever held. A second send before the first is consumed
 * replaces it rather than queueing, because a human who typed a sentence and
 * then typed a better one meant the better one.
 */
object PendingPortalPrompt {
    private val _prompt = MutableStateFlow<String?>(null)

    /** Observers can watch for a prompt arriving while the portal is already open. */
    val prompt: StateFlow<String?> = _prompt.asStateFlow()

    /** Hand a sentence to the portal. Blank input is ignored rather than sent. */
    fun set(text: String) {
        val trimmed = text.trim()
        if (trimmed.isNotEmpty()) {
            _prompt.value = trimmed
        }
    }

    /**
     * Take the waiting prompt, clearing it in the same call.
     *
     * Read-and-clear rather than read, so a recomposition cannot send the same
     * sentence twice.
     */
    fun consume(): String? {
        val waiting = _prompt.value
        _prompt.value = null
        return waiting
    }
}
