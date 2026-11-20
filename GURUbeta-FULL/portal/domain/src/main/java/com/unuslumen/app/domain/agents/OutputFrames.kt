// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * Reading and writing the frame array inside a `MODEL_OUTPUT_BATCH` payload.
 *
 * WHY A BATCH RATHER THAN ONE EVENT PER CHUNK. A long reply is hundreds of text chunks and
 * can be thousands of reasoning chunks. One row each, with a sequence allocated in a
 * transaction that also updates the run row, turns a single turn into thousands of writes
 * on a phone. Batching bounds that: frames accumulate for 250 ms or 8 KiB and then land
 * together, in order, with their interleaving preserved.
 *
 * WHAT BATCHING COSTS, STATED RATHER THAN HIDDEN. An abrupt process kill loses the final
 * uncommitted buffer. Everything already flushed is durable, and every boundary that
 * matters — a tool dispatch, a checkpoint, a question, a state change, the end of the
 * stream — flushes first, so no action is ever taken against output that was never
 * recorded.
 *
 * THE FORMAT IS DELIBERATELY SIMPLE. A JSON array of objects with a type, a text and an
 * optional source index, written and read by hand so the reducer stays free of a
 * serialisation library and can be tested with a plain string literal.
 */
object OutputFrames {

    /**
     * Decode a frame array.
     *
     * TOLERANT OF DAMAGE IN ONE DIRECTION ONLY. A frame this cannot parse is dropped
     * rather than throwing, because one malformed frame should not make a whole run
     * unreplayable, and the alternative — refusing the entire batch — loses good output
     * to protect the bad. The dropped frame is visible in the rebuilt text being shorter
     * than the completion event, which is a mismatch a test can catch.
     */
    fun decode(json: String?): List<AgentRunReplay.OutputFrame> {
        if (json.isNullOrBlank()) return emptyList()
        val out = mutableListOf<AgentRunReplay.OutputFrame>()
        var i = 0
        val n = json.length
        while (i < n) {
            val start = json.indexOf('{', i)
            if (start < 0) break
            var depth = 0
            var end = -1
            for (j in start until n) {
                when (json[j]) {
                    '{' -> depth++
                    '}' -> { depth--; if (depth == 0) { end = j; break } }
                }
            }
            if (end < 0) break
            val obj = json.substring(start + 1, end)
            val kind = readField(obj, "type")
            val text = readField(obj, "text") ?: ""
            val index = readField(obj, "sourceIndex")?.toIntOrNull()
            val parsed = when (kind?.uppercase()) {
                "TEXT" -> AgentRunReplay.OutputFrame(AgentRunReplay.OutputFrame.Kind.TEXT, text, index)
                "REASONING" -> AgentRunReplay.OutputFrame(AgentRunReplay.OutputFrame.Kind.REASONING, text, index)
                else -> null
            }
            if (parsed != null) out += parsed
            i = end + 1
        }
        return out
    }

    /**
     * Read one string field from a JSON object body.
     *
     * Only what a frame needs: no nesting, and a value is either quoted or bare. A frame
     * has no nested objects by construction, so a general parser here would be more
     * machinery than the format requires.
     */
    private fun readField(body: String, name: String): String? {
        val key = "\"$name\""
        val keyAt = body.indexOf(key)
        if (keyAt < 0) return null
        val colon = body.indexOf(':', keyAt + key.length)
        if (colon < 0) return null
        var i = colon + 1
        while (i < body.length && body[i].isWhitespace()) i++
        if (i >= body.length) return null
        return if (body[i] == '"') {
            val sb = StringBuilder()
            i++
            while (i < body.length) {
                val c = body[i]
                when (c) {
                    '\\' -> {
                        i++
                        if (i >= body.length) break
                        when (body[i]) {
                            '"' -> sb.append('"')
                            '\\' -> sb.append('\\')
                            'n' -> sb.append('\n')
                            'r' -> sb.append('\r')
                            't' -> sb.append('\t')
                            'u' -> {
                                val hex = body.substring(i + 1, minOf(i + 5, body.length))
                                hex.toIntOrNull(16)?.let { sb.append(it.toChar()) }
                                i += 4
                            }
                            else -> sb.append(body[i])
                        }
                    }
                    '"' -> return sb.toString()
                    else -> sb.append(c)
                }
                i++
            }
            sb.toString()
        } else {
            val endAt = body.indexOfFirst(i) { it == ',' || it == '}' }
            body.substring(i, if (endAt < 0) body.length else endAt).trim().ifBlank { null }
        }
    }

    private inline fun String.indexOfFirst(from: Int, predicate: (Char) -> Boolean): Int {
        for (idx in from until length) if (predicate(this[idx])) return idx
        return -1
    }

    /** Encode frames back into the array form, used by the writer and by tests. */
    fun encode(frames: List<AgentRunReplay.OutputFrame>): String =
        frames.joinToString(separator = ",", prefix = "[", postfix = "]") { f ->
            buildString {
                append("{\"type\":\"")
                append(if (f.kind == AgentRunReplay.OutputFrame.Kind.TEXT) "TEXT" else "REASONING")
                append("\",\"text\":")
                append(quote(f.text))
                f.sourceIndex?.let { append(",\"sourceIndex\":").append(it) }
                append('}')
            }
        }

    private fun quote(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (c < ' ') append("\\u").append(String.format("%04x", c.code)) else append(c)
            }
        }
        append('"')
    }
}
