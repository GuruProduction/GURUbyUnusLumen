// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.agents

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
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.GuruAgent
import com.unuslumen.app.domain.repository.GrantableTool
import com.unuslumen.app.presentation.agents.components.AgentCard
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.navigation.Screen
import org.koin.androidx.compose.koinViewModel

/**
 * The agents screen: what you have, and the place you make more.
 *
 * Two modes in one route. The list is the default. The creator takes over the
 * whole screen rather than arriving as a sheet, because writing an agent's
 * instructions is long-form typing and a half-height sheet fights the keyboard.
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

    Scaffold(
        topBar = { guruAppBar("Agents") },
        floatingActionButton = {
            if (editing == null) {
                Column(horizontalAlignment = Alignment.End) {
                    // The marketplace lives one tap from the list rather than in
                    // the lobby, because browsing agents is something you do
                    // while looking at your own.
                    OutlinedButton(
                        onClick = { navController.navigate(Screen.AgentMarketScreen) }
                    ) { Text("Browse marketplace") }
                    Spacer(Modifier.height(10.dp))
                    FloatingActionButton(
                        onClick = { viewModel.onEvent(AgentsEvent.StartCreate) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Make an agent")
                    }
                }
            }
        }
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
                    onSave = { viewModel.onEvent(AgentsEvent.Save) },
                    onCancel = { viewModel.onEvent(AgentsEvent.CancelEdit) }
                )

                state.isLoading -> CenteredText("Loading your agents…")

                state.agents.isEmpty() -> EmptyAgents()

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 96.dp, start = 12.dp, end = 12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        state.notice?.let { notice ->
                            NoticeLine(notice) { viewModel.onEvent(AgentsEvent.ClearNotice) }
                        }
                        state.error?.let { error ->
                            NoticeLine(error) { viewModel.onEvent(AgentsEvent.ClearNotice) }
                        }
                    }
                    items(state.agents, key = { it.id }) { agent ->
                        AgentCard(
                            agent = agent,
                            onToggle = { enabled ->
                                viewModel.onEvent(AgentsEvent.ToggleEnabled(agent.id, enabled))
                            },
                            onTap = { viewModel.onEvent(AgentsEvent.StartEdit(agent)) }
                        )
                    }
                }
            }
        }
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

@Composable
private fun CenteredText(message: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(
            text = message,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(24.dp),
            textAlign = TextAlign.Center
        )
    }
}

@Composable
private fun EmptyAgents() {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            modifier = Modifier.padding(28.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "No agents yet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(Modifier.size(8.dp))
            Text(
                text = "Make one with the plus button. Give it instructions and the tools it may use, and GURU can hand it work to do in the background.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun NoticeLine(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
            TextButton(onClick = onDismiss) { Text("Dismiss") }
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
    onSave: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {
        Text(
            text = if (draft.id == null) "New agent" else "Edit agent",
            style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(12.dp))

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
            text = "This is the whole of the agent. Write it as you would brief someone starting cold: the job, the standard, and what to do when something is unclear.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(Modifier.height(18.dp))
        Text(
            text = "Tools it may use",
            style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold),
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(Modifier.height(4.dp))
        Text(
            text = "It can use only what is switched on here. Nothing else is even offered to it. Leave them all off and it reasons without touching anything.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.height(8.dp))

        AgentToolPicker(
            tools = availableTools,
            selected = draft.toolPermissions,
            onToggle = onToggleTool
        )

        if (error != null) {
            Spacer(Modifier.height(12.dp))
            Text(
                text = error,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }

        Spacer(Modifier.height(20.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            OutlinedButton(
                onClick = onCancel,
                modifier = Modifier.weight(1f),
                enabled = !saving
            ) { Text("Cancel") }

            TextButton(
                onClick = onSave,
                modifier = Modifier.weight(1f),
                enabled = !saving
            ) { Text(if (saving) "Saving…" else "Save") }
        }
        Spacer(Modifier.height(24.dp))
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
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
        return
    }

    var expandedCategory by remember { mutableStateOf<String?>(null) }

    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        tools.groupBy { it.category.ifBlank { "other" } }.toSortedMap().forEach { (category, toolsInGroup) ->
            val tools = toolsInGroup
            val chosenInCategory = tools.count { it.name in selected }
            val open = expandedCategory == category

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
            ) {
                Column(modifier = Modifier.fillMaxWidth().padding(12.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = category,
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.weight(1f)
                        )
                        Text(
                            text = if (chosenInCategory > 0) "$chosenInCategory on" else "",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(8.dp))
                        TextButton(onClick = {
                            expandedCategory = if (open) null else category
                        }) { Text(if (open) "Hide" else "Show") }
                    }

                    if (open) {
                        tools.forEach { tool ->
                            Row(
                                modifier = Modifier.fillMaxWidth().padding(top = 4.dp),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(
                                        text = tool.name,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurface
                                    )
                                    Text(
                                        text = tool.description,
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        maxLines = 2
                                    )
                                }
                                Switch(
                                    checked = tool.name in selected,
                                    onCheckedChange = { onToggle(tool.name, it) }
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
