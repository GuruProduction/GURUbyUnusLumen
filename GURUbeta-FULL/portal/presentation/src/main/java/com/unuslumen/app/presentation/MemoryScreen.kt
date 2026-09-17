package com.unuslumen.app.presentation

import android.content.res.Configuration
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import androidx.navigation.compose.rememberNavController
import com.unuslumen.app.domain.memory.MemoryFact
import com.unuslumen.app.domain.memory.ConversationThread
import com.unuslumen.app.ui.theme.guruTheme
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MemoryScreen(
    navController: NavHostController,
    viewModel: MemoryViewModel = koinViewModel()
) {
    val facts by viewModel.facts.collectAsState()
    val threads by viewModel.threads.collectAsState()
    val selectedCategory by viewModel.selectedCategory.collectAsState()
    val totalFacts by viewModel.totalFacts.collectAsState()
    val totalThreads by viewModel.totalThreads.collectAsState()
    val context = LocalContext.current

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Memory Brain") },
                navigationIcon = {
                    IconButton(onClick = { navController.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    IconButton(onClick = { viewModel.loadAll() }) {
                        Icon(Icons.Filled.Refresh, "Refresh")
                    }
                }
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp)
        ) {
            item {
                Spacer(Modifier.height(8.dp))
                Text(
                    "$totalFacts facts extracted  $totalThreads threads discovered",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(Modifier.height(8.dp))
            }

            item {
                Row(modifier = Modifier.fillMaxWidth()) {
                    FilterChip(
                        selected = selectedCategory == null,
                        onClick = { viewModel.filterByCategory(null) },
                        label = { Text("All") },
                        modifier = Modifier.padding(end = 4.dp)
                    )
                    viewModel.categories.take(4).forEach { cat ->
                        FilterChip(
                            selected = selectedCategory == cat,
                            onClick = { viewModel.filterByCategory(cat) },
                            label = { Text(cat.replace("_", " ")) },
                            modifier = Modifier.padding(end = 4.dp)
                        )
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            items(facts.sortedByDescending { it.extractedDate }) { fact ->
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Row {
                            Text(
                                fact.category.replace("_", " ").uppercase(),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(Modifier.width(8.dp))
                            LinearProgressIndicator(
                                progress = { fact.confidence },
                                modifier = Modifier
                                    .weight(1f)
                                    .padding(top = 4.dp)
                            )
                        }
                        Spacer(Modifier.height(4.dp))
                        Text(fact.fact, style = MaterialTheme.typography.bodyMedium)
                    }
                }
            }

            if (threads.isNotEmpty()) {
                item {
                    Spacer(Modifier.height(16.dp))
                    Text(
                        "Threads",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Spacer(Modifier.height(8.dp))
                }
                items(threads) { thread ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.tertiaryContainer
                        )
                    ) {
                        Column(modifier = Modifier.padding(12.dp)) {
                            Text(thread.title, style = MaterialTheme.typography.titleSmall)
                            Spacer(Modifier.height(4.dp))
                            Text(thread.summary, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
        }
    }
}

@Preview
@Preview(uiMode = Configuration.UI_MODE_NIGHT_YES)
@Composable
private fun MemoryScreenPreview() {
    guruTheme {
        MemoryScreen(navController = rememberNavController())
    }
}
