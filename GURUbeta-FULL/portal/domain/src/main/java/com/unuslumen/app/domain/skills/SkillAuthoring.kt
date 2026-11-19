// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.skills

/**
 * What a skill a model writes has to be before it is stored (issue 492).
 *
 * THE GAP. The registry exposed six skill names and not one created or edited a
 * skill: four were the library, one was discovery and one was execution. The Self
 * Improvement Engine's remit names skills as part of development and tells the agent
 * to build them, and it could use one and install someone else's but never author
 * one.
 *
 * The repository underneath already had `createSkill`, `updateSkill`, `enableSkill`,
 * `disableSkill` and `deleteSkill`. What was missing was a tool reaching them, and a
 * rule about what makes a written skill usable.
 *
 * WHY A VALIDATOR RATHER THAN A BARE PASS-THROUGH. A skill is a prompt injection the
 * agent chose to accept: its body expands into the model's context when it is used.
 * A skill with no name cannot be found, one with no body does nothing when it runs,
 * and one whose allowed-tools name tools that do not exist grants nothing while
 * reading as though it granted something. Those three failures are cheap to catch at
 * the write and expensive to find at the moment a methodology is needed.
 */
object SkillAuthoring {

    /** A skill's name must be findable: letters, digits, dashes and spaces. */
    private val NAME_ALLOWED = Regex("^[A-Za-z0-9][A-Za-z0-9 _-]{0,63}$")

    /** What is wrong with a proposed skill, or none. */
    sealed interface Verdict {
        data object Valid : Verdict
        data class Invalid(val reason: String) : Verdict
    }

    /**
     * Check a skill before it is written.
     *
     * `knownToolNames` is the registry's own set. An allowed-tool that is not in it
     * is refused BY NAME rather than dropped, because a silently dropped tool is a
     * skill that believes it can do something it cannot.
     */
    fun validate(
        name: String?,
        description: String?,
        bodyMarkdown: String?,
        allowedTools: List<String>,
        knownToolNames: Set<String>,
    ): Verdict {
        val trimmedName = name?.trim().orEmpty()
        if (trimmedName.isEmpty()) {
            return Verdict.Invalid("A skill needs a name, or nothing can find it again.")
        }
        if (!NAME_ALLOWED.matches(trimmedName)) {
            return Verdict.Invalid(
                "The name \"$trimmedName\" is not usable: use letters, digits, dashes and spaces, " +
                    "starting with a letter or digit, up to 64 characters.",
            )
        }
        if (description?.trim().isNullOrEmpty()) {
            return Verdict.Invalid(
                "A skill needs a one-line description: it is what a listing shows and how a " +
                    "caller decides whether to read the body.",
            )
        }
        val body = bodyMarkdown?.trim().orEmpty()
        if (body.isEmpty()) {
            return Verdict.Invalid(
                "A skill needs a body. The body is the instruction set that expands when the " +
                    "skill is used, so a skill with none does nothing.",
            )
        }
        if (body.length < MIN_BODY_CHARS) {
            return Verdict.Invalid(
                "The body is ${body.length} characters, which is too short to be a methodology. " +
                    "Write the steps a future run would need.",
            )
        }
        // A frontmatter block is part of the format the skill loader reads. A body that
        // opens with `---` and never closes it leaves every field after it unparsed,
        // which reads as a skill that loaded and has no allowed tools.
        if (body.startsWith("---")) {
            val closing = body.indexOf("\n---", startIndex = 3)
            if (closing < 0) {
                return Verdict.Invalid(
                    "The body opens a frontmatter block with --- and never closes it. " +
                        "Close it with a second --- or remove the opening one.",
                )
            }
        }
        val unknown = allowedTools.firstOrNull { it !in knownToolNames }
        if (unknown != null) {
            return Verdict.Invalid(
                "\"$unknown\" is not a registered tool, so granting it would grant nothing " +
                    "while reading as though it did. Ask what the tool is called, or omit it " +
                    "and grant the tools you will actually use.",
            )
        }
        return Verdict.Valid
    }

    /**
     * The smallest body worth storing.
     *
     * Not a style rule: a body under this length cannot carry a trigger, a sequence
     * and a success condition, which are the three things a methodology needs to
     * change behaviour on a future run.
     */
    const val MIN_BODY_CHARS = 80

    /**
     * A slug derived from a skill's name, so a created skill has a stable handle.
     *
     * Derived rather than asked for, because a slug is a machine concept and the house
     * rule is that a machine concept is derived and never shown to a person.
     */
    fun slugFor(name: String): String = name
        .trim()
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')
        .take(64)
        .ifEmpty { "skill" }

    /**
     * A when-to-use line for a skill written without one.
     *
     * Derived rather than left empty, because the trigger is what makes a skill fire
     * on its own. A skill with no trigger is one the agent must remember to use, which
     * is the same as one it will not use. The line is built from the skill's own words
     * so it describes this skill and no other.
     */
    fun defaultWhenToUse(name: String, description: String): String {
        val what = description.trim().ifEmpty { name.trim() }.trimEnd('.')
        return "Use when the task needs $what."
    }
}
