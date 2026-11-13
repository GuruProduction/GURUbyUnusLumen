// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.discovery

import com.unuslumen.app.util.shell.ShellResult
import com.unuslumen.app.util.shell.ShellTier

/**
 * How a read of a system table actually ended.
 *
 * WHY THIS TYPE EXISTS. Issues 221 and 224 are one fault filed twice: `arpScan`
 * returned `{"success":true,"devices":[]}` on a network with seven live hosts,
 * because `/proc/net/arp` is unreadable to an app UID and the refusal was
 * swallowed into an empty list. A caller then concludes the house is empty,
 * which is a false statement about the physical world presented as a measurement.
 *
 * THREE OUTCOMES, NEVER TWO. The whole distinction is that [REFUSED] is not
 * [EMPTY] and must never be rendered as one:
 *
 *  - [FOUND]    the table was read and has entries. A real answer.
 *  - [EMPTY]    the table was read and genuinely has no entries. Also a real
 *               answer, and a different one.
 *  - [REFUSED]  the table could not be opened. This says nothing about the
 *               network, and the answer is about permissions or the tier.
 *
 * A tool that collapses [REFUSED] into [EMPTY] is worse than one that fails
 * loudly, because the model trusts it and reports the absence onward as fact.
 * That is issue 337's whole class, and this file is the shape it asks for.
 */
enum class NetworkReadOutcome {
    FOUND,
    EMPTY,
    REFUSED,
}

/**
 * One table read, decided purely from what the shell returned.
 *
 * Deliberately free of Android imports so a JVM test pins the rule with no
 * Robolectric and no device. [ShellResult] and [ShellTier] are plain Kotlin in
 * `:core:util`, so constructing one here costs nothing.
 */
object NetworkReads {

    /**
     * The outcome of one read, and the sentence a caller should show for it.
     *
     * @param result what the shell actually returned, including the tier that
     *   served it and any denial it already detected.
     * @param what the table's name in a sentence, e.g. "ARP table".
     * @param count how many entries the parser recovered from the output.
     */
    data class Read(
        val outcome: NetworkReadOutcome,
        val tier: ShellTier,
        /** One actionable sentence when [outcome] is [NetworkReadOutcome.REFUSED], else null. */
        val sentence: String?,
    ) {
        /** True when the read happened, whatever it found. */
        val answered: Boolean get() = outcome != NetworkReadOutcome.REFUSED
    }

    /**
     * Decide an outcome from a finished shell call and the entries parsed out of it.
     *
     * The order matters. Failure is checked first, because a command that failed
     * can still print partial output (a half-written file, a banner before a
     * denial) and counting those entries would turn a refusal into a small
     * success. Only a call that actually succeeded may be believed about its
     * count.
     */
    fun verdict(result: ShellResult, what: String, count: Int): Read = when {
        !result.success -> Read(
            outcome = NetworkReadOutcome.REFUSED,
            tier = result.tier,
            sentence = refusalSentence(result, what),
        )
        count == 0 -> Read(
            outcome = NetworkReadOutcome.EMPTY,
            tier = result.tier,
            sentence = null,
        )
        else -> Read(
            outcome = NetworkReadOutcome.FOUND,
            tier = result.tier,
            sentence = null,
        )
    }

    /**
     * The sentence for a refused read.
     *
     * Shape: that the table was refused, what that means for the answer, then the
     * tier's own capability wording reused rather than reinvented, then the route
     * that would unlock it. Reusing [ShellTier.capabilities] is the same decision
     * [com.unuslumen.app.util.shell.ShellDenialSignatures] made: a denial described
     * in words the tier does not actually promise is its own kind of lie.
     */
    fun refusalSentence(result: ShellResult, what: String): String {
        val detail = result.denial
            ?: result.stderr.ifBlank { result.stdout }
                .lineSequence()
                .firstOrNull { it.isNotBlank() }
                ?.trim()

        return buildString {
            append("The ")
            append(what)
            append(" was refused, so read this as a table that was never opened rather than a ")
            append("network that is empty. ")
            append(result.tier.capabilities())
            append('.')
            if (detail != null) {
                append(" The read said: ")
                append(detail)
                append('.')
            }
            if (result.tier == ShellTier.APP_UID || result.tier == ShellTier.NONE) {
                append(" Android hides /proc/net/arp from an app UID, so Shizuku or an ADB shell ")
                append("is what makes this read succeed.")
            }
        }
    }
}
