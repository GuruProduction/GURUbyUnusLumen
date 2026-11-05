// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

/**
 * MOVED TO `com.unuslumen.app.domain.PendingPortalPrompt`.
 *
 * The holder now has two callers: the ask bar on every screen, and the Heartbeat
 * room's fire button, which must deliver its prompt into the portal as a real turn.
 * The room is its own Gradle module and cannot reach a presentation class in this one,
 * so the object lives in domain and this name is a forwarding alias kept so existing
 * call sites read the same as they always have.
 *
 * Deleting this file instead would have meant editing every existing caller for no
 * behaviour change, which is churn. The alias is three lines and states where the
 * truth lives.
 */
typealias PendingPortalPrompt = com.unuslumen.app.domain.PendingPortalPrompt
