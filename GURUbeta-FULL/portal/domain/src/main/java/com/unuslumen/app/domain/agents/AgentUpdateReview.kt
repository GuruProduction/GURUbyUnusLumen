// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * What changed between the agent somebody installed and the one the publisher is offering now.
 *
 * THE REQUIREMENT THIS EXISTS FOR, in the specification's own words: an update must not silently
 * grant new permissions. An agent that arrives from a catalogue holds real tools on a real phone,
 * and a publisher who adds `sendSms` to version 7 of their agent would be reaching a capability the
 * person never agreed to when they installed version 3. Trusting a version number to carry that
 * change quietly is the whole failure.
 *
 * SO THE DIFF IS COMPUTED AND PUT IN FRONT OF A PERSON. This object is that diff: what was added,
 * what was removed, and whether anything about the identity changed underneath them.
 *
 * WHY [addedTools] IS THE ONLY FIELD THAT BLOCKS. Removing a tool makes an agent weaker, which is
 * never a surprise worth stopping for. Changing the prompt alters behaviour but is the publisher's
 * business. Adding a tool is a new permission on the person's device, and that is the one that needs
 * a yes.
 */
data class AgentUpdateReview(
    val slug: String,
    val installedVersion: Int,
    val publishedVersion: Int,
    val installedName: String,
    val publishedName: String,
    val addedTools: Set<String>,
    val removedTools: Set<String>,
    val promptChanged: Boolean,
    val descriptionChanged: Boolean,
) {
    /** Is there anything at all to tell the person about? */
    val hasChanges: Boolean
        get() = addedTools.isNotEmpty() ||
            removedTools.isNotEmpty() ||
            promptChanged ||
            descriptionChanged ||
            installedName != publishedName

    /**
     * Does this update need an explicit decision before it may land?
     *
     * ONLY A NEW TOOL DOES. A silent permission increase is the thing the specification forbids; a
     * prompt tweak or a narrowed grant is not.
     */
    val needsApproval: Boolean get() = addedTools.isNotEmpty()

    /** A rename is a different kind of change: it is how a publisher impersonates another agent. */
    val identityChanged: Boolean get() = installedName != publishedName

    /**
     * One line for a surface with room for one line.
     *
     * NULL WHEN THERE IS NOTHING TO SAY, which is the common case and must not render a box.
     */
    fun headline(): String? = when {
        !hasChanges -> null
        needsApproval ->
            "This update asks for ${addedTools.size} new " +
                "permission${if (addedTools.size == 1) "" else "s"}: " +
                addedTools.sorted().joinToString(", ")
        identityChanged ->
            "This update renames the agent from '$installedName' to '$publishedName'."
        removedTools.isNotEmpty() ->
            "This update removes ${removedTools.size} tool${if (removedTools.size == 1) "" else "s"}: " +
                removedTools.sorted().joinToString(", ")
        promptChanged -> "This update changes the agent's instructions."
        else -> "This update changes the agent's description."
    }

    companion object {
        /**
         * Compare two versions of an agent.
         *
         * NOTHING TO COMPARE IS NOT A CHANGE. A fresh install has no previous version, and reporting
         * "everything was added" would train a person to dismiss the prompt that matters.
         */
        fun between(
            installedTools: Set<String>,
            installedPrompt: String,
            installedDescription: String,
            installedName: String,
            installedVersion: Int,
            publishedTools: Set<String>,
            publishedPrompt: String,
            publishedDescription: String,
            publishedName: String,
            publishedVersion: Int,
            slug: String,
        ): AgentUpdateReview = AgentUpdateReview(
            slug = slug,
            installedVersion = installedVersion,
            publishedVersion = publishedVersion,
            installedName = installedName,
            publishedName = publishedName,
            addedTools = publishedTools - installedTools,
            removedTools = installedTools - publishedTools,
            /*
             * COMPARED EXACTLY, NOT FUZZILY. A whitespace-only edit is a change the publisher made
             * and pretending otherwise would hide one. A false positive here costs a line of text;
             * a false negative costs a behaviour change nobody was told about.
             */
            promptChanged = installedPrompt != publishedPrompt,
            descriptionChanged = installedDescription != publishedDescription,
        )

        /**
         * Decide what to do with an update.
         *
         * THE VERSION NUMBERS DO NOT ENTER INTO IT, AND THAT IS DELIBERATE. A "downgrade" that
         * arrives carrying a tool the person did not have is not a downgrade, it is a permission
         * increase wearing an older number. Gating on whether the tool set grew, rather than on
         * whether the version fell, means the number cannot be used to smuggle a grant past the
         * check. An earlier draft had a second condition for downgrades, which was unreachable
         * because the first already covered every case where a tool was added.
         */
        fun dispositionFor(review: AgentUpdateReview): Disposition =
            if (review.needsApproval) Disposition.Ask(review) else Disposition.Silent
    }
}

/**
 * What to do about an update, as a decision rather than a comment.
 *
 * THREE OUTCOMES BECAUSE THERE ARE THREE REAL CASES. Silent applies the update, because nothing a
 * person needs to know changed. Ask holds it for a yes. Refuse is for a case the caller has already
 * decided against, and exists so a caller never has to express "no change" as a fake approval.
 *
 * TOP LEVEL RATHER THAN NESTED, because Kotlin cannot resolve a nested type through its enclosing
 * class from inside that class's own companion, and the alternative was four fully qualified names
 * at every call site.
 */
sealed interface Disposition {
    /** Nothing a person needs to see. Apply it. */
    data object Silent : Disposition

    /** A new permission. Hold it until somebody says yes. */
    data class Ask(val review: AgentUpdateReview) : Disposition

    /** The caller has decided not to apply it, and says why. */
    data class Refuse(val reason: String) : Disposition
}
