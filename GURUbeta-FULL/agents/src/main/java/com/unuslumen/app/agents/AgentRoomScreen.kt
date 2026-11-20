// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.unuslumen.app.domain.agents.AgentRoomState
import com.unuslumen.app.domain.agents.ApprovalView
import com.unuslumen.app.domain.agents.ArtefactView
import com.unuslumen.app.domain.agents.MessageView
import com.unuslumen.app.domain.agents.RunView
import com.unuslumen.app.domain.agents.ToolActivityView
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle

/**
 * The Agent Room.
 *
 * A DASHBOARD, NOT A CHAT SCREEN. Chat is one tab among eight. The landing view answers "is it
 * working, what is it doing, is anything waiting on me", and every other area is one tap away. The
 * specification is blunt that a room reduced to a transcript with decorative cards has failed, and
 * the way to avoid that is for the default tab to contain no conversation at all.
 *
 * EVERYTHING DRAWN COMES FROM AgentRoomState, WHICH COMES FROM ROWS. There is no placeholder, no
 * sample activity, and no progress percentage. Where a thing does not exist the room says which
 * thing and why, because a blank panel and a broken one look identical.
 *
 * THE VISIBILITY RULES ARE HONOURED AT DRAW TIME. A category the owner turned display off for is
 * not rendered, but its capture is untouched in the database, so switching it back on shows the
 * history rather than starting from the moment it was turned on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentRoomScreen(
    viewModel: AgentRoomViewModel,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val room = state.room

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            // THE DOOR COMES FROM THE ROOM NAVIGATION, which every room provides. Passing a
            // lambda here would be a second way to leave and the two would disagree.
            guruAppBar(
                title = room?.agentName?.uppercase() ?: "AGENT",
                stamp = room?.run?.status,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize().padding(padding)) {
            when {
                state.isLoading -> Message("Reading this agent's record…")
                room == null -> Message(state.error ?: "This agent no longer exists.")
                else -> RoomBody(state = state, room = room, viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun RoomBody(
    state: AgentRoomViewModel.UiState,
    room: AgentRoomState,
    viewModel: AgentRoomViewModel,
) {
    Column(Modifier.fillMaxSize()) {
        TabStrip(current = state.tab, onSelect = { viewModel.onEvent(AgentRoomViewModel.Event.SelectTab(it)) })

        state.controlError?.let { problem ->
            ControlError(problem) {
                viewModel.onEvent(AgentRoomViewModel.Event.ClearControlError)
            }
        }

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 28.dp),
        ) {
            when (state.tab) {
                AgentRoomViewModel.RoomTab.OVERVIEW -> overview(room, viewModel)
                AgentRoomViewModel.RoomTab.CHAT -> chat(room, state, viewModel)
                AgentRoomViewModel.RoomTab.TOOLS -> tools(room)
                AgentRoomViewModel.RoomTab.REASONING -> reasoning(room)
                AgentRoomViewModel.RoomTab.CONTEXT -> context(room)
                AgentRoomViewModel.RoomTab.OUTPUTS -> outputs(room)
                AgentRoomViewModel.RoomTab.HISTORY -> history(room)
                AgentRoomViewModel.RoomTab.SETTINGS -> settings(room)
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Overview: is it working, what is it doing, is anything waiting on me
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.overview(
    room: AgentRoomState,
    viewModel: AgentRoomViewModel,
) {
    item("identity") {
        Column(Modifier.fillMaxWidth()) {
            Text(room.agentName.uppercase(), style = posterStyle(20f))
            if (room.agentDescription.isNotBlank()) {
                Text(
                    room.agentDescription,
                    style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.65f)),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            Spacer(Modifier.height(10.dp))
            StatusBand(room)
        }
    }

    val run = room.run
    if (run == null) {
        item("idle") {
            Panel(
                title = "NOTHING RUNNING",
                body = room.idleBecause,
            )
        }
        item("controls-idle") {
            // NO CONTROLS WHEN THERE IS NO RUN. A Stop button with nothing to stop is the exact
            // decorative control the specification forbids.
            Spacer(Modifier.height(4.dp))
        }
        return
    }

    // Anything waiting on the person goes FIRST, because it is the only thing that needs them.
    if (run.pendingApprovals.isNotEmpty()) {
        item("approvals") {
            Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
                run.pendingApprovals.forEach { approval ->
                    ApprovalCard(approval, viewModel)
                    Spacer(Modifier.height(9.dp))
                }
            }
        }
    }

    item("assignment") {
        Panel(
            title = "THE JOB",
            body = run.assignment,
            modifier = Modifier.padding(top = 12.dp),
        )
    }

    item("controls") {
        Column(Modifier.fillMaxWidth().padding(top = 12.dp)) {
            Row(
                Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(9.dp),
            ) {
                PaperButton("Talk to it", Modifier.weight(1f)) {
                    viewModel.onEvent(
                        AgentRoomViewModel.Event.SelectTab(AgentRoomViewModel.RoomTab.CHAT)
                    )
                }
                if (!run.isTerminal) {
                    PaperButton(
                        label = "Stop",
                        modifier = Modifier.weight(1f),
                        filled = true,
                    ) { viewModel.onEvent(AgentRoomViewModel.Event.StopRun) }
                }
            }
            /*
             * PAUSE AND RESUME ARE DRAWN ONLY WHERE EACH ONE IS REAL.
             *
             * Pause is offered while the run is moving, because there has to be a loop to stop. A
             * run that is already waiting on a person or on GURU is not moving, so a Pause button
             * there would do nothing and say otherwise. Resume is offered only when the recorded
             * state is PAUSED, because that is the one state a resume can continue from; every
             * other non-terminal state either resumes itself or is waiting on an answer.
             */
            if (run.canPause || run.canResume) {
                Spacer(Modifier.height(9.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                    if (run.canPause) {
                        PaperButton("Pause", Modifier.weight(1f)) {
                            viewModel.onEvent(AgentRoomViewModel.Event.PauseRun)
                        }
                    }
                    if (run.canResume) {
                        PaperButton("Carry on", Modifier.weight(1f), filled = true) {
                            viewModel.onEvent(AgentRoomViewModel.Event.ResumeRun())
                        }
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    if (run.canResume) {
                        "This run is stopped with its working state kept. Carrying on continues the " +
                            "same run rather than starting a new one."
                    } else {
                        "Pausing stops at the agent's next safe boundary, keeping everything it has " +
                            "worked out. Stopping ends the work instead."
                    },
                    style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                )
            }
        }
    }

    run.planProgress?.let { progress ->
        item("plan") {
            /*
             * THE PLAN AROUND THE STEPS, THEN THE STEPS.
             *
             * A list of steps with no objective tells a reader what is being done and not what for,
             * and the specification asks for both. The extra fields are drawn only when the agent
             * supplied them, because a labelled blank row claims there was nothing to say.
             */
            val plan = run.plan
            Panel(
                title = "PLAN · $progress",
                body = buildString {
                    if (plan != null && plan.hasDetail) {
                        if (plan.objective.isNotBlank()) appendLine("OBJECTIVE: ${plan.objective}")
                        if (plan.dependencies.isNotBlank()) appendLine("ORDER: ${plan.dependencies}")
                        if (plan.expectedOutputs.isNotBlank()) appendLine("EXPECTED: ${plan.expectedOutputs}")
                        if (plan.verification.isNotBlank()) appendLine("VERIFY: ${plan.verification}")
                        if (plan.tools.isNotBlank()) appendLine("TOOLS: ${plan.tools}")
                        if (plan.reason.isNotBlank()) appendLine("WHY THIS PLAN: ${plan.reason}")
                        appendLine()
                    }
                    append(
                        run.planSteps.joinToString("\n") { "${stateMark(it.state)} ${it.description}" }
                    )
                },
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    item("counts") {
        CountsRow(run)
    }

    run.failureMessage?.let { failure ->
        item("failure") {
            Panel(
                title = "FAILED",
                body = failure,
                accent = Memphis.Coral,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }

    run.resultSummary?.takeIf { it.isNotBlank() }?.let { summary ->
        item("result") {
            Panel(
                title = "REPORT",
                body = summary,
                modifier = Modifier.padding(top = 12.dp),
            )
        }
    }
}

@Composable
private fun StatusBand(room: AgentRoomState) {
    val run = room.run
    val (label, colour) = when {
        !room.agentEnabled -> "SWITCHED OFF" to Memphis.Ink.copy(alpha = 0.5f)
        run == null -> "IDLE" to Memphis.Ink.copy(alpha = 0.5f)
        run.isWaitingOnUser -> "WAITING ON YOU" to Memphis.Yellow
        run.status == com.unuslumen.app.domain.agents.AgentRunStatuses.WAITING_FOR_GURU ->
            "WAITING ON GURU" to Memphis.Yellow
        run.isTerminal -> run.status to Memphis.Ink
        else -> "WORKING · ${run.status}" to Memphis.Teal
    }

    Row(
        Modifier.fillMaxWidth().background(colour).border(2.5.dp, Memphis.Ink)
            .padding(horizontal = 11.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = captionStyle(10f, Memphis.Paper))
    }
}

@Composable
private fun CountsRow(run: RunView) {
    Row(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Count("TOOLS", run.toolActivity.size.toString(), Modifier.weight(1f))
        Count("MESSAGES", run.messages.size.toString(), Modifier.weight(1f))
        Count("OUTPUTS", run.artefacts.size.toString(), Modifier.weight(1f))
        Count("CONTEXT", "${run.contextSupplied}/${run.contextOffered}", Modifier.weight(1f))
    }
}

// ---------------------------------------------------------------------------
// Chat with Agent, a destination of its own
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.chat(
    room: AgentRoomState,
    state: AgentRoomViewModel.UiState,
    viewModel: AgentRoomViewModel,
) {
    val run = room.run
    if (run == null) {
        item("no-run") {
            Panel("NOT RUNNING", "There is no run to talk to. Start this agent by asking GURU to " +
                "give it a job, and its conversation will appear here.")
        }
        return
    }

    item("explain") {
        Text(
            "Your message is queued and picked up at the agent's next safe boundary, so it never " +
                "arrives in the middle of a tool call.",
            style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.height(10.dp))
    }

    items(run.messages, key = { it.messageId }) { message ->
        MessageRow(message)
    }

    item("composer") {
        Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
            MemphisSurface(
                modifier = Modifier.fillMaxWidth(),
                color = Memphis.Paper,
                borderWidth = 2.5.dp,
                shadowOffset = 4.dp,
            ) {
                BasicTextField(
                    value = state.draft,
                    onValueChange = { viewModel.onEvent(AgentRoomViewModel.Event.UpdateDraft(it)) },
                    textStyle = captionStyle(11f, Memphis.Ink),
                    modifier = Modifier.fillMaxWidth().padding(11.dp),
                    decorationBox = { inner ->
                        if (state.draft.isEmpty()) {
                            Text(
                                "Say something to ${room.agentName}…",
                                style = captionStyle(11f, Memphis.Ink.copy(alpha = 0.4f)),
                            )
                        }
                        inner()
                    },
                )
            }
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PaperButton("Send", Modifier.weight(1f), filled = true) {
                    viewModel.onEvent(AgentRoomViewModel.Event.SendToAgent)
                }
                PaperButton("Change the job", Modifier.weight(1f)) {
                    viewModel.onEvent(
                        AgentRoomViewModel.Event.Steer(
                            state.draft,
                            com.unuslumen.app.domain.agents.AgentMessageTypes.ASSIGNMENT_AMENDMENT,
                        )
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Changing the job records an amendment; the original brief stays as it was.",
                style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
            )
        }
    }
}

@Composable
private fun MessageRow(message: MessageView) {
    Column(Modifier.fillMaxWidth().padding(top = 9.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                message.authorName.uppercase(),
                style = captionStyle(8.5f, if (message.isFromAgent) Memphis.Ink else Memphis.Lilac),
                fontWeight = FontWeight.Bold,
            )
            Spacer(Modifier.width(6.dp))
            Text(
                message.messageType.replace('_', ' '),
                style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.45f)),
            )
            Spacer(Modifier.width(6.dp))
            if (message.deliveryState != com.unuslumen.app.domain.agents.AgentDeliveryStates.PROCESSED) {
                Text(
                    message.deliveryState,
                    style = captionStyle(7.5f, Memphis.Coral),
                )
            }
        }
        Text(
            message.content,
            style = captionStyle(10f, Memphis.Ink),
            modifier = Modifier.padding(top = 3.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Tools
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.tools(room: AgentRoomState) {
    val run = room.run
    val activity = run?.toolActivity.orEmpty()
    if (activity.isEmpty()) {
        item("empty") {
            Panel(
                "NO TOOL ACTIVITY",
                if (run == null) "Nothing is running." else
                    "This run has not called a tool. An agent with no tools granted reasons and " +
                        "reports without touching anything.",
            )
        }
        return
    }
    items(activity, key = { it.invocationId }) { call -> ToolCard(call) }
}

@Composable
private fun ToolCard(call: ToolActivityView) {
    var open by remember { mutableStateOf(false) }

    MemphisSurface(
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp).clickable { open = !open },
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(call.toolName.uppercase(), style = posterStyle(12f), modifier = Modifier.weight(1f))
                when {
                    call.refused -> Badge("REFUSED", Memphis.Coral)
                    call.outcomeUnknown -> Badge("UNKNOWN", Memphis.Yellow)
                    call.failed -> Badge("FAILED", Memphis.Coral)
                    call.result != null -> Badge("DONE", Memphis.Teal)
                    else -> Badge("RUNNING", Memphis.Lilac)
                }
            }
            call.permissionOutcome?.let {
                Text(
                    "permission $it",
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
            call.durationMillis?.let {
                Text("took ${it}ms", style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)))
            }

            // EXPANDABLE, because a raw payload in the main dashboard is unreadable.
            if (open) {
                Spacer(Modifier.height(7.dp))
                Text("ARGUMENTS", style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.6f)))
                Text(
                    call.arguments,
                    style = captionStyle(9f, Memphis.Ink),
                    maxLines = 12,
                    overflow = TextOverflow.Ellipsis,
                )
                call.result?.let { result ->
                    Spacer(Modifier.height(6.dp))
                    Text("RESULT", style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.6f)))
                    Text(
                        result,
                        style = captionStyle(9f, Memphis.Ink),
                        maxLines = 12,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            } else {
                Text(
                    "tap for arguments and result",
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.4f)),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Reasoning
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.reasoning(room: AgentRoomState) {
    val run = room.run

    /*
     * DECISIONS FIRST, BECAUSE THEY EXIST ON EVERY MODEL.
     *
     * The two halves of this tab are different kinds of information and the specification requires
     * them labelled as such. A decision is a fact the agent wrote deliberately, so a model with no
     * reasoning channel still produces a full record of its thinking here. Putting reasoning above
     * it would make the one that is often empty the headline.
     */
    if (run != null && run.decisions.isNotEmpty()) {
        item("decisions-head") {
            Text("DECISIONS IT RECORDED", style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.7f)))
        }
        items(run.decisions, key = { it.sequence }) { decision -> DecisionCard(decision) }
        item("decisions-note") {
            Text(
                "These are the agent's own account of what it decided and why, written as it worked.",
                style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                modifier = Modifier.padding(top = 6.dp, bottom = 14.dp),
            )
        }
    }

    item("reasoning") {
        val reasoning = run?.reasoning
        Panel(
            title = "RAW REASONING FROM THE MODEL",
            body = when {
                reasoning == null -> "Nothing is running."
                // THE HONEST ANSWER WHEN THERE IS NONE. A model without a reasoning channel cannot
                // be made to produce one, and saying so is better than an empty panel.
                !reasoning.hasAnything -> reasoning.status
                else -> reasoning.text
            },
        )
    }

    if (run != null && run.decisions.isEmpty() && !run.reasoning.hasAnything) {
        item("nothing") {
            Text(
                "This run recorded no decisions and the model provided no reasoning. An agent that " +
                    "never calls recordDecision leaves only its report, which is on the Overview tab.",
                style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                modifier = Modifier.padding(top = 10.dp),
            )
        }
    }
}

/** One decision, with every field the agent filled in and nothing it left blank. */
@Composable
private fun DecisionCard(decision: com.unuslumen.app.domain.agents.DecisionView) {
    MemphisSurface(
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "#${decision.sequence}",
                    style = posterStyle(12f),
                    modifier = Modifier.weight(1f),
                )
                Badge("DECIDED", Memphis.Lilac)
            }
            Text(
                decision.decision,
                style = captionStyle(11f, Memphis.Ink),
                modifier = Modifier.padding(top = 5.dp),
            )
            /*
             * ONLY WHAT WAS SAID. A field the agent left blank is not drawn, because an empty
             * labelled row reads as a claim that there was nothing to say about it, which is a
             * different fact from not having been asked.
             */
            if (decision.problem.isNotBlank()) Fact("Problem", decision.problem)
            if (decision.approach.isNotBlank()) Fact("Approach", decision.approach)
            if (decision.alternatives.isNotEmpty()) {
                Fact("Considered", decision.alternatives.joinToString("\n"))
            }
            if (decision.reason.isNotBlank()) Fact("Why", decision.reason)
            if (decision.evidence.isNotBlank()) Fact("Evidence", decision.evidence)
            if (decision.uncertainty.isNotBlank()) Fact("Unsure", decision.uncertainty)
            if (decision.next.isNotBlank()) Fact("Next", decision.next)
            if (!decision.hasDetail) {
                Text(
                    "It gave a decision and a reason, and nothing further.",
                    style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.5f)),
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Context
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.context(room: AgentRoomState) {
    val run = room.run
    item("context") {
        Panel(
            title = "CONTEXT",
            body = when {
                run == null -> "Nothing is running."
                run.contextOffered == 0 ->
                    "This run has not been given any retrieved context. Its job text is its whole " +
                        "brief."
                else ->
                    "${run.contextSupplied} of ${run.contextOffered} retrieved sources reached the " +
                        "model. The rest were found and did not fit, which is recorded so a thin " +
                        "answer can be explained rather than guessed at."
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Outputs
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.outputs(room: AgentRoomState) {
    val artefacts = room.run?.artefacts.orEmpty()
    if (artefacts.isEmpty()) {
        item("none") {
            Panel(
                "NO OUTPUTS",
                "This run has not registered an output. An agent that only reasons and reports " +
                    "produces none, and the report is on the Overview tab.",
            )
        }
        return
    }
    items(artefacts, key = { it.artefactId }) { artefact -> ArtefactRow(artefact) }
}

@Composable
private fun ArtefactRow(artefact: ArtefactView) {
    MemphisSurface(
        modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp)) {
            Text(artefact.title.uppercase(), style = posterStyle(12f))
            Text(
                buildString {
                    append(artefact.kind)
                    append(" · ")
                    append(if (artefact.finalOutput) "final" else "intermediate")
                    if (artefact.sizeBytes > 0) {
                        append(" · ")
                        append("${artefact.sizeBytes / 1024} KB")
                    }
                    append(" · ")
                    append(artefact.storageKind.lowercase().replace('_', ' '))
                },
                style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                modifier = Modifier.padding(top = 4.dp),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// History
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.history(room: AgentRoomState) {
    if (room.history.isEmpty()) {
        item("none") {
            Panel("NO HISTORY", "This agent has not finished a run yet.")
        }
        return
    }
    items(room.history, key = { it.runId }) { past ->
        MemphisSurface(
            modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
            color = Memphis.Paper,
            borderWidth = 2.5.dp,
            shadowOffset = 4.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(11.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(past.status, style = captionStyle(9f), modifier = Modifier.weight(1f))
                    Text(
                        past.relativeAge(),
                        style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.5f)),
                    )
                }
                Text(
                    past.assignment.take(160),
                    style = captionStyle(9.5f, Memphis.Ink),
                    modifier = Modifier.padding(top = 4.dp),
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${past.toolCallCount} tool calls · ${past.artefactCount} outputs",
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.5f)),
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings
// ---------------------------------------------------------------------------

private fun androidx.compose.foundation.lazy.LazyListScope.settings(room: AgentRoomState) {
    item("visibility") {
        Column(Modifier.fillMaxWidth()) {
            Text("VISIBILITY", style = captionStyle(10f))
            Spacer(Modifier.height(6.dp))
            Text(
                "Each category is captured and shown independently. Turning display off hides it " +
                    "here and does not delete the record, so turning it back on shows what happened.",
                style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
            )
            Spacer(Modifier.height(9.dp))
            room.visibility.asMap().forEach { (category, rule) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        category.label,
                        style = captionStyle(9.5f),
                        modifier = Modifier.weight(1f),
                    )
                    Text(
                        buildString {
                            append(if (rule.capture) "captured" else "not captured")
                            append(" · ")
                            append(if (rule.display) "shown" else "hidden")
                            append(" · ")
                            append(rule.detail.name.lowercase())
                        },
                        style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.6f)),
                    )
                }
            }
        }
    }

    item("autonomy") {
        Column(Modifier.fillMaxWidth().padding(top = 16.dp)) {
            Text("AUTONOMY", style = captionStyle(10f))
            Spacer(Modifier.height(6.dp))
            room.autonomy.asMap().forEach { (dimension, permitted) ->
                Row(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(dimension.label, style = captionStyle(9.5f), modifier = Modifier.weight(1f))
                    Text(
                        if (permitted) "allowed" else "not allowed",
                        style = captionStyle(8.5f, if (permitted) Memphis.Ink else Memphis.Ink.copy(alpha = 0.45f)),
                    )
                }
            }
            Spacer(Modifier.height(6.dp))
            Text(
                "Changing these lives in the agent's creator. This room shows what the run is " +
                    "operating under rather than editing it mid-flight.",
                style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Shared pieces
// ---------------------------------------------------------------------------

@Composable
private fun ApprovalCard(approval: ApprovalView, viewModel: AgentRoomViewModel) {
    MemphisSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Memphis.Yellow,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text("NEEDS YOUR DECISION", style = posterStyle(11f))
            Spacer(Modifier.height(7.dp))
            // THE SIX FACTS, EACH LABELLED. A Yes button with less than this behind it is the
            // approval the specification's section 7.2 forbids.
            Fact("What", approval.what)
            Fact("Why", approval.why)
            Fact("Affects", approval.affected)
            Fact("Reversible", if (approval.reversible) "yes" else "no")
            Fact("Could go wrong", approval.consequences)
            Fact("Asks for", approval.requestedPermission)
            Spacer(Modifier.height(9.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                PaperButton("Allow", Modifier.weight(1f), filled = true) {
                    viewModel.onEvent(AgentRoomViewModel.Event.Approve(approval.approvalId))
                }
                PaperButton("Refuse", Modifier.weight(1f)) {
                    viewModel.onEvent(AgentRoomViewModel.Event.Reject(approval.approvalId))
                }
            }
        }
    }
}

@Composable
private fun Fact(label: String, value: String) {
    Row(Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Text(
            label.uppercase(),
            style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.6f)),
            modifier = Modifier.width(86.dp),
        )
        Text(value, style = captionStyle(9f, Memphis.Ink), modifier = Modifier.weight(1f))
    }
}

@Composable
private fun TabStrip(current: AgentRoomViewModel.RoomTab, onSelect: (AgentRoomViewModel.RoomTab) -> Unit) {
    LazyRow(
        modifier = Modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        items(AgentRoomViewModel.RoomTab.entries) { tab ->
            val active = tab == current
            Box(
                Modifier
                    .background(if (active) Memphis.Ink else Memphis.Paper)
                    .border(2.5.dp, Memphis.Ink)
                    .clickable { onSelect(tab) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
            ) {
                Text(
                    tab.name,
                    style = captionStyle(8.5f, if (active) Memphis.Paper else Memphis.Ink),
                )
            }
        }
    }
}

@Composable
private fun Panel(
    title: String,
    body: String,
    accent: Color = Memphis.Paper,
    modifier: Modifier = Modifier,
) {
    MemphisSurface(
        modifier = modifier.fillMaxWidth(),
        color = accent,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(title, style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.7f)))
            Spacer(Modifier.height(6.dp))
            Text(body, style = captionStyle(10f, Memphis.Ink))
        }
    }
}

@Composable
private fun Count(label: String, value: String, modifier: Modifier = Modifier) {
    Column(
        modifier.border(2.5.dp, Memphis.Ink).padding(vertical = 8.dp, horizontal = 6.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, style = posterStyle(14f))
        Text(label, style = captionStyle(7f, Memphis.Ink.copy(alpha = 0.55f)))
    }
}

@Composable
private fun Badge(label: String, colour: Color) {
    Box(
        Modifier.background(colour).border(2.dp, Memphis.Ink).padding(horizontal = 6.dp, vertical = 2.dp),
    ) {
        Text(label, style = captionStyle(7.5f, Memphis.Paper))
    }
}

@Composable
private fun PaperButton(
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean = false,
    onClick: () -> Unit,
) {
    MemphisSurface(
        modifier = modifier.clickable { onClick() },
        color = if (filled) Memphis.Ink else Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 11.dp, horizontal = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.uppercase(), style = captionStyle(8.5f, if (filled) Memphis.Paper else Memphis.Ink))
        }
    }
}

@Composable
private fun ControlError(message: String, onDismiss: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)
            .background(Memphis.Coral).border(2.5.dp, Memphis.Ink).padding(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(message, style = captionStyle(9f, Memphis.Paper), modifier = Modifier.weight(1f))
        Text(
            "DISMISS",
            style = captionStyle(8.5f, Memphis.Paper),
            modifier = Modifier.clickable { onDismiss() }.padding(start = 8.dp),
        )
    }
}

@Composable
private fun Message(text: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text, style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)), modifier = Modifier.padding(24.dp))
    }
}

/** A plan step's mark, so proposed and done cannot be read as the same thing. */
private fun stateMark(state: String): String = when (state) {
    com.unuslumen.app.domain.agents.PlanStepView.STATE_DONE -> "[x]"
    com.unuslumen.app.domain.agents.PlanStepView.STATE_ACTIVE -> "[>]"
    com.unuslumen.app.domain.agents.PlanStepView.STATE_BLOCKED -> "[!]"
    com.unuslumen.app.domain.agents.PlanStepView.STATE_ABANDONED -> "[-]"
    else -> "[ ]"
}
