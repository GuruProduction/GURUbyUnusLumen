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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

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

    /*
     * OPEN THE PORTAL WHEN A BEAT IS FIRED BY HAND.
     *
     * The view model hands the prompt to the portal's shared holder and reports
     * success; the navigation belongs here because this composable is the one holding
     * the NavHostController. Navigation stays out of the DI module on purpose: a module
     * that routes would need to know the app's whole screen graph, and the room is
     * meant to be a room rather than a second router.
     *
     * The prompt is already waiting by the time this runs, and the portal consumes it
     * on entry, so the beat arrives as a real streaming turn.
     */
    LaunchedEffect(state.firedAt) {
        if (state.firedAt > 0L) {
            navController.navigate(com.unuslumen.app.ui.navigation.Screen.PortalScreen) {
                launchSingleTop = true
            }
        }
    }

    Scaffold(
        containerColor = Color.Transparent,
        topBar = {
            guruAppBar(
                title = "Heartbeat",
                // The stamp states the switch, because that is the first thing a
                // person needs to know about a chain that is silent by design.
                stamp = if (state.enabled) "on" else "off",
                stampColor = if (state.enabled) Memphis.Yellow else Memphis.Lilac,
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
                        /*
                         * THE SWITCH COMES FIRST, ABOVE EVERYTHING.
                         *
                         * It decides whether the clock below means anything at all,
                         * and a person opening this room has one question before any
                         * other: is it running. Putting the prompt above it would bury
                         * the control that governs the whole screen.
                         */
                        /*
                         * THE SWITCH, AND ITS REFUSAL, AS ONE BLOCK OF PRINTED TEXT.
                         *
                         * There is no separate warning bar. A slab under the switch drew
                         * its hard shadow outside its own bounds and landed on the
                         * sentence beneath it, and it also said the same thing twice: the
                         * switch cannot be on without a prompt, so the reason belongs
                         * inside the switch rather than floating below it.
                         */
                        item(key = "switch") {
                            ChainSwitch(
                                enabled = state.enabled,
                                toggling = state.toggling,
                                canEnable = state.canEnable,
                                promptRequired = state.promptRequired,
                                onToggle = { viewModel.onEvent(HeartbeatEvent.SetEnabled(it)) },
                            )
                        }

                        /*
                         * THE PROMPT PANEL.
                         *
                         * A dismissible sheet that tells the owner a heartbeat master
                         * prompt has to be written before the chain can be switched on.
                         * It sits in the list flow with its own padding so the printed
                         * drop stays inside its own cell, which a bar pinned under the
                         * switch did not: that one drew its shadow onto the sentence
                         * below it.
                         */
                        if (state.promptRequired) {
                            item(key = "prompt-required") {
                                PromptRequiredPanel(
                                    onDismiss = {
                                        viewModel.onEvent(HeartbeatEvent.DismissPromptRequired)
                                    },
                                )
                            }
                        }

                        item(key = "intro") {
                            Text(
                                when {
                                    state.enabled ->
                                        "GURU wakes every thirty minutes and sends itself the " +
                                            "prompt below. Change it and the next beat uses your words."
                                    state.draft.isBlank() ->
                                        "A prompt has to exist before the heartbeat can be switched " +
                                            "on, because that prompt is what every beat sends."
                                    else ->
                                        "GURU is not waking. Switch the heartbeat on above and it " +
                                            "will fire every thirty minutes with the prompt below."
                                },
                                style = bodyText(),
                            )
                        }

                        // THE CHAIN'S PULSE, BEFORE THE PROMPT.
                        //
                        // A heartbeat that has stopped is the fault a person notices,
                        // and until this block existed the room could only show the
                        // prompt, which looked perfectly healthy whether or not anything
                        // ever fired. Both numbers are printed because neither is enough
                        // alone: a next booking with no last fire is a chain written and
                        // never delivered, which is exactly what a dead heartbeat looks
                        // like from the inside.
                        item(key = "chain") { ChainState(state, viewModel) }

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

                    // THE FOOT. Save is the commit. Reset clears the prompt, because
                    // there is no shipped one to go back to.
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

/**
 * Whether the chain is alive, said in plain numbers and one plain sentence.
 *
 * WHY THIS IS ON THE SCREEN AT ALL. The beat is silent by design, which means its
 * failure is silent too. A person can only tell that something stopped from outside:
 * GURU stops saying anything unprompted. That is a bad way to learn the chain is
 * dead, and it is unfixable by reading code, so the room states the two facts that
 * distinguish the cases and offers the one action that separates them.
 */
@Composable
private fun ChainState(state: HeartbeatState, viewModel: HeartbeatViewModel) {
    val clock = remember { SimpleDateFormat("HH:mm", Locale.UK) }
    val day = remember { SimpleDateFormat("d MMM", Locale.UK) }

    fun stamp(millis: Long): String {
        if (millis <= 0L) return "never"
        val at = Date(millis)
        return "${day.format(at)} ${clock.format(at)}"
    }

    Band("The chain", if (state.neverFired) "no beat yet" else "alive")

    MemphisSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(12.dp)) {
            ChainLine("LAST FIRED", stamp(state.lastFiredAt))
            Spacer(Modifier.height(6.dp))
            ChainLine("NEXT DUE", stamp(state.nextBookedAt))

            if (state.lastOutcome.isNotBlank()) {
                Spacer(Modifier.height(10.dp))
                Text(
                    state.lastOutcome,
                    style = captionStyle(8.5f, Memphis.Coral),
                )
            }

            Spacer(Modifier.height(11.dp))
            MemphisSurface(
                modifier = Modifier
                    .fillMaxWidth()
                    .then(
                        if (state.firing) Modifier
                        else Modifier.clickable { viewModel.onEvent(HeartbeatEvent.FireNow) }
                    ),
                color = if (state.firing) Memphis.Paper else Memphis.Ink,
                borderWidth = 2.5.dp,
                shadowOffset = 3.dp,
            ) {
                Box(
                    Modifier.fillMaxWidth().padding(vertical = 11.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(
                        when {
                            state.firing -> "STARTING A BEAT…"
                            else -> "FIRE ONE NOW"
                        },
                        style = captionStyle(9f, if (state.firing) Memphis.Ink.copy(alpha = 0.4f) else Memphis.Paper),
                    )
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                "If that beat reaches the model, the chain above is the fault rather than the beat. " +
                    "If it reports an outcome instead, that sentence is the fault.",
                style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
            )
        }
    }
}

/** One label and its value, printed as a line rather than a card. */
@Composable
private fun ChainLine(label: String, value: String) {
    Row(
        Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)))
        Text(value.uppercase(), style = posterStyle(13f))
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

/**
 * The switch, printed rather than slid.
 *
 * WHY A PRESSED BLOCK AND NOT A TOGGLE. The Memphis language in this app is printed
 * blocks with hard ink outlines: nothing here glides, springs or fades, so a Material
 * switch would be the one soft object on a poster. It reads as one control with two
 * stated states, and the state is a word rather than a knob position.
 *
 * THE THIRD LINE IS THE REFUSAL, AND IT LIVES HERE. When there is no prompt the block
 * says so in its own caption rather than in a warning bar underneath, because the
 * reason the switch will not move is a property of the switch. Pressing it while
 * there is no prompt sets [promptRequired] on the view model, which lights this line
 * up and draws the border in coral; the switch itself never moves.
 *
 * IT CANNOT BE PRESSED WHILE A WRITE IS IN FLIGHT, which is the same guard the rest of
 * the room uses for Save. A double tap on a switch that books an alarm is not a thing
 * to leave possible.
 */
@Composable
private fun ChainSwitch(
    enabled: Boolean,
    toggling: Boolean,
    canEnable: Boolean,
    promptRequired: Boolean,
    onToggle: (Boolean) -> Unit,
) {
    // A refused press is stated in the block itself: coral ink so the refusal is
    // unmistakable, ink otherwise so a normal off state stays quiet.
    val refused = promptRequired && !canEnable && !enabled
    Column(Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        MemphisSurface(
            modifier = Modifier
                .fillMaxWidth()
                .then(
                    if (toggling) Modifier else Modifier.clickable { onToggle(!enabled) }
                ),
            color = when {
                enabled -> Memphis.Yellow
                refused -> Memphis.Coral.copy(alpha = 0.18f)
                else -> Memphis.Paper
            },
            borderWidth = 2.5.dp,
            shadowOffset = 4.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp)) {
                Text(
                    if (enabled) "HEARTBEAT ON" else "HEARTBEAT OFF",
                    style = posterStyle(15f),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    when {
                        toggling -> "SAVING…"
                        enabled -> "FIRES EVERY 30 MINUTES. TAP TO STOP IT."
                        refused -> "NEEDS A PROMPT FIRST. WRITE ONE BELOW AND SAVE IT."
                        !canEnable -> "WRITE AND SAVE A PROMPT, THEN THIS TURNS ON."
                        else -> "NOTHING IS SCHEDULED. TAP TO START IT."
                    },
                    style = captionStyle(
                        8.5f,
                        if (refused) Memphis.Coral else Memphis.Ink.copy(alpha = 0.65f),
                    ),
                )
            }
        }
    }
}

/**
 * The prompt the owner must now write, and the way to close it.
 *
 * WHY A PANEL AND NOT A BAR. A warning printed as a permanent strip under the switch
 * covered the sentence beneath it and could not be put away. This is a printed sheet
 * that says what is required, offers one action that closes it, and leaves the rest of
 * the page alone.
 *
 * IT DOES NOT WRITE ANYTHING. Dismissing closes the sheet. The switch has not moved and
 * the prompt is still missing, which is the honest state: the owner has been told what
 * is needed and decides when to do it.
 */
@Composable
private fun PromptRequiredPanel(onDismiss: () -> Unit) {
    Box(Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        MemphisSurface(
            modifier = Modifier.fillMaxWidth(),
            color = Memphis.Sun,
            borderWidth = 2.5.dp,
            shadowOffset = 5.dp,
        ) {
            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                Text("A MASTER PROMPT IS NEEDED", style = posterStyle(15f))

                Spacer(Modifier.height(7.dp))
                Text(
                    "The heartbeat cannot be switched on without one. A master prompt is " +
                        "what GURU sends itself every thirty minutes, so with nothing " +
                        "written there would be nothing to run.",
                    style = bodyText(),
                )

                Spacer(Modifier.height(9.dp))
                Text(
                    "Write one in the editor below, save it, then the switch will turn on.",
                    style = captionStyle(9f, Memphis.Ink.copy(alpha = 0.75f)),
                )

                Spacer(Modifier.height(13.dp))
                MemphisSurface(
                    modifier = Modifier.fillMaxWidth().clickable { onDismiss() },
                    color = Memphis.Ink,
                    borderWidth = 2.5.dp,
                    shadowOffset = 3.dp,
                ) {
                    Box(
                        Modifier.fillMaxWidth().padding(vertical = 11.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text("GOT IT", style = captionStyle(9.5f, Memphis.Paper))
                    }
                }
            }
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
