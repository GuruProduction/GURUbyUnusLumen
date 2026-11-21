// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

import com.unuslumen.app.domain.model.AiMessage

/**
 * How the older half of a conversation is written into an agent's notebook.
 *
 * WHY THIS IS A SEPARATE OBJECT AND NOT A PRIVATE METHOD. The fold decides what survives when a
 * conversation gets too large to send again, and section 2.3.3 asks it to preserve outstanding
 * questions, decisions, tool results, constraints and the current plan. That is a rule with a
 * right and a wrong answer, so it is a value function that a test can put documents through
 * rather than a step inside a class holding a live session that nothing can construct cheaply.
 *
 * THE DISTINCTION IT EXISTS TO DRAW: an ordinary tool call is a step, a decision is why the work
 * went that way, and a plan is where it is going. Reading a decision back out of the notes as
 * "RAN recordDecision" describes a call and loses the reason, which is the part a later reader
 * needs. So a decision and a plan get their own heading and their own, larger, allowance.
 */
object AgentNotebookFold {

    /** How much of one tool result is quoted verbatim before it is shortened. */
    const val EXCERPT_CHARS: Int = 300

    /**
     * How much of a decision or plan field is kept.
     *
     * LARGER THAN AN ORDINARY RESULT, AND STILL BOUNDED. A decision carries a problem, an approach,
     * a reason and the evidence behind it, so 300 characters reliably cuts before the reason. The
     * bound is real because the notebook rides in every later request.
     */
    const val DOCUMENT_EXCERPT_CHARS: Int = 700

    /** The heading a recorded decision gets, so it is not read back as a tool call. */
    const val DECIDED_LABEL: String = "DECIDED"

    /** The heading a plan revision gets. */
    const val PLANNED_LABEL: String = "PLANNED"

    /**
     * The names of the tools whose output is a document rather than a step.
     *
     * PASSED IN RATHER THAN IMPORTED. The runtime holds these names as its own constants and the
     * tool modules own their definitions; a domain object importing either would invert the layering.
     * The caller supplies what it recognises, which also means a new document-shaped tool is added
     * in one place.
     */
    data class DocumentTools(
        val decisions: Set<String> = emptySet(),
        val plans: Set<String> = emptySet(),
    )

    /**
     * One message, as a line or lines of notes.
     *
     * Returns null for a message with nothing worth writing down, so the caller can concatenate
     * without deciding what counts as empty. A message that is present but carries no content is
     * not the same as one that never existed, but in a notebook both leave nothing to read.
     */
    fun line(
        message: AiMessage,
        documents: DocumentTools,
        excerptChars: Int = EXCERPT_CHARS,
        documentExcerptChars: Int = DOCUMENT_EXCERPT_CHARS,
    ): String? = when (message) {
        is AiMessage.UserMessage -> "INSTRUCTION: ${clip(message.content, excerptChars)}\n"

        is AiMessage.ToolCall -> {
            val failed = if (message.isFailed) " (FAILED)" else ""
            when (message.name) {
                in documents.decisions ->
                    "$DECIDED_LABEL$failed: ${clip(message.rawContent, documentExcerptChars)}\n"

                in documents.plans ->
                    "$PLANNED_LABEL$failed: ${clip(message.rawContent, documentExcerptChars)}\n"

                else -> buildString {
                    append("RAN ").append(message.name).append(failed).append(": ")
                    append(clip(message.rawContent, excerptChars)).append('\n')
                    if (message.resultRawContent.isNotBlank()) {
                        append("  RETURNED: ")
                        append(clip(message.resultRawContent, excerptChars)).append('\n')
                    }
                }
            }
        }

        is AiMessage.AssistantMessage ->
            if (message.content.isBlank()) null else "SAID: ${clip(message.content, excerptChars)}\n"

        // A partial message is a stream that had not finished when the fold ran. Writing it down
        // would turn half a sentence into a note that reads as a completed thought.
        is AiMessage.StreamingAssistant, is AiMessage.StreamingToolCall, is AiMessage.PortalMessage -> null
    }

    /**
     * Fold a middle section into the notebook that already exists.
     *
     * THE BOUNDARY LINE SAYS WHERE IT HAPPENED, because a reader who did not fold it cannot
     * otherwise tell one continuous account from two that were joined. It names the round so the
     * note lines up with the ledger.
     */
    fun append(
        existing: String,
        middle: List<AiMessage>,
        round: Int,
        documents: DocumentTools,
    ): String {
        val written = StringBuilder(existing)
        if (written.isNotEmpty()) written.append('\n')
        written.append("— folded at round ").append(round).append(" —\n")
        for (message in middle) {
            line(message, documents)?.let { written.append(it) }
        }
        return written.toString()
    }

    /** One field, flattened to a single line and cut at the allowance. */
    private fun clip(value: String, allowance: Int): String =
        value.take(allowance).replace('\n', ' ')
}
