// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.repository

/**
 * How a streaming response ended, and whether that ending may be reported as a
 * finished turn.
 *
 * THE DEFECT THIS EXISTS FOR (issue 307). A multi-step turn stopped after a few
 * messages or tool calls, with no error and no message to the user, and GURU
 * carried on as though it had finished. The owner's words: it sends a couple of
 * messages and just stops, and it does not know it stopped.
 *
 * The mechanism was a Kotlin Flow property rather than a bug in any one line. A
 * `flow { }` builder cannot distinguish two completions that look identical from
 * the outside:
 *
 *  - the producer saw the protocol's terminal event (`done: true`) and finished,
 *  - the producer's input simply ran out, because the socket closed.
 *
 * Both are normal completion of the flow. So when the HTTP stream dropped before
 * the server's final line, `reader.readLine()` returned null, the loop broke, the
 * flow completed cleanly, and `AiRepositoryImpl` read that as "the model thinks
 * it is finished". It set `succeeded = true`, found no completed tool calls,
 * emitted whatever text it had, and broke out of the turn loop. No exception was
 * thrown, so the retry ladder and the fallback model never engaged.
 *
 * The tool call the model had asked for was sitting in the accumulator and was
 * thrown away. GURU said it would do something and then stopped.
 *
 * The rule this encodes: an ending is only [Complete] when the protocol said so.
 * Everything else is a failure that has to be visible, because a failure nobody
 * can see cannot be retried and cannot be reported.
 *
 * Pure and Android-free, so [StreamCompletionTest] proves every ending on the JVM
 * without a socket, a device or an emulator.
 */
object StreamCompletion {

    /** How one streaming response ended. */
    enum class Ending {
        /** The protocol's terminal event arrived. This is the ONLY finished turn. */
        COMPLETE,
        /** The connection closed before the terminal event. Data may have been lost. */
        TRUNCATED,
        /** The server sent an error field in-band. */
        SERVER_ERROR,
        /** A line would not parse as the protocol's JSON. */
        MALFORMED,
        /** The request never got a success status. */
        HTTP_ERROR,
        /** The collector was cancelled deliberately, by the user or a newer send. */
        CANCELLED,
    }

    /**
     * The decision for one ending, in the one sentence a reader needs.
     *
     * The sentences name the observable fact rather than guessing at a cause: this
     * object knows the stream closed early, and nothing else. A server that closed
     * the connection and a network that dropped it look identical from here, and
     * claiming one would be the same invented-cause failure that made this
     * defect hard to find in the first place.
     */
    fun message(
        ending: Ending,
        linesRead: Int,
        pendingToolCalls: Int,
        textLength: Int,
    ): String = when (ending) {
        Ending.COMPLETE ->
            "The stream finished normally after $linesRead line(s)."

        Ending.TRUNCATED ->
            "The connection closed before the response was finished, after $linesRead line(s). " +
                (if (pendingToolCalls > 0) {
                    "The model had asked for $pendingToolCalls tool call(s) that were never run. "
                } else if (textLength > 0) {
                    "It had produced $textLength character(s) of text. "
                } else {
                    "Nothing usable had arrived. "
                }) +
                "This is not the model choosing to stop; the response was cut short."

        Ending.SERVER_ERROR ->
            "The server reported an error part way through the response, after $linesRead line(s), " +
                "so the reply is incomplete."

        Ending.MALFORMED ->
            "A line of the response (number $linesRead) was not the JSON this client expects, " +
                "so the reply could not be read past that point."

        Ending.HTTP_ERROR ->
            "The request was refused before any reply arrived."

        Ending.CANCELLED ->
            "The response was stopped deliberately after $linesRead line(s)."
    }

    /** Whether an ending may be reported to the user as a finished turn. */
    fun isTerminalSuccess(ending: Ending): Boolean = ending == Ending.COMPLETE

    /**
     * Whether retrying the same request could plausibly help.
     *
     * A truncated stream is worth retrying because the request itself was valid
     * and the server had begun answering it. A malformed line is not, because the
     * same request will produce the same line. A cancellation is never retried:
     * the caller asked for it to stop.
     */
    fun isRetryable(ending: Ending): Boolean = when (ending) {
        Ending.TRUNCATED, Ending.SERVER_ERROR -> true
        Ending.COMPLETE, Ending.MALFORMED, Ending.HTTP_ERROR, Ending.CANCELLED -> false
    }
}
