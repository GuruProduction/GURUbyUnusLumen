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
