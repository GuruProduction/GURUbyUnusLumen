package com.unuslumen.app.presentation.luxify

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.LuxifySkill
import com.unuslumen.app.presentation.luxify.components.SkillCard
import com.unuslumen.app.presentation.luxify.components.SkillDetailSheet
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.navigation.Screen
import org.koin.androidx.compose.koinViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SkillsScreen(
    navController: NavHostController,
    viewModel: SkillsViewModel = koinViewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var selectedSkill by remember { mutableStateOf<LuxifySkill?>(null) }
    var skillToDelete by remember { mutableStateOf<LuxifySkill?>(null) }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    Scaffold(
        topBar = {
            guruAppBar(stringResource(R.string.skills))
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { navController.navigate(Screen.PortalScreen) },
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
            ) {
                Icon(Icons.Filled.Add, contentDescription = "Create skill")
            }
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                state.isLoading -> {
                    Text(
                        text = "Loading skills...",
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.align(Alignment.Center)
                    )
                }

                state.error != null -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = state.error ?: "Something went wrong",
                            style = MaterialTheme.typography.bodyLarge,
                            color = MaterialTheme.colorScheme.error,
                            textAlign = TextAlign.Center
                        )
                        Spacer(Modifier.size(16.dp))
                        TextButton(onClick = { viewModel.onEvent(SkillsEvent.LoadSkills) }) {
                            Text("Retry")
                        }
                    }
                }

                state.skills.isEmpty() -> {
                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(20.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "No skills yet",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(Modifier.size(8.dp))
                        Text(
                            text = "Skills created through the interview flow will appear here.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    }
                }

                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(
                            top = 8.dp,
                            bottom = 24.dp,
                            start = 12.dp,
                            end = 12.dp
                        ),
                        verticalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        items(state.skills, key = { it.id }) { skill ->
                            SkillCard(
                                skill = skill,
                                onToggle = { enabled ->
                                    viewModel.onEvent(SkillsEvent.ToggleSkill(skill.id, enabled))
                                },
                                onTap = { selectedSkill = skill }
                            )
                        }
                    }
                }
            }
        }
    }

    selectedSkill?.let { skill ->
        ModalBottomSheet(
            onDismissRequest = { selectedSkill = null },
            sheetState = sheetState,
            containerColor = MaterialTheme.colorScheme.background,
            contentColor = MaterialTheme.colorScheme.onBackground
        ) {
            SkillDetailSheet(skill = skill)

            if (skill.source == "user") {
                Spacer(Modifier.size(8.dp))
                TextButton(
                    onClick = {
                        skillToDelete = skill
                        selectedSkill = null
                    },
                    modifier = Modifier.padding(horizontal = 20.dp)
                ) {
                    Text(
                        text = "Delete skill",
                        color = MaterialTheme.colorScheme.error
                    )
                }
            }
            Spacer(Modifier.size(24.dp))
        }
    }

    skillToDelete?.let { skill ->
        AlertDialog(
            onDismissRequest = { skillToDelete = null },
            title = { Text("Delete ${skill.name}?") },
            text = { Text("This cannot be undone. The skill will be permanently removed.") },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onEvent(SkillsEvent.DeleteSkill(skill.id))
                        skillToDelete = null
                    }
                ) {
                    Text("Delete", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { skillToDelete = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}