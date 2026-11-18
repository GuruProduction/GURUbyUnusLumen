// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.presentation.luxify.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.LuxifySkill
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle

/**
 * One skill as a ruled row: its colour, its name, what it does, the tools it may use, and a
 * switch.
 *
 * THIS REPLACED A ROUNDED MATERIAL CARD. The card was cream with a blurred elevation, lavender
 * tool pills and a grey switch, which read as a settings list that had wandered into a poster.
 * A skill is now the same block as an agent: paper, a hard ink edge, a printed drop, and a
 * swatch in the room's own colour, so the two lists in the app cannot disagree about how a
 * thing on GURU is drawn.
 *
 * THE COLOUR COMES FROM THE NAME, not the position, because skills are loaded in whatever order
 * the repository returns and a skill that changed colour between visits would look broken.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillCard(
    skill: LuxifySkill,
    onToggle: (Boolean) -> Unit,
    onTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    MemphisSurface(
        modifier = modifier
            .fillMaxWidth()
            .clickable { onTap() },
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 4.dp,
    ) {
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 11.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The swatch carries the skill's colour, from the same rotation the lobby uses.
            Box(Modifier.size(22.dp).background(Memphis.inkForName(skill.name)))

            Column(
                Modifier
                    .weight(1f)
                    .padding(start = 10.dp),
            ) {
                Text(
                    text = skill.name.uppercase(),
                    style = posterStyle(15f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )

                if (skill.description.isNotBlank()) {
                    Text(
                        text = skill.description,
                        style = captionStyle(8.5f, Memphis.Ink.copy(alpha = 0.6f)).copy(
                            letterSpacing = TextUnit.Unspecified,
                        ),
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }

                if (skill.allowedTools.isNotEmpty()) {
                    FlowRow(
                        modifier = Modifier.padding(top = 6.dp),
                        horizontalArrangement = Arrangement.spacedBy(5.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        skill.allowedTools.take(4).forEach { tool ->
                            ToolTag(tool = tool)
                        }
                        if (skill.allowedTools.size > 4) {
                            ToolTag(tool = "+${skill.allowedTools.size - 4}")
                        }
                    }
                }
            }

            Spacer(Modifier.width(10.dp))

            // The switch, flat and printed. No Material pill, no grey.
            FlatSwitch(checked = skill.enabled, onChange = onToggle)
        }
    }
}

/**
 * A tool name set as a small printed tag.
 *
 * Small enough for five to sit under a description, so it takes the lighter 1.5dp rule that
 * marks the page's minor shapes rather than the 2.5dp edge of a block. It is a rectangle with
 * an ink outline and no fill, because a tool is a note about the skill and not another thing
 * to press.
 */
@Composable
private fun ToolTag(tool: String) {
    Box(
        Modifier
            .border(1.5.dp, Memphis.Ink)
            .padding(horizontal = 5.dp, vertical = 2.dp),
    ) {
        Text(
            text = tool.uppercase(),
            style = captionStyle(7.5f, Memphis.Ink.copy(alpha = 0.75f)),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/**
 * A switch in the page's own grammar.
 *
 * A Material switch is a soft grey capsule with a raised thumb, which reads as a different
 * product beside a hard black outline. This is a rectangle that fills with ink when it is on,
 * with the same border weight as every other block.
 */
@Composable
private fun FlatSwitch(checked: Boolean, onChange: (Boolean) -> Unit) {
    Box(
        modifier = Modifier
            .size(width = 42.dp, height = 24.dp)
            .background(if (checked) Memphis.Ink else Memphis.Paper)
            .border(2.5.dp, Memphis.Ink)
            .clickable { onChange(!checked) },
        contentAlignment = if (checked) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        Box(
            Modifier
                .padding(horizontal = 2.dp)
                .size(16.dp)
                .background(if (checked) Memphis.Paper else Memphis.Ink),
        )
    }
}
