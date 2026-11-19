// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.prompt

/**
 * Reading the served prompt stack back apart (issue 492).
 *
 * THE GAP. The registry exposed exactly one name containing "prompt":
 * `getSystemPrompt`, which returns the whole assembled string. There was nothing to
 * list the sections, read one, or propose a change. Section 8 of the Self Improvement
 * Engine's remit requires a record of meaningful changes to the agent's own
 * instructions, and Section 2 puts agent prompts inside the remit — against a surface
 * a model could not read in parts.
 *
 * WHY THIS SPLITS RATHER THAN WRITES. The assembled prompt arrives as one string, and
 * the rows behind it live in the server's database, which the device has no
 * credential to write. So the honest deliverable is: read the stack, list what it is
 * made of, and let a model PROPOSE an edit that is recorded and surfaced rather than
 * applied. A tool that claimed to write server rows from the phone would be the same
 * class of defect as the prompt rows that never reached a device at all.
 *
 * WHAT THE SPLIT IS, AND WHY IT IS THIS ONE. The assembled payload is every active
 * section joined with a blank line, in a fixed category order. It carries no
 * delimiter of its own, so a reader cannot recover the row boundaries exactly. What
 * it CAN recover is the structure a model needs: the frontmatter blocks each section
 * opens with, and the paragraphs between them. Both are listed, with their offsets,
 * so a caller can ask for one by index and get exactly the text it names.
 */
object PromptSections {

    /** One readable piece of the assembled prompt. */
    data class Section(
        /** Stable handle within this assembled payload: `section-0`, `section-1`, ... */
        val id: String,
        /** The first line, trimmed, up to a sensible length: enough to recognise it. */
        val heading: String,
        /** Character offset in the assembled payload where this piece starts. */
        val startOffset: Int,
        /** How many characters it holds. */
        val length: Int,
    )

    /**
     * Split the assembled prompt into readable sections.
     *
     * A SECTION BOUNDARY IS A BLANK LINE, because that is exactly the separator the
     * assembler uses when it joins the rows. Blank lines inside a fenced code block
     * are NOT boundaries: a prompt row routinely carries an example, and splitting
     * one down the middle would hand a caller half an instruction that reads as
     * complete.
     */
    fun split(assembled: String?): List<Section> {
        val payload = assembled.orEmpty()
        if (payload.isEmpty()) return emptyList()

        val sections = mutableListOf<Section>()
        var index = 0
        var cursor = 0
        var inFence = false

        fun closeSection(start: Int, endExclusive: Int) {
            if (endExclusive <= start) return
            val text = payload.substring(start, endExclusive).trim()
            if (text.isEmpty()) return
            val leadingTrim = payload.substring(start, endExclusive).indexOfFirst { !it.isWhitespace() }
            val realStart = start + leadingTrim.coerceAtLeast(0)
            sections.add(
                Section(
                    id = "section-$index",
                    heading = headingOf(text),
                    startOffset = realStart,
                    length = text.length,
                ),
            )
            index++
        }

        // A BLANK LINE BEFORE A FENCE IS NOT A BOUNDARY.
        //
        // A prompt row is written as "here is the rule:" then a blank line then the
        // fenced example. Treating that blank line as a section break puts the
        // introducing sentence in one section and the example in the next, which is
        // exactly the half-an-instruction failure this splitter exists to avoid. So a
        // boundary is DEFERRED while the next non-blank line opens a fence, and the
        // fence stays with the paragraph that introduces it.
        var pendingBoundary: Int? = null

        var sectionStart = 0
        while (cursor < payload.length) {
            val lineEnd = payload.indexOf('\n', cursor).let { if (it < 0) payload.length else it }
            val line = payload.substring(cursor, lineEnd)
            val trimmed = line.trimStart()

            if (trimmed.startsWith("```")) {
                // A fence that opens immediately after a deferred boundary claims that
                // paragraph, so the boundary is cancelled.
                pendingBoundary = null
                inFence = !inFence
            } else if (!inFence && line.isBlank()) {
                if (pendingBoundary == null) pendingBoundary = cursor
            } else if (!inFence && pendingBoundary != null) {
                // A real line after a blank line closes the section at the blank.
                closeSection(sectionStart, pendingBoundary!!)
                sectionStart = pendingBoundary!!.let { boundary ->
                    payload.indexOf('\n', boundary).let { if (it < 0) payload.length else it + 1 }
                }
                pendingBoundary = null
            }
            cursor = lineEnd + 1
        }
        // A trailing boundary closes the last section after it, not before it.
        if (pendingBoundary != null && !inFence) {
            closeSection(sectionStart, pendingBoundary!!)
            sectionStart = payload.indexOf('\n', pendingBoundary!!).let {
                if (it < 0) payload.length else it + 1
            }
        }
        closeSection(sectionStart, payload.length)
        return sections
    }

    /**
     * The exact text of one section, by its id.
     *
     * Returns null rather than an empty string when the id does not resolve, so a
     * caller can tell "no such section" from "a section that says nothing".
     */
    fun read(assembled: String?, sectionId: String): String? {
        val payload = assembled.orEmpty()
        val section = split(payload).firstOrNull { it.id == sectionId } ?: return null
        return payload.substring(section.startOffset, section.startOffset + section.length)
    }

    /**
     * A one-line heading for a section.
     *
     * The first non-empty line, with any markdown heading marks removed, trimmed to a
     * length that fits a listing. A section with no newline is its own heading.
     */
    fun headingOf(text: String): String = text
        .lineSequence()
        .map { it.trim() }
        .firstOrNull { it.isNotEmpty() }
        .orEmpty()
        .trimStart('#', '-', '*', ' ')
        .trim()
        .take(MAX_HEADING_CHARS)

    /** How long a heading may be before it stops being a heading and becomes the body. */
    const val MAX_HEADING_CHARS = 120
}
