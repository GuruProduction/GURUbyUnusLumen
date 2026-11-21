// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.agents

/**
 * Rebuilding a session from its ledger, without guessing.
 *
 * WHY THIS IS PURE AND ANDROID-FREE. Replay is the one piece of the recovery path whose
 * correctness is a question of logic rather than of storage: given the same events in the
 * same order, does it produce the same session? That question can be answered in
 * milliseconds on a JVM with no device, no database and no keystore, so it is asked there
 * rather than being discovered on a handset at the worst moment.
 *
 * THE MESSAGE LIST IS THE THING BEING REBUILT, not a by-product. Accruing text into a
 * string is not a session; the session is the ordered list of messages with their roles,
 * tool-call ids, arguments and results. A rebuild that produces the right text but loses
 * the id pairing a tool call to its result produces a history the upstream rejects, which
 * is a real defect this codebase has already shipped once.
 *
 * A COMPLETION WITH NO REQUEST IS AN INCONSISTENCY, NOT SOMETHING TO ABSORB. If text
 * completes for a request that was never started, the ledger is malformed and replay
 * refuses. Quietly attaching it to whatever request happens to be open is how two
 * separate responses get merged into one and nobody ever finds out.
 */
object AgentRunReplay {

    /** One frame of model output, in arrival order. */
    data class OutputFrame(
        val kind: Kind,
        val text: String,
        val sourceIndex: Int? = null,
    ) {
        enum class Kind { TEXT, REASONING }
    }

    /** One tool invocation, from the model's proposal to its recorded outcome. */
    data class Invocation(
        val invocationId: String,
        val toolName: String,
        val repairedArguments: String?,
        val proposedArguments: String?,
        val state: State,
        val resultContent: String? = null,
        val error: String? = null,
    ) {
        enum class State { STARTED, COMPLETED, FAILED, REFUSED, OUTCOME_UNKNOWN }
    }

    /** One model request and everything that came back for it. */
    data class Request(
        val requestId: String,
        val roundIndex: Int,
        val exchangeIndex: Int,
        val text: String = "",
        val reasoning: String = "",
        val frames: List<OutputFrame> = emptyList(),
        val proposals: List<ProposedCall> = emptyList(),
        val completed: Boolean = false,
    )

    data class ProposedCall(val toolName: String, val callId: String, val arguments: String)

    /** One thing the conversation contains, in order. */
    sealed interface Message {
        data class Instruction(val content: String) : Message
        data class Said(val content: String) : Message
        data class ToolCall(val name: String, val callId: String, val arguments: String, val result: String, val failed: Boolean) : Message
    }

    /**
     * What replay produced.
     *
     * [consistent] is not decoration. A false value means the ledger contradicts itself
     * and nothing downstream should trust the rebuild, so a caller has to look at it
     * rather than being handed a plausible session and no warning.
     */
    data class Rebuilt(
        val messages: List<Message>,
        val requests: List<Request>,
        val invocations: List<Invocation>,
        val pendingQuestion: String?,
        val rounds: Int,
        val exchangeCount: Int,
        val notebook: String,
        val consistent: Boolean,
        val problems: List<String>,
    )

    /**
     * An event, reduced to what replay needs.
     *
     * The database row carries far more — actors, timestamps, correlation — and none of
     * it changes the rebuilt session. Reducing here keeps the reducer cheap to test and
     * honest about what it actually uses.
     */
    data class Event(
        val sequence: Long,
        val type: String,
        val payload: Map<String, String>,
    )

    /**
     * Apply events in order.
     *
     * [startingNotebook] and [startingCompactions] come from the checkpoint, because the
     * folded text is state rather than a sequence of events; everything after the
     * checkpoint is applied here.
     */
    fun replay(
        events: List<Event>,
        startingNotebook: String = "",
    ): Rebuilt {
        val problems = mutableListOf<String>()
        val requests = linkedMapOf<String, Request>()
        var currentRequestId: String? = null
        val invocations = linkedMapOf<String, Invocation>()
        val messages = mutableListOf<Message>()
        var pendingQuestion: String? = null
        var rounds = 0
        var exchanges = 0
        var notebook = startingNotebook
        /** Call ids already taken, so two invocations of one tool do not share an id. */
        val claimedCallIds = mutableSetOf<String>()

        for (event in events.sortedBy { it.sequence }) {
            when (event.type) {
                AgentEventTypes.MODEL_REQUEST_STARTED -> {
                    val id = event.payload["requestId"]
                    if (id.isNullOrBlank()) {
                        problems += "event ${event.sequence} started a request with no id"
                        continue
                    }
                    val round = event.payload["roundIndex"]?.toIntOrNull() ?: 0
                    val exchange = event.payload["exchangeIndex"]?.toIntOrNull() ?: 0
                    requests[id] = Request(id, round, exchange)
                    currentRequestId = id
                    rounds = maxOf(rounds, round)
                    exchanges = maxOf(exchanges, exchange)
                }

                AgentEventTypes.MODEL_OUTPUT_BATCH -> {
                    val id = event.payload["requestId"] ?: currentRequestId
                    val request = id?.let { requests[it] }
                    if (request == null) {
                        problems += "event ${event.sequence} appended output for an unknown request"
                        continue
                    }
                    val frames = OutputFrames.decode(event.payload["frames"])
                    requests[request.requestId] = request.copy(frames = request.frames + frames)
                }

                AgentEventTypes.MODEL_TEXT_COMPLETED -> {
                    val id = event.payload["requestId"]
                    if (id == null) {
                        // A completion with no request id cannot be attributed. Attaching
                        // it to whatever is open would merge two responses into one.
                        problems += "event ${event.sequence} completed text with no request id"
                        continue
                    }
                    val request = requests[id]
                    if (request == null) {
                        problems += "event ${event.sequence} completed text for unknown request $id"
                        continue
                    }
                    requests[id] = request.copy(text = event.payload["text"].orEmpty())
                }

                AgentEventTypes.MODEL_REASONING_COMPLETED -> {
                    val id = event.payload["requestId"]
                    if (id == null) {
                        problems += "event ${event.sequence} completed reasoning with no request id"
                        continue
                    }
                    val request = requests[id]
                    if (request == null) {
                        problems += "event ${event.sequence} completed reasoning for unknown request $id"
                        continue
                    }
                    requests[id] = request.copy(reasoning = event.payload["reasoning"].orEmpty())
                }

                AgentEventTypes.MODEL_TOOL_CALL_PROPOSED -> {
                    val id = currentRequestId
                    val request = id?.let { requests[it] }
                    if (request == null) {
                        problems += "event ${event.sequence} proposed a call with no open request"
                        continue
                    }
                    requests[request.requestId] = request.copy(
                        proposals = request.proposals + ProposedCall(
                            toolName = event.payload["tool"].orEmpty(),
                            callId = event.payload["callId"].orEmpty(),
                            arguments = event.payload["arguments"].orEmpty(),
                        )
                    )
                }

                AgentEventTypes.MODEL_REQUEST_COMPLETED -> {
                    val id = currentRequestId
                    val request = id?.let { requests[it] }
                    if (request != null) {
                        requests[id] = request.copy(completed = true)
                        // The assistant message is materialised here, not at the batch.
                        // A batch is a fragment stream; a completed request is a turn the
                        // conversation actually contains.
                        if (request.text.isNotBlank()) {
                            messages += Message.Said(request.text)
                        }
                    }
                    currentRequestId = null
                }

                AgentEventTypes.TOOL_CALL_STARTED -> {
                    val invocationId = event.payload["invocationId"]
                    if (invocationId.isNullOrBlank()) {
                        problems += "event ${event.sequence} started a tool with no invocation id"
                        continue
                    }
                    invocations[invocationId] = Invocation(
                        invocationId = invocationId,
                        toolName = event.payload["tool"].orEmpty(),
                        repairedArguments = event.payload["arguments"],
                        proposedArguments = event.payload["proposedArguments"],
                        state = Invocation.State.STARTED,
                    )
                }

                AgentEventTypes.TOOL_CALL_COMPLETED -> {
                    val invocationId = event.payload["invocationId"]
                    val existing = invocationId?.let { invocations[it] }
                    if (existing == null) {
                        problems += "event ${event.sequence} completed a tool with no start"
                        continue
                    }
                    invocations[existing.invocationId] = existing.copy(
                        state = Invocation.State.COMPLETED,
                        resultContent = event.payload["result"].orEmpty(),
                    )
                    // The tool response is materialised with the model's own call id, so a
                    // resumed history pairs each call to its result the way the upstream
                    // requires. Losing that id is what produced unrenderable histories.
                    val callId = callIdFor(existing, currentRequestId, requests, claimedCallIds)
                    messages += Message.ToolCall(
                        name = existing.toolName,
                        callId = callId,
                        arguments = existing.repairedArguments.orEmpty(),
                        result = event.payload["result"].orEmpty(),
                        failed = false,
                    )
                }

                AgentEventTypes.TOOL_CALL_FAILED -> {
                    val invocationId = event.payload["invocationId"]
                    val existing = invocationId?.let { invocations[it] }
                    if (existing == null) {
                        problems += "event ${event.sequence} failed a tool with no start"
                        continue
                    }
                    val error = event.payload["error"].orEmpty()
                    invocations[existing.invocationId] = existing.copy(
                        state = Invocation.State.FAILED,
                        error = error,
                    )
                    val callId = callIdFor(existing, currentRequestId, requests, claimedCallIds)
                    messages += Message.ToolCall(
                        name = existing.toolName,
                        callId = callId,
                        arguments = existing.repairedArguments.orEmpty(),
                        result = """{"error":${quote(error)}}""",
                        failed = true,
                    )
                }

                AgentEventTypes.TOOL_CALL_REFUSED -> {
                    val invocationId = event.payload["invocationId"]
                    val reason = event.payload["reason"].orEmpty()
                    if (invocationId != null && invocations.containsKey(invocationId)) {
                        val existing = invocations.getValue(invocationId)
                        invocations[invocationId] = existing.copy(
                            state = Invocation.State.REFUSED,
                            error = reason,
                        )
                    }
                    val name = event.payload["tool"].orEmpty()
                    messages += Message.ToolCall(
                        name = name,
                        callId = "",
                        arguments = "",
                        result = """{"error":${quote(reason)}}""",
                        failed = true,
                    )
                }

                AgentEventTypes.TOOL_CALL_OUTCOME_UNKNOWN -> {
                    val invocationId = event.payload["invocationId"]
                    val existing = invocationId?.let { invocations[it] }
                    if (existing != null) {
                        invocations[existing.invocationId] = existing.copy(
                            state = Invocation.State.OUTCOME_UNKNOWN,
                            error = event.payload["reason"],
                        )
                    }
                    // NOT materialised as a message. An unknown outcome is a fact about
                    // the run, not something the model said or was told; inserting a
                    // fabricated result would be inventing a tool response.
                }

                AgentEventTypes.QUESTION_OPENED -> {
                    pendingQuestion = event.payload["question"]
                }

                AgentEventTypes.QUESTION_ANSWERED -> {
                    pendingQuestion = null
                    val answer = event.payload["answer"].orEmpty()
                    if (answer.isNotBlank()) {
                        messages += Message.Instruction(answer)
                    }
                }

                AgentEventTypes.SESSION_COMPACTED -> {
                    // The notebook is recorded whole, so replay never has to reproduce the
                    // folding algorithm to know what the agent had in front of it.
                    event.payload["notebook"]?.let { notebook = it }
                }

                AgentEventTypes.MESSAGE_CREATED -> {
                    val author = event.payload["author"]?.uppercase()
                    val content = event.payload["content"].orEmpty()
                    if (content.isNotBlank()) {
                        when (author) {
                            "USER" -> messages += Message.Instruction(content)
                            "AGENT", "GURU" -> messages += Message.Said(content)
                            else -> Unit
                        }
                    }
                }

                /*
                 * NO SILENT FALLTHROUGH. THIS IS THE LINE THE WHOLE CLASSIFICATION EXISTS FOR.
                 *
                 * This used to be `else -> Unit`, so any event type the reducer had no branch
                 * for was discarded without a trace. A ledger that has been faithfully written
                 * for two years would rebuild a session quietly missing every fact added after
                 * this file was last touched, and nothing anywhere would say so.
                 *
                 * The type's declared policy decides what happens now. A type classed as having
                 * no session effect is ignored and that is correct. A type classed for a later
                 * phase is reported as an inconsistency, because replaying it today means a
                 * producer exists that the reducer was never taught to read. An undeclared type
                 * is itself the problem: somebody wrote an event the vocabulary does not know.
                 */
                else -> when (val policy = AgentEventTypes.RESTORE_POLICY[event.type]) {
                    is AgentEventTypes.RestorePolicy.Rebuilds -> {
                        // Declared as changing the session, but this branch never ran: the
                        // reducer is missing a case it promised to handle.
                        problems += "event ${event.sequence} is '${event.type}', which is declared to " +
                            "rebuild session state, but the reducer has no branch for it"
                    }
                    is AgentEventTypes.RestorePolicy.NoSessionEffect -> Unit
                    is AgentEventTypes.RestorePolicy.LaterPhase -> {
                        problems += "event ${event.sequence} is '${event.type}', which belongs to a later " +
                            "phase (${policy.reason}) but was written by this run. The reducer cannot " +
                            "honour it yet, so the rebuild is reported as inconsistent rather than " +
                            "quietly missing it."
                    }
                    null -> {
                        problems += "event ${event.sequence} has type '${event.type}', which is not in " +
                            "AgentEventTypes.ALL at all. It was written by something that does not share " +
                            "this vocabulary."
                    }
                }
            }
        }

        return Rebuilt(
            messages = messages,
            requests = requests.values.toList(),
            invocations = invocations.values.toList(),
            pendingQuestion = pendingQuestion,
            rounds = rounds,
            exchangeCount = exchanges,
            notebook = notebook,
            consistent = problems.isEmpty(),
            problems = problems,
        )
    }

    /**
     * The model's own call id for an invocation.
     *
     * TAKEN FROM THE PROPOSAL, matched by tool name and then claimed so it cannot be
     * reused. The proposal is the only event that carries the id the model minted, and
     * that id is what pairs a tool call to its result in the rebuilt history. Getting it
     * wrong produces a history the upstream refuses, which is a defect this codebase has
     * already shipped once.
     *
     * Claims are tracked per replay so two invocations of the same tool take two
     * different ids rather than both taking the first one.
     */
    private fun callIdFor(
        invocation: Invocation,
        currentRequestId: String?,
        requests: Map<String, Request>,
        claimed: MutableSet<String>,
    ): String {
        val request = currentRequestId?.let { requests[it] } ?: return ""
        val match = request.proposals.firstOrNull {
            it.toolName == invocation.toolName && it.callId !in claimed
        } ?: return ""
        claimed += match.callId
        return match.callId
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
