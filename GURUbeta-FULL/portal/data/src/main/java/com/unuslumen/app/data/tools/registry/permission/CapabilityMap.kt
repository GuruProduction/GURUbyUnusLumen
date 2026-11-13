// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry.permission

/**
 * What GURU can and cannot do right now, in a person's words rather than a permission's.
 *
 * WHY THIS EXISTS (issue 181). Its own goal: hand the phone to somebody who has never heard of a
 * language model and have them productive in five minutes. The measured obstacle is nine setup
 * steps, five of which require knowing what the thing is before you can grant it.
 *
 * The acceptance criterion this file answers is the map: "a capability map screen listing what
 * works, what does not, and what each missing grant unlocks", sorted by value rather than by
 * permission name, with no technical jargon in any user-facing text.
 *
 * WHY IT IS PURE. A map that is assembled in a composable cannot be tested, and this is the screen
 * whose whole job is being true about what works. So the entries, the ordering, the grouping and the
 * sentences are decided here from [GrantState] values, and the presentation layer only draws what
 * it is given.
 *
 * THE ONE RULE THAT MATTERS: an entry is never allowed to say a capability is unavailable without
 * saying what would unlock it, unless the honest answer is that nothing can. Those are two
 * different sentences and a person acts differently on each, which is the distinction [GrantState]
 * already carries.
 */
object CapabilityMap {

    /**
     * One line of the map.
     *
     * [unlockedBy] is the single grant that would turn this from unavailable to available, or null
     * when no grant exists that could. A caller renders [sentence] and does not have to invent one.
     */
    data class Entry(
        /** Stable id, for a list key and for a test to name a row. */
        val id: String,
        /** What GURU can do, in the words a person would use. Never a permission name. */
        val capability: String,
        val available: Boolean,
        /** The grant this needs, in a person's words, or null when nothing would help. */
        val unlockedBy: String? = null,
        /** True when the reason it cannot work is the phone rather than a missing grant. */
        val isDeviceLimitation: Boolean = false,
        /**
         * How much this is worth to the person, used for ordering.
         *
         * HIGH is what makes GURU useful at all: talking to it, seeing the screen, reading messages.
         * LOW is a refinement. The ticket asks for value order rather than permission-name order,
         * because an alphabetical list of forty-four permissions is a wall and a value-ordered one
         * starts with the three things that matter.
         */
        val value: Value = Value.MEDIUM,
    ) {
        /**
         * One sentence a person can act on.
         *
         * THREE SHAPES, and the difference is the whole point: available reads as a plain statement,
         * a missing grant reads as an action ("granting X would let me Y"), and a device limitation
         * reads as a fact about the phone so nobody hunts for a setting that does not exist.
         */
        val sentence: String
            get() = when {
                available -> "I can $capability."
                isDeviceLimitation ->
                    "I cannot $capability, because this phone does not have what that needs."
                unlockedBy != null ->
                    "I cannot $capability yet. Granting $unlockedBy would let me."
                else ->
                    "I cannot $capability, and no setting on this phone changes that."
            }
    }

    enum class Value { HIGH, MEDIUM, LOW }

    /**
     * Build the map from the checks the registry already produced.
     *
     * ORDERING IS BY VALUE FIRST, then unavailable-before-available inside each value band, because
     * the thing a person came to this screen for is what is MISSING. A list that opens with fifteen
     * things that already work buries the one thing they wanted to fix.
     *
     * Duplicates are impossible by construction: the input is keyed on the permission, so one
     * capability appears once however many tools use it. That matters because forty-four tools share
     * far fewer permissions, and a map with one row per tool would be unreadable.
     */
    fun build(checks: List<GrantCheck>): List<Entry> =
        checks
            .map { entryOf(it) }
            /*
             * ONE ROW PER PERMISSION, because forty-four tools share far fewer permissions and a
             * row per tool is not a list a person can act on. Deduplicating here rather than relying
             * on the caller means the screen cannot show the same capability twice however the
             * registry is wired later.
             *
             * A duplicate is resolved by keeping the AVAILABLE one, because a capability that works
             * for one tool and not another is available: the row exists to tell a person what they
             * can do, and hiding a working capability behind a duplicate denial would be the
             * pessimism in the other direction.
             */
            .groupBy { it.id }
            .map { (_, sameCapability) ->
                sameCapability.firstOrNull { it.available } ?: sameCapability.first()
            }
            .sortedWith(
                compareBy<Entry> { it.value.ordinal }
                    .thenBy { if (it.available) 1 else 0 }
                    .thenBy { it.capability },
            )

    /**
     * Turn one grant check into one map entry.
     *
     * THE CAPABILITY IS THE SPEC'S OWN `why` CLAUSE, which is already written as "GURU can ..." for
     * every entry in the catalogue. Reusing it means the map and the refusal sentence a tool returns
     * say the same thing, which is what stops a person reading two different descriptions of one
     * capability.
     */
    fun entryOf(check: GrantCheck): Entry {
        val spec = check.spec
        val capability = spec.why
            // The catalogue writes these clauses beginning "GURU can ...", and the entry's sentence
            // already supplies the subject, so the prefix comes off rather than being repeated.
            .removePrefix("GURU can ")
            .removePrefix("GURU could ")
            .removePrefix("GURU ")
            .trim()
            .trimEnd('.')
            .replaceFirstChar { it.lowercase() }

        return Entry(
            id = spec.permission,
            capability = capability,
            available = check.allowed,
            unlockedBy = when (check.state) {
                GrantState.GRANTED, GrantState.INSTALL -> null
                // A device that lacks the hardware cannot be fixed by a grant, and saying
                // "granting X would let me" about a missing sensor would send somebody to a
                // settings screen that has nothing to do with it.
                GrantState.ABSENT -> null
                GrantState.NOT_GRANTABLE, GrantState.UNKNOWN_SPEC -> null
                GrantState.PRIVILEGED -> spec.label
                GrantState.NOT_GRANTED, GrantState.UNCATALOGUED -> spec.label
            },
            isDeviceLimitation = check.state == GrantState.ABSENT,
            value = valueOf(spec),
        )
    }

    /**
     * How much a capability is worth, by the permission it needs.
     *
     * SET HERE RATHER THAN IN THE CATALOGUE, because this is a product judgement and the catalogue
     * is a technical record. A permission can be added without deciding its value, and this answers
     * MEDIUM for anything it does not know rather than pretending.
     *
     * HIGH covers what the acceptance list names as making GURU usable at all: talking to it, seeing
     * the screen, tapping for the person, and reading their messages.
     */
    fun valueOf(spec: PermissionSpec): Value = when {
        spec.tier == PermissionTier.INSTALL -> Value.LOW
        spec.permission.endsWith("POST_NOTIFICATIONS") -> Value.HIGH
        spec.permission.contains("MEDIA_PROJECTION") -> Value.HIGH
        spec.permission.contains("ACCESSIBILITY") -> Value.HIGH
        spec.permission.contains("NOTIFICATION_LISTENER") ||
            spec.permission.contains("BIND_NOTIFICATION") -> Value.HIGH
        spec.permission.contains("READ_SMS") ||
            spec.permission.contains("READ_CALL_LOG") ||
            spec.permission.contains("READ_CONTACTS") -> Value.HIGH
        spec.permission.contains("MANAGE_EXTERNAL_STORAGE") -> Value.MEDIUM
        spec.permission.contains("RECORD_AUDIO") -> Value.MEDIUM
        spec.permission.contains("CAMERA") -> Value.MEDIUM
        spec.permission.contains("ACCESS_FINE_LOCATION") -> Value.MEDIUM
        spec.permission.contains("READ_CALENDAR") -> Value.MEDIUM
        spec.tier == PermissionTier.PRIVILEGED -> Value.LOW
        else -> Value.MEDIUM
    }

    /**
     * The count a person reads first: how many capabilities are working.
     *
     * SEPARATE FROM THE LIST, because a screen that opens with "3 of 12 things work" tells a person
     * more in one line than twelve rows do, and it is what makes progress visible as they grant.
     */
    data class Summary(val available: Int, val total: Int) {
        val sentence: String
            get() = when {
                total == 0 -> "Nothing has been reported yet, so there is nothing to show."
                available == total -> "Everything GURU can do on this phone is working."
                available == 0 ->
                    "Nothing is working yet. Every row below names the one grant that would " +
                        "change that."
                else ->
                    "$available of $total things are working. The rows that are not name the one " +
                        "grant that would change it."
            }
    }

    fun summarise(entries: List<Entry>): Summary =
        Summary(available = entries.count { it.available }, total = entries.size)
}
