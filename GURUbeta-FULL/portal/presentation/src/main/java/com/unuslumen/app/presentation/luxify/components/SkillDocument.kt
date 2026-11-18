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
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBodyText
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle

/**
 * A single step inside a rendered skill document.
 *
 * @property title The step name.
 * @property description What the step does.
 * @property successCriteria What proves the step is done.
 */
data class SkillStepData(
    val title: String,
    val description: String,
    val successCriteria: String
)

/**
 * Final skill summary rendered as a native interactive document.
 *
 * @property name Skill identifier/name.
 * @property description What the skill does.
 * @property whenToUse When the skill should auto-invoke.
 * @property allowedTools List of tool names the skill may use.
 * @property steps Numbered steps the skill performs.
 */
data class SkillDocumentData(
    val name: String,
    val description: String,
    val whenToUse: String,
    val allowedTools: List<String>,
    val steps: List<SkillStepData>
)

/**
 * The skill document a person confirms before it is saved, in the house language.
 *
 * WHAT IT WAS. A rounded 20dp Material card with a soft elevation, a primary-coloured
 * section label, lavender tool badges, grey step cards, an outlined Back button and a
 * filled primary Confirm. Every one of those shapes is the one this pass removes.
 *
 * NOW. A paper page with a hard ink edge and a printed drop, an ink band per section, the
 * skill's own colour from the lobby's rotation as the masthead, printed tags for the tools
 * and numbered printed blocks for the steps.
 *
 * Both callbacks are unchanged: Back keeps editing, Confirm saves.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillDocument(
    skill: SkillDocumentData,
    onConfirm: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val ink = Memphis.inkForName(skill.name)

    MemphisSurface(
        modifier = modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 3.dp,
        shadowOffset = 6.dp,
    ) {
        Column(Modifier.fillMaxWidth()) {
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(ink)
                    .border(2.5.dp, Memphis.Ink)
                    .padding(horizontal = 14.dp, vertical = 12.dp)
            ) {
                Text(
                    text = skill.name.uppercase(),
                    style = posterStyle(20f),
                )
            }

            Column(Modifier.fillMaxWidth().padding(14.dp)) {
                if (skill.description.isNotBlank()) {
                    Text(text = skill.description, style = MemphisBodyText)
                }

                Band("When to use")
                Text(text = skill.whenToUse, style = MemphisBodyText)

                if (skill.allowedTools.isNotEmpty()) {
                    Band("Tools", "${skill.allowedTools.size}")
                    FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(6.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        skill.allowedTools.forEach { tool ->
                            Text(
                                text = tool,
                                style = captionStyle(7.5f),
                                modifier = Modifier
                                    .background(Memphis.Paper)
                                    .border(2.dp, Memphis.Ink)
                                    .padding(horizontal = 6.dp, vertical = 3.dp),
                            )
                        }
                    }
                }

                if (skill.steps.isNotEmpty()) {
                    Band("Steps", "${skill.steps.size}")
                    skill.steps.forEachIndexed { index, step ->
                        StepCard(number = index + 1, step = step, ink = Memphis.inkFor(index))
                        Spacer(Modifier.height(9.dp))
                    }
                }

                Spacer(Modifier.height(10.dp))
                Box(Modifier.fillMaxWidth().height(3.dp).background(Memphis.Ink))
                Spacer(Modifier.height(12.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MemphisSurface(
                        modifier = Modifier.weight(1f),
                        color = Memphis.Paper,
                        borderWidth = 2.5.dp,
                        shadowOffset = 4.dp,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickableNoRipple(onBack)
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("BACK", style = captionStyle(9.5f))
                        }
                    }
                    MemphisSurface(
                        modifier = Modifier.weight(1f),
                        color = Memphis.Yellow,
                        borderWidth = 2.5.dp,
                        shadowOffset = 4.dp,
                    ) {
                        Box(
                            Modifier
                                .fillMaxWidth()
                                .clickableNoRipple(onConfirm)
                                .padding(vertical = 12.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text("CONFIRM", style = captionStyle(9.5f))
                        }
                    }
                }
            }
        }
    }
}

/** A small helper so both buttons share one press behaviour. */
private fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier =
    this.clickable(onClick = onClick)

/** A section rule: an ink bar with the label in paper type. */
@Composable
private fun Band(label: String, note: String? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(top = 12.dp, bottom = 7.dp)
            .background(Memphis.Ink)
            .padding(horizontal = 9.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label.uppercase(), style = captionStyle(9f, Memphis.Paper))
        if (note != null) {
            Text(
                text = note,
                style = captionStyle(8f, Memphis.Ink),
                modifier = Modifier.background(Memphis.Paper).padding(horizontal = 5.dp),
            )
        }
    }
}

@Composable
private fun StepCard(
    number: Int,
    step: SkillStepData,
    ink: androidx.compose.ui.graphics.Color,
    modifier: Modifier = Modifier
) {
    MemphisSurface(
        modifier = modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 3.dp,
    ) {
        Column(Modifier.fillMaxWidth().padding(11.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier
                        .background(ink)
                        .border(2.dp, Memphis.Ink)
                        .padding(horizontal = 9.dp, vertical = 3.dp)
                ) {
                    Text(number.toString(), style = posterStyle(12f))
                }
                Spacer(Modifier.width(8.dp))
                Text(
                    text = step.title.uppercase(),
                    style = posterStyle(12f),
                )
            }

            if (step.description.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(text = step.description, style = MemphisBodyText)
            }

            if (step.successCriteria.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = "DONE WHEN: ${step.successCriteria}",
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.6f))
                )
            }
        }
    }
}
