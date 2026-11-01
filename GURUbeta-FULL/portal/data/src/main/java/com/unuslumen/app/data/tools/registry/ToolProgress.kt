// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.registry

/**
 * A report from inside a tool that is still running.
 *
 * WHY THIS EXISTS. Until now a tool call was a black box: the executor was
 * handed its arguments and returned one result at the end, and the only thing a
 * person saw while it ran was a spinner. That is fine for a tool that takes
 * 200ms and useless for one that walks a network, because the honest options
 * collapse to "wait an unknown length of time" or "cap the work so it finishes",
 * and capping is what made tvDiscover miss every set on the network (issue #234).
 *
 * A tool that can say what it has found so far does not need to be capped to be
 * bearable. This is the channel that carries those reports from the executor,
 * through the dispatcher, onto the conversation flow and into the canvas while
 * the work is still happening.
 *
 * IT IS DELIBERATELY SMALL. [message] is a sentence a person reads; [payload] is
 * optional structured detail for a caller that wants to render something richer
 * than text. Nothing here is persisted: progress is a live view of work in
 * flight, and the durable record is still the tool result.
 */
data class ToolProgressEvent(
    /** The tool that is running, so a caller can label which one is speaking. */
    val toolName: String,
    /** One line a person reads. Never a JSON blob, never a stack trace. */
    val message: String,
    /** 0.0 to 1.0 when the tool can estimate it, null when it genuinely cannot. */
    val fraction: Double? = null,
    /** Optional structured detail, for a surface that renders more than words. */
    val payload: Map<String, String> = emptyMap(),
    val time: Long = System.currentTimeMillis(),
)

/**
 * Where a running tool sends its [ToolProgressEvent]s.
 *
 * A tool that has nothing useful to say simply never calls it. There is no
 * obligation to report, and a silent section of work is not a failure.
 */
fun interface ToolProgressSink {
    fun report(event: ToolProgressEvent)

    companion object {
        /** A sink that discards everything, for callers that do not care. */
        val NONE = ToolProgressSink { }
    }
}
