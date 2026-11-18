// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
@file:OptIn(ExperimentalLayoutApi::class)

package com.unuslumen.app.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.NoteFolder
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import com.unuslumen.app.ui.ItemView
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.components.notes.NoteCard
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.snackbar.LocalisedSnackbarHost
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import com.unuslumen.app.ui.titleRes
import org.koin.androidx.compose.koinViewModel

/**
 * The notes room, in the house language.
 *
 * WHAT IT WAS. A Material page: a small tab strip, sliders and search icons floating
 * above the list, a liquid floating button hovering over the last card, and rounded
 * elevated cards for each note. Beside the lobby it read as a different application.
 *
 * WHAT IT IS NOW. The same page every other room wears. The two tabs are two printed
 * blocks that fill with ink when chosen; the controls are printed buttons on the page
 * rather than floating glyphs; the folders are ink-outlined cards on the rotation; the
 * order panel selects by pressing a block rather than a Material radio button.
 *
 * Every event, every navigation call and every string resource is unchanged: this is a
 * skin, and the behaviour underneath it is the same code it was.
 */
@Suppress("AssignedValueIsNeverRead")
@Composable
fun NotesScreen(
    navController: NavHostController,
    viewModel: NotesViewModel = koinViewModel()
) {
    val uiState by viewModel.notesUiState.collectAsStateWithLifecycle()
    var orderSettingsVisible by remember { mutableStateOf(false) }
    var selectedTab by rememberSaveable { mutableIntStateOf(0) }
    var openCreateFolderDialog by remember { mutableStateOf(false) }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = {
            LocalisedSnackbarHost(uiState.snackbarHostState)
        },
        topBar = {
            guruAppBar(
                title = if (selectedTab == 0) {
                    stringResource(R.string.notes)
                } else {
                    stringResource(R.string.folders)
                },
                stamp = if (selectedTab == 0 && uiState.notes.isNotEmpty()) {
                    "${uiState.notes.size} notes"
                } else if (selectedTab == 1 && uiState.folders.isNotEmpty()) {
                    "${uiState.folders.size} folders"
                } else {
                    null
                },
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .padding(paddingValues)
                .fillMaxSize()
        ) {
            NotesTabs(
                selectedTab = selectedTab,
                onSelect = { selectedTab = it },
            )

            if (selectedTab == 0) {
                NotesControlRow(
                    onToggleOrder = { orderSettingsVisible = !orderSettingsVisible },
                    onSearch = { navController.navigate(Screen.NoteSearchScreen) },
                    onAdd = { navController.navigate(Screen.NoteDetailsScreen()) },
                )

                AnimatedVisibility(visible = orderSettingsVisible) {
                    NotesSettingsSection(
                        order = uiState.notesOrder,
                        view = uiState.noteView,
                        showAllNotes = uiState.showAllNotes,
                        onOrderChange = { viewModel.onEvent(NoteEvent.UpdateOrder(it)) },
                        onViewChange = { viewModel.onEvent(NoteEvent.UpdateView(it)) },
                        onShowAllNotesChange = { viewModel.onEvent(NoteEvent.ShowAllNotes(it)) },
                    )
                }

                if (uiState.notes.isEmpty()) {
                    NoNotesMessage()
                } else if (uiState.noteView == ItemView.LIST) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(9.dp),
                        contentPadding = PaddingValues(
                            top = 12.dp,
                            bottom = 28.dp,
                            start = 18.dp,
                            end = 18.dp,
                        ),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(uiState.notes, key = { it.id }) { note ->
                            NoteCard(
                                note = note,
                                onClick = {
                                    navController.navigate(
                                        Screen.NoteDetailsScreen(
                                            noteId = note.id,
                                            folderId = note.folderId
                                        )
                                    )
                                },
                                modifier = Modifier.animateItem(),
                            )
                        }
                    }
                } else {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(150.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(18.dp),
                        modifier = Modifier.weight(1f)
                    ) {
                        items(uiState.notes) { note ->
                            key(note.id) {
                                NoteCard(
                                    note = note,
                                    onClick = {
                                        navController.navigate(
                                            Screen.NoteDetailsScreen(
                                                noteId = note.id,
                                                folderId = note.folderId
                                            )
                                        )
                                    },
                                    modifier = Modifier.padding(bottom = 10.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                NotesControlRow(
                    onToggleOrder = { orderSettingsVisible = !orderSettingsVisible },
                    onSearch = null,
                    onAdd = { openCreateFolderDialog = true },
                    addLabel = "New folder",
                )

                FoldersTab(uiState.folders) {
                    navController.navigate(
                        Screen.NoteFolderDetailsScreen(folderId = it.id)
                    )
                }

                if (openCreateFolderDialog) {
                    CreateFolderDialog(
                        onCreate = {
                            viewModel.onEvent(NoteEvent.CreateFolder(name = it.trim()))
                            openCreateFolderDialog = false
                        },
                        onDismiss = { openCreateFolderDialog = false },
                    )
                }
            }
        }
    }
}

/**
 * The two tabs, drawn as flat printed blocks.
 *
 * The chosen one fills with ink and carries paper type, so which half of the room is
 * open reads before a single word does. That is the same signal the lobby's selected
 * chip gives, and it means the tab strip is part of the page rather than a Material
 * strip laid across it.
 */
@Composable
private fun NotesTabs(selectedTab: Int, onSelect: (Int) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 2.dp, bottom = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(9.dp),
    ) {
        listOf(stringResource(R.string.notes), stringResource(R.string.folders))
            .forEachIndexed { index, label ->
                val on = selectedTab == index
                Box(
                    Modifier
                        .weight(1f)
                        .background(if (on) Memphis.Ink else Memphis.Paper)
                        .clickable { onSelect(index) }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        text = label.uppercase(),
                        style = captionStyle(9.5f, if (on) Memphis.Paper else Memphis.Ink),
                    )
                }
            }
    }
}

/**
 * The row of page controls: order, search and add.
 *
 * These were icon buttons in the app bar area, which is where a Material app puts
 * them and where they read as chrome. On a printed page they are blocks, labelled in
 * the caption voice, sitting on the page with everything else.
 */
@Composable
private fun NotesControlRow(
    onToggleOrder: () -> Unit,
    onSearch: (() -> Unit)?,
    onAdd: () -> Unit,
    addLabel: String = "New note",
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp)
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        PageButton(label = "Order", onClick = onToggleOrder)
        if (onSearch != null) {
            PageButton(label = "Search", onClick = onSearch)
        }
        PageButton(label = addLabel, onClick = onAdd, filled = true)
    }
}

/** One control on the page, as a printed block. */
@Composable
private fun PageButton(
    label: String,
    onClick: () -> Unit,
    filled: Boolean = false,
) {
    MemphisSurface(
        modifier = Modifier.clickable { onClick() },
        color = if (filled) Memphis.Ink else Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 3.dp,
    ) {
        Box(
            Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.uppercase(),
                style = captionStyle(9f, if (filled) Memphis.Paper else Memphis.Ink),
                maxLines = 1,
            )
        }
    }
}

/** The folders, as ink-outlined cards on the rotation. */
@Composable
fun FoldersTab(
    folders: List<NoteFolder>,
    onItemClick: (NoteFolder) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        verticalArrangement = Arrangement.spacedBy(10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        contentPadding = PaddingValues(top = 12.dp, bottom = 28.dp, start = 18.dp, end = 18.dp),
    ) {
        itemsIndexed(folders, key = { _, folder -> folder.id }) { index, folder ->
            MemphisSurface(
                modifier = Modifier
                    .height(160.dp)
                    .clickable { onItemClick(folder) },
                color = Memphis.inkFor(index),
                borderWidth = 2.5.dp,
                shadowOffset = 4.dp,
            ) {
                Column(
                    Modifier.fillMaxSize().padding(10.dp),
                    verticalArrangement = Arrangement.SpaceBetween,
                ) {
                    Image(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = folder.name,
                        modifier = Modifier.size(34.dp),
                        alpha = 0.85f,
                    )
                    Text(
                        text = folder.name.uppercase(),
                        style = posterStyle(12f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }
        }
    }
}

/**
 * The order and view controls, on printed blocks.
 *
 * Every option does exactly what the Material radio button did: choosing a block calls
 * the same callback with the same value. Nothing about the panel's behaviour changed,
 * only the control that represents it.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun NotesSettingsSection(
    order: Order,
    view: ItemView,
    showAllNotes: Boolean,
    onOrderChange: (Order) -> Unit,
    onViewChange: (ItemView) -> Unit,
    onShowAllNotesChange: (Boolean) -> Unit
) {
    val orders = remember {
        listOf(
            Order.DateModified(),
            Order.DateCreated(),
            Order.Alphabetical()
        )
    }
    val orderTypes = remember {
        listOf(
            OrderType.ASC,
            OrderType.DESC
        )
    }
    val noteViews = remember {
        listOf(
            ItemView.LIST,
            ItemView.GRID
        )
    }

    Column(
        Modifier
            .fillMaxWidth()
            .padding(start = 18.dp, end = 18.dp, top = 10.dp, bottom = 10.dp)
    ) {
        Text(
            text = stringResource(R.string.order_by).uppercase(),
            style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            orders.forEach { option ->
                PageButton(
                    label = stringResource(option.titleRes),
                    onClick = {
                        if (order != option) {
                            onOrderChange(option.copyOrder(orderType = order.orderType))
                        }
                    },
                    filled = order::class == option::class,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            orderTypes.forEach { option ->
                PageButton(
                    label = stringResource(option.titleRes),
                    onClick = {
                        if (order.orderType != option) {
                            onOrderChange(order.copyOrder(option))
                        }
                    },
                    filled = order.orderType == option,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            text = stringResource(R.string.view_as).uppercase(),
            style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
        )
        Spacer(Modifier.height(6.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            noteViews.forEach { option ->
                PageButton(
                    label = stringResource(option.title),
                    onClick = {
                        if (view.title != option.title) {
                            onViewChange(option)
                        }
                    },
                    filled = view.title == option.title,
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            FlatBox(checked = showAllNotes, onChange = { onShowAllNotesChange(it) })
            Spacer(Modifier.width(9.dp))
            Text(
                text = stringResource(R.string.show_all_notes).uppercase(),
                style = captionStyle(9f),
            )
        }
        Spacer(Modifier.height(4.dp))
    }
}

/** A checkbox in the page's grammar: a square that fills with ink. */
@Composable
private fun FlatBox(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(22.dp)
            .background(if (checked) Memphis.Ink else Memphis.Paper)
            .clickable { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("×", style = posterStyle(13f, Memphis.Paper))
        }
    }
}

@Composable
fun NoNotesMessage() {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.no_notes_message).uppercase(),
            style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)),
        )
        Spacer(Modifier.height(12.dp))
        Image(
            modifier = Modifier.size(125.dp),
            painter = painterResource(id = R.drawable.notes_img),
            contentDescription = stringResource(R.string.no_notes_message),
            alpha = 0.5f
        )
    }
}

/**
 * Name a new folder, on a printed sheet rather than a Material dialog.
 *
 * The shape is the same as the lobby's add-a-room sheet, because both do the same job
 * and a second dialog grammar would be a second product.
 */
@Suppress("AssignedValueIsNeverRead")
@Composable
fun CreateFolderDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }

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
                .padding(horizontal = 18.dp)
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
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    Text(
                        text = stringResource(id = R.string.create_folder).uppercase(),
                        style = posterStyle(26f),
                    )
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(3.dp)
                            .background(Memphis.Ink)
                    )
                }

                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 4.dp)) {
                    Text(
                        text = stringResource(id = R.string.name).uppercase(),
                        style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
                    )
                    Spacer(Modifier.height(7.dp))
                    MemphisSurface(
                        modifier = Modifier.fillMaxWidth(),
                        color = Memphis.Paper,
                        borderWidth = 2.5.dp,
                        shadowOffset = 3.dp,
                    ) {
                        Box(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 12.dp)) {
                            if (name.isEmpty()) {
                                Text(
                                    text = "A name you will recognise",
                                    style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.35f)),
                                )
                            }
                            BasicTextField(
                                value = name,
                                onValueChange = { typed -> name = typed.replace("\n", "") },
                                singleLine = true,
                                textStyle = posterStyle(18f),
                                cursorBrush = SolidColor(Memphis.Ink),
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }

                Box(Modifier.fillMaxWidth().padding(top = 14.dp).height(3.dp).background(Memphis.Ink))
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
                            Text(
                                text = stringResource(R.string.cancel).uppercase(),
                                style = captionStyle(9.5f),
                            )
                        }
                    }
                    val ready = name.trim().isNotEmpty()
                    MemphisSurface(
                        modifier = Modifier
                            .weight(1f)
                            .then(if (ready) Modifier.clickable { onCreate(name) } else Modifier),
                        color = if (ready) Memphis.Ink else Memphis.Paper,
                        borderWidth = 2.5.dp,
                        shadowOffset = 4.dp,
                    ) {
                        Box(
                            Modifier.fillMaxWidth().padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = stringResource(R.string.create_folder).uppercase(),
                                style = captionStyle(9.5f, if (ready) Memphis.Paper else Memphis.Ink.copy(alpha = 0.4f)),
                            )
                        }
                    }
                }
            }
        }
    }
}
