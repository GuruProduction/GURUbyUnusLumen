// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.heartbeat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.style.TextAlign
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
 * The heartbeat room: the prompt GURU beats with, in its owner's hands.
 *
 * WHAT THIS REPLACED. The prompt shipped inside the APK. Changing a word of what
 * GURU says to itself every thirty minutes meant a developer, a commit and a
 * release, on an app whose whole premise is that its owner is in charge. This screen
 * is the fix: read it, edit it, save it, and the next beat uses the new text.
 *
 * TWO FIELDS, NOT ONE. The editor shows the text as written, tokens and all, and the
 * preview shows exactly what will be injected after substitution. Without the second
 * one a person cannot tell what `${human}` turns into, and a prompt that looks right
 * and reads wrong is the worst of both.
 *
 * SHAPE. Fixed head, scrolling body, fixed action foot, the same as the agent creator
 * and the detail sheets: the Save button is always on screen, so a long prompt can
 * never push the only way to commit it off the bottom.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HeartbeatScreen(
    navController: NavHostController,
    viewModel: HeartbeatViewModel = koinViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = "Heartbeat",
                stamp = if (state.edited) "edited" else "shipped",
                stampColor = if (state.edited) Memphis.Yellow else Memphis.Cyan,
            )
        },
    ) { paddingValues ->
        Box(Modifier.fillMaxSize().padding(paddingValues)) {
            when {
                state.isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Reading the prompt…", style = captionStyle(10f, Memphis.Ink.copy(alpha = 0.6f)))
                }

                else -> Column(Modifier.fillMaxSize()) {
                    LazyColumn(
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(start = 18.dp, end = 18.dp, top = 12.dp, bottom = 16.dp),
                    ) {
                        item(key = "intro") {
                            Text(
                                "GURU wakes every thirty minutes and sends itself this. " +
                                    "Change it and the next beat uses your words.",
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
                            Band("The prompt", if (state.draft.isBlank()) null else "as written")
                        }
                        item(key = "editor") {
                            PromptField(
                                value = state.draft,
                                onChange = { viewModel.onEvent(HeartbeatEvent.Edit(it)) },
                            )
                        }

                        item(key = "tokens") {
                            Spacer(Modifier.height(12.dp))
                            Text(
                                "These get filled in at beat time. The preview below shows the result.",
                                style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.6f)),
                            )
                            Spacer(Modifier.height(7.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                            ) {
                                TokenChip("\${human}", "your name")
                                TokenChip("\${time}", "the clock")
                            }
                        }

                        item(key = "preview-band") {
                            Band("What a beat injects", if (state.preview.isBlank()) null else "now")
                        }
                        item(key = "preview") {
                            MemphisSurface(
                                modifier = Modifier.fillMaxWidth(),
                                color = Memphis.Paper,
                                borderWidth = 2.5.dp,
                                shadowOffset = 4.dp,
                            ) {
                                Box(
                                    Modifier
                                        .fillMaxWidth()
                                        .heightIn(max = 320.dp)
                                        .verticalScroll(rememberScrollState())
                                        .padding(12.dp),
                                ) {
                                    if (state.preview.isBlank()) {
                                        Text(
                                            "Nothing yet. Write a prompt above.",
                                            style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.45f)),
                                        )
                                    } else {
                                        Text(state.preview, style = bodyText())
                                    }
                                }
                            }
                        }
                    }

                    // THE FOOT. Save is the commit, Reset puts the shipped prompt back.
                    Box(Modifier.fillMaxWidth().height(3.dp).background(Memphis.Ink))
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 18.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        PrintedButton(
                            label = "Reset",
                            modifier = Modifier.weight(1f),
                            filled = false,
                            enabled = state.edited && !state.saving,
                            onClick = { viewModel.onEvent(HeartbeatEvent.Reset) },
                        )
                        PrintedButton(
                            label = when {
                                state.saving -> "Saving…"
                                else -> "Save"
                            },
                            modifier = Modifier.weight(1f),
                            filled = true,
                            enabled = state.canSave && !state.saving,
                            onClick = { viewModel.onEvent(HeartbeatEvent.Save) },
                        )
                    }
                }
            }
        }
    }
}

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
                .heightIn(min = 180.dp, max = 420.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            if (value.isEmpty()) {
                Text(
                    "Write what GURU should say to itself when it wakes.",
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
private fun RowScope.TokenChip(token: String, means: String) {
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
private fun Notice(message: String, ink: Color) {
    MemphisSurface(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        color = ink,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Text(
            message,
            style = captionStyle(9f, Memphis.Paper),
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
