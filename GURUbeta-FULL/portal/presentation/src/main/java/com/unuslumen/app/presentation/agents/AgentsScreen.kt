// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.repository.GrantableTool
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import org.koin.androidx.compose.koinViewModel

/**
 * The agents room, in the lobby's language.
 *
 * THIS REPLACED A LIST OF ROUNDED CARDS. Each agent was a cream card with a soft
 * shadow, lavender tool pills and a grey Material switch, which read as a settings
 * page that happened to share the house background. The lobby is a printed poster:
 * flat saturated blocks, hard ink edges, printed drops, poster type. A room is now
 * the same page, so the app reads as one thing rather than two.
 *
 * ONE MORE FIX WENT WITH IT. The two buttons and the plus lived in the floating
 * action corner, where they overlapped the last card and clipped their own labels.
 * They are part of the page now, so nothing can sit on top of anything.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentsScreen(
    navController: NavHostController,
    viewModel: AgentsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var agentToDelete by remember { mutableStateOf<GuruAgent?>(null) }

    val editing = state.editing
    val enabledCount = state.agents.count { it.enabled }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = if (editing != null) {
                    if (editing.id == null) "New agent" else "Edit agent"
                } else {
                    "Agents"
                },
                // The one fact worth printing in the corner: how many are live.
                stamp = if (editing == null && state.agents.isNotEmpty()) "$enabledCount on" else null,
            )
        },
    ) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                editing != null -> AgentCreator(
                    draft = editing,
                    saving = state.saving,
                    error = state.error,
                    availableTools = state.availableTools,
                    onDraftChange = { viewModel.onEvent(AgentsEvent.UpdateDraft(it)) },
                    onToggleTool = { name, selected ->
                        viewModel.onEvent(AgentsEvent.ToggleTool(name, selected))
                    },
                    onSelectAllTools = { viewModel.onEvent(AgentsEvent.SelectAllTools) },
                    onClearAllTools = { viewModel.onEvent(AgentsEvent.ClearAllTools) },
                    onSave = { viewModel.onEvent(AgentsEvent.Save) },
                    onSaveAndPublish = { viewModel.onEvent(AgentsEvent.SaveAndPublish) },
                    onCancel = { viewModel.onEvent(AgentsEvent.CancelEdit) }
                )

                state.isLoading -> PageMessage("Loading your agents…")

                else -> AgentList(
                    state = state,
                    onShowIntro = { viewModel.onEvent(AgentsEvent.ShowIntro) },
                    onToggle = { agent, enabled ->
                        viewModel.onEvent(AgentsEvent.ToggleEnabled(agent.id, enabled))
                    },
                    onOpen = { agent -> viewModel.onEvent(AgentsEvent.StartEdit(agent)) },
                    onNew = { viewModel.onEvent(AgentsEvent.StartCreate) },
                    onClearNotice = { viewModel.onEvent(AgentsEvent.ClearNotice) },
                )
            }
        }
    }

    // The intro sits over everything, including the creator, because it is an
    // explanation rather than a step in a flow.
    if (state.showIntro) {
        AgentsIntroPanel(onDismiss = { viewModel.onEvent(AgentsEvent.DismissIntro) })
    }

    agentToDelete?.let { agent ->
        AlertDialog(
            onDismissRequest = { agentToDelete = null },
            title = { Text("Delete ${agent.name}?") },
            text = { Text("This removes it from your app permanently.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onEvent(AgentsEvent.Delete(agent))
                        agentToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { agentToDelete = null }) { Text("Cancel") }
            }
        )
    }
}

/**
 * The list itself: two doors, a ruled list of agents, and one way to make more.
 *
 * The agents are rows rather than cards because the lobby established that shape:
 * a swatch, a name, a count, a door. A row that runs to any length reads as
 * designed, which a grid of cards does not once there are forty of them.
 */
@Composable
private fun AgentList(
    state: AgentsState,
    onShowIntro: () -> Unit,
    onToggle: (GuruAgent, Boolean) -> Unit,
    onOpen: (GuruAgent) -> Unit,
    onNew: () -> Unit,
    onClearNotice: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 28.dp),
    ) {
        // One door, not two. Ottio is its own room in the lobby now, so a second
        // door here would be the same shop reachable twice from different places.
        item(key = "doors") {
            PaperButton(
                label = "What is an agent?",
                modifier = Modifier.fillMaxWidth(),
                onClick = onShowIntro,
            )
        }

        state.notice?.let { notice ->
            item(key = "notice") { NoticeLine(notice, onClearNotice) }
        }
        state.error?.let { error ->
            item(key = "error") { NoticeLine(error, onClearNotice) }
        }

        if (state.agents.isEmpty()) {
            item(key = "empty") {
                Column(Modifier.fillMaxWidth().padding(top = 26.dp, bottom = 8.dp)) {
                    Text("NO AGENTS YET", style = captionStyle(10f))
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Make one below. Give it instructions and the tools it may use, and GURU " +
                            "can hand it work to do on its own while your chat carries on.",
                        style = captionStyle(9.5f, Memphis.Ink).copy(
                            fontFamily = com.unuslumen.app.ui.theme.MemphisBody,
                            letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
                        ),
                    )
                }
            }
        } else {
            item(key = "band") {
                SectionBand(
                    label = "Yours and downloaded",
                    count = state.agents.size,
                    modifier = Modifier.padding(top = 16.dp),
                )
            }

            itemsIndexed(state.agents, key = { _, agent -> agent.id }) { index, agent ->
                AgentRow(
                    agent = agent,
                    ink = Memphis.inkFor(index),
                    onToggle = { onToggle(agent, it) },
                    onOpen = { onOpen(agent) },
                )
            }
        }

        item(key = "make") {
            SectionBand(label = "Make one", count = null, modifier = Modifier.padding(top = 18.dp))
            MemphisSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 9.dp)
                    .clickable { onNew() },
                color = Memphis.Ink,
                borderWidth = 2.5.dp,
                shadowOffset = 4.dp,
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 13.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text("+ New agent", style = posterStyle(14f, Memphis.Paper))
                }
            }
        }
    }
}

/** One agent as a ruled row: its colour, its name, its tool count, and a door. */
@Composable
private fun AgentRow(
    agent: GuruAgent,
    ink: Color,
    onToggle: (Boolean) -> Unit,
    onOpen: () -> Unit,
) {
    MemphisSurface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 9.dp)
            .clickable { onOpen() },
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The swatch carries the agent's colour, from the same rotation the
            // lobby uses, so a downloaded agent is dressed the moment it lands.
            Box(Modifier.size(22.dp).background(ink))

            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    text = agent.name.uppercase(),
                    style = posterStyle(15f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = buildString {
                        append(if (agent.isUserOwned) "MADE HERE" else "DOWNLOADED")
                        append(" · ")
                        append("${agent.toolPermissions.size} TOOL")
                        if (agent.toolPermissions.size != 1) append("S")
                    },
                    style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.5f)),
                    maxLines = 1,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }

            // The switch, flat and printed. No Material pill, no grey.
            FlatSwitch(checked = agent.enabled, onChange = onToggle)
        }
    }
}

/**
 * A switch in the page's own grammar.
 *
 * A Material switch is a soft grey capsule with a raised thumb, which reads as a
 * different product beside a hard black outline. This is a rectangle that fills
 * with ink when it is on, with the same border weight as every other block.
 */
@Composable
private fun FlatSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 24.dp)
            .background(if (checked) Memphis.Ink else Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(16.dp)
                .background(if (checked) Memphis.Paper else Memphis.Ink),
        )
    }
}

/**
 * One category in the creator's row.
 *
 * Drawn as a printed block that fills with ink when chosen, because the creator is
 * a page in the same grammar as everything else. The selected one is the only block
 * on the row carrying paper text, so which is chosen reads before any word does.
 */
@Composable
private fun RowScope.CategoryChip(
    label: String,
    on: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier
            .weight(1f)
            .background(if (on) Memphis.Ink else Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .clickable { onClick() }
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label.uppercase(),
            style = captionStyle(6.5f, if (on) Memphis.Paper else Memphis.Ink),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A full-width black band that separates one group from the next. */
@Composable
private fun SectionBand(label: String, count: Int?, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .background(Memphis.Ink)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = captionStyle(9.5f, Memphis.Paper))
        if (count != null) {
            Text(
                text = "$count",
                style = captionStyle(9.5f, Memphis.Ink),
                modifier = Modifier
                    .background(Memphis.Paper)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** A button drawn as a printed block, not a Material outlined button. */
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
            Text(
                label.uppercase(),
                style = captionStyle(9f, if (filled) Memphis.Paper else Memphis.Ink),
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun PageMessage(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)),
            modifier = Modifier.padding(24.dp),
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun NoticeLine(message: String, onDismiss: () -> Unit) {
    MemphisSurface(
        modifier = Modifier.fillMaxWidth().padding(top = 12.dp),
        color = Memphis.Yellow,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(11.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = message,
                style = captionStyle(9f),
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onDismiss) {
                Text("DISMISS", style = captionStyle(9f))
            }
        }
    }
}

/**
 * The creator. Instructions first because that is what an agent IS; the tool
 * list comes after and is honest about the consequence, since an agent with no
 * tools can still think and one with a tool can change something on the phone.
 */
@Composable
private fun AgentCreator(
    draft: EditableAgent,
    saving: Boolean,
    error: String?,
    availableTools: List<GrantableTool>,
    onDraftChange: (EditableAgent) -> Unit,
    onToggleTool: (String, Boolean) -> Unit,
    onSelectAllTools: () -> Unit,
    onClearAllTools: () -> Unit,
    onSave: () -> Unit,
    onSaveAndPublish: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(18.dp)
    ) {
        OutlinedTextField(
            value = draft.name,
            onValueChange = { onDraftChange(draft.copy(name = it)) },
            label = { Text("Name") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = draft.description,
            onValueChange = { onDraftChange(draft.copy(description = it)) },
            label = { Text("What it is for") },
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(10.dp))

        OutlinedTextField(
            value = draft.systemPrompt,
            onValueChange = { onDraftChange(draft.copy(systemPrompt = it)) },
            label = { Text("Instructions") },
            minLines = 8,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "This is the whole of the agent. Write it as you would brief someone starting " +
                "cold: the job, the standard, and what to do when something is unclear.",
            style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
        )

        Spacer(Modifier.height(18.dp))
        SectionBand(label = "What it is for", count = null)
        Spacer(Modifier.height(8.dp))
        // The market browses by category, so the creator asks for one. A closed set
        // of six rather than a text box, because free text produces forty spellings
        // of "coding" and an aisle strip nobody can use.
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            EditableAgent.CATEGORIES.forEach { category ->
                CategoryChip(
                    modifier = Modifier.weight(1f),
                    label = category,
                    on = draft.category == category,
                    onClick = { onDraftChange(draft.copy(category = category)) },
                )
            }
        }

        Spacer(Modifier.height(18.dp))
        SectionBand(label = "Tools it may use", count = draft.toolPermissions.size)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "It can use only what is switched on here. Nothing else is even offered to it. " +
                "Leave them all off and it reasons without touching anything.",
            style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.height(8.dp))

        // ALL OR NONE, IN ONE PRESS. Granting forty tools one switch at a time was
        // the missing control, and "all" means every tool the picker was handed,
        // which is already stripped of the agent verbs.
        Row(
            Modifier.fillMaxWidth().padding(bottom = 9.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            PaperButton(
                label = "All tools",
                modifier = Modifier.weight(1f),
                filled = draft.toolPermissions.size == availableTools.size &&
                    availableTools.isNotEmpty(),
                onClick = onSelectAllTools,
            )
            PaperButton(
                label = "None",
                modifier = Modifier.weight(1f),
                filled = draft.toolPermissions.isEmpty(),
                onClick = onClearAllTools,
            )
        }

        AgentToolPicker(
            tools = availableTools,
            selected = draft.toolPermissions,
            onToggle = onToggleTool
        )

        Spacer(Modifier.height(18.dp))
        SectionBand(label = "Price on Ottio", count = null)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Leave it empty to give it away. You keep the majority of every sale; " +
                "Unus Lumen takes a small cut for running the market.",
            style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.height(8.dp))
        OutlinedTextField(
            value = draft.price,
            onValueChange = { onDraftChange(draft.copy(price = it)) },
            label = { Text("Price in pounds") },
            placeholder = { Text("Free") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = error,
                style = captionStyle(9f, Memphis.Coral),
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            PaperButton(
                label = "Cancel",
                modifier = Modifier.weight(1f),
                onClick = onCancel,
            )
            PaperButton(
                label = if (saving) "Saving…" else "Save agent",
                modifier = Modifier.weight(1f),
                filled = true,
                onClick = onSave,
            )
        }
        Spacer(Modifier.height(10.dp))
        // PUBLISH IS ITS OWN PRESS. Saving keeps the agent on this phone; publishing
        // puts it in Ottio for review, which is a network call and a public listing.
        // Binding them into one button would publish work on the way past.
        PaperButton(
            label = if (saving) "Working…" else "Save and publish to Ottio",
            modifier = Modifier.fillMaxWidth(),
            onClick = onSaveAndPublish,
        )
        Spacer(Modifier.height(9.dp))
        Text(
            text = "Publishing sends it for review. It goes live once it has been checked, " +
                "and what a buyer downloads is what was checked.",
            style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.height(28.dp))
    }
}

/**
 * The tool picker.
 *
 * Grouped by the category each tool already declares, so a long catalogue stays
 * navigable and a person can see the shape of what they are granting. The list
 * arrives already sorted and already stripped of the agent verbs, so a tool that
 * does not exist cannot be granted and an agent cannot be given the ability to
 * spawn another.
 */
@Composable
private fun AgentToolPicker(
    tools: List<GrantableTool>,
    selected: Set<String>,
    onToggle: (String, Boolean) -> Unit
) {
    if (tools.isEmpty()) {
        Text(
            text = "No tools are available right now, so agents can only think.",
            style = captionStyle(9f, Memphis.Coral),
        )
        return
    }

    var expandedCategory by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        tools.groupBy { it.category.ifBlank { "other" } }.toSortedMap()
            .forEach { (category, toolsInGroup) ->
                val chosenInCategory = toolsInGroup.count { it.name in selected }
                val open = expandedCategory == category

                MemphisSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Memphis.Paper,
                    borderWidth = 2.5.dp,
                    shadowOffset = 4.dp,
                ) {
                    Column(Modifier.fillMaxWidth().padding(11.dp)) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    expandedCategory = if (open) null else category
                                },
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = category.uppercase(),
                                style = captionStyle(9.5f),
                                modifier = Modifier.weight(1f),
                            )
                            if (chosenInCategory > 0) {
                                Text(
                                    text = "$chosenInCategory ON",
                                    style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                                )
                                Spacer(Modifier.width(8.dp))
                            }
                            Text(
                                text = if (open) "HIDE" else "SHOW",
                                style = captionStyle(9f),
                            )
                        }

                        if (open) {
                            toolsInGroup.forEach { tool ->
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(top = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = tool.name,
                                            style = captionStyle(9.5f),
                                        )
                                        Text(
                                            text = tool.description,
                                            style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                                            maxLines = 2,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Spacer(Modifier.width(10.dp))
                                    FlatSwitch(
                                        checked = tool.name in selected,
                                        onChange = { onToggle(tool.name, it) },
                                    )
                                }
                            }
                        }
                    }
                }
            }
    }
}
