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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.ui.components.common.guruAppBar
import org.koin.androidx.compose.koinViewModel

/**
 * The agent marketplace: what the publisher has published, and what this phone
 * already holds of it.
 *
 * The permissions are printed on every row before the install button, because
 * that is the whole of the decision the person is making. Nothing here asks
 * for a second confirmation beyond that; the review already happened on our
 * side before the agent was published at all.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AgentMarketScreen(
    navController: NavHostController,
    viewModel: MarketViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val visible = MarketViewModel.visibleEntries(state)

    Scaffold(topBar = { guruAppBar("Agent marketplace") }) { paddingValues ->
        Box(modifier = Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                state.isLoading -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        "Reading the catalogue…",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }

                !state.reachable -> Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Column(
                        modifier = Modifier.padding(28.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = state.error ?: "The catalogue could not be reached.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.size(12.dp))
                        TextButton(onClick = { viewModel.onEvent(MarketEvent.Load) }) {
                            Text("Try again")
                        }
                    }
                }

                else -> LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = PaddingValues(12.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    item {
                        OutlinedTextField(
                            value = state.query,
                            onValueChange = { viewModel.onEvent(MarketEvent.Search(it)) },
                            label = { Text("Search agents") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth()
                        )
                    }
                    state.notice?.let { notice ->
                        item { MarketNotice(notice) { viewModel.onEvent(MarketEvent.ClearNotice) } }
                    }
                    state.error?.let { error ->
                        item { MarketNotice(error) { viewModel.onEvent(MarketEvent.ClearNotice) } }
                    }
                    if (visible.isEmpty()) {
                        item {
                            Text(
                                text = if (state.query.isBlank()) {
                                    "Nothing is published yet."
                                } else {
                                    "No agent matches that."
                                },
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(20.dp)
                            )
                        }
                    }
                    items(visible, key = { it.slug }) { entry ->
                        MarketRow(
                            entry = entry,
                            installing = state.installingSlug == entry.slug,
                            onInstall = { viewModel.onEvent(MarketEvent.Install(entry.slug)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketRow(
    entry: MarketEntry,
    installing: Boolean,
    onInstall: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.elevatedCardElevation(3.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                text = entry.name,
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (entry.roleDescription.isNotBlank()) {
                Spacer(Modifier.size(4.dp))
                Text(
                    text = entry.roleDescription,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 3,
                    overflow = TextOverflow.Ellipsis
                )
            }

            Spacer(Modifier.size(8.dp))
            Text(
                text = if (entry.tools.isEmpty()) {
                    "Uses no tools. It reasons and answers."
                } else {
                    "Can use: ${entry.tools.joinToString(", ")}"
                },
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(Modifier.size(10.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = entry.authorLabel?.let { "by $it" } ?: "",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(8.dp))
                if (entry.installed) {
                    Text(
                        text = "In your agents",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                } else {
                    TextButton(onClick = onInstall, enabled = !installing) {
                        Text(if (installing) "Adding…" else "Add")
                    }
                }
            }
        }
    }
}

@Composable
private fun MarketNotice(message: String, onDismiss: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
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
