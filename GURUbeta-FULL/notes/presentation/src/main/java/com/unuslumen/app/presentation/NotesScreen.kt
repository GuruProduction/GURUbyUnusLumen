@file:OptIn(ExperimentalLayoutApi::class)

package com.unuslumen.app.presentation

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.staggeredgrid.LazyVerticalStaggeredGrid
import androidx.compose.foundation.lazy.staggeredgrid.StaggeredGridCells
import androidx.compose.foundation.lazy.staggeredgrid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.PrimaryTabRow
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.NoteFolder
import com.unuslumen.app.preferences.domain.model.Order
import com.unuslumen.app.preferences.domain.model.OrderType
import com.unuslumen.app.ui.ItemView
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.LiquidFloatingActionButton
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.components.notes.NoteCard
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.snackbar.LocalisedSnackbarHost
import com.unuslumen.app.ui.titleRes
import io.github.fletchmckee.liquid.liquefiable
import io.github.fletchmckee.liquid.rememberLiquidState
import org.koin.androidx.compose.koinViewModel

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
    val liquidState = rememberLiquidState()
    Scaffold(
        snackbarHost = {
            LocalisedSnackbarHost(uiState.snackbarHostState)
        },
        topBar = {
            guruAppBar(
                if (selectedTab == 0) stringResource(R.string.notes) else stringResource(
                    R.string.folders
                )
            )
        },
        floatingActionButton = {
            LiquidFloatingActionButton(
                onClick = {
                    if (selectedTab == 0) {
                        navController.navigate(Screen.NoteDetailsScreen())
                    } else {
                        openCreateFolderDialog = true
                    }
                },
                iconPainter = if (selectedTab == 0) painterResource(R.drawable.ic_add) else painterResource(
                    R.drawable.ic_create_folder
                ),
                contentDescription = stringResource(R.string.add_note),
                liquidState = liquidState
            )

        },
    ) { paddingValues ->
        Column(modifier = Modifier.liquefiable(liquidState).padding(paddingValues).fillMaxSize()) {
            PrimaryTabRow(
                selectedTabIndex = selectedTab,
                containerColor = MaterialTheme.colorScheme.background,
            ) {
                Tab(
                    text = {
                        Text(
                            stringResource(R.string.notes),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    selected = selectedTab == 0,
                    onClick = {
                        selectedTab = 0
                    },
                    unselectedContentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
                Tab(
                    text = {
                        Text(
                            stringResource(R.string.folders),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    },
                    selected = selectedTab == 1,
                    onClick = {
                        selectedTab = 1
                    },
                    unselectedContentColor = MaterialTheme.colorScheme.onBackground.copy(alpha = 0.5f)
                )
            }
            if (selectedTab == 0) {
                if (uiState.notes.isEmpty())
                    NoNotesMessage()
                Row(
                    Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    IconButton(onClick = { orderSettingsVisible = !orderSettingsVisible }) {
                        Icon(
                            modifier = Modifier.size(25.dp),
                            painter = painterResource(R.drawable.ic_settings_sliders),
                            contentDescription = stringResource(R.string.order_by)
                        )
                    }
                    IconButton(onClick = {
                        navController.navigate(Screen.NoteSearchScreen)
                    }) {
                        Icon(
                            modifier = Modifier.size(25.dp),
                            painter = painterResource(id = R.drawable.ic_search),
                            contentDescription = stringResource(R.string.search)
                        )
                    }
                }
                AnimatedVisibility(visible = orderSettingsVisible) {
                    NotesSettingsSection(
                        uiState.notesOrder,
                        uiState.noteView,
                        uiState.showAllNotes,
                        onOrderChange = {
                            viewModel.onEvent(NoteEvent.UpdateOrder(it))
                        },
                        onViewChange = {
                            viewModel.onEvent(NoteEvent.UpdateView(it))
                        },
                        onShowAllNotesChange = {
                            viewModel.onEvent(NoteEvent.ShowAllNotes(it))
                        }
                    )
                }
                if (uiState.noteView == ItemView.LIST) {
                    LazyColumn(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = PaddingValues(
                            top = 12.dp,
                            bottom = 24.dp,
                            start = 12.dp,
                            end = 12.dp
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
                                modifier = Modifier.animateItem()
                            )
                        }
                    }
                } else {
                    LazyVerticalStaggeredGrid(
                        columns = StaggeredGridCells.Adaptive(150.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        contentPadding = PaddingValues(12.dp),
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
                                    modifier = Modifier.padding(bottom = 12.dp)
                                )
                            }
                        }
                    }
                }
            } else {
                FoldersTab(uiState.folders) {
                    navController.navigate(
                        Screen.NoteFolderDetailsScreen(
                            folderId = it.id
                        )
                    )
                }
                if (openCreateFolderDialog)
                    CreateFolderDialog(
                        onCreate = {
                            viewModel.onEvent(
                                NoteEvent.CreateFolder(name = it.trim())
                            )
                            openCreateFolderDialog = false
                        },
                        onDismiss = {
                            openCreateFolderDialog = false
                        }
                    )
            }
        }
    }
}

@Composable
fun FoldersTab(
    folders: List<NoteFolder>,
    onItemClick: (NoteFolder) -> Unit
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(
            top = 12.dp,
            bottom = 24.dp,
            start = 12.dp,
            end = 12.dp
        )
    ) {
        items(folders) { folder ->
            Card(
                modifier = Modifier.height(180.dp),
                shape = RoundedCornerShape(20.dp),
                elevation = CardDefaults.elevatedCardElevation(
                    8.dp
                )
            ) {
                Column(
                    Modifier
                        .fillMaxSize()
                        .clickable { onItemClick(folder) },
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_folder),
                        contentDescription = folder.name,
                        modifier = Modifier.size(100.dp)
                    )
                    Text(
                        text = folder.name,
                        style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

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
        Modifier.background(color = MaterialTheme.colorScheme.background)
    ) {
        Text(
            text = stringResource(R.string.order_by),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp)
        )
        FlowRow(
            modifier = Modifier.padding(end = 8.dp)
        ) {
            orders.forEach {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = order::class == it::class,
                        onClick = {
                            if (order != it)
                                onOrderChange(
                                    it.copyOrder(orderType = order.orderType)
                                )
                        }
                    )
                    Text(
                        text = stringResource(it.titleRes),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
        HorizontalDivider()
        FlowRow {
            orderTypes.forEach {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = order.orderType == it,
                        onClick = {
                            if (order != it)
                                onOrderChange(
                                    order.copyOrder(it)
                                )
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(it.titleRes),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
        HorizontalDivider()
        Text(
            text = stringResource(R.string.view_as),
            style = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.padding(start = 8.dp, top = 8.dp)
        )
        FlowRow {
            noteViews.forEach {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    RadioButton(
                        selected = view.title == it.title,
                        onClick = {
                            if (view.title != it.title)
                                onViewChange(
                                    it
                                )
                        }
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(it.title),
                        style = MaterialTheme.typography.bodyLarge
                    )
                }
            }
        }
        HorizontalDivider()
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = showAllNotes, onCheckedChange = { onShowAllNotesChange(it) })
            Text(
                text = stringResource(R.string.show_all_notes),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(start = 8.dp)
            )
        }
        Spacer(Modifier.height(8.dp))
    }
}

@Composable
fun NoNotesMessage() {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = stringResource(R.string.no_notes_message),
            style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.Bold),
            color = Color.Gray,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(12.dp))
        Image(
            modifier = Modifier.size(125.dp),
            painter = painterResource(id = R.drawable.notes_img),
            contentDescription = stringResource(R.string.no_notes_message),
            alpha = 0.7f
        )
    }
}

@Suppress("AssignedValueIsNeverRead")
@Composable
fun CreateFolderDialog(
    onCreate: (String) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = { onDismiss() },
        title = {
            Text(
                text = stringResource(id = R.string.create_folder),
                style = MaterialTheme.typography.titleLarge
            )
        },
        text = {
            TextField(
                value = name,
                onValueChange = { name = it },
                label = {
                    Text(
                        text = stringResource(id = R.string.name),
                        style = MaterialTheme.typography.bodyLarge
                    )
                },
            )
        },
        confirmButton = {
            Button(
                shape = RoundedCornerShape(25.dp),
                onClick = {
                    onCreate(name)
                },
            ) {
                Text(stringResource(R.string.create_folder), color = Color.White)
            }
        },
        dismissButton = {
            TextButton(
                shape = RoundedCornerShape(25.dp),
                onClick = { onDismiss() },
            ) {
                Text(stringResource(R.string.cancel), color = Color.White)
            }
        }
    )
}
