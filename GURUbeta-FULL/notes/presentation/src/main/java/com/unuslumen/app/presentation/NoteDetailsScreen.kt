// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
@file:OptIn(ExperimentalLayoutApi::class)

package com.unuslumen.app.presentation

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.presentation.components.AiResultSheet
import com.unuslumen.app.presentation.components.ShareNoteAsPlainTextOption
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.defaultMarkdownTypography
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.components.common.withHardLineBreaks
import com.unuslumen.app.ui.snackbar.LocalisedSnackbarHost
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBodyText
import com.unuslumen.app.ui.theme.MemphisPill
import com.unuslumen.app.ui.theme.MemphisSheet
import com.unuslumen.app.ui.theme.MemphisSheetButton
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import com.unuslumen.app.util.date.formatDateDependingOnDay
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import io.github.fletchmckee.liquid.rememberLiquidState
import kotlinx.coroutines.delay
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf

/**
 * One note, in the house language.
 *
 * WHAT IT WAS. A Material editor: two outlined fields with rounded corners and floating
 * labels, a row of gradient chips for the AI actions, a rounded delete dialog in pure red,
 * and a folder picker made of grey pill outlines. It was the furthest room from the lobby.
 *
 * WHAT IT IS NOW. Printed fields, a row of coloured action blocks, the folder choice as
 * printed cells that fill with colour, and both dialogs on the same sheet every other
 * form uses. The masthead carries the same stamp-and-door shape as every other room.
 *
 * Every event, every view model call and every string resource is unchanged.
 */
@Composable
fun NoteDetailsScreen(
    navController: NavHostController,
    noteId: String?,
    folderId: String?,
    viewModel: NoteDetailsViewModel = koinViewModel(
        parameters = { parametersOf(noteId.orEmpty(), folderId.orEmpty()) }
    ),
) {
    val state by viewModel.noteUiState.collectAsStateWithLifecycle()
    var openDeleteDialog by rememberSaveable { mutableStateOf(false) }
    var openFolderDialog by rememberSaveable { mutableStateOf(false) }
    var showShareMenu by rememberSaveable { mutableStateOf(false) }

    val context = LocalContext.current
    val keyboardController = LocalSoftwareKeyboardController.current

    val title = viewModel.title
    val content = viewModel.content
    val pinned = state.pinned
    val readingMode = state.readingMode
    val folder = state.folder
    val lastModified by remember(state.note?.updatedDate) {
        derivedStateOf {
            state.note?.updatedDate?.formatDateDependingOnDay(context) ?: ""
        }
    }
    var wordCountString by remember { mutableStateOf("") }
    val aiEnabled by viewModel.aiEnabled.collectAsStateWithLifecycle()
    val aiState = viewModel.aiState
    val showAiSheet = aiState.showAiSheet

    LaunchedEffect(content) {
        delay(500)
        wordCountString = content.countWords().toString()
    }
    LaunchedEffect(state.navigateUp) {
        if (state.navigateUp) {
            openDeleteDialog = false
            navController.navigateUp()
        }
    }
    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            viewModel.onEvent(NoteDetailsEvent.ScreenOnStop)
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = if (state.note == null) "New note" else "Note",
                stamp = lastModified.ifBlank { null },
                actions = {
                    // The actions live on the masthead as printed blocks rather than as
                    // five icon buttons in a Material app bar, because a row of grey
                    // glyphs is the loudest remaining tell that this was a Material page.
                    if (folder != null) {
                        MemphisPill(
                            label = folder.name,
                            selected = false,
                            onClick = { openFolderDialog = true },
                        )
                    } else {
                        MemphisPill(
                            label = "Folder",
                            selected = false,
                            onClick = { openFolderDialog = true },
                        )
                    }
                    Spacer(Modifier.width(6.dp))
                    MemphisPill(
                        label = if (pinned) "Pinned" else "Pin",
                        selected = pinned,
                        ink = Memphis.Sun,
                        onClick = { viewModel.onEvent(NoteDetailsEvent.UpdatePinned(!pinned)) },
                    )
                    Spacer(Modifier.width(6.dp))
                    MemphisPill(
                        label = if (readingMode) "Reading" else "Edit",
                        selected = readingMode,
                        ink = Memphis.Teal,
                        onClick = { viewModel.onEvent(NoteDetailsEvent.ToggleReadingMode) },
                    )
                },
            )
        },
        snackbarHost = {
            LocalisedSnackbarHost(state.snackbarHostState)
        },
    ) { paddingValues ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(horizontal = 18.dp)
                .padding(paddingValues)
                .imePadding()
                .verticalScroll(rememberScrollState())
        ) {
            // The title, as a printed field with no floating label.
            Text(
                text = stringResource(R.string.title).uppercase(),
                style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
                modifier = Modifier.padding(bottom = 5.dp),
            )
            MemphisSurface(
                modifier = Modifier.fillMaxWidth(),
                color = Memphis.Paper,
                borderWidth = 2.5.dp,
                shadowOffset = 3.dp,
            ) {
                Box(Modifier.fillMaxWidth().padding(11.dp)) {
                    if (title.isEmpty()) {
                        Text(
                            "A name for this note",
                            style = MemphisBodyText.copy(color = Memphis.Ink.copy(alpha = 0.4f)),
                        )
                    }
                    BasicTextField(
                        value = title,
                        onValueChange = { viewModel.onEvent(NoteDetailsEvent.UpdateTitle(it)) },
                        textStyle = posterStyle(16f),
                        cursorBrush = SolidColor(Memphis.Ink),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }

            if (aiEnabled) {
                LazyRow(
                    Modifier
                        .fillMaxWidth()
                        .padding(vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(7.dp)
                ) {
                    item {
                        MemphisSurface(
                            modifier = Modifier.clickable {
                                viewModel.onEvent(NoteDetailsEvent.Summarize(content))
                                keyboardController?.hide()
                            },
                            color = Memphis.Yellow,
                            borderWidth = 2.5.dp,
                            shadowOffset = 3.dp,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_summarize),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = Memphis.Ink,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(id = R.string.summarize).uppercase(), style = captionStyle(8.5f))
                            }
                        }
                    }
                    item {
                        MemphisSurface(
                            modifier = Modifier.clickable {
                                viewModel.onEvent(NoteDetailsEvent.AutoFormat(content))
                                keyboardController?.hide()
                            },
                            color = Memphis.Cyan,
                            borderWidth = 2.5.dp,
                            shadowOffset = 3.dp,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_auto_format),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = Memphis.Ink,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(id = R.string.auto_format).uppercase(), style = captionStyle(8.5f))
                            }
                        }
                    }
                    item {
                        MemphisSurface(
                            modifier = Modifier.clickable {
                                viewModel.onEvent(NoteDetailsEvent.CorrectSpelling(content))
                                keyboardController?.hide()
                            },
                            color = Memphis.Lilac,
                            borderWidth = 2.5.dp,
                            shadowOffset = 3.dp,
                        ) {
                            Row(
                                Modifier.padding(horizontal = 10.dp, vertical = 8.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    painter = painterResource(id = R.drawable.ic_spelling),
                                    contentDescription = null,
                                    modifier = Modifier.size(15.dp),
                                    tint = Memphis.Ink,
                                )
                                Spacer(Modifier.width(6.dp))
                                Text(stringResource(id = R.string.correct_spelling).uppercase(), style = captionStyle(8.5f))
                            }
                        }
                    }
                }
            }

            if (readingMode) {
                MemphisSurface(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    color = Memphis.Paper,
                    borderWidth = 2.5.dp,
                    shadowOffset = 3.dp,
                ) {
                    Markdown(
                        content = content.withHardLineBreaks(),
                        modifier = Modifier.fillMaxWidth().padding(12.dp),
                        imageTransformer = Coil2ImageTransformerImpl,
                        typography = defaultMarkdownTypography()
                    )
                }
            } else {
                Text(
                    text = stringResource(R.string.note_content).uppercase(),
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
                    modifier = Modifier.padding(top = 6.dp, bottom = 5.dp),
                )
                MemphisSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Memphis.Paper,
                    borderWidth = 2.5.dp,
                    shadowOffset = 3.dp,
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .heightIn(min = 220.dp)
                            .padding(11.dp),
                    ) {
                        if (content.isEmpty()) {
                            Text(
                                "Write it here.",
                                style = MemphisBodyText.copy(color = Memphis.Ink.copy(alpha = 0.4f)),
                            )
                        }
                        BasicTextField(
                            value = content,
                            onValueChange = { viewModel.onEvent(NoteDetailsEvent.UpdateContent(it)) },
                            textStyle = MemphisBodyText,
                            cursorBrush = SolidColor(Memphis.Ink),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = lastModified.uppercase(),
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.5f))
                )
                Text(
                    text = "$wordCountString WORDS",
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.5f))
                )
            }
        }

        AnimatedVisibility(
            visible = showAiSheet,
            enter = slideInVertically(
                initialOffsetY = { it }, animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessVeryLow
                )
            ),
            exit = slideOutVertically(targetOffsetY = { it }, animationSpec = tween(700))
        ) {
            val interactionSource = remember { MutableInteractionSource() }
            Box(
                Modifier
                    .fillMaxSize()
                    .clickable(
                        interactionSource = interactionSource,
                        indication = null
                    ) {
                        viewModel.onEvent(NoteDetailsEvent.AiResultHandled)
                    },
                contentAlignment = Alignment.BottomCenter
            ) {
                AiResultSheet(
                    loading = aiState.loading,
                    result = aiState.result,
                    error = aiState.error?.toUserMessage(),
                    onCopyClick = {
                        val clipboard =
                            context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        val clip = ClipData.newPlainText("ai result", aiState.result.toString())
                        clipboard.setPrimaryClip(clip)
                        viewModel.onEvent(NoteDetailsEvent.AiResultHandled)
                    },
                    onReplaceClick = {
                        viewModel.onEvent(NoteDetailsEvent.UpdateContent(aiState.result.toString()))
                        viewModel.onEvent(NoteDetailsEvent.AiResultHandled)
                    },
                    liquidState = rememberLiquidState(),
                    onAddToNoteClick = {
                        viewModel.onEvent(NoteDetailsEvent.UpdateContent(aiState.result + "\n" + content))
                        viewModel.onEvent(NoteDetailsEvent.AiResultHandled)
                    }
                )
            }
        }

        if (openDeleteDialog) {
            MemphisSheet(
                title = stringResource(R.string.delete_note_confirmation_title),
                subtitle = stringResource(R.string.delete_note_confirmation_message, state.note?.title ?: ""),
                onDismiss = { openDeleteDialog = false },
                accent = Memphis.Coral,
                footer = {
                    MemphisSheetButton(
                        label = stringResource(R.string.cancel),
                        onClick = { openDeleteDialog = false },
                    )
                    MemphisSheetButton(
                        label = stringResource(R.string.delete_note),
                        filled = true,
                        ink = Memphis.Coral,
                        onClick = {
                            state.note?.let { viewModel.onEvent(NoteDetailsEvent.DeleteNote(it)) }
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

        if (openFolderDialog) {
            MemphisSheet(
                title = stringResource(R.string.change_folder),
                subtitle = "Which folder this note lives in",
                onDismiss = { openFolderDialog = false },
                footer = {
                    MemphisSheetButton(
                        label = stringResource(R.string.cancel),
                        onClick = { openFolderDialog = false },
                    )
                },
                body = {
                    FlowRow(
                        Modifier.fillMaxWidth().padding(vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        MemphisPill(
                            label = stringResource(R.string.none),
                            selected = folder == null,
                            onClick = {
                                viewModel.onEvent(NoteDetailsEvent.UpdateFolder(null))
                                openFolderDialog = false
                            },
                        )
                        state.folders.forEachIndexed { index, candidate ->
                            MemphisPill(
                                label = candidate.name,
                                selected = folder?.id == candidate.id,
                                ink = Memphis.inkFor(index),
                                onClick = {
                                    viewModel.onEvent(NoteDetailsEvent.UpdateFolder(candidate))
                                    openFolderDialog = false
                                },
                            )
                        }
                    }
                },
            )
        }
    }
}

private fun String.countWords(): Int {
    var count = 0
    var inWord = false

    forEach { char ->
        if (char == ' ' || char == '\n') {
            inWord = false
        } else if (!inWord) {
            count++
            inWord = true
        }
    }

    return count
}
