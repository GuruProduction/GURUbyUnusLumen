// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBody
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle

/**
 * Settings, in the room theme.
 *
 * WHAT THIS REPLACED, AND WHY IT MATTERED. Settings was the last page still wearing
 * the old language: rounded 20dp cards, a cream vertical gradient, hairline gold
 * borders and gold monospace accents. Beside the lobby and the rooms it read as a
 * different product that happened to share a background, which is exactly the thing
 * the redesign set out to end.
 *
 * NOW IT IS THE SAME PRINTED PAGE. Paper ground, hard ink outlines, a solid printed
 * drop instead of an elevation, poster capitals for section keys, and a switch drawn
 * as a block that fills with ink rather than a Material capsule. Nothing glows, floats
 * or rounds.
 *
 * THE NAMES ARE KEPT. Every token and composable below keeps the name the rest of the
 * settings screens already call, so this is a change of appearance rather than a
 * rewrite of six call sites. [SettingsGold] survives as an alias for the ink because
 * a stale name in a call site is a smaller problem than a half-converted page.
 */

// ─── Tokens, kept under their old names so call sites do not move ───
val SettingsInk = Memphis.Ink
val SettingsInkSoft = Memphis.Ink.copy(alpha = 0.72f)
val SettingsInkFaint = Memphis.Ink.copy(alpha = 0.5f)
val SettingsPaperHigh = Memphis.Paper
val SettingsPaperLow = Memphis.Paper
val SettingsPaperField = Memphis.Paper

/** Formerly a gold accent. In this language the accent IS the ink. */
val SettingsGold = Memphis.Ink
val SettingsGoldDeep = Memphis.Ink
val SettingsSlate = Memphis.Teal

/**
 * The settings page brush, fully transparent.
 *
 * Settings used to paint its own cream wash, which fought the one shared background
 * every other screen inherits. The token is kept because call sites still reference
 * it, and an alpha of zero is the honest statement: paint nothing.
 */
val SettingsPageBrush = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color.Transparent)
)

/** One printed block: paper, a hard ink edge and a solid offset drop. */
@Composable
fun SettingsSectionCard(
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    MemphisSurface(
        modifier = modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Column(Modifier.fillMaxWidth()) { content() }
    }
}

/** A section key: small, tracked-out capitals, in ink. */
@Composable
fun SettingsSectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = captionStyle(9.5f, Memphis.Ink.copy(alpha = 0.65f)),
        modifier = modifier,
    )
}

/**
 * One settings row. The icon sits in its own square block rather than a soft chip,
 * because a soft chip is the shape this language removed.
 */
@Composable
fun SettingsRow(
    title: String,
    modifier: Modifier = Modifier,
    caption: String? = null,
    icon: Painter? = null,
    trailing: @Composable (() -> Unit)? = null,
    onClick: (() -> Unit)? = null,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .then(if (onClick != null) Modifier.clickable { onClick() } else Modifier)
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Box(
                modifier = Modifier
                    .size(32.dp)
                    .background(Memphis.Paper)
                    .border(2.dp, Memphis.Ink),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    painter = icon,
                    contentDescription = title,
                    modifier = Modifier.size(18.dp),
                    tint = Memphis.Ink,
                )
            }
            Spacer(Modifier.width(11.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                text = title,
                style = rowTitle(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (caption != null) {
                Text(
                    text = caption,
                    style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.55f)),
                )
            }
        }
        if (trailing != null) {
            Spacer(Modifier.width(8.dp))
            trailing()
        }
    }
}

/**
 * A row whose trailing is a dropdown.
 *
 * The menu is a printed panel rather than a Material popup: the same paper and ink as
 * everything behind it, so opening a picker does not drop a different product on top
 * of the page for one moment.
 */
@Composable
fun SettingsDropdownRow(
    title: String,
    selectedLabel: String,
    options: List<Pair<String, () -> Unit>>,
    modifier: Modifier = Modifier,
    caption: String? = null,
    icon: Painter? = null,
) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        SettingsRow(
            title = title,
            caption = caption,
            icon = icon,
            trailing = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = selectedLabel.uppercase(),
                        style = captionStyle(8.5f, Memphis.Ink),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Spacer(Modifier.width(6.dp))
                    Text(text = "▾", style = posterStyle(11f))
                }
            },
            onClick = { expanded = true },
        )
        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            modifier = Modifier
                .heightIn(max = 320.dp)
                .background(Memphis.Paper)
                .border(2.5.dp, Memphis.Ink),
        ) {
            options.forEach { (label, action) ->
                DropdownMenuItem(
                    text = {
                        Text(
                            text = label.uppercase(),
                            style = captionStyle(9f, Memphis.Ink),
                        )
                    },
                    onClick = {
                        action()
                        expanded = false
                    },
                )
            }
        }
    }
}

/** A printed field: paper, hard ink edge, printed drop. */
@Composable
fun SettingsTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
) {
    Column(modifier.fillMaxWidth()) {
        Text(
            text = label.uppercase(),
            style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.6f)),
            modifier = Modifier.padding(start = 2.dp, bottom = 5.dp),
        )
        MemphisSurface(
            modifier = Modifier.fillMaxWidth(),
            color = Memphis.Paper,
            borderWidth = 2.5.dp,
            shadowOffset = 3.dp,
        ) {
            Box(Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 11.dp)) {
                if (value.isEmpty() && placeholder != null) {
                    Text(
                        text = placeholder,
                        style = rowTitle().copy(color = Memphis.Ink.copy(alpha = 0.4f)),
                    )
                }
                androidx.compose.foundation.text.BasicTextField(
                    value = value,
                    onValueChange = onValueChange,
                    singleLine = singleLine,
                    textStyle = rowTitle(),
                    cursorBrush = SolidColor(Memphis.Ink),
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
    }
}

/** The primary action of a block: solid ink, paper text. */
@Composable
fun SettingsPrimaryButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    MemphisSurface(
        modifier = modifier.fillMaxWidth().clickable { onClick() },
        color = Memphis.Ink,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Box(
            Modifier.fillMaxWidth().padding(vertical = 13.dp),
            contentAlignment = Alignment.Center,
        ) {
            Text(text.uppercase(), style = captionStyle(9.5f, Memphis.Paper))
        }
    }
}

/** A nav link row: icon in a printed square, name beside it. */
@Composable
fun SettingsNavLinkRow(
    title: String,
    icon: Painter,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onClick() }
            .padding(horizontal = 12.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(32.dp)
                .background(Memphis.Paper)
                .border(2.dp, Memphis.Ink),
            contentAlignment = Alignment.Center,
        ) {
            Icon(
                painter = icon,
                contentDescription = title,
                modifier = Modifier.size(18.dp),
                tint = Memphis.Ink,
            )
        }
        Spacer(Modifier.width(11.dp))
        Text(
            text = title,
            style = rowTitle(),
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        Text(text = "›", style = posterStyle(14f))
    }
}

/**
 * A switch in the page's grammar.
 *
 * A Material switch is a soft grey capsule with a raised thumb, which reads as a
 * different product beside a hard ink outline. This is a rectangle that fills with ink
 * when it is on, at the same border weight as every other block on the page.
 */
@Composable
fun SettingsSwitchRow(
    title: String,
    checked: Boolean,
    onCheck: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    caption: String? = null,
    icon: Painter? = null,
) {
    SettingsRow(
        title = title,
        caption = caption,
        icon = icon,
        modifier = modifier,
        trailing = { PrintedSwitch(checked = checked, onChange = onCheck) },
        onClick = { onCheck(!checked) },
    )
}

@Composable
private fun PrintedSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        Modifier
            .size(width = 44.dp, height = 25.dp)
            .background(if (checked) Memphis.Ink else Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(17.dp)
                .background(if (checked) Memphis.Paper else Memphis.Ink),
        )
    }
}

/** The rule between rows: a hard ink hairline, not a soft one. */
@Composable
fun SettingsRowDivider() {
    Box(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp)
            .height(1.5.dp)
            .background(Memphis.Ink.copy(alpha = 0.18f))
    )
}

/** The working sans at a readable size, in the house voice. */
private fun rowTitle(): TextStyle = TextStyle(
    fontFamily = MemphisBody,
    fontSize = 12.5.sp,
    lineHeight = 17.sp,
    color = Memphis.Ink,
)
