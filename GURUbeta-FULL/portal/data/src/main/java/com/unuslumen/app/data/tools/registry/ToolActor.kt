// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

/**
 * Who is calling a tool.
 *
 * WHY THIS EXISTS. GURU and every spawned agent write into one Cerebrum brain,
 * and until now nothing on the write path said which of them wrote a fact. The
 * provenance label was built as `guru.role= conversation= message=` because the
 * tool lane never populated a single one of those three fields: `persistFacts`
 * called `BrainService.storeFact(fact)` with one argument and every other
 * parameter took its empty default. So Self Improvement Engine, Code Engineer and
 * GURU reasoning in the main chat all produced byte-identical provenance, and a
 * contradiction between two agents was indistinguishable in storage from one
 * agent changing its mind.
 *
 * THE RULE THIS TYPE ENFORCES IS HONESTY, NOT COMPLETENESS. A field that is not
 * known stays empty rather than being filled with a plausible value. An
 * unattributed call is [UNATTRIBUTED], which writes exactly the provenance the
 * app wrote before this type existed, so every existing memory keeps the identity
 * and the label it already had.
 *
 * WHAT IT IS NOT. This is not authentication. A caller that lies about its own
 * identity is believed, because the actor travels as a value through the
 * dispatcher rather than being proved by the platform. It records attribution for
 * a system whose agents are all running in one process on one device, which is
 * the threat model this app actually has. A model that wanted to forge another
 * agent's name would have to be running code the dispatcher did not hand the
 * actor to.
 */
data class ToolActor(
    /** [KIND_GURU], [KIND_AGENT], or empty for a call nobody claimed. */
    val kind: String = "",
    /** The `guru_agents` row id of a spawned agent. Empty for GURU and for anyone else. */
    val agentId: String = "",
    /** The agent's display name, carried so the label is readable without a database read. */
    val agentName: String = "",
    /** The conversation this call happened inside. Empty when the caller has none. */
    val conversationId: String = "",
    /** The message being answered. Empty when the caller has none. */
    val messageId: String = "",
) {

    /** Nobody claimed this call. Writes the same provenance the app wrote before attribution existed. */
    val isAttributed: Boolean get() = kind.isNotBlank()

    /**
     * What goes in the `role` slot of the brain's provenance label.
     *
     * THE STABLE ID COMES FIRST, THE DISPLAY NAME SECOND, AND THAT ORDER IS THE FIX.
     *
     * The first shape of this label was `agent:<display name>`, which is readable and
     * useless for two agents that share a name: "Code Engineer" installed twice, or a
     * person's own agent called the same thing as a catalogue one, produced identical
     * provenance and could not be told apart in storage. The display name is not an
     * identity, it is a label a person can edit.
     *
     * So the id is primary and the name is supplementary. The name is also
     * percent-encoded, because it can contain spaces and a slash, and a space in an
     * unescaped `key=value` string silently breaks a reader that splits on whitespace.
     *
     * The shapes, exactly:
     *   guru
     *   agent:<id>:<percent-encoded name>            (attributed agent, name known)
     *   agent:<id>                                   (attributed agent, no name)
     *   agent                                         (attributed, no id either)
     *   ""                                           (nobody attributed this call)
     */
    fun roleLabel(): String = when {
        kind == KIND_GURU -> KIND_GURU
        kind == KIND_AGENT && agentId.isNotBlank() && agentName.isNotBlank() ->
            "$KIND_AGENT:$agentId:${escapeSegment(agentName)}"
        kind == KIND_AGENT && agentId.isNotBlank() -> "$KIND_AGENT:$agentId"
        kind == KIND_AGENT -> KIND_AGENT
        else -> ""
    }

    /**
     * A one-clause attribution for a provenance string the caller controls.
     *
     * `cortexRecord` takes a free provenance label from the model, so the actor is
     * appended rather than replacing what the model said. An unattributed call appends
     * nothing at all, which is what keeps the default path byte-identical.
     *
     * The same id-first rule as [roleLabel], for the same reason: this is the only
     * place an agent's identity reaches a typed record.
     */
    fun provenanceSuffix(): String = when {
        !isAttributed -> ""
        kind == KIND_AGENT && agentId.isNotBlank() && agentName.isNotBlank() ->
            "actor=agent:$agentId:${escapeSegment(agentName)}"
        kind == KIND_AGENT && agentId.isNotBlank() -> "actor=agent:$agentId"
        else -> "actor=$kind"
    }

    /**
     * True when this actor claims an identity that the label can actually carry.
     *
     * Used by the write path to decide whether an unattributed-capable store is good
     * enough. A GURU call is always carryable. An agent call is carryable only with an
     * id, because a bare `agent` in the role slot names no one.
     */
    val hasIdentityToRecord: Boolean
        get() = when (kind) {
            KIND_GURU -> true
            KIND_AGENT -> agentId.isNotBlank()
            else -> false
        }

    companion object {
        const val KIND_GURU = "guru"
        const val KIND_AGENT = "agent"

        /**
         * Make a name safe to sit inside a colon-and-space delimited label.
         *
         * PERCENT-ENCODING, NOT A BACKSLASH PREFIX. A backslash before a space leaves
         * the space character in place, so `Code\ Engineer` still splits on whitespace
         * and the escape achieves nothing. The character itself has to go.
         *
         * Anything outside letters, digits, dot, dash and underscore becomes `%XX` of
         * its UTF-8 bytes. `Code Engineer` becomes `Code%20Engineer`. Reversible,
         * unambiguous, no whitespace left anywhere, and it is the same rule a URL uses
         * so nobody has to learn a second one.
         */
        fun escapeSegment(raw: String): String {
            val out = StringBuilder(raw.length + 8)
            for (byte in raw.toByteArray(Charsets.UTF_8)) {
                val ch = byte.toInt().toChar()
                val safe = ch.isLetterOrDigit() || ch == '.' || ch == '-' || ch == '_'
                if (safe) {
                    out.append(ch)
                } else {
                    out.append('%')
                    val code = byte.toInt() and 0xFF
                    out.append("0123456789ABCDEF"[code shr 4])
                    out.append("0123456789ABCDEF"[code and 0x0F])
                }
            }
            return out.toString()
        }

        /**
         * Read a name back out of a label segment.
         *
         * Present because an escape nothing can undo is a one-way hash with extra
         * steps. A reader that wants the agent's display name from a stored label calls
         * this rather than hand-rolling a decoder.
         */
        fun unescapeSegment(escaped: String): String {
            val bytes = java.io.ByteArrayOutputStream(escaped.length)
            var i = 0
            while (i < escaped.length) {
                val ch = escaped[i]
                if (ch == '%' && i + 2 < escaped.length) {
                    val hex = escaped.substring(i + 1, i + 3)
                    val value = hex.toIntOrNull(16)
                    if (value != null) {
                        bytes.write(value)
                        i += 3
                        continue
                    }
                }
                bytes.write(ch.toString().toByteArray(Charsets.UTF_8))
                i++
            }
            return String(bytes.toByteArray(), Charsets.UTF_8)
        }

        /** GURU itself, reasoning in the main chat. */
        fun guru(conversationId: String = "", messageId: String = ""): ToolActor =
            ToolActor(
                kind = KIND_GURU,
                conversationId = conversationId,
                messageId = messageId,
            )

        /** A spawned agent, running a job the assistant handed it. */
        fun agent(
            agentId: String,
            agentName: String,
            conversationId: String = "",
            messageId: String = "",
        ): ToolActor = ToolActor(
            kind = KIND_AGENT,
            agentId = agentId,
            agentName = agentName,
            conversationId = conversationId,
            messageId = messageId,
        )

        /**
         * A call nobody claimed.
         *
         * This is the default at every dispatch site so a caller that has not been
         * taught to attribute cannot silently claim to be GURU. It writes the empty
         * provenance the app wrote before this type existed.
         */
        val UNATTRIBUTED = ToolActor()
    }
}
