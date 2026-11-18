// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleStartEffect
import androidx.navigation.NavHostController
import com.unuslumen.app.domain.model.JournalEntry
import com.unuslumen.app.domain.model.Mood
import com.unuslumen.app.ui.R
import com.unuslumen.app.ui.components.common.DateTimeDialog
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
import com.unuslumen.app.util.date.fullDate
import com.unuslumen.app.util.date.now
import com.mikepenz.markdown.coil2.Coil2ImageTransformerImpl
import com.mikepenz.markdown.m3.Markdown
import org.koin.androidx.compose.koinViewModel
import org.koin.core.parameter.parametersOf
import kotlin.uuid.Uuid

/**
 * One journal entry, in the house language.
 *
 * WHAT IT WAS. A Material editor with rounded outlined fields, a mood row of grey
 * tinted icons, icon buttons in the app bar and a rounded red delete dialog.
 *
 * WHAT IT IS NOW. Printed fields, a mood row of coloured blocks that fill with the
 * mood's own colour when chosen, the date and the reading mode as printed pills on the
 * masthead, and the delete confirmation on the shared printed sheet.
 *
 * The mood picker keeps every option and every callback it had. It is a real control,
 * drawn in the page's grammar rather than as five grey glyphs.
 */
@Composable
fun JournalEntryDetailsScreen(
    navController: NavHostController,
    entryId: String?,
    viewModel: JournalDetailsViewModel = koinViewModel(parameters = { parametersOf(entryId.orEmpty()) })
) {
    val state by viewModel.uiState.collectAsState()
    var openDialog by rememberSaveable { mutableStateOf(false) }

    var title by remember { mutableStateOf("") }
    var content by remember { mutableStateOf("") }
    var mood by remember { mutableStateOf(Mood.OKAY) }
    var date by remember { mutableLongStateOf(now()) }
    val readingMode = state.readingMode
    var showDateDialog by remember { mutableStateOf(false) }
    val context = LocalContext.current

    LaunchedEffect(state.entry) {
        val entry = state.entry
        if (entry != null) {
            title = entry.title
            content = entry.content
            date = entry.createdDate
            mood = entry.mood
        }
    }
    LaunchedEffect(state.navigateUp) {
        if (state.navigateUp) {
            openDialog = false
            navController.navigateUp()
        }
    }
    LifecycleStartEffect(Unit) {
        onStopOrDispose {
            viewModel.onEvent(
                JournalDetailsEvent.ScreenOnStop(
                    JournalEntry(
                        title = title,
                        content = content,
                        mood = mood,
                        createdDate = date,
                        id = entryId ?: Uuid.random().toString()
                    )
                )
            )
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        snackbarHost = { LocalisedSnackbarHost(state.snackbarHostState) },
        topBar = {
            guruAppBar(
                title = if (state.entry == null) "New entry" else "Entry",
                actions = {
                    MemphisPill(
                        label = date.fullDate(context),
                        selected = false,
                        onClick = { showDateDialog = true },
                    )
                    Spacer(Modifier.width(6.dp))
                    MemphisPill(
                        label = if (readingMode) "Reading" else "Edit",
                        selected = readingMode,
                        ink = Memphis.Teal,
                        onClick = { viewModel.onEvent(JournalDetailsEvent.ToggleReadingMode) },
                    )
                    if (state.entry != null) {
                        Spacer(Modifier.width(6.dp))
                        MemphisPill(
                            label = "Delete",
                            selected = false,
                            ink = Memphis.Coral,
                            onClick = { openDialog = true },
                        )
                    }
                }
            )
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
            Text(
                text = stringResource(R.string.mood).uppercase(),
                style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
                modifier = Modifier.padding(top = 4.dp, bottom = 6.dp)
            )
            EntryMoodSection(currentMood = mood) { mood = it }
            Spacer(Modifier.height(10.dp))

            Text(
                text = stringResource(R.string.title).uppercase(),
                style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
                modifier = Modifier.padding(bottom = 5.dp)
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
                            "A name for today",
                            style = MemphisBodyText.copy(color = Memphis.Ink.copy(alpha = 0.4f)),
                        )
                    }
                    BasicTextField(
                        value = title,
                        onValueChange = { title = it },
                        textStyle = posterStyle(15f),
                        cursorBrush = SolidColor(Memphis.Ink),
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))

            if (readingMode) {
                MemphisSurface(
                    modifier = Modifier.fillMaxWidth(),
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
                    text = stringResource(R.string.content).uppercase(),
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
                    modifier = Modifier.padding(bottom = 5.dp)
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
                                "How the day went.",
                                style = MemphisBodyText.copy(color = Memphis.Ink.copy(alpha = 0.4f)),
                            )
                        }
                        BasicTextField(
                            value = content,
                            onValueChange = { content = it },
                            textStyle = MemphisBodyText,
                            cursorBrush = SolidColor(Memphis.Ink),
                            modifier = Modifier.fillMaxWidth(),
                        )
                    }
                }
            }
            Spacer(Modifier.height(24.dp))
        }

        if (showDateDialog) {
            DateTimeDialog(
                onDismissRequest = { showDateDialog = false },
                initialDate = date
            ) {
                date = it
                showDateDialog = false
            }
        }

        if (openDialog) {
            MemphisSheet(
                title = stringResource(R.string.delete_journal_entry_confirmation_title),
                subtitle = stringResource(R.string.delete_journal_entry_confirmation_message),
                onDismiss = { openDialog = false },
                accent = Memphis.Coral,
                footer = {
                    MemphisSheetButton(
                        label = stringResource(R.string.cancel),
                        onClick = { openDialog = false },
                    )
                    MemphisSheetButton(
                        label = stringResource(R.string.delete_entry),
                        filled = true,
                        ink = Memphis.Coral,
                        onClick = { viewModel.onEvent(JournalDetailsEvent.DeleteEntry) },
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
    }
}

/**
 * The five moods, as coloured printed cells.
 *
 * A cell fills with the mood's own colour when it is chosen and carries the mood's
 * mark in ink, so the chosen one reads from across the page. Every mood stays pickable
 * and every choice still calls the same callback.
 */
@Composable
fun EntryMoodSection(
    currentMood: Mood,
    onMoodChange: (Mood) -> Unit
) {
    val moods = listOf(Mood.AWESOME, Mood.GOOD, Mood.OKAY, Mood.BAD, Mood.TERRIBLE)
    Row(
        Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(7.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        moods.forEach { mood ->
            MoodItem(
                mood = mood,
                chosen = mood == currentMood,
                onMoodChange = { onMoodChange(mood) }
            )
        }
    }
}

@Composable
private fun MoodItem(mood: Mood, chosen: Boolean, onMoodChange: () -> Unit) {
    Box(
        Modifier
            .background(if (chosen) mood.color else Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .clickable { onMoodChange() }
            .padding(horizontal = 9.dp, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Icon(
                painter = painterResource(id = mood.iconRes),
                contentDescription = stringResource(mood.titleRes),
                tint = Memphis.Ink,
                modifier = Modifier.size(30.dp)
            )
            Spacer(Modifier.height(5.dp))
            Text(
                text = stringResource(mood.titleRes).uppercase(),
                style = captionStyle(7.5f, Memphis.Ink),
            )
        }
    }
}
