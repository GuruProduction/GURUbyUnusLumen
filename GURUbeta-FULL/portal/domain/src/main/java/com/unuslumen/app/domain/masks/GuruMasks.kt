// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.masks

/**
 * The masks this build carries (issue 491).
 *
 * WHAT A MASK ACTUALLY IS, which the issue asked about before shaping any tool. It is
 * NOT a stored record an agent can author: it is a `GuruMask` enum with four members,
 * rendered by `GuruMaskSelector`, and the selection is held in the portal screen's own
 * saveable state and never written anywhere. There is no table, no row, no id and no
 * body text.
 *
 * That answers the open question the ticket raised, and it changes what the honest
 * deliverable is. A tool that claimed to CREATE a mask would be writing nowhere, which
 * is the same defect as the prompt rows that never reached a device. So the tools list
 * what exists, read one in full, and say plainly that the catalogue is compile-time.
 *
 * WHY THE CATALOGUE MOVED HERE. The enum lived in a Compose component, which the tool
 * layer cannot see, so a model had no way to learn that masks existed at all. One
 * source now feeds both the selector and the tool, and the two cannot disagree about
 * what a mask is.
 */
object GuruMasks {

    /**
     * One mask, with the text a caller needs to tell it from another.
     *
     * The names are the enum's own, so a caller quoting one is quoting the value the
     * selector matches on rather than a description of it.
     */
    data class Mask(
        val name: String,
        val purpose: String,
        /** Whether the portal opens with this mask selected. Always true for exactly one. */
        val isDefault: Boolean = false,
    )

    /**
     * The catalogue, in the order the selector renders it.
     *
     * The purposes are the roles these four play in this product, written here because
     * nothing in the codebase said them in words a model could read: the enum carried a
     * display name and no explanation.
     */
    val ALL: List<Mask> = listOf(
        Mask(
            name = "Coder",
            purpose = "Writes and changes code: reads a file, makes the edit, proves it builds.",
            isDefault = true,
        ),
        Mask(
            name = "Grafter",
            purpose = "Does the blockchain and wallet work: signs, sends, and reconciles against the chain.",
        ),
        Mask(
            name = "Wingman",
            purpose = "Runs the owner's world: messages, calendar, bookings, and the follow-ups that keep them moving.",
        ),
        Mask(
            name = "Slik",
            purpose = "Presentation and polish: surfaces, wording and the feel of a thing before it ships.",
        ),
    )

    /** The mask the portal opens with. */
    fun default(): Mask = ALL.first { it.isDefault }

    /**
     * One mask by the name the selector uses.
     *
     * Case-insensitive on purpose: a caller reaching for "coder" means the Coder mask,
     * and refusing a lowercase spelling would teach the wrong lesson. Returns null for a
     * name this build does not carry, so a caller can be told the real catalogue rather
     * than handed an empty answer.
     */
    fun byName(name: String?): Mask? {
        val wanted = name?.trim().orEmpty()
        if (wanted.isEmpty()) return null
        return ALL.firstOrNull { it.name.equals(wanted, ignoreCase = true) }
    }

    /** Every mask name, in catalogue order, for a refusal that names the real set. */
    fun names(): List<String> = ALL.map { it.name }
}
