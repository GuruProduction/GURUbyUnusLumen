// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.memory

/**
 * The shape of a `tool_defect` memory (issue 466).
 *
 * WHY A SHAPE RATHER THAN A CATEGORY AND A SENTENCE. The issue names five things a
 * defect record needs: which tool, what it did, what the correct behaviour is, the
 * workaround, and the build it was observed on. The last one is the load-bearing
 * one, because it is what lets an agent notice the bug has been fixed and stop
 * applying a workaround that is now doing harm.
 *
 * Without the observation build, every workaround an agent writes is permanent by
 * default: it is retrieved by similarity, applied long after it stopped being true,
 * and nothing ever contradicts it. That is worse than forgetting, because a stale
 * workaround is a believed falsehood.
 *
 * THE RENDERED FORM IS PROSE. The brain stores facts as sentences and the write gate
 * refuses raw machine blobs, so this builds a reader's paragraph rather than a JSON
 * blob. A person reading it in a listing learns the same thing an agent does.
 */
data class ToolDefectNote(
    /** The tool that misbehaved, by the name a caller invokes. */
    val toolName: String,

    /** What it did, and what the caller expected instead. Both, because a symptom
     *  without the expectation is not a defect. */
    val observed: String,

    /** The correct behaviour, in the terms the tool promises. */
    val expected: String,

    /**
     * What works while the defect stands.
     *
     * Blank is legitimate: a defect can be recorded before a workaround is found,
     * and that is still worth remembering because it stops the agent concluding the
     * tool is broken in a new way every session.
     */
    val workaround: String = "",

    /**
     * The build this was observed on, as the app reports it: `3.80 (104)`.
     *
     * THIS IS THE FIELD THAT MAKES THE RECORD EXPIRE. A workaround retrieved on a
     * later build can be checked against this one and, if the version has moved,
     * treated as due for re-testing rather than as current truth.
     */
    val observedOnBuild: String,
) {
    /** Whether a workaround was recorded at all. */
    val hasWorkaround: Boolean get() = workaround.isNotBlank()

    /**
     * The memory text.
     *
     * Written as a sentence so it passes the write gate and reads properly in a
     * listing, with the build named last because that is the part a reader needs
     * when deciding whether to trust it today.
     */
    fun toFactText(): String = buildString {
        append(toolName.trim())
        append(" did not do what it promises: ")
        append(observed.trim())
        append(" It should have ")
        append(expected.trim().removeSuffix("."))
        append(". ")
        if (hasWorkaround) {
            append("What works for now: ")
            append(workaround.trim())
            append(" ")
        }
        append("Observed on build ")
        append(observedOnBuild.trim())
        append(".")
    }
}
