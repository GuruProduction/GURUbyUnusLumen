// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One sentence on its way into the portal from somewhere else.
 *
 * WHY THIS IS IN DOMAIN RATHER THAN IN THE PORTAL'S PRESENTATION LAYER. Two callers
 * need it now. The app carries an ask bar on every screen, so a human can start typing
 * in Notes and finish in the chat; and the Heartbeat room has a button that fires a
 * beat by hand, which must land in the portal as a real turn rather than as a
 * background service call nobody can see.
 *
 * A presentation package is not reachable from another Gradle module without dragging
 * that module's whole dependency graph in, so the holder lives here: app state that
 * more than one screen hands things through.
 *
 * A holder rather than a navigation argument, for one reason: the portal is reachable
 * from the bottom bar, from a deep link, from the lobby and from the heartbeat room,
 * and none of those should have to know that a prompt might be waiting. The portal
 * watches this and takes whatever is there.
 *
 * Only ONE prompt is ever held. A second send before the first is consumed replaces it
 * rather than queueing, because a human who typed a sentence and then typed a better
 * one meant the better one.
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
