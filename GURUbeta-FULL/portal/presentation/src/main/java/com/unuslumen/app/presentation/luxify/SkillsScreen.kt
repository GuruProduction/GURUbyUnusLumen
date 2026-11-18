// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.luxify

import androidx.compose.foundation.background
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
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.LuxifySkill
import com.unuslumen.app.presentation.luxify.components.SkillCard
import com.unuslumen.app.presentation.luxify.components.SkillDetailSheet
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import org.koin.androidx.compose.koinViewModel

/**
 * The skills room, in the lobby's language.
 *
 * THIS REPLACED A LIST OF ROUNDED CARDS AND A FLOATING BUTTON. Each skill was a cream card with
 * a soft shadow, tool pills and a grey Material switch, and the button that made one floated
 * over the last row. A room is now the same printed page as the lobby and the agents room:
 * flat paper, ink edges, printed drops, poster type, and every door part of the page so
 * nothing can sit on top of anything.
 */
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
    val skillCount = state.skills.size

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = stringResource(R.string.skills),
                // The one fact worth printing in the corner: how many there are.
                stamp = "$skillCount skills",
            )
        },
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                // The way to make a skill was a floating button before and was reachable while
                // the list loaded, so the loading page keeps it rather than losing a door.
                state.isLoading -> PageMessage(
                    message = "Loading skills...",
                    action = {
                        PaperButton(
                            label = "New skill",
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .fillMaxWidth(),
                            onClick = { navController.navigate(Screen.PortalScreen) },
                        )
                    },
                )

                state.error != null -> PageMessage(
                    message = state.error ?: "Something went wrong",
                    action = {
                        PaperButton(
                            label = "Retry",
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .fillMaxWidth(),
                            filled = true,
                            onClick = { viewModel.onEvent(SkillsEvent.LoadSkills) },
                        )
                        Spacer(Modifier.height(9.dp))
                        // The door the floating button used to hold, kept on the page so a
                        // failure still leaves a way to make a skill.
                        PaperButton(
                            label = "New skill",
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .fillMaxWidth(),
                            onClick = { navController.navigate(Screen.PortalScreen) },
                        )
                    },
                )

                state.skills.isEmpty() -> PageMessage(
                    message = "No skills yet",
                    secondary = "Skills created through the interview flow will appear here.",
                    action = {
                        MakeSkillBlock(
                            modifier = Modifier
                                .padding(horizontal = 24.dp)
                                .fillMaxWidth(),
                            onClick = { navController.navigate(Screen.PortalScreen) },
                        )
                    },
                )

                else -> SkillsList(
                    skills = state.skills,
                    onToggle = { skill, enabled ->
                        viewModel.onEvent(SkillsEvent.ToggleSkill(skill.id, enabled))
                    },
                    onOpen = { skill -> selectedSkill = skill },
                    onCreate = { navController.navigate(Screen.PortalScreen) },
                )
            }
        }
    }

    selectedSkill?.let { skill ->
        ModalBottomSheet(
            onDismissRequest = { selectedSkill = null },
            sheetState = sheetState,
            // Square, paper and ink, so the sheet is a page pulled up rather than a Material panel.
            shape = RectangleShape,
            containerColor = Memphis.Paper,
            contentColor = Memphis.Ink,
            scrimColor = Memphis.Ink.copy(alpha = 0.45f),
            // The hand that pulls the sheet is a printed bar, not a grey Material pill.
            dragHandle = {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Box(Modifier.width(40.dp).height(4.dp).background(Memphis.Ink))
                }
            },
        ) {
            SkillDetailSheet(
                skill = skill,
                onBack = { selectedSkill = null },
            )

            if (skill.source == "user") {
                Spacer(Modifier.height(10.dp))
                PaperButton(
                    label = "Delete skill",
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp),
                    onClick = {
                        skillToDelete = skill
                        selectedSkill = null
                    },
                )
            }
            Spacer(Modifier.height(24.dp))
        }
    }

    skillToDelete?.let { skill ->
        AlertDialog(
            onDismissRequest = { skillToDelete = null },
            shape = RectangleShape,
            containerColor = Memphis.Paper,
            tonalElevation = 0.dp,
            title = {
                Text(
                    text = "Delete ${skill.name}?",
                    style = posterStyle(18f),
                )
            },
            text = {
                Text(
                    text = "This cannot be undone. The skill will be permanently removed.",
                    style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.7f)).copy(
                        letterSpacing = TextUnit.Unspecified,
                    ),
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        viewModel.onEvent(SkillsEvent.DeleteSkill(skill.id))
                        skillToDelete = null
                    }
                ) {
                    Text("Delete", style = captionStyle(9f, Memphis.Coral))
                }
            },
            dismissButton = {
                TextButton(onClick = { skillToDelete = null }) {
                    Text("Cancel", style = captionStyle(9f))
                }
            }
        )
    }
}

/**
 * The list itself: a ruled band, one row per skill, and one way to make more.
 *
 * A row that runs to any length reads as designed, which a grid of cards does not once there
 * are forty of them, so the skills follow the agents list rather than a wall of tiles.
 */
@Composable
private fun SkillsList(
    skills: List<LuxifySkill>,
    onToggle: (LuxifySkill, Boolean) -> Unit,
    onOpen: (LuxifySkill) -> Unit,
    onCreate: () -> Unit,
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 14.dp, bottom = 28.dp),
    ) {
        item(key = "band") {
            SectionBand(label = "All skills", count = skills.size)
        }

        items(skills, key = { it.id }) { skill ->
            SkillCard(
                skill = skill,
                onToggle = { enabled -> onToggle(skill, enabled) },
                onTap = { onOpen(skill) },
                modifier = Modifier.padding(top = 9.dp),
            )
        }

        item(key = "make") {
            SectionBand(label = "Make one", count = null, modifier = Modifier.padding(top = 18.dp))
            MakeSkillBlock(
                modifier = Modifier.fillMaxWidth().padding(top = 9.dp),
                onClick = onCreate,
            )
        }
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

/** The way to make a skill, printed as the page's own black block rather than floated over it. */
@Composable
private fun MakeSkillBlock(modifier: Modifier = Modifier, onClick: () -> Unit) {
    MemphisSurface(
        modifier = modifier.clickable { onClick() },
        color = Memphis.Ink,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text("+ New skill", style = posterStyle(14f, Memphis.Paper))
        }
    }
}

/**
 * A state message set as a caption in the middle of the page.
 *
 * Loading, failure and emptiness are not dialog boxes and not cards: they are the page saying
 * one thing, so the message carries the same caption voice as every other label and any door
 * out of the state is drawn as a printed block beneath it.
 */
@Composable
private fun PageMessage(
    message: String,
    secondary: String? = null,
    action: (@Composable () -> Unit)? = null,
) {
    Box(
        modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = message,
                style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)),
                textAlign = TextAlign.Center,
            )
            if (secondary != null) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = secondary,
                    style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.55f)).copy(
                        letterSpacing = TextUnit.Unspecified,
                    ),
                    textAlign = TextAlign.Center,
                )
            }
            if (action != null) {
                Spacer(Modifier.height(16.dp))
                action()
            }
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
            Modifier.fillMaxWidth().padding(vertical = 11.dp, horizontal = 10.dp),
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