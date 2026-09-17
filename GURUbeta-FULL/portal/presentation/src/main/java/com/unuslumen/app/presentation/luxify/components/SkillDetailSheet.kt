package com.unuslumen.app.presentation.luxify.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.unuslumen.app.domain.model.LuxifySkill

@Composable
fun SkillDetailSheet(
    skill: LuxifySkill,
    modifier: Modifier = Modifier
) {
    val scrollState = rememberScrollState()

    Column(
        modifier = modifier
            .fillMaxWidth()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp, vertical = 16.dp)
    ) {
        Text(
            text = skill.name,
            style = MaterialTheme.typography.headlineSmall.copy(
                fontWeight = FontWeight.Bold
            ),
            color = MaterialTheme.colorScheme.onSurface
        )

        Spacer(Modifier.size(8.dp))

        Text(
            text = skill.description,
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        if (skill.whenToUse.isNotBlank()) {
            Spacer(Modifier.size(16.dp))
            SectionLabel("When to use")
            Spacer(Modifier.size(4.dp))
            Card(
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                ),
                elevation = CardDefaults.cardElevation(0.dp)
            ) {
                Text(
                    text = skill.whenToUse,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(12.dp)
                )
            }
        }

        if (skill.allowedTools.isNotEmpty()) {
            Spacer(Modifier.size(16.dp))
            SectionLabel("Tools")
            Spacer(Modifier.size(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                skill.allowedTools.forEach { tool ->
                    Card(
                        shape = RoundedCornerShape(8.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                        elevation = CardDefaults.cardElevation(0.dp)
                    ) {
                        Text(
                            text = tool,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                        )
                    }
                }
            }
        }

        if (skill.bodyMarkdown.isNotBlank()) {
            Spacer(Modifier.size(16.dp))
            SectionLabel("Full skill document")
            Spacer(Modifier.size(4.dp))
            HorizontalDivider(
                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f),
                thickness = 0.5.dp
            )
            Spacer(Modifier.size(12.dp))

            val sections = parseMarkdownSections(skill.bodyMarkdown)
            sections.forEach { section ->
                SkillSectionCard(section)
                Spacer(Modifier.size(12.dp))
            }
        }

        Spacer(Modifier.size(8.dp))
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Source: ${skill.source}",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
            )
            if (skill.source == "bundled") {
                Text(
                    text = "Bundled skills can be toggled but not deleted",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.labelLarge.copy(
            fontWeight = FontWeight.SemiBold
        ),
        color = MaterialTheme.colorScheme.primary
    )
}

@Composable
private fun SkillSectionCard(section: MarkdownSection) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)
        ),
        elevation = CardDefaults.cardElevation(0.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(
            modifier = Modifier.padding(14.dp)
        ) {
            Text(
                text = section.title,
                style = MaterialTheme.typography.titleSmall.copy(
                    fontWeight = FontWeight.Bold
                ),
                color = MaterialTheme.colorScheme.onSurface
            )
            if (section.content.isNotBlank()) {
                Spacer(Modifier.size(6.dp))
                Text(
                    text = section.content,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
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