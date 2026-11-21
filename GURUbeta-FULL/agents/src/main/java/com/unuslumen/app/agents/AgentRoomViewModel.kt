// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.agents

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.unuslumen.app.data.agents.AgentInteractionBroker
import com.unuslumen.app.data.agents.AgentRoomAssembler
import com.unuslumen.app.data.agents.AgentRunHost
import com.unuslumen.app.data.agents.AgentRuntime
import com.unuslumen.app.domain.agents.AgentRoomState
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.last
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * The Agent Room's state and controls.
 *
 * WHAT A VIEW MODEL IS FOR HERE. The room is a dashboard over a live execution: the state must
 * refresh as the run writes events, a person must be able to say something to an agent that is
 * working, and every control must reach a real capability. All of that is orchestration, and none of
 * it belongs in a composable.
 *
 * EVERY EVENT BELOW IS BACKED BY A RUNTIME CAPABILITY. There is no Pause button wired to a boolean,
 * no Stop that only hides the screen, and no control drawn for a phase that has not been built. The
 * specification is explicit that a control must not be displayed as operational because it looks
 * useful, and the way to keep that true is for the ViewModel to expose only what it can actually do.
 *
 * CLOSING THE ROOM DOES NOT CANCEL THE RUN. Nothing here cancels anything on teardown: work lives on
 * [AgentRunHost]'s application-lifetime scope, and the only thing that stops it is Stop.
 */
class AgentRoomViewModel(
    private val agentId: String,
    private val assembler: AgentRoomAssembler,
    private val broker: AgentInteractionBroker,
    private val runtime: AgentRuntime,
    private val runHost: AgentRunHost,
    /**
     * The agent store, for the one write this room owns: visibility.
     *
     * THE ROOM MAY CHANGE WHAT IT SHOWS AND NOTHING ELSE. Tool permissions, autonomy, memory scope
     * and budgets belong to the creator, and the specification is explicit that a visibility change
     * must not alter any of them. A narrow repository call rather than the creator's save is how that
     * stays true by construction instead of by care.
     */
    private val agents: com.unuslumen.app.domain.repository.GuruAgentRepository,
    /**
     * The store a run's conversation is created in.
     *
     * A RUN BELONGS TO A CONVERSATION AND A CONVERSATION IS NOT OPTIONAL. The foreign key on
     * `agent_runs.conversation_id` is real, so an agent cannot be started from a screen that has
     * no conversation in hand. Every run until now was started by GURU from the portal, which
     * always has one; a room opened directly had none, so this is what lets the room start work.
     */
    private val conversations: com.unuslumen.app.domain.memory.MemoryRepository,
) : ViewModel() {

    data class UiState(
        val isLoading: Boolean = true,
        /** Null once the agent has been deleted; the screen closes rather than drawing nothing. */
        val room: AgentRoomState? = null,
        val error: String? = null,
        /** What the person is typing into Chat with Agent. */
        val draft: String = "",
        /**
         * How the agent should treat what is typed.
         *
         * A CORRECTION IS NOT A QUESTION AND NEITHER IS AN INSTRUCTION. The runtime stores the type
         * with the message and the agent reads the framing from it, so sending everything as one
         * kind is how "why did you do that" arrives as "do it differently".
         */
        val draftType: String = com.unuslumen.app.domain.agents.AgentSpeakableTo.INSTRUCTION,
        /** A control that failed, shown as a sentence rather than swallowed. */
        val controlError: String? = null,
        /** True while the poll is waiting for a run to appear after a start. */
        val awaitingRun: Boolean = false,
        /** The tab the room is showing. Chat is a destination, not the whole screen. */
        val tab: RoomTab = RoomTab.OVERVIEW,
    )

    /**
     * The room's areas.
     *
     * CHAT IS ONE OF THEM, WHICH IS THE SPECIFICATION'S POINT. A room whose only destination is a
     * chat transcript is not a dashboard, whatever else it draws.
     */
    enum class RoomTab {
        OVERVIEW,
        CHAT,
        TOOLS,
        REASONING,
        CONTEXT,
        OUTPUTS,
        HISTORY,
        SETTINGS,
    }

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /**
     * The live poll.
     *
     * A POLL RATHER THAN A DATABASE FLOW, DELIBERATELY. The room's state is assembled from six
     * tables plus the ledger; a Flow would need six subscriptions and a combine, and the assemble
     * would still run on every emission. One refresh on a short interval is simpler to reason about
     * and cannot produce a half-updated room.
     *
     * The interval is a live view of work in flight, not a load spinner: two seconds is fast enough
     * that a tool appearing feels immediate and slow enough that the assemble does not run in a
     * tight loop while nothing changes.
     */
    private var poll: Job? = null

    init {
        refresh()
        startPolling()
    }

    fun onEvent(event: Event) {
        when (event) {
            is Event.SelectTab -> _state.update { it.copy(tab = event.tab) }
            is Event.UpdateDraft -> _state.update { it.copy(draft = event.text) }
            is Event.SelectDraftType -> _state.update { it.copy(draftType = event.type) }
            Event.SendToAgent -> sendToAgent()
            is Event.StartJob -> startJob(event.job)
            Event.StopRun -> stopRun()
            Event.PauseRun -> pauseRun()
            is Event.ResumeRun -> resumeRun(event.instruction)
            is Event.Approve -> decide(event.approvalId, approved = true)
            is Event.Reject -> decide(event.approvalId, approved = false)
            Event.Refresh -> refresh()
            Event.ClearControlError -> _state.update { it.copy(controlError = null) }
            is Event.Steer -> steer(event.text, event.messageType)
            is Event.SetVisibilityDisplay -> setVisibility(event.category, event.shown)
        }
    }

    sealed interface Event {
        data class SelectTab(val tab: RoomTab) : Event
        data class UpdateDraft(val text: String) : Event

        /**
         * Choose how the next message should be read.
         *
         * ONLY THE KINDS `AgentSpeakableTo` OFFERS, so the stored type is always one the runtime
         * knows how to frame. The screen draws its buttons from that list rather than from its own.
         */
        data class SelectDraftType(val type: String) : Event

        /** Send the draft to the agent through the broker. */
        data object SendToAgent : Event

        /**
         * Give this agent a job, from inside its own room.
         *
         * THE DOOR THE ROOM WAS MISSING. Until this existed an agent could only be started by
         * asking GURU in the portal, so a room opened from the agents list had nothing running and
         * a chat box with nothing to talk to. That is the whole reason the chat read as broken:
         * not a control wired wrong, but no way to get a run at all.
         */
        data class StartJob(val job: String) : Event

        /** Send an instruction that changes the work rather than asking a question. */
        data class Steer(val text: String, val messageType: String) : Event

        /** Stop the run. The only control that ends work for good. */
        data object StopRun : Event

        /**
         * Stop the run for now, keeping everything it has.
         *
         * A DIFFERENT CONTROL FROM STOP, because it is a different capability. Stopping ends the
         * work; pausing keeps its working state and lets it be continued. A room that offered only
         * one of them would be hiding a real option, and one that offered both wired to the same
         * action would be lying about one.
         */
        data object PauseRun : Event

        /** Carry on a paused run, optionally with something the person wants taken into account. */
        data class ResumeRun(val instruction: String? = null) : Event

        data class Approve(val approvalId: String) : Event
        data class Reject(val approvalId: String) : Event
        data object Refresh : Event
        data object ClearControlError : Event

        /**
         * Show or hide one visibility category.
         *
         * DISPLAY ONLY, AND THAT IS THE WHOLE CHOICE. The specification separates what is captured
         * from what is drawn, and hiding a category must never delete what was recorded. So this
         * changes display and leaves capture alone: a person who hides tool arguments today can turn
         * them back on tomorrow and see everything that ran in between.
         */
        data class SetVisibilityDisplay(
            val category: com.unuslumen.app.domain.model.VisibilityCategory,
            val shown: Boolean,
        ) : Event
    }

    /**
     * Reload the room from persisted state.
     *
     * A FAILURE IS SHOWN, NOT SWALLOWED. A room that silently stops updating looks like an agent
     * that stopped working, which is the worst possible confusion in a surface whose entire job is
     * to say what is happening.
     */
    private fun refresh() {
        viewModelScope.launch {
            try {
                val room = assembler.assemble(agentId)
                _state.update {
                    it.copy(
                        isLoading = false,
                        room = room,
                        error = if (room == null) {
                            "This agent no longer exists. It may have been deleted."
                        } else null,
                    )
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        isLoading = false,
                        error = e.message ?: "Could not read this agent's state.",
                    )
                }
            }
        }
    }

    private fun startPolling() {
        poll?.cancel()
        poll = viewModelScope.launch {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                // Only poll while there is something to watch. An idle agent's room does not need a
                // refresh every two seconds, and polling anyway is a battery cost for no new fact.
                val room = _state.value.room
                val worthWatching = room == null ||
                    room.isRunning ||
                    _state.value.awaitingRun
                if (!worthWatching) continue
                runCatching { assembler.assemble(agentId) }.getOrNull()?.let { fresh ->
                    _state.update { it.copy(room = fresh, awaitingRun = fresh.run == null && it.awaitingRun) }
                }
            }
        }
    }

    /**
     * Send what the person typed to the agent.
     *
     * THE MESSAGE IS QUEUED, NOT INJECTED, and the room says so. The loop takes it at its next
     * round boundary, so nothing arrives in the middle of a tool call. Saying "sent" at the moment
     * of writing would be the defect that makes sent mean typed.
     *
     * SPEAKING TO A STOPPED RUN CARRIES IT ON, AND THAT IS THE WHOLE POINT OF A CHAT BOX.
     *
     * The first version recorded the message and returned. On a run that was still executing that
     * is right: the loop reads it at its next boundary. On a run that had stopped it was a lie
     * dressed as a queue — the message went in as QUEUED and no boundary was ever going to arrive,
     * so a person typed something, saw it appear, and waited for an answer that could not come.
     * That is exactly the "the chat does not work" report, and the database showed it plainly: one
     * user message at QUEUED and a run at INTERRUPTED with nothing driving it.
     *
     * So a resumable run is resumed after the message is recorded, on the host, under the same run
     * id. The loop then reads the queued message at its first boundary exactly as it would have
     * done had it still been running. A run that is genuinely finished has nothing to carry on and
     * is refused with a sentence rather than given a message it can never read.
     */
    private fun sendToAgent() {
        val text = _state.value.draft.trim()
        if (text.isEmpty()) return
        val room = _state.value.room
        val runId = room?.run?.runId

        if (runId == null) {
            _state.update {
                it.copy(
                    controlError = "This agent is not running, so there is nothing to talk to. " +
                        "Its history is below if you want to read what it did.",
                )
            }
            return
        }

        /*
         * THE CHOSEN KIND IS WHAT TRAVELS. Sending everything as INSTRUCTION was the defect section
         * 9.9 names: a question stored as an instruction tells the agent it is being ordered to do
         * something, and a correction stored as one leaves the original brief apparently intact.
         *
         * THE TYPE IS CHECKED AGAINST THE OFFERED SET rather than trusted from the UI, because the
         * runtime frames a message by its type and an unrecognised one would arrive unframed.
         */
        val kind = _state.value.draftType
        val messageType = if (
            com.unuslumen.app.domain.agents.AgentSpeakableTo.allows(kind)
        ) {
            kind
        } else {
            com.unuslumen.app.domain.agents.AgentSpeakableTo.INSTRUCTION
        }

        /*
         * A FINISHED RUN IS CONTINUED AS A NEW ONE, NOT REFUSED.
         *
         * This used to record nothing and say the run was over, which made the chat a single
         * exchange: one job, one answer, then a dead composer. A conversation has to survive its
         * answer. So a finished run's history stays on screen, and a message sent from it starts the
         * next run with that message as its job, in the same conversation.
         *
         * THE PRECEDING TRANSCRIPT IS NOT CARRIED AS CONTEXT, DELIBERATELY. A new run is a new brief
         * with its own assignment row, and pretending it inherited an old run's conversation would
         * report a continuity the record does not have. The person can read what came before on this
         * screen; the new run starts from what they just said.
         */
        val finishedRun = room?.run?.takeIf { it.isTerminal }
        if (finishedRun != null) {
            _state.update { it.copy(draft = "", controlError = null) }
            startJob(text)
            return
        }

        viewModelScope.launch {
            try {
                val sent = runtime.steer(
                    runId = runId,
                    text = text,
                    messageType = messageType,
                )
                if (!sent) {
                    _state.update {
                        it.copy(controlError = "That message could not be recorded, so it was not sent.")
                    }
                    return@launch
                }
                _state.update { it.copy(draft = "", controlError = null) }

                /*
                 * THEN CARRY IT ON, WHEN NOTHING IS DRIVING IT.
                 *
                 * "MOVING" IS A QUESTION FOR THE HOST, NOT FOR THE STATUS COLUMN. This first asked
                 * whether the row said RUNNING, which is exactly the reading that failed in the
                 * field: a process killed mid-tool leaves the row at RUNNING until the recovery
                 * scan notices, so a person typed into a run that reported itself as working and
                 * nothing was there to read it. The host is the only component that knows whether a
                 * job is actually executing, so it is the one asked.
                 *
                 * A live loop takes the message at its next boundary by itself. Anything else has to
                 * be started again before the queued message can mean anything, and the host admits
                 * it because the previous job under this run id has finished.
                 *
                 * The instruction is passed with the resume as well as recorded as a message, so the
                 * agent sees it in the turn it wakes up in rather than only at a boundary after it.
                 */
                val moving = runHost.isActive(runId)
                if (!moving) {
                    val deferred =
                        kotlinx.coroutines.CompletableDeferred<AgentRuntime.SpawnResult>()
                    val admission = runHost.start(runId, deferred) {
                        runtime.resumeRun(runId, text).last()
                    }
                    _state.update {
                        it.copy(
                            awaitingRun = admission !is AgentRunHost.Admission.AlreadyRunning,
                            controlError = if (admission is AgentRunHost.Admission.AlreadyRunning) {
                                "That run was already executing, so the message will be read at its " +
                                    "next boundary rather than starting it twice."
                            } else null,
                        )
                    }
                }
                refresh()
            } catch (e: Exception) {
                _state.update { it.copy(controlError = e.message ?: "Could not send that message.") }
            }
        }
    }

    /**
     * Give this agent a job, from its own room.
     *
     * THREE FACTS, IN THIS ORDER, AND EACH ONE IS REQUIRED.
     *
     * A run belongs to a conversation, so the conversation is created first. Its id is then used
     * for the run, which is what makes a room opened directly able to start work at all: every
     * run before this could only come from the portal, which always had a conversation in hand.
     *
     * THE JOB IS FROZEN ON THE RUN ROW. The runtime seals the assignment when it creates the run,
     * so what is typed here is what the run did, and a later edit to the agent's definition cannot
     * rewrite it.
     *
     * THE WORK GOES ON THE HOST, not this view model's scope. Leaving the room is not stopping the
     * agent, and a job started here has to survive the screen it was started from.
     */
    private fun startJob(job: String) {
        val trimmed = job.trim()
        if (trimmed.isEmpty()) return
        val room = _state.value.room ?: return

        /*
         * ONE START AT A TIME. Two taps on Start would create two conversations and two runs for
         * one intention, and the second would be invisible because the room shows the newest. The
         * waiting flag is cleared when the run appears or when the attempt fails.
         */
        if (_state.value.awaitingRun) return
        _state.update { it.copy(awaitingRun = true, controlError = null) }

        viewModelScope.launch {
            /*
             * THE CONVERSATION IS REUSED WHEN THIS AGENT ALREADY HAS ONE.
             *
             * A new conversation per message would give every exchange its own record, so the
             * thread a person is reading would have no single identity behind it and the run rows
             * could never be read as one history. The newest run's conversation is continued; a
             * fresh one is only created when there is nothing to continue.
             */
            val conversationId = try {
                val existing = state.value.room?.run?.conversationId?.takeIf { it.isNotBlank() }
                if (existing != null) {
                    existing
                } else {
                    val now = System.currentTimeMillis()
                    val id = java.util.UUID.randomUUID().toString()
                    conversations.createConversation(
                        com.unuslumen.app.domain.memory.Conversation(
                            id = id,
                            title = "${room.agentName}: ${trimmed.take(60)}",
                            createdDate = now,
                            updatedDate = now,
                        )
                    )
                    id
                }
            } catch (e: Exception) {
                _state.update {
                    it.copy(
                        awaitingRun = false,
                        controlError = "This agent was not started because its conversation could not " +
                            "be recorded: ${e.message ?: e.javaClass.simpleName}.",
                    )
                }
                return@launch
            }

            val runId = java.util.UUID.randomUUID().toString()
            val deferred = kotlinx.coroutines.CompletableDeferred<AgentRuntime.SpawnResult>()
            val admission = runHost.start(runId, deferred) {
                runtime.spawn(
                    // BY SLUG OR NAME, WHICH IS WHAT THE RESOLVER TAKES. The room holds all three
                    // identifiers and the id is not one of them.
                    slugOrName = room.agentSlug.ifBlank { room.agentName },
                    job = trimmed,
                    conversationId = conversationId,
                    runId = runId,
                ).last()
            }
            if (admission is AgentRunHost.Admission.AlreadyRunning) {
                _state.update {
                    it.copy(
                        awaitingRun = false,
                        controlError = "That run was already executing, so it was not started twice.",
                    )
                }
                return@launch
            }
            _state.update { it.copy(draft = "", awaitingRun = true) }
            refresh()
        }
    }

    /** Send a message of a specific type, such as an assignment amendment. */
    private fun steer(text: String, messageType: String) {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return
        val runId = _state.value.room?.run?.runId ?: return
        viewModelScope.launch {
            runCatching {
                runtime.steer(runId = runId, text = trimmed, messageType = messageType)
            }.onSuccess { sent ->
                _state.update {
                    it.copy(
                        draft = "",
                        controlError = if (!sent) "That instruction was not recorded." else null,
                    )
                }
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(controlError = e.message ?: "Could not send that instruction.") }
            }
        }
    }

    /**
     * Stop the run, and say plainly what stopping means.
     *
     * THE HOST CANCELLATION IS THE CAPABILITY. There is no separate cancel flag to keep in step: the
     * job on [AgentRunHost] is cancelled, the runtime's cancellation handler marks the run CANCELLED
     * and releases the lease, and the record says a person stopped it.
     */
    private fun stopRun() {
        val runId = _state.value.room?.run?.runId ?: return
        runHost.cancel(runId)
        refresh()
    }

    /**
     * Ask the run to stop at its next boundary.
     *
     * THE REQUEST AND THE PAUSE ARE TWO FACTS AND THE ROOM SAYS BOTH. The runtime sets the flag and
     * records the request; the loop writes the PAUSED state when it reaches a boundary it can stop
     * at. So this reports "pausing" rather than "paused", because claiming the second before the
     * loop has stopped would be a surface telling a person something that has not happened yet.
     */
    private fun pauseRun() {
        val runId = _state.value.room?.run?.runId
        if (runId == null) {
            _state.update {
                it.copy(controlError = "There is no run to pause. Nothing is executing for this agent.")
            }
            return
        }
        viewModelScope.launch {
            runCatching { runtime.pause(runId) }
                .onSuccess { accepted ->
                    if (!accepted) {
                        /*
                         * THE HONEST REFUSAL. A run held only on disk is not executing, so there
                         * is nothing to pause; the work is already stopped and resuming it goes
                         * through the checkpoint. Saying so is better than a button that appears
                         * to work on a run that is not moving anyway.
                         */
                        _state.update {
                            it.copy(
                                controlError = "This run is not executing in this process, so there " +
                                    "is nothing to pause. Its work is already stopped.",
                            )
                        }
                    } else {
                        _state.update { it.copy(controlError = null) }
                        refresh()
                    }
                }
                .onFailure { e ->
                    _state.update { it.copy(controlError = e.message ?: "Could not pause the run.") }
                }
        }
    }

    /**
     * Carry on a paused run, through the host so it outlives this screen.
     *
     * THE HOST ADMITS IT BECAUSE THE PAUSED JOB ENDED. A pause returns from the loop, so the job
     * for that run id is finished and the same id can be started again. The room keeps watching
     * the same run rather than opening a new one, which is what makes the history continuous.
     */
    private fun resumeRun(instruction: String?) {
        val runId = _state.value.room?.run?.runId
        if (runId == null) {
            _state.update { it.copy(controlError = "There is no run to resume.") }
            return
        }
        viewModelScope.launch {
            val deferred = kotlinx.coroutines.CompletableDeferred<AgentRuntime.SpawnResult>()
            val admission = runHost.start(runId, deferred) {
                runtime.resumeRun(runId, instruction).last()
            }
            if (admission is AgentRunHost.Admission.AlreadyRunning) {
                _state.update {
                    it.copy(
                        controlError = "That run is already executing, so it was not started twice.",
                    )
                }
                return@launch
            }
            _state.update { it.copy(controlError = null, awaitingRun = true) }
            refresh()
        }
    }

    /**
     * Show or hide one category.
     *
     * THE STORED RULE IS REBUILT FROM WHAT THE ROOM HOLDS, with one field changed. Building it from
     * something else — a save request, or a fresh read — would risk writing a stale copy of the rest
     * of the definition, which is exactly the silent permission change the specification forbids.
     *
     * THE CURRENT RUN IS NOT RE-DRAWN WITH THE NEW RULE BY THIS CALL: the refresh that follows reads
     * the agent again, and the room's next assemble carries it. Capture is untouched, so what the run
     * recorded stays recorded.
     */
    private fun setVisibility(
        category: com.unuslumen.app.domain.model.VisibilityCategory,
        shown: Boolean,
    ) {
        val room = _state.value.room ?: return
        val current = room.visibility
        val changed = current.asMap().toMutableMap().apply {
            val existing = this[category] ?: com.unuslumen.app.domain.model.VisibilityRule.DEFAULT
            this[category] = existing.copy(display = shown)
        }
        val rebuilt = com.unuslumen.app.domain.model.AgentVisibility(
            plans = changed.getValue(com.unuslumen.app.domain.model.VisibilityCategory.PLANS),
            activity = changed.getValue(com.unuslumen.app.domain.model.VisibilityCategory.ACTIVITY),
            reasoning = changed.getValue(com.unuslumen.app.domain.model.VisibilityCategory.REASONING),
            decisions = changed.getValue(com.unuslumen.app.domain.model.VisibilityCategory.DECISIONS),
            tools = changed.getValue(com.unuslumen.app.domain.model.VisibilityCategory.TOOLS),
            context = changed.getValue(com.unuslumen.app.domain.model.VisibilityCategory.CONTEXT),
            memory = changed.getValue(com.unuslumen.app.domain.model.VisibilityCategory.MEMORY),
            communications = changed.getValue(
                com.unuslumen.app.domain.model.VisibilityCategory.COMMUNICATIONS
            ),
            verification = changed.getValue(com.unuslumen.app.domain.model.VisibilityCategory.VERIFICATION),
            errors = changed.getValue(com.unuslumen.app.domain.model.VisibilityCategory.ERRORS),
            outputs = changed.getValue(com.unuslumen.app.domain.model.VisibilityCategory.OUTPUTS),
        )

        viewModelScope.launch {
            runCatching { agents.updateVisibility(agentId, rebuilt) }
                .onSuccess {
                    _state.update { it.copy(controlError = null) }
                    refresh()
                }
                .onFailure { e ->
                    _state.update {
                        it.copy(controlError = e.message ?: "Could not change what this room shows.")
                    }
                }
        }
    }

    /**
     * Decide an approval.
     *
     * THE BROKER ENFORCES ONE DECISION. A second tap finds the row already decided and changes
     * nothing, which is why this does not need its own guard.
     */
    private fun decide(approvalId: String, approved: Boolean) {
        viewModelScope.launch {
            runCatching {
                broker.decideAndDescribe(
                    approvalId = approvalId,
                    approved = approved,
                    decidedBy = com.unuslumen.app.domain.agents.AgentMessageAuthors.USER,
                )
            }.onSuccess {
                refresh()
            }.onFailure { e ->
                _state.update { it.copy(controlError = e.message ?: "Could not record that decision.") }
            }
        }
    }

    override fun onCleared() {
        // THE RUN IS NOT TOUCHED. Leaving the room is not stopping the work; that is the whole
        // point of the run living on the host rather than in this view model's scope.
        poll?.cancel()
        super.onCleared()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 2_000L
    }
}
