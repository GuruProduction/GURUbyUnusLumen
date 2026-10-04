// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain

/**
 * Spinner verbs for the loading state in the portal.
 * Content moved to server database (master_prompts, category: other).
 */
val GURU_SPINNER_VERBS: List<String> = emptyList()

/**
 * Pick a random spinner verb. Returns null if the list is empty.
 */
fun randomSpinnerVerb(): String? = GURU_SPINNER_VERBS.randomOrNull()
