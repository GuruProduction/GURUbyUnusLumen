// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.model

/**
 * Decides whether an agent's message may interrupt its owner right now.
 *
 * WHY THIS IS A DECISION RATHER THAN A BOOLEAN. `AgentNotifications` has carried an enabled flag, a
 * minimum urgency and a quiet window since Phase 2 and nothing has ever read them, so an agent's
 * notification preferences were a setting that changed nothing. The specification requires an
 * agent's ability to initiate communication to be configurable, with urgency thresholds and quiet
 * periods, and a configured preference that no code consults is worse than no preference at all
 * because it tells the owner they have control they do not have.
 *
 * THE MESSAGE IS ALWAYS RECORDED AND ALWAYS VISIBLE IN THE ROOM. This decides only whether it also
 * interrupts. Suppressing the notification must never suppress the finding: a person who set a quiet
 * window asked not to be woken, not to be kept ignorant, and the room shows what arrived either way.
 *
 * IT IS A PURE FUNCTION OF TWO VALUES. No clock, no context, no database. The caller supplies the
 * minute of the day, which is what makes the whole rule testable without a device and without waiting
 * for 22:00.
 */
object AgentNotificationPolicy {

    /** Whether a message may interrupt, and why when it may not. */
    data class Verdict(
        val shouldNotify: Boolean,
        /**
         * Why not, in words a surface can show.
         *
         * IT IS NULL WHEN THE MESSAGE MAY INTERRUPT. A reason for a thing that happened would be a
         * second way to say yes, and two ways to say yes eventually disagree.
         */
        val suppressedBecause: String?,
    ) {
        val isSuppressed: Boolean get() = !shouldNotify
    }

    /**
     * Decide for one message.
     *
     * THE ORDER OF THE CHECKS IS THE ORDER OF THE OWNER'S INTENT: the whole feature off beats a
     * threshold, and a threshold beats a quiet window. An agent whose notifications are disabled is
     * silent whatever urgency it claims, and an agent below its owner's threshold is silent even
     * outside quiet hours. Reversing those would let a high-urgency message override a master switch,
     * which is the one thing an owner turning notifications off means.
     */
    fun evaluate(
        notifications: AgentNotifications,
        urgency: AgentUrgency,
        minuteOfDay: Int,
    ): Verdict {
        if (!notifications.enabled) {
            return Verdict(
                shouldNotify = false,
                suppressedBecause = "This agent's notifications are switched off, so it will not " +
                    "interrupt. What it found is in its room.",
            )
        }

        if (rank(urgency) < rank(notifications.minUrgency)) {
            return Verdict(
                shouldNotify = false,
                suppressedBecause = "This is ${urgency.name.lowercase()} urgency and its owner asked " +
                    "to be interrupted only for ${notifications.minUrgency.name.lowercase()} and " +
                    "above.",
            )
        }

        /*
         * BLOCKING BEATS THE QUIET WINDOW, AND ONLY BLOCKING.
         *
         * A quiet window means "do not wake me for ordinary work". BLOCKING is the urgency an agent
         * uses when it cannot continue at all, and an agent that has stopped dead at 03:00 with
         * nobody knowing is not being considerate, it is stuck. Everything below BLOCKING waits for
         * morning, which is what the window was asked for.
         */
        if (notifications.isQuietAt(minuteOfDay) && urgency != AgentUrgency.BLOCKING) {
            return Verdict(
                shouldNotify = false,
                suppressedBecause = "It is inside this agent's quiet hours, so the message waits. " +
                    "What it found is in its room.",
            )
        }

        return Verdict(shouldNotify = true, suppressedBecause = null)
    }

    /**
     * Order the urgencies so a threshold is a comparison rather than three special cases.
     *
     * DECLARED ORDER IS THE CONTRACT. LOW is least and BLOCKING is most, and a test pins the ranking
     * so reordering the enum cannot silently invert every threshold in the app.
     */
    fun rank(urgency: AgentUrgency): Int = when (urgency) {
        AgentUrgency.LOW -> 0
        AgentUrgency.NORMAL -> 1
        AgentUrgency.HIGH -> 2
        AgentUrgency.BLOCKING -> 3
    }
}
