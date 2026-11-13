// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

/**
 * One answer from a tool that looked at the world, with the three outcomes kept apart.
 *
 * WHY THIS TYPE EXISTS AT ALL (issue 337). Its own words: "a tool returns a value that a genuine
 * result could also produce". Individual tickets had been fixed one at a time — #221 and #224 for
 * arpScan, #222 for mdns, #44 for pmDump, #333 for ssdp — and each fix invented its own enum, so
 * the next tool was free to make the same mistake with a different name. The rule was a
 * preference that lived in one engineer's head.
 *
 * This is that rule expressed once, so a tool cannot express the wrong answer without doing it
 * deliberately:
 *
 *  - [Found]    looked, and there are things.
 *  - [Empty]    looked, and there is genuinely nothing. A real answer about the world.
 *  - [Refused]  could not look. Says nothing about the world, and carries the tier and the
 *               grant that would unlock it.
 *
 * THE COLLAPSE IS PREVENTED BY THE TYPE, not by discipline. There is no constructor that turns a
 * refusal into an empty list, no `valueOrEmpty()` that quietly defaults, and [values] is null on a
 * refusal rather than an empty list, so a caller who wants the items has to decide what a refusal
 * means at that point. A tool that wants to lie now has to write `when` and choose to.
 *
 * WHY [Refused] CARRIES A TIER AND A REMEDY. A refusal that does not say what would unlock it is
 * a dead end, and the model has nothing to tell the user. The tier is the vocabulary the rest of
 * the app already uses ([com.unuslumen.app.util.shell.ShellTier]), and [remedy] is the one action
 * a person could take.
 */
sealed interface ToolRead<out T> {

    /** The read happened and returned these items. */
    data class Found<T>(val items: List<T>) : ToolRead<T> {
        init {
            require(items.isNotEmpty()) {
                "Found must hold at least one item. An empty result is Empty, and the difference " +
                    "between the two is the whole point of this type (issue 337)."
            }
        }
    }

    /** The read happened and there is nothing there. A real answer. */
    data object Empty : ToolRead<Nothing>

    /**
     * The read could not happen.
     *
     * [tier] is the capability tier that served the call, so a reader can tell "the app tried and
     * was refused" from "the app never tried". [remedy] is what would unlock it, in words a person
     * can act on, and it is required: a refusal with no route is a closed door.
     */
    data class Refused(
        val tier: String,
        val reason: String,
        val remedy: String,
    ) : ToolRead<Nothing>

    /**
     * The items, or null when this is not a [Found].
     *
     * DELIBERATELY NOT `emptyList()` FOR THE OTHER TWO. A default of an empty list is exactly the
     * collapse this type exists to prevent: it lets a caller write `read.values.orEmpty()` and be
     * back to reporting a refusal as an absence, with no sign in the code that a choice was made.
     */
    val values: List<T>? get() = (this as? Found<T>)?.items

    /** True only when the read happened and found something. */
    val found: Boolean get() = this is Found<*>

    /** True when the read happened, whatever it found. This is the question "did we look". */
    val answered: Boolean get() = this is Found<*> || this is Empty

    /** True when nothing was looked at, so nothing here is a fact about the world. */
    val refused: Boolean get() = this is Refused
}

/**
 * The rules for building a [ToolRead] from what a tool actually observed.
 *
 * Kept as pure functions so a JVM test pins them with no device, no Robolectric and no shell. Every
 * one of the audited tools can be routed through one of these, which is what makes the habit
 * enforceable rather than remembered.
 */
object ToolReads {

    /**
     * A read that a shell call served.
     *
     * ORDER MATTERS, and this is the specific defect issues #44 and #221 both had. A failed call
     * can still print partial output — a banner before a denial, a half-written file — so the
     * items are only believed when the call itself succeeded. Checking the count first is how a
     * refusal becomes a small success.
     */
    fun <T> fromShell(
        succeeded: Boolean,
        items: List<T>,
        tier: String,
        refusalReason: String,
        remedy: String,
    ): ToolRead<T> = when {
        !succeeded -> ToolRead.Refused(tier, refusalReason, remedy)
        items.isEmpty() -> ToolRead.Empty
        else -> ToolRead.Found(items)
    }

    /**
     * A read that a grant the app does not hold blocked.
     *
     * The distinction from [fromShell] is that nothing was attempted: the app knew before it
     * started that the capability is not there. That is still a refusal and still must not render
     * as an absence, and the remedy is a grant rather than a different tier.
     */
    fun <T> missingGrant(permission: String, tier: String, remedy: String): ToolRead<T> =
        ToolRead.Refused(
            tier = tier,
            reason = "$permission is not granted, so nothing was read.",
            remedy = remedy,
        )

    /**
     * A read whose probe could not distinguish "nothing there" from "did not look".
     *
     * THIS IS THE HONEST ANSWER FOR A TOOL THAT CANNOT TELL, and it exists so that such a tool has
     * somewhere to say so rather than defaulting to an empty list. A tool in this state should be
     * fixed to probe properly; until it is, reporting a refusal is worse for the caller than
     * reporting nothing, and better than lying.
     */
    fun <T> cannotTell(what: String, tier: String, remedy: String): ToolRead<T> =
        ToolRead.Refused(
            tier = tier,
            reason = "The $what probe could not tell a refusal from an empty result, so it is " +
                "not reporting either.",
            remedy = remedy,
        )

    /**
     * One sentence a model can repeat without distorting it.
     *
     * The refusal branch is the one that matters: it names what could not be read, that this is
     * not a fact about the world, and what would unlock it. A caller that renders this instead of
     * a count cannot accidentally say the house is empty when the table was never opened.
     */
    fun <T> sentence(read: ToolRead<T>, plural: String = "items"): String = when (read) {
        is ToolRead.Found -> "${read.items.size} $plural."
        is ToolRead.Empty -> "None found. This is an answer about the world, not a failure."
        is ToolRead.Refused ->
            "${read.reason} This is not a result about the world, so do not report it as one. " +
                "What would unlock it: ${read.remedy}"
    }

    /**
     * The name a serialised result should carry for this read.
     *
     * The same three words `arpScan` already uses, so one vocabulary covers every tool rather than
     * each family inventing its own spelling of REFUSED.
     */
    fun outcomeName(read: ToolRead<*>): String = when (read) {
        is ToolRead.Found -> "FOUND"
        is ToolRead.Empty -> "EMPTY"
        is ToolRead.Refused -> "REFUSED"
    }

    /**
     * True when a refusal and an empty result could be confused by a reader of [reads].
     *
     * A guard for the audited set: if every tool in a family returns the same shape for both
     * outcomes, the family has not been fixed even if each one has a three-state enum. Used by
     * the test rather than at runtime, so the check itself cannot become a production branch no
     * one reads.
     */
    fun collapsesRefusalIntoEmpty(reads: List<ToolRead<*>>): Boolean =
        reads.any { it.refused } && reads.none { it.answered }
}
