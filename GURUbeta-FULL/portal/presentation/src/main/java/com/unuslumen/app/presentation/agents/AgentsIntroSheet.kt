// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.agents

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle

/**
 * What an agent is, for the first time somebody opens the Agents room.
 *
 * WHY IT IS A PANEL AND NOT A DIALOG. A stock Android dialog arrives with the
 * system's own weight, rounded corners and grey, and drops a Memphis page into a
 * different product for one screen. This is drawn as another printed block on
 * the same paper: hard ink edge, offset drop, poster type.
 *
 * WHY IT IS SHOWN ONCE AND REMEMBERED. The explanation is worth reading once and
 * is in the way on the tenth visit. It is dismissed by the button, by tapping
 * the paper outside, or by the back gesture, and the preference is stored so it
 * never interrupts again. There is also a permanent way back to it, because a
 * person who forgot what an agent was should not have to clear app data.
 *
 * THE COPY IS DELIBERATELY PLAIN. Most people using this will not know what a
 * tool grant or a catalogue is, so the panel says what an agent does and what
 * you can do with one, and leaves the mechanism to the screens that need it.
 */
@Composable
fun AgentsIntroPanel(
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    // Tapping the paper outside dismisses, and the scrim must not ripple: a
    // ripple on an empty area reads as a button that did nothing.
    Box(
        modifier = modifier
            .fillMaxSize()
            .background(Memphis.Ink.copy(alpha = 0.55f))
            // SAFE AREA. Without these the sheet could sit under the status bar or
            // the navigation gesture strip on a phone with either, which is what
            // put the head and the way out outside the visible area.
            .statusBarsPadding()
            .navigationBarsPadding()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onDismiss,
            ),
        contentAlignment = Alignment.Center,
    ) {
        MemphisSurface(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 18.dp)
                // A HARD CEILING. The head and the way out are fixed; only the
                // body between them scrolls, so GOT IT is always on screen no
                // matter how long the explanation runs or how short the phone is.
                .heightIn(max = 620.dp)
                // The tap on the sheet itself must not fall through to the scrim
                // and dismiss what the person is reading.
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null,
                    onClick = {},
                ),
            color = Memphis.Paper,
            borderWidth = 3.dp,
            shadowOffset = 8.dp,
        ) {
            Column(Modifier.fillMaxWidth()) {
                // --- fixed head ---
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp)) {
                    // The masthead, in the same grammar as the lobby's own.
                    Row(
                        Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(
                            text = "AGENTS",
                            style = posterStyle(34f),
                        )
                        Text(
                            text = "NEW",
                            style = captionStyle(9f),
                            modifier = Modifier
                                .background(Memphis.Coral)
                                .padding(horizontal = 7.dp, vertical = 4.dp),
                        )
                    }

                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 6.dp)
                            .height(3.dp)
                            .background(Memphis.Ink),
                    )
                }

                // --- the body scrolls ---
                Column(
                    Modifier
                        .weight(1f, fill = false)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 14.dp, bottom = 12.dp),
                ) {
                    IntroLine(
                        mark = Memphis.Yellow,
                        title = "A MINI GURU",
                        body = "An agent is a mini GURU you send off with written instructions to do a " +
                            "side job on its own. It does the digging and comes back with a report, so " +
                            "your main chat stays clean and you carry on with what you were doing.",
                    )

                    IntroLine(
                        mark = Memphis.Cyan,
                        title = "YOURS AND GURU'S",
                        body = "You can run one yourself, or GURU can call one mid conversation and manage " +
                            "it live while it works. They can share the session context they were sent " +
                            "from, so a job that depends on what you were just talking about still lands " +
                            "properly. GURU double checks what comes back, because an agent can be wrong.",
                    )

                    IntroLine(
                        mark = Memphis.Purple,
                        title = "MAKE YOUR OWN",
                        body = "Say what it is for and what it is allowed to touch. It is yours, it lives " +
                            "on your phone, and you can change it any time.",
                    )

                    IntroLine(
                        mark = Memphis.Teal,
                        title = "USE OTHER PEOPLE'S",
                        body = "Browse what people have built, read exactly what an agent does before you " +
                            "add it, then download it. A good one turns a job you would have had to " +
                            "explain from scratch into a job you just hand over.",
                    )

                    IntroLine(
                        mark = Memphis.Pink,
                        title = "SHARE YOURS",
                        body = "Publish an agent you have built so anyone can use it, free or at a price " +
                            "you set. Every one is checked before it goes live, so what you download is " +
                            "what somebody actually tested.",
                        last = true,
                    )
                }

                // --- the fixed foot: the way out, always on screen ---
                Box(Modifier.fillMaxWidth().height(3.dp).background(Memphis.Ink))
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp, bottom = 14.dp)) {
                    MemphisSurface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onDismiss() },
                        color = Memphis.Ink,
                        borderWidth = 3.dp,
                        shadowOffset = 5.dp,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .padding(vertical = 13.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(
                                text = "GOT IT",
                                style = posterStyle(17f, Memphis.Paper),
                            )
                        }
                    }

                    Spacer(Modifier.height(9.dp))

                    Text(
                        text = "You can read this again from the Agents room any time.",
                        style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.6f)),
                        textAlign = TextAlign.Center,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
        }
    }
}

/**
 * One point in the panel: a coloured square, a heading and a paragraph.
 *
 * The marks come from the same rotation the lobby uses, so the panel looks like
 * a page from the same hand rather than a list of bullet points.
 */
@Composable
private fun IntroLine(
    mark: Color,
    title: String,
    body: String,
    last: Boolean = false,
) {
    Row(Modifier.fillMaxWidth()) {
        Box(
            Modifier
                .padding(top = 3.dp)
                .size(13.dp)
                .background(mark),
        )
        Spacer(Modifier.width(10.dp))
        Column(Modifier.fillMaxWidth()) {
            Text(
                text = title,
                style = captionStyle(9.5f),
            )
            Spacer(Modifier.height(3.dp))
            Text(
                text = body,
                style = androidx.compose.ui.text.TextStyle(
                    fontFamily = com.unuslumen.app.ui.theme.MemphisBody,
                    fontSize = androidx.compose.ui.unit.TextUnit(12.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                    lineHeight = androidx.compose.ui.unit.TextUnit(17.5f, androidx.compose.ui.unit.TextUnitType.Sp),
                    color = Memphis.Ink,
                ),
            )
            if (!last) Spacer(Modifier.height(12.dp))
        }
    }
}
