// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
@file:OptIn(ExperimentalLayoutApi::class)

package com.unuslumen.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Code
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.Project
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBody
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import org.koin.androidx.compose.koinViewModel

/**
 * The projects room, in the house language.
 *
 * WHAT IT WAS. A Material grid of rounded cards, each one filled with a colour parsed
 * from a hex string, an icon in a circle, a Material overflow menu, a liquid floating
 * button over the last card and a Material dialog to create one. It was the last room
 * still dressed by a different hand.
 *
 * WHAT IT IS NOW. Ruled cards on paper carrying the project's own colour as a spine,
 * a swatch-and-posters page, a printed add bar on the page rather than a button over
 * it, and a create sheet in the same geometry as the lobby's add-a-room and the
 * heartbeat's booking sheet.
 *
 * Every event, every navigation target and every string resource is the one the page
 * already used.
 */
@Composable
fun ProjectsScreen(
    navController: NavHostController,
    viewModel: ProjectsViewModel = koinViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    var showCreateDialog by remember { mutableStateOf(false) }
    var projectToEdit by remember { mutableStateOf<Project?>(null) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = stringResource(R.string.projects),
                stamp = if (uiState.projects.isEmpty()) null else "${uiState.projects.size} projects",
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            if (uiState.projects.isEmpty()) {
                NoProjectsMessage(
                    onAdd = { showCreateDialog = true },
                )
            } else {
                ProjectsGrid(
                    projects = uiState.projects,
                    onAdd = { showCreateDialog = true },
                    onProjectClick = { project ->
                        navController.navigate(Screen.ProjectDetailScreen(project.id))
                    },
                    onProjectLongPress = { project ->
                        projectToEdit = project
                    },
                )
            }
        }
    }

    if (showCreateDialog || projectToEdit != null) {
        ProjectDialog(
            project = projectToEdit,
            onDismiss = {
                showCreateDialog = false
                projectToEdit = null
            },
            onSave = { title, description, promptOverlay, color, icon ->
                if (projectToEdit != null) {
                    viewModel.onEvent(
                        ProjectsEvent.UpdateProject(
                            projectToEdit!!.copy(
                                title = title,
                                description = description,
                                promptOverlay = promptOverlay,
                                color = color,
                                icon = icon
                            )
                        )
                    )
                } else {
                    viewModel.onEvent(
                        ProjectsEvent.CreateProject(
                            title = title,
                            description = description,
                            promptOverlay = promptOverlay,
                            color = color,
                            icon = icon
                        )
                    )
                }
                showCreateDialog = false
                projectToEdit = null
            }
        )
    }
}

@Composable
fun NoProjectsMessage(onAdd: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "NO PROJECTS YET",
            style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.height(8.dp))
        Text(
            text = "A project is a place to work with GURU on one thing, with its own " +
                "instructions and its own history.",
            style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.55f)).copy(
                fontFamily = MemphisBody,
                letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
            ),
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(18.dp))
        PrintedBar(label = "+ New project", onClick = onAdd)
    }
}

@Composable
fun ProjectsGrid(
    projects: List<Project>,
    onAdd: () -> Unit,
    onProjectClick: (Project) -> Unit,
    onProjectLongPress: (Project) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 160.dp),
        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 4.dp, bottom = 28.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
            PrintedBar(label = "+ New project", onClick = onAdd)
        }

        itemsIndexed(projects, key = { _, project -> project.id }) { index, project ->
            ProjectCard(
                project = project,
                ink = projectInk(project, index),
                onClick = { onProjectClick(project) },
                onLongPress = { onProjectLongPress(project) },
            )
        }
    }
}

/**
 * The project's own colour, or the rotation when it has none.
 *
 * A project carries a hex string from the creator. It is honoured where it parses,
 * because that is the colour the owner chose, and falls back to the lobby's rotation
 * so a project made before the picker existed still gets a real one.
 */
private fun projectInk(project: Project, index: Int): Color = try {
    Color(android.graphics.Color.parseColor(project.color))
} catch (_: Exception) {
    Memphis.inkFor(index)
}

/** A full-width printed bar, used for the one action a page exists to offer. */
@Composable
private fun PrintedBar(label: String, onClick: () -> Unit) {
    MemphisSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = Memphis.Ink,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label, style = posterStyle(14f, Memphis.Paper))
        }
    }
}

@Composable
fun ProjectCard(
    project: Project,
    ink: Color = Memphis.inkForName(project.title),
    onClick: () -> Unit,
    onLongPress: () -> Unit
) {
    var showMenu by remember { mutableStateOf(false) }

    MemphisSurface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .height(46.dp)
                    .background(ink)
                    .border(2.5.dp, Memphis.Ink)
            ) {
                Box(
                    Modifier.align(Alignment.CenterStart).padding(start = 9.dp),
                ) {
                    Icon(
                        imageVector = getProjectIcon(project.icon),
                        contentDescription = null,
                        tint = Memphis.Ink,
                        modifier = Modifier.size(22.dp),
                    )
                }
                Box(
                    Modifier.align(Alignment.CenterEnd).padding(end = 2.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.MoreVert,
                        contentDescription = "More options",
                        tint = Memphis.Ink,
                        modifier = Modifier
                            .size(24.dp)
                            .clickable { showMenu = true },
                    )
                    DropdownMenu(
                        expanded = showMenu,
                        onDismissRequest = { showMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text("EDIT", style = captionStyle(9.5f)) },
                            onClick = {
                                showMenu = false
                                onLongPress()
                            }
                        )
                    }
                }
            }

            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Text(
                    text = project.title.uppercase(),
                    style = posterStyle(13f),
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (project.description.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(
                        text = project.description,
                        style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.6f)).copy(
                            fontFamily = MemphisBody,
                            letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatChip(label = "${project.messageCount} msgs")
                    StatChip(label = "${project.documentCount} docs")
                }
            }
        }
    }
}

@Composable
fun StatChip(label: String) {
    Text(
        text = label.uppercase(),
        style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.55f)),
        modifier = Modifier
            .background(Memphis.Paper)
            .border(1.5.dp, Memphis.Ink.copy(alpha = 0.25f))
            .padding(horizontal = 5.dp, vertical = 2.dp),
    )
}

@Composable
fun getProjectIcon(iconName: String): ImageVector {
    return when (iconName.lowercase()) {
        "code", "coding", "development" -> Icons.Default.Code
        "work", "business" -> Icons.Default.Work
        "description", "document", "writing" -> Icons.Default.Description
        else -> Icons.Default.Folder
    }
}

/**
 * Create or edit a project, on a printed sheet.
 *
 * The same geometry as the lobby's add-a-room and the heartbeat's booking sheet: a
 * scrim, a paper sheet with a heavier border and a bigger drop than anything behind
 * it, a fixed masthead and foot, and a body that scrolls. Create stays disabled until
 * a title exists, because a project with no name is not a project.
 */
@Composable
fun ProjectDialog(
    project: Project?,
    onDismiss: () -> Unit,
    onSave: (title: String, description: String, promptOverlay: String, color: String, icon: String) -> Unit
) {
    var title by remember { mutableStateOf(project?.title ?: "") }
    var description by remember { mutableStateOf(project?.description ?: "") }
    var promptOverlay by remember { mutableStateOf(project?.promptOverlay ?: "") }
    var selectedColor by remember { mutableStateOf(project?.color ?: "#6366f1") }
    var selectedIcon by remember { mutableStateOf(project?.icon ?: "folder") }

    val colors = listOf(
        "#FFCE1B", "#5BC8E0", "#EE5B45", "#7A5AF8",
        "#15807A", "#FF8FA8", "#C9B8FF", "#FFB020",
    )

    val icons = listOf("folder", "code", "work", "description")

    Box(
        Modifier
            .fillMaxSize()
            .background(Memphis.Ink.copy(alpha = 0.45f))
            .clickable(
                interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MemphisSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
                .heightIn(max = 640.dp)
                .clickable(
                    interactionSource = remember { androidx.compose.foundation.interaction.MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            color = Memphis.Paper,
            borderWidth = 3.dp,
            shadowOffset = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 15.dp)) {
                    Text(
                        text = if (project == null) "NEW PROJECT" else "EDIT PROJECT",
                        style = posterStyle(24f),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 7.dp)
                            .height(3.dp)
                            .background(Memphis.Ink),
                    )
                }

                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 10.dp),
                ) {
                    FieldLabel("Title")
                    SheetField(
                        value = title,
                        placeholder = "The thing you are working on",
                        onChange = { title = it },
                    )

                    FieldLabel("Description")
                    SheetField(
                        value = description,
                        placeholder = "One line so you know which one this is",
                        onChange = { description = it },
                    )

                    FieldLabel("Instructions GURU works to here")
                    SheetField(
                        value = promptOverlay,
                        placeholder = "You are helping with wedding planning. Always check dates before answering.",
                        onChange = { promptOverlay = it },
                        minHeight = 84.dp,
                    )

                    FieldLabel("Colour")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        colors.forEach { colour ->
                            val chosen = selectedColor == colour
                            Box(
                                modifier = Modifier
                                    .size(34.dp)
                                    .background(Color(android.graphics.Color.parseColor(colour)))
                                    .border(if (chosen) 3.dp else 2.dp, Memphis.Ink)
                                    .clickable { selectedColor = colour },
                                contentAlignment = Alignment.Center,
                            ) {
                                if (chosen) {
                                    Text("×", style = posterStyle(15f))
                                }
                            }
                        }
                    }

                    FieldLabel("Icon")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        icons.forEach { icon ->
                            val chosen = selectedIcon == icon
                            Box(
                                modifier = Modifier
                                    .size(38.dp)
                                    .background(if (chosen) Memphis.Ink else Memphis.Paper)
                                    .border(2.5.dp, Memphis.Ink)
                                    .clickable { selectedIcon = icon },
                                contentAlignment = Alignment.Center,
                            ) {
                                Icon(
                                    imageVector = getProjectIcon(icon),
                                    contentDescription = null,
                                    tint = if (chosen) Memphis.Paper else Memphis.Ink,
                                    modifier = Modifier.size(20.dp),
                                )
                            }
                        }
                    }

                    Spacer(Modifier.height(14.dp))
                }

                Box(Modifier.fillMaxWidth().height(3.dp).background(Memphis.Ink))
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    MemphisSurface(
                        modifier = Modifier.weight(1f).clickable { onDismiss() },
                        color = Memphis.Paper,
                        borderWidth = 2.5.dp,
                        shadowOffset = 4.dp,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("CANCEL", style = captionStyle(9.5f))
                        }
                    }
                    val ready = title.isNotBlank()
                    MemphisSurface(
                        modifier = Modifier
                            .weight(1f)
                            .then(
                                if (ready) {
                                    Modifier.clickable {
                                        onSave(title, description, promptOverlay, selectedColor, selectedIcon)
                                    }
                                } else {
                                    Modifier
                                }
                            ),
                        color = if (ready) Memphis.Ink else Memphis.Paper,
                        borderWidth = 2.5.dp,
                        shadowOffset = 4.dp,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = if (project == null) "CREATE" else "SAVE",
                                style = captionStyle(
                                    9.5f,
                                    if (ready) Memphis.Paper else Memphis.Ink.copy(alpha = 0.4f),
                                ),
                            )
                        }
                    }
                }
            }
        }
    }
}

/** A small printed label above a field. */
@Composable
private fun FieldLabel(text: String) {
    Text(
        text = text.uppercase(),
        style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
        modifier = Modifier.padding(top = 12.dp, bottom = 5.dp),
    )
}

/** A field the owner types into, in the same surface as every other object. */
@Composable
private fun SheetField(
    value: String,
    placeholder: String,
    onChange: (String) -> Unit,
    minHeight: androidx.compose.ui.unit.Dp = 44.dp,
) {
    MemphisSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 3.dp,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = minHeight)
                .padding(11.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    placeholder,
                    style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.4f)).copy(
                        fontFamily = MemphisBody,
                        letterSpacing = androidx.compose.ui.unit.TextUnit.Unspecified,
                    ),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = androidx.compose.ui.text.TextStyle(
                    fontFamily = MemphisBody,
                    fontSize = 12.5.sp,
                    lineHeight = 17.sp,
                    color = Memphis.Ink,
                ),
                cursorBrush = SolidColor(Memphis.Ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
