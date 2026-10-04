// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.hooks

import com.unuslumen.app.domain.model.HookEventType

/**
 * EventCycleBus — the EVENT thought-cycle dispatcher seam.
 *
 * Lives in portal-domain because it is a CONTRACT, not machinery: portal-data's
 * HookEventBus calls dispatch() when a real app event fires, and the thoughts
 * module sets `dispatcher` at init to resolve and run matching EVENT cycles.
 *
 * Why the seam exists here rather than on HookEventBus itself: HookEventBus
 * lives in portal-data and the cycle engine lives in thoughts-data, and
 * neither module may depend on the other (portal-data needs thoughts-data for
 * the engine, so thoughts-data reaching back would create the cycle Gradle
 * refuses to build). Both may depend on portal-domain. This bus breaks that
 * knot with one shared interface point.
 *
 * Thread discipline: dispatch() is called from HookEventBus inside its own
 * coroutine scope, after the hooks pipeline, in an isolated try block. The
 * dispatcher's own failures are caught THERE; they must never propagate back
 * into hook handling.
 */
object EventCycleBus {

    /**
     * Set once by the thoughts module at init. Null until then: EVENT cycle
     * dispatch is a no-op, hooks and events are unaffected.
     */
    var dispatcher: (suspend (HookEventType, Map<String, Any?>) -> Unit)? = null

    /**
     * Invoke the dispatcher if present. Never throws: dispatcher failures are
     * logged and swallowed by design, the event's host operation and the
     * hooks pipeline stay untouched either way. (println not android.util.Log:
     * portal-domain is a pure JVM module, no Android runtime up here.)
     */
    suspend fun dispatch(eventType: HookEventType, data: Map<String, Any?>) {
        try {
            dispatcher?.invoke(eventType, data)
        } catch (e: Exception) {
            println("guru_thoughts: EVENT cycle dispatch failed for $eventType: ${e.message}")
        }
    }
}