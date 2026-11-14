// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.prompt

/**
 * The two name tokens a prompt can carry, and what they become (issue 340).
 *
 * WHY THIS IS ITS OWN OBJECT. `${human}` substitution already existed inside `PromptRepositoryImpl`,
 * and `${guru}` had to be added beside it. Two tokens with two fallbacks and a shared rule about blank
 * values is exactly the kind of thing that drifts when it is written twice, so the rules live here and
 * both the prompt path and any future surface read them from one place.
 *
 * THE BUG THIS FILE EXISTS TO PREVENT, from the codebase's own history: a shipped heartbeat asset used
 * `${human}` while the loader only knew `{{human}}`, so every released build injected the literal token
 * where a name belonged. Nobody saw it, because a prompt is read by a model rather than by a person,
 * and the model did what the literal text said. So every spelling a person might reasonably type is
 * accepted, and [substitute] is asserted to leave no token visible.
 */
object NameTokens {

    /** The token prompts use for the owner's name. */
    const val HUMAN = "\${human}"

    /** The token prompts use for GURU's own name (issue 340). */
    const val GURU = "\${guru}"

    /** Also accepted, because a prompt author may reach for the brace spelling by habit. */
    private val HUMAN_ALT = listOf("{{human}}", "{{human_name}}", "\${human_name}")
    private val GURU_ALT = listOf("{{guru}}", "{{guru_name}}", "\${guru_name}")

    /**
     * What GURU calls itself when the owner has not chosen a name.
     *
     * THE DEFAULT IS THE NAME IT ALREADY HAD, so an install that never opens the setting reads exactly
     * as it did before the setting existed. Anything else would be a silent behaviour change dressed as
     * a default.
     */
    const val DEFAULT_GURU = "GURU"

    /** What the prompt calls the owner when he has not saved a name. */
    const val DEFAULT_HUMAN = "your human"

    /**
     * Replace both tokens in a prompt.
     *
     * BLANK MEANS UNSET IN BOTH CASES, and each has its own fallback rather than one shared one, because
     * "your human" for GURU's own name would be nonsense and "GURU" for the owner would be a lie about
     * who is speaking.
     *
     * Whitespace is trimmed rather than trusted: a name typed on a phone keyboard very often arrives
     * with a trailing space, and "Steve " rendered into a sentence shows a gap that reads as a bug.
     */
    fun substitute(prompt: String, humanName: String?, guruName: String?): String {
        if (prompt.isEmpty()) return prompt
        val human = humanName?.trim().orEmpty().ifBlank { DEFAULT_HUMAN }
        val guru = guruName?.trim().orEmpty().ifBlank { DEFAULT_GURU }

        var out = prompt
        // GURU FIRST, because it is the more specific token: substituting the human name first would
        // not affect it, but doing the longer-looking replacement first keeps the order obvious to a
        // reader who comes to this function later.
        for (token in listOf(GURU) + GURU_ALT) out = out.replace(token, guru)
        for (token in listOf(HUMAN) + HUMAN_ALT) out = out.replace(token, human)
        return out
    }

    /**
     * Whether any token this contract answers is still visible in the text.
     *
     * Exposed so a test — or a caller that wants to assert its own invariant — can check the one thing
     * that went wrong in the shipped heartbeat asset, rather than trusting the substitution.
     */
    fun hasUnsubstitutedToken(text: String): Boolean {
        val all = listOf(HUMAN, GURU) + HUMAN_ALT + GURU_ALT
        return all.any { text.contains(it) }
    }

    /**
     * The name GURU should present itself under, for a label or a heading.
     *
     * THE SAME RULE AS THE TOKEN, because a label that fell back differently from a prompt would show
     * one name on screen and another in the text the model reads.
     */
    fun guruName(savedName: String?): String =
        savedName?.trim().orEmpty().ifBlank { DEFAULT_GURU }

    /** The owner's name for a label, with its own fallback. */
    fun humanName(savedName: String?): String =
        savedName?.trim().orEmpty().ifBlank { DEFAULT_HUMAN }
}
