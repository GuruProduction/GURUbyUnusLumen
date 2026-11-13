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
 * What a *listener* is doing, which is a different question from what a read found.
 *
 * WHY THIS IS SEPARATE FROM [NetworkReadOutcome]. A table read is a single act that
 * either happened or did not. A listener runs over time, so "nothing heard yet" is a
 * legitimate state, and it is neither "found nothing" nor "never ran". Issue 222 is
 * exactly that: `mdnsListDevices` returned `[]` whether the listener had never
 * started, had failed to bind, was running and had not been seen yet, or was running
 * on a network with no mDNS traffic at all. Four different facts, one empty array.
 *
 * COLLAPSING [LISTENING] INTO [STOPPED] WOULD BE THE SAME BUG IN REVERSE. A listener
 * that is up and has heard nothing tells you something real about the network. A
 * listener that is down tells you nothing at all, and saying so is the whole point.
 */
enum class ListenerState {
    /** Never started, or started and then stopped. Nothing is being heard, by choice. */
    STOPPED,
    /** Every service type was asked and Android refused to start any of them. */
    BIND_FAILED,
    /** At least one listener is up but some types were refused, so results are partial. */
    PARTIAL,
    /** A listener is up and has found nothing yet. A real answer about the network. */
    LISTENING,
    /** A listener is up and has found devices. */
    FOUND,
}

/**
 * What a listener has to say for itself.
 *
 * [refusedTypes] is carried rather than flattened into [state] because a partial bind
 * is the case a reader most needs: before treating silence on `_airplay._tcp` as
 * evidence that no Apple TV exists, they need to know that type was being watched.
 */
data class ListenerReport(
    val state: ListenerState,
    /** Service types the platform accepted. */
    val listening: List<String> = emptyList(),
    /** Service types the platform refused, with the platform's own error code. */
    val refusedTypes: List<String> = emptyList(),
    /** How many devices are held right now. */
    val found: Int = 0,
    /** One sentence when the state is not a plain answer, naming the route where one exists. */
    val sentence: String? = null,
) {
    /** True when at least one listener is actually running. */
    val watching: Boolean
        get() = state == ListenerState.LISTENING ||
            state == ListenerState.PARTIAL ||
            state == ListenerState.FOUND
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
     * Decide what a listener is doing from what the platform said, and what is held.
     *
     * THE FOUR FACTS ISSUE 222 NAMED, KEPT APART. A listener that was never started, one
     * the platform refused to start, one that is up with nothing heard yet, and one that
     * is up with results are four different answers, and an empty array cannot carry any
     * of them. The decision lives here, pure, so the rule needs no NsdManager to prove.
     *
     * [started] is whether startDiscovery was called at all. [listening] and [refused]
     * are per-service-type, because Android refuses types individually: a partial bind
     * is common and is the case where silence on one type is least trustworthy.
     */
    fun listenerReport(
        started: Boolean,
        listening: List<String>,
        refused: List<String>,
        found: Int,
    ): ListenerReport {
        if (!started) {
            return ListenerReport(
                state = ListenerState.STOPPED,
                listening = listening,
                refusedTypes = refused,
                found = found,
                sentence = "Discovery has not been started, so an empty list here would say " +
                    "nothing about the network. Start discovery first.",
            )
        }

        if (listening.isEmpty()) {
            return ListenerReport(
                state = ListenerState.BIND_FAILED,
                listening = emptyList(),
                refusedTypes = refused,
                found = found,
                sentence = "Android refused every mDNS service type, so nothing is being " +
                    "listened for and this is not a report about the network. " +
                    refused.firstOrNull()?.let { "The first refusal was $it. " }.orEmpty() +
                    "Another app holding the multicast group, or multicast being filtered on " +
                    "this wifi, are the usual causes.",
            )
        }

        val state = when {
            refused.isNotEmpty() -> ListenerState.PARTIAL
            found > 0 -> ListenerState.FOUND
            else -> ListenerState.LISTENING
        }

        return ListenerReport(
            state = state,
            listening = listening,
            refusedTypes = refused,
            found = found,
            sentence = if (state == ListenerState.PARTIAL) {
                "Android refused ${refused.joinToString(", ")}, so nothing is listening for " +
                    "those and their silence is not evidence of absence. " +
                    listening.joinToString(", ") + " are being watched."
            } else {
                null
            },
        )
    }

    /**
     * How a listener's state renders in one line a model can repeat without distorting it.
     *
     * The distinction the sentence protects is the one issue 222 was filed about: a
     * listener that is up with no results is describing the network, and a listener that
     * failed to bind is describing the app.
     */
    fun listenerSentence(report: ListenerReport): String? = report.sentence

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
