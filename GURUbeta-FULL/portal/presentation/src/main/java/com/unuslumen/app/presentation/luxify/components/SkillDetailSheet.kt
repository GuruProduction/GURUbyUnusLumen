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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.LuxifySkill
import com.unuslumen.app.ui.theme.Memphis
import com.unuslumen.app.ui.theme.MemphisBodyText
import com.unuslumen.app.ui.theme.MemphisSurface
import com.unuslumen.app.ui.theme.captionStyle
import com.unuslumen.app.ui.theme.posterStyle

/**
 * One skill in full, in the house language.
 *
 * WHAT IT WAS. A Material sheet: rounded 12dp cards, a soft lavender tool pill cloud, a
 * primary-coloured section label, a hairline divider and body text in the Material type
 * ramp. The list behind it had already been converted, so opening a skill dropped the
 * reader back into the old product, which is the exact fault the theme pass exists to
 * remove.
 *
 * WHAT IT IS NOW. The same page as the list: poster headings, ink bands, paper cards with
 * hard edges and printed drops, and tools as printed tags. The skill's own colour comes
 * from the same rotation the lobby uses, keyed on its name, so a skill keeps its colour
 * between visits.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SkillDetailSheet(
    skill: LuxifySkill,
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()
    val ink = Memphis.inkForName(skill.name)

    Column(
        modifier = modifier
            .fillMaxWidth()
    ) {
        // THE DOOR OUT, ABOVE THE PAGE.
        //
        // A sheet with no visible way out is a trap: swiping it down and tapping the
        // scrim are both gestures a person has to guess at, and neither looks like a
        // door. This is the same printed BACK block every room wears, in the same
        // place, so leaving a skill reads exactly like leaving a room.
        Row(
            Modifier
                .fillMaxWidth()
                .padding(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 8.dp),
        ) {
            MemphisSurface(
                modifier = Modifier.clickable { onBack() },
                color = Memphis.Paper,
                borderWidth = 2.5.dp,
                shadowOffset = 4.dp,
            ) {
                Row(
                    Modifier.padding(start = 9.dp, end = 11.dp, top = 6.dp, bottom = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("‹", style = posterStyle(15f))
                    Spacer(Modifier.width(6.dp))
                    Text("BACK", style = captionStyle(9.5f))
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(scrollState)
                .padding(bottom = 12.dp)
        ) {
        // Masthead: the skill's own colour as a block, its name in the poster face.
        Box(
            Modifier
                .fillMaxWidth()
                .background(ink)
                .border(2.5.dp, Memphis.Ink)
                .padding(horizontal = 14.dp, vertical = 12.dp)
        ) {
            Column {
                Text(
                    text = skill.name.uppercase(),
                    style = posterStyle(20f),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    text = skill.source.uppercase(),
                    style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.6f)),
                )
            }
        }

        Spacer(Modifier.height(10.dp))

        Column(Modifier.padding(horizontal = 16.dp)) {
            if (skill.description.isNotBlank()) {
                MemphisSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Memphis.Paper,
                    borderWidth = 2.5.dp,
                    shadowOffset = 3.dp,
                ) {
                    Text(
                        text = skill.description,
                        style = MemphisBodyText,
                        modifier = Modifier.padding(11.dp),
                    )
                }
            }

            if (skill.whenToUse.isNotBlank()) {
                Band("When to use")
                MemphisSurface(
                    modifier = Modifier.fillMaxWidth(),
                    color = Memphis.Paper,
                    borderWidth = 2.5.dp,
                    shadowOffset = 3.dp,
                ) {
                    Text(
                        text = skill.whenToUse,
                        style = MemphisBodyText,
                        modifier = Modifier.padding(11.dp),
                    )
                }
            }

            if (skill.allowedTools.isNotEmpty()) {
                Band("Tools", "${skill.allowedTools.size}")
                FlowRow(
                    Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
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

            if (skill.bodyMarkdown.isNotBlank()) {
                Band("Full skill document")
                val sections = parseMarkdownSections(skill.bodyMarkdown)
                sections.forEach { section ->
                    SkillSectionCard(section, ink)
                    Spacer(Modifier.height(9.dp))
                }
            }

            Spacer(Modifier.height(8.dp))
            Text(
                text = if (skill.source == "bundled") {
                    "BUNDLED SKILLS CAN BE SWITCHED OFF BUT NOT DELETED."
                } else {
                    "SOURCE: ${skill.source.uppercase()}"
                },
                style = captionStyle(8f, Memphis.Ink.copy(alpha = 0.5f)),
            )
        }
        }
    }
}

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
                modifier = Modifier
                    .background(Memphis.Paper)
                    .padding(horizontal = 5.dp),
            )
        }
    }
}

@Composable
private fun SkillSectionCard(section: MarkdownSection, ink: androidx.compose.ui.graphics.Color) {
    MemphisSurface(
        modifier = Modifier.fillMaxWidth(),
        color = Memphis.Paper,
        borderWidth = 2.5.dp,
        shadowOffset = 3.dp,
    ) {
        Column(Modifier.padding(12.dp)) {
            if (section.title.isNotBlank()) {
                Text(
                    text = section.title.uppercase(),
                    style = posterStyle(12f),
                )
                Box(
                    Modifier
                        .fillMaxWidth()
                        .padding(top = 5.dp, bottom = 6.dp)
                        .height(2.5.dp)
                        .background(ink),
                )
            }
            if (section.content.isNotBlank()) {
                Text(
                    text = section.content,
                    style = MemphisBodyText,
                )
            }
        }
    }
}

private data class MarkdownSection(
    val title: String,
    val content: String
)

private fun parseMarkdownSections(markdown: String): List<MarkdownSection> {
    val sections = mutableListOf<MarkdownSection>()
    val lines = markdown.lines()
    var currentTitle = ""
    var currentContent = StringBuilder()

    for (line in lines) {
        val trimmed = line.trim()
        if (trimmed.startsWith("#") && trimmed.length > 1) {
            if (currentTitle.isNotEmpty() || currentContent.isNotEmpty()) {
                sections.add(MarkdownSection(currentTitle, currentContent.toString().trim()))
            }
            currentTitle = trimmed.removePrefix("#").trim()
            currentContent = StringBuilder()
        } else if (trimmed.startsWith("##") && trimmed.length > 2) {
            if (currentTitle.isNotEmpty() || currentContent.isNotEmpty()) {
                sections.add(MarkdownSection(currentTitle, currentContent.toString().trim()))
            }
            currentTitle = trimmed.removePrefix("#").trim()
            currentContent = StringBuilder()
        } else {
            if (trimmed.isNotEmpty()) {
                if (currentContent.isNotEmpty()) currentContent.append("\n")
                currentContent.append(trimmed)
            }
        }
    }

    if (currentTitle.isNotEmpty() || currentContent.isNotEmpty()) {
        sections.add(MarkdownSection(currentTitle, currentContent.toString().trim()))
    }

    return sections
}
