// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.metadata

/**
 * Whether GURU is awake, as one question any screen can ask.
 *
 * WHY THIS IS A SEPARATE THING FROM THE HEARTBEAT'S OWN STATE. The heartbeat owns its
 * scheduler and its switch, and neither of those belongs to a lobby that only needs to draw
 * one word. This is the narrow door: one boolean, read from the same preference the chain
 * itself reads, so the lobby cannot disagree with the chain about whether the beats are
 * running.
 *
 * IT IS SYNCHRONOUS ON PURPOSE. A status line is drawn during composition and cannot wait on
 * a coroutine, and the preference is small enough that a read behind a cached container is
 * cheap. An unreadable preference answers false, which is the safe direction: a lobby that
 * says asleep when it could not tell is honest, and one that says awake when the chain is off
 * is a lie on the front door.
 */
object HeartbeatAwareness {

    /** True when the chain is switched on and beats will fire. */
    fun isChainOn(): Boolean = runCatching {
        com.unuslumen.app.data.heartbeat.HeartbeatChainState.isEnabled()
    }.getOrDefault(false)
}
