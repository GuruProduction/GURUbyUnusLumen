// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
@file:Suppress("AssignedValueIsNeverRead")

package com.unuslumen.app.presentation

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.ui.ItemView
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.components.notes.NoteCard
import com.unuslumen.app.ui.navigation.Screen
import com.unuslumen.app.ui.snackbar.LocalisedSnackbarHost
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBodyText
import com.unuslumen.app.ui.theme.MemphisEmpty
import com.unuslumen.app.ui.theme.MemphisField
import com.unuslumen.app.ui.theme.MemphisPill
import com.unuslumen.app.ui.theme.MemphisSheet
import com.unuslumen.app.ui.theme.MemphisSheetButton
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * One folder's notes, in the house language.
 *
 * WHAT IT WAS. A Material page: two icon buttons in the app bar, a liquid floating
 * button hovering over the last card, and two rounded dialogs in pure red and white.
 *
 * WHAT IT IS NOW. Printed blocks for delete and rename on the masthead, the add door as
 * the first row of the list rather than a button over it, and both dialogs on the shared
 * printed sheet.
 *
 * Every view model call, navigation target and string resource is unchanged.
 */
@Composable
fun NoteFolderDetailsScreen(
    navController: NavHostController,
    id: String,
    viewModel: NoteFolderDetailsViewModel = koinViewModel(parameters = { parametersOf(id) })
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val folder = uiState.folder
    val snackbarHostState = uiState.snackbarHostState

    var openDeleteDialog by remember { mutableStateOf(false) }
    var openEditDialog by remember { mutableStateOf(false) }

    LaunchedEffect(uiState.navigateUp) {
        if (uiState.navigateUp) {
            navController.navigateUp()
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { LocalisedSnackbarHost(snackbarHostState) },
        topBar = {
            guruAppBar(
                title = folder?.name ?: "Folder",
                stamp = if (uiState.folderNotes.isEmpty()) null else "${uiState.folderNotes.size} notes",
                actions = {
                    MemphisPill(
                        label = "Rename",
                        selected = false,
                        onClick = { openEditDialog = true },
                    )
                    Spacer(Modifier.width(6.dp))
                    MemphisPill(
                        label = "Delete",
                        selected = false,
                        ink = Memphis.Coral,
                        onClick = { openDeleteDialog = true },
                    )
                }
            )
        },
    ) { contentPadding ->
        Column(
            Modifier.fillMaxSize().padding(contentPadding)
        ) {
            if (uiState.noteView == ItemView.LIST) {
                LazyColumn(
                    verticalArrangement = Arrangement.spacedBy(9.dp),
                    contentPadding = PaddingValues(
                        top = 12.dp,
                        bottom = 28.dp,
                        start = 18.dp,
                        end = 18.dp
                    ),
                ) {
                    item(key = "add") {
                        com.unuslumen.app.ui.theme.MemphisBarButton(
                            label = "+ New note",
                            onClick = {
                                navController.navigate(Screen.NoteDetailsScreen(folderId = id))
                            },
                        )
                    }
                    items(uiState.folderNotes, key = { it.id }) { note ->
                        NoteCard(
                            note = note,
                            onClick = {
                                navController.navigate(
                                    Screen.NoteDetailsScreen(
                                        noteId = note.id,
                                        folderId = id
                                    )
                                )
                            }
                        )
                    }
                    if (uiState.folderNotes.isEmpty()) {
                        item(key = "empty") {
                            MemphisEmpty(
                                title = "Nothing in here yet",
                                line = "Notes you put in this folder land here.",
                                modifier = Modifier.fillMaxWidth().height(240.dp),
                            )
                        }
                    }
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(150.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    contentPadding = PaddingValues(
                        top = 12.dp,
                        bottom = 28.dp,
                        start = 18.dp,
                        end = 18.dp
                    ),
                ) {
                    item(span = { androidx.compose.foundation.lazy.grid.GridItemSpan(maxLineSpan) }) {
                        com.unuslumen.app.ui.theme.MemphisBarButton(
                            label = "+ New note",
                            onClick = {
                                navController.navigate(Screen.NoteDetailsScreen(folderId = id))
                            },
                        )
                    }
                    items(uiState.folderNotes) { note ->
                        key(note.id) {
                            NoteCard(
                                note = note,
                                onClick = {
                                    navController.navigate(
                                        Screen.NoteDetailsScreen(
                                            noteId = note.id,
                                            folderId = id
                                        )
                                    )
                                },
                                modifier = Modifier
                                    .animateItem()
                                    .height(220.dp)
                            )
                        }
                    }
                }
            }
        }

        if (openDeleteDialog) {
            MemphisSheet(
                title = stringResource(R.string.delete_note_confirmation_title),
                subtitle = stringResource(R.string.delete_folder_confirmation_message),
                onDismiss = { openDeleteDialog = false },
                accent = Memphis.Coral,
                footer = {
                    MemphisSheetButton(
                        label = stringResource(R.string.cancel),
                        onClick = { openDeleteDialog = false },
                    )
                    MemphisSheetButton(
                        label = stringResource(R.string.delete_folder),
                        filled = true,
                        ink = Memphis.Coral,
                        onClick = {
                            viewModel.deleteCurrentFolder()
                            openDeleteDialog = false
                        },
                    )
                },
                body = {
                    Text(
                        text = "This cannot be undone.",
                        style = MemphisBodyText,
                        modifier = Modifier.padding(vertical = 14.dp),
                    )
                },
            )
        }

        if (openEditDialog) {
            var folderName by remember { mutableStateOf(folder?.name ?: "") }
            MemphisSheet(
                title = stringResource(id = R.string.edit_folder),
                subtitle = "What this folder is called",
                onDismiss = { openEditDialog = false },
                footer = {
                    MemphisSheetButton(
                        label = stringResource(R.string.cancel),
                        onClick = { openEditDialog = false },
                    )
                    MemphisSheetButton(
                        label = stringResource(R.string.save),
                        filled = true,
                        onClick = {
                            viewModel.updateCurrentFolderName(folderName)
                            openEditDialog = false
                        },
                    )
                },
                body = {
                    MemphisField(
                        label = stringResource(id = R.string.name),
                        value = folderName,
                        onChange = { folderName = it },
                        placeholder = "A name you will recognise",
                        singleLine = true,
                    )
                    Spacer(Modifier.height(14.dp))
                },
            )
        }
    }
}
