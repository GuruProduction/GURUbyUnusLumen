// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

/**
 * The catalogue identity of an agent, derived from its name. Issue #233.
 *
 * WHY A PERSON NEVER SEES OR TYPES THIS. Most people using GURU will not know what a
 * slug is, and they should not have to. They name their agent "coding agent" and the
 * machine works out `coding-agent`. The slug exists because a display name is a poor
 * key: two people can call an agent the same thing, a name can contain spaces and
 * punctuation, and a rename must not change what an installed copy points at.
 *
 * WHY THE RULE LIVES HERE, ALONE. The local save and the publish path used to derive
 * slugs separately, which is how the local row ended up with an empty slug while the
 * publish path had a correct one. One function, called by both, means the slug a person
 * gets on save is byte-for-byte the slug their agent publishes under.
 *
 * THE SHAPE IS THE SERVER'S, NOT OURS. The submit route refuses anything that is not
 * lowercase ascii words joined by single dashes, so this produces exactly that. Getting
 * it wrong here would mean a person fills in a creator form, presses publish, and is
 * told their agent's identity is invalid — for a field they never saw.
 */
object AgentSlug {

    /** The longest slug the catalogue accepts. */
    const val MAX_LENGTH: Int = 64

    /** What a name that slugifies to nothing falls back to, so a slug is never empty. */
    const val FALLBACK: String = "agent"

    /**
     * The slug for a display name.
     *
     * Lowercase, ascii letters and digits kept, everything else a separator, single
     * dashes between words, no leading or trailing dash. An accented or non-latin name
     * becomes separators rather than vanishing, so the fallback only appears when the
     * name carried no usable character at all.
     */
    fun from(name: String): String {
        val base = name.lowercase()
            .map { c -> if (c.isLetterOrDigit() && c.code < 128) c else ' ' }
            .joinToString("")
            .trim()
            .replace(Regex("\\s+"), "-")
            .replace(Regex("-+"), "-")
            .trim('-')
            .take(MAX_LENGTH)
            .trim('-')
        return base.ifBlank { FALLBACK }
    }

    /**
     * The slug for a name, made distinct from the slugs already in use.
     *
     * A SECOND AGENT WITH THE SAME NAME GETS A SUFFIX, NEVER A COLLISION. The server
     * enforces `slug UNIQUE`, so a duplicate would be refused at publish time with a
     * message about a field the person never saw. Resolving it here means two agents
     * called "coding agent" both exist and both publish, as `coding-agent` and
     * `coding-agent-2`.
     *
     * [taken] is compared case-insensitively because the catalogue's slugs are
     * lowercase and a case difference is not a difference.
     */
    fun unique(name: String, taken: Collection<String>): String {
        val base = from(name)
        val used = taken.map { it.trim().lowercase() }.filter { it.isNotEmpty() }.toSet()
        if (base !in used) return base

        // Suffix with the lowest free number, so deleting an agent frees its number
        // rather than leaving a permanent gap.
        var n = 2
        while (true) {
            val suffix = "-$n"
            // Trim the base so the suffix cannot push past the catalogue limit.
            val candidate = base.take(MAX_LENGTH - suffix.length).trimEnd('-') + suffix
            if (candidate !in used) return candidate
            n++
            // A name colliding this many times is not a real case, but an unbounded
            // loop in a save path is not acceptable either.
            if (n > 9_999) return base
        }
    }
}
