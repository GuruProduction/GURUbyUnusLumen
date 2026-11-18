// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * THE HOUSE KIT.
 *
 * One file holds the shapes every screen in the app is built from, so a form on the
 * settings page, a form in the calendar, a form in the agents room and a form in a
 * room GURU builds for itself are the same object in the same hand. The lobby is the
 * reference and the rules come from it: flat saturated colour, a hard ink edge, a
 * printed drop, poster type, nothing rounded, nothing that glides or glows.
 *
 * WHY THIS EXISTS RATHER THAN EACH SCREEN DRAWING ITS OWN. Before it there were
 * 235 rounded corners and 48 soft elevated cards spread across the tree, each one a
 * small separate decision, which is exactly why the app read as several products
 * stitched together. A screen now names the object it wants instead of describing it,
 * and changing how every form in the app looks means changing one file.
 *
 * EVERY PIECE TAKES ITS COLOUR FROM THE SAME ROTATION the lobby uses, so a page of
 * coloured blocks reads as composed rather than as a wash of one accent.
 */

/** The body face at a readable size, in the house voice. */
val MemphisBodyText: TextStyle = TextStyle(
    fontFamily = MemphisBody,
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
    color = Memphis.Ink,
)

/**
 * The house sheet: a full-screen scrim, an ink-edged paper page, a fixed masthead with
 * a rule, a scrolling body and a fixed foot.
 *
 * This is the geometry the lobby's add-a-room, the heartbeat's booking sheet, the
 * project creator and the permission gate all use. A new form gets it by calling this
 * rather than by rebuilding a dialog, which is what stops a second dialog grammar
 * appearing again the way it did before.
 *
 * @param title the masthead word, set in the poster face at full size
 * @param subtitle one line under it, in the caption voice, or null
 * @param accent the colour of the stamp in the corner and the filled action
 */
@Composable
fun MemphisSheet(
    title: String,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
    subtitle: String? = null,
    accent: Color = Memphis.Yellow,
    maxHeight: Dp = 660.dp,
    footer: @Composable RowScope.() -> Unit,
    body: @Composable ColumnScope.() -> Unit,
) {
    Box(
        modifier
            .fillMaxSize()
            .background(Memphis.Ink.copy(alpha = 0.45f))
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
                .padding(horizontal = 16.dp)
                .heightIn(max = maxHeight)
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
                Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 15.dp)) {
                    Text(
                        text = title.uppercase(),
                        style = posterStyle(24f),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    if (subtitle != null) {
                        Spacer(Modifier.height(5.dp))
                        Text(
                            text = subtitle.uppercase(),
                            style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                        )
                    }
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .padding(top = 8.dp)
                            .height(3.dp)
                            .background(Memphis.Ink),
                    )
                }

                Column(
                    Modifier
                        .heightIn(max = maxHeight - 150.dp)
                        .verticalScroll(rememberScrollState())
                        .padding(start = 16.dp, end = 16.dp, top = 4.dp),
                    content = body,
                )

                Box(Modifier.fillMaxWidth().padding(top = 6.dp).height(3.dp).background(Memphis.Ink))
                Row(
                    Modifier.fillMaxWidth().padding(14.dp),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    content = footer,
                )
            }
        }
    }
}

/**
 * A labelled field, drawn as a printed box on paper.
 *
 * The label sits above the box in the caption voice, the box takes a hard ink edge and
 * a printed drop, and the input has no underline, no ripple and no floating
 * placeholder. An empty field shows the placeholder inside the box, which is where a
 * printed form puts it.
 */
@Composable
fun MemphisField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    minHeight: Dp = 46.dp,
    singleLine: Boolean = false,
    note: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.55f)),
            modifier = Modifier.padding(top = 12.dp, bottom = 5.dp),
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
                    .heightIn(min = minHeight)
                    .padding(11.dp),
            ) {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(
                        text = placeholder,
                        style = MemphisBodyText.copy(color = Memphis.Ink.copy(alpha = 0.4f)),
                    )
                }
                BasicTextField(
                    value = value,
                    onValueChange = onChange,
                    textStyle = MemphisBodyText,
                    singleLine = singleLine,
                    cursorBrush = SolidColor(Memphis.Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        if (note != null) {
            Text(
                text = note,
                style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.5f)),
                modifier = Modifier.padding(top = 5.dp),
            )
        }
    }
}

/**
 * A switch in the page's grammar.
 *
 * A rectangle that fills with its own colour when it is on, and stays paper when it
 * is off. A Material switch is a soft grey capsule with a raised thumb, which beside a
 * hard black outline reads as a different product; this one is the same block as every
 * other object on the page, so a row of them reads as a page of switches.
 */
@Composable
fun MemphisSwitch(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    ink: Color = Memphis.Ink,
    width: Dp = 46.dp,
    height: Dp = 26.dp,
) {
    val fill = when {
        !enabled -> Memphis.Ink.copy(alpha = 0.12f)
        checked -> ink
        else -> Memphis.Paper
    }

    Box(
        modifier = modifier
            .size(width = width, height = height)
            .background(fill)
            .border(2.5.dp, Memphis.Ink.copy(alpha = if (enabled) 1f else 0.35f))
            .then(if (enabled) Modifier.clickable { onChange(!checked) } else Modifier),
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(height - 8.dp)
                .background(
                    if (checked) Memphis.Paper else Memphis.Ink.copy(alpha = if (enabled) 1f else 0.35f)
                ),
        )
    }
}

/** A square that fills with ink when chosen, for a checkbox. */
@Composable
fun MemphisCheckbox(
    checked: Boolean,
    onChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    ink: Color = Memphis.Ink,
    side: Dp = 22.dp,
) {
    Box(
        modifier = modifier
            .size(side)
            .background(if (checked) ink else Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .clickable { onChange(!checked) },
        contentAlignment = Alignment.Center,
    ) {
        if (checked) {
            Text("×", style = posterStyle(side.value * 0.6f, Memphis.Paper))
        }
    }
}

/**
 * One choice on a row, printed.
 *
 * The chosen one fills with its colour and carries ink type; the rest sit on paper.
 * Which is in force reads before a single word does, which is the whole reason the
 * lobby's chips work.
 */
@Composable
fun RowScope.MemphisChoice(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    ink: Color = Memphis.Ink,
    fill: Boolean = true,
) {
    Box(
        Modifier
            .weight(1f)
            .background(
                when {
                    selected && fill -> ink
                    else -> Memphis.Paper
                }
            )
            .border(2.5.dp, Memphis.Ink)
            .clickable { onClick() }
            .padding(vertical = 9.dp, horizontal = 2.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = captionStyle(7.5f, if (selected && fill) Memphis.Paper else Memphis.Ink),
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** A choice that stands on its own rather than sharing a row. */
@Composable
fun MemphisPill(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    ink: Color = Memphis.Ink,
) {
    Box(
        modifier
            .background(if (selected) ink else Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .clickable { onClick() }
            .padding(horizontal = 10.dp, vertical = 6.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = label.uppercase(),
            style = captionStyle(8.5f, if (selected) Memphis.Paper else Memphis.Ink),
            maxLines = 1,
        )
    }
}

/**
 * The one action a sheet exists to offer, or the way out of it.
 *
 * A filled button is the commit and takes an accent colour; an outline is the door. The
 * geometry is the same in both cases so a footer of two reads as one row rather than as
 * two different kinds of object.
 */
@Composable
fun RowScope.MemphisSheetButton(
    label: String,
    onClick: () -> Unit,
    filled: Boolean = false,
    enabled: Boolean = true,
    ink: Color = Memphis.Yellow,
) {
    MemphisSurface(
        modifier = Modifier
            .weight(1f)
            .then(if (enabled) Modifier.clickable { onClick() } else Modifier),
        color = if (filled && enabled) ink else Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 12.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                text = label.uppercase(),
                style = captionStyle(
                    9.5f,
                    when {
                        !enabled -> Memphis.Ink.copy(alpha = 0.35f)
                        filled -> Memphis.Ink
                        else -> Memphis.Ink
                    },
                ),
                textAlign = TextAlign.Center,
            )
        }
    }
}

/** A full-width printed bar, for the one action a page exists to offer. */
@Composable
fun MemphisBarButton(
    label: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    filled: Boolean = true,
    ink: Color = Memphis.Ink,
) {
    MemphisSurface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() },
        color = if (filled) ink else Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(label.uppercase(), style = posterStyle(13f, if (filled) Memphis.Paper else Memphis.Ink))
        }
    }
}

/** A black band that separates one part of a page from the next, with an optional count. */
@Composable
fun MemphisBand(
    label: String,
    modifier: Modifier = Modifier,
    note: String? = null,
    ink: Color = Memphis.Ink,
) {
    Row(
        modifier
            .fillMaxWidth()
            .background(ink)
            .padding(horizontal = 10.dp, vertical = 7.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = captionStyle(9.5f, Memphis.Paper))
        if (note != null) {
            Text(
                text = note.uppercase(),
                style = captionStyle(8f, Memphis.Ink),
                modifier = Modifier
                    .background(Memphis.Paper)
                    .padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
    }
}

/** A short message on a coloured block, for a notice or a fault. */
@Composable
fun MemphisNotice(
    message: String,
    modifier: Modifier = Modifier,
    ink: Color = Memphis.Teal,
) {
    MemphisSurface(
        modifier = modifier.fillMaxWidth(),
        color = ink,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Text(
            text = message,
            style = captionStyle(9f, Memphis.Ink),
            modifier = Modifier.fillMaxWidth().padding(11.dp),
        )
    }
}

/** Small spaced capitals, for a label above a group. */
@Composable
fun MemphisLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
        modifier = modifier,
    )
}

/** A colour swatch you can press, for a picker. */
@Composable
fun MemphisSwatch(
    colour: Color,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    side: Dp = 34.dp,
) {
    Box(
        modifier = modifier
            .size(side)
            .background(colour)
            .border(if (selected) 3.dp else 2.dp, Memphis.Ink)
            .clickable { onClick() },
        contentAlignment = Alignment.Center,
    ) {
        if (selected) {
            Text("×", style = posterStyle(side.value * 0.42f))
        }
    }
}

/** An empty state: a word, a line and the one thing to do next. */
@Composable
fun MemphisEmpty(
    title: String,
    line: String,
    modifier: Modifier = Modifier,
    action: (@Composable () -> Unit)? = null,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(28.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text(title.uppercase(), style = posterStyle(18f), textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            text = line,
            style = MemphisBodyText.copy(color = Memphis.Ink.copy(alpha = 0.6f)),
            textAlign = TextAlign.Center,
        )
        if (action != null) {
            Spacer(Modifier.height(16.dp))
            action()
        }
    }
}

/** A section heading on a page: a colour block, a word and a rule. */
@Composable
fun MemphisSectionHead(
    label: String,
    modifier: Modifier = Modifier,
    ink: Color = Memphis.Cyan,
) {
    Column(modifier.fillMaxWidth().padding(top = 14.dp, bottom = 8.dp)) {
        Text(
            text = label.uppercase(),
            style = posterStyle(16f),
        )
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 5.dp)
                .height(3.dp)
                .background(ink),
        )
    }
}
