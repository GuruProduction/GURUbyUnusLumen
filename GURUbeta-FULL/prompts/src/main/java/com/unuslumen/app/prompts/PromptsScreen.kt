// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.prompts

import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.NavHostController
import com.unuslumen.app.ui.components.common.guruAppBar
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBody
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle
import org.koin.androidx.compose.koinViewModel

/**
 * The prompts room: what GURU is told, what the publisher sent, and your own version.
 *
 * WHY THIS ROOM EXISTS. The README says you can read the instructions in the app and
 * write your own. That was not true until this screen: the assembled prompt was
 * fetched, cached and used, with nothing to look at and nothing to change. A promise
 * in the README is only worth the screen behind it.
 *
 * TWO TABS, ONE SUBJECT. The publisher tab is what Unus Lumen is telling every install
 * right now. The yours tab is what this device will send instead. Keeping them apart
 * matters because one is a record and the other is a choice, and a single editor would
 * make it impossible to tell which you were looking at.
 *
 * SHAPE. Fixed head, scrolling body, fixed action foot, the same as the heartbeat room
 * and the agent creator: the commit is always on screen, so a long prompt can never
 * push the only way to save it off the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PromptsScreen(
    navController: NavHostController,
    viewModel: PromptsViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    /*
     * WHICH TAB IS OPEN, HELD HERE RATHER THAN IN A VIEW MODEL. A selected tab does not
     * outlive the room and nobody expects it to, so rememberSaveable covers the only
     * lifetime it needs: surviving a rotation.
     */
    var tab by rememberSaveable { mutableIntStateOf(0) }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = "Prompts",
                stamp = if (state.overrideActive) "yours" else "published",
                stampColor = if (state.overrideActive) Memphis.Yellow else Memphis.Lilac,
            )
        },
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        "Reading what GURU is told…",
                        style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)),
                    )
                }

                else -> Column(Modifier.fillMaxSize()) {
                    Column(
                        Modifier.fillMaxWidth().padding(start = 18.dp, end = 18.dp, top = 12.dp),
                    ) {
                        Row(
                            Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            PrintedTab(
                                label = "Published",
                                selected = tab == 0,
                                modifier = Modifier.weight(1f),
                                onClick = { tab = 0 },
                            )
                            PrintedTab(
                                label = "Yours",
                                selected = tab == 1,
                                modifier = Modifier.weight(1f),
                                onClick = { tab = 1 },
                            )
                        }
                    }

                    if (tab == 0) {
                        PublishedTab(state = state, viewModel = viewModel)
                    } else {
                        YoursTab(state = state, viewModel = viewModel)
                    }
                }
            }
        }
    }
}

/**
 * What the publisher is serving, read-only.
 *
 * READ-ONLY IS THE POINT. This is the record of what every install of GURU is being
 * told, and editing it here would only change this device while looking like it
 * changed the product. The way to change it is the Yours tab, and that is labelled.
 */
@Composable
private fun PublishedTab(state: PromptsState, viewModel: PromptsViewModel) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp),
        ) {
            item(key = "intro") {
                Text(
                    when {
                        state.published.isBlank() ->
                            "Nothing is cached on this device yet. GURU fetches its " +
                                "instructions from the publisher at boot; press Fetch to try now."
                        state.overrideActive ->
                            "This is what Unus Lumen sends. You are running your own text " +
                                "instead, so this is not what the model sees right now."
                        else ->
                            "This is what GURU is told, fetched from Unus Lumen. The Yours " +
                                "tab replaces all of it."
                    },
                    style = bodyText(),
                )
            }

            state.notice?.let { notice ->
                item(key = "notice") { Notice(notice, Memphis.Teal) }
            }
            state.error?.let { error ->
                item(key = "error") { Notice(error, Memphis.Coral) }
            }

            item(key = "version-band") {
                Band(
                    "Version",
                    when {
                        state.publishedVersion <= 0 -> "none"
                        state.publishedFresh -> "${state.publishedVersion} · fresh"
                        else -> "${state.publishedVersion} · older than a day"
                    },
                )
            }

            item(key = "published-text") {
                TextBlock(
                    text = state.published,
                    empty = "Nothing cached. GURU runs on an empty prompt until it reaches the server.",
                )
            }

            item(key = "tokens") {
                Spacer(Modifier.height(12.dp))
                Text(
                    "These two get filled in at send time. The Yours tab shows the result.",
                    style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
                )
                Spacer(Modifier.height(7.dp))
                Row(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    TokenChip("\${human}", "your name")
                    TokenChip("\${guru}", "its name")
                }
            }
        }

        Box(Modifier.fillMaxWidth().height(3.dp).background(Memphis.Ink))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrintedButton(
                label = when {
                    state.saving -> "Fetching…"
                    else -> "Fetch now"
                },
                modifier = Modifier.weight(1f),
                filled = false,
                enabled = !state.saving,
                onClick = { viewModel.onEvent(PromptsEvent.Refresh) },
            )
            PrintedButton(
                label = "Replace it",
                modifier = Modifier.weight(1f),
                filled = true,
                enabled = true,
                // Switching to the editor rather than acting on the text: this tab is a
                // record, and the only write belongs on the other side.
                onClick = { viewModel.onEvent(PromptsEvent.ClearNotice) },
            )
        }
    }
}

/**
 * The owner's own instructions, and what the model will actually receive.
 *
 * THE PREVIEW IS NOT AN EXTRA. A prompt with `${human}` in it is not what the model
 * reads, so an editor that shows only the raw text cannot tell you what you are about
 * to send. Both fields are on screen, the same way the heartbeat room does it.
 */
@Composable
private fun YoursTab(state: PromptsState, viewModel: PromptsViewModel) {
    Column(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp),
        ) {
            item(key = "intro") {
                Text(
                    when {
                        state.overrideActive ->
                            "GURU is running your text. Change it here, or clear it to go " +
                                "back to what the publisher sends."
                        else ->
                            "Write anything here and it replaces the published prompt " +
                                "entirely. Leave it empty and the publisher's version is used."
                    },
                    style = bodyText(),
                )
            }

            state.notice?.let { notice ->
                item(key = "notice") { Notice(notice, Memphis.Teal) }
            }
            state.error?.let { error ->
                item(key = "error") { Notice(error, Memphis.Coral) }
            }

            item(key = "editor-band") {
                Band("Your prompt", if (state.overrideActive) "in force" else "not set")
            }
            item(key = "editor") {
                PromptField(
                    value = state.override,
                    onChange = { viewModel.onEvent(PromptsEvent.EditOverride(it)) },
                )
            }

            item(key = "preview-band") {
                Band("What GURU receives", if (state.preview.isBlank()) null else "now")
            }
            item(key = "preview") {
                TextBlock(
                    text = state.preview,
                    empty = "Nothing. With no override and nothing cached, the model gets no instructions at all.",
                )
            }
        }

        Box(Modifier.fillMaxWidth().height(3.dp).background(Memphis.Ink))
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            PrintedButton(
                label = "Clear",
                modifier = Modifier.weight(1f),
                filled = false,
                enabled = state.overrideActive && !state.saving,
                onClick = { viewModel.onEvent(PromptsEvent.ClearOverride) },
            )
            PrintedButton(
                label = when {
                    state.saving -> "Saving…"
                    else -> "Save"
                },
                modifier = Modifier.weight(1f),
                filled = true,
                enabled = state.canSave && !state.saving,
                onClick = { viewModel.onEvent(PromptsEvent.Save) },
            )
        }
    }
}

/** One printed tab. The active one fills with ink, which is this language's selected state. */
@Composable
private fun PrintedTab(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    MemphisSurface(
        modifier = modifier.clickable { onClick() },
        color = if (selected) Memphis.Ink else Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 11.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.uppercase(),
                style = captionStyle(9.5f, if (selected) Memphis.Paper else Memphis.Ink),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/**
 * A body of prompt text on its own printed surface.
 *
 * THE HEIGHT IS BOUNDED AND SCROLLS. An assembled prompt runs to hundreds of thousands
 * of characters, and letting it size its own box would make the page one enormous
 * scroll that cannot be navigated on a phone. The cap keeps the page usable and the
 * inner scroll gets to the end of the text.
 */
@Composable
private fun TextBlock(text: String, empty: String) {
    MemphisSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(max = 380.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            if (text.isBlank()) {
                Text(empty, style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.45f)))
            } else {
                Text(text, style = bodyText())
            }
        }
    }
}

/** The editor. Tall, scrolls internally, cursor in ink. */
@Composable
private fun PromptField(value: String, onChange: (String) -> Unit) {
    MemphisSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .heightIn(min = 220.dp, max = 460.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    "Write what GURU should be told. Anything here replaces the published prompt.",
                    style = bodyText().copy(color = Memphis.Ink.copy(alpha = 0.4f)),
                )
            }
            BasicTextField(
                value = value,
                onValueChange = onChange,
                textStyle = bodyText(),
                cursorBrush = SolidColor(Memphis.Ink),
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}

/** A token as it is written, with what it becomes underneath. */
@Composable
private fun androidx.compose.foundation.layout.RowScope.TokenChip(token: String, means: String) {
    Box(
        Modifier
            .weight(1f)
            .background(Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(token, style = posterStyle(12f))
            Text(means.uppercase(), style = captionStyle(7f, Memphis.Ink.copy(alpha = 0.55f)))
        }
    }
}

/** The black band that separates one part of the page from the next. */
@Composable
private fun Band(label: String, note: String?) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 16.dp, bottom = 9.dp)
            .background(Memphis.Ink)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = captionStyle(9.5f, Memphis.Paper))
        if (note != null) {
            Text(
                note.uppercase(),
                style = captionStyle(8f, Memphis.Ink),
                modifier = Modifier
                    .background(Memphis.Paper)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

@Composable
private fun Notice(message: String, ink: Color, label: Color = Memphis.Paper) {
    MemphisSurface(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        color = ink,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Text(
            message,
            style = captionStyle(9f, label),
            modifier = Modifier.fillMaxWidth().padding(11.dp),
        )
    }
}

@Composable
private fun PrintedButton(
    label: String,
    modifier: Modifier = Modifier,
    filled: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    MemphisSurface(
        modifier = modifier.then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        color = if (filled && enabled) Memphis.Ink else Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                label.uppercase(),
                style = captionStyle(
                    9.5f,
                    when {
                        !enabled -> Memphis.Ink.copy(alpha = 0.35f)
                        filled -> Memphis.Paper
                        else -> Memphis.Ink
                    },
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** The body face at a readable size, in the house voice. */
private fun bodyText() = androidx.compose.ui.text.TextStyle(
    fontFamily = MemphisBody,
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
    color = Memphis.Ink,
)
