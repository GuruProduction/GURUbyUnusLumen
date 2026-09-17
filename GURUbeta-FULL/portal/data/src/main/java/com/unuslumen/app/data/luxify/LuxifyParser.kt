package com.unuslumen.app.data.luxify

import com.unuslumen.app.domain.model.LuxifySkill

data class ParsedSkill(
    val name: String,
    val description: String,
    val whenToUse: String,
    val allowedTools: List<String>,
    val bodyMarkdown: String
)

object LuxifyParser {

    private val FRONTMATTER_REGEX = Regex("""^---\s*\n(.*?)\n---\s*\n(.*)""", RegexOption.DOT_MATCHES_ALL)

    fun parse(skillMdContent: String): ParsedSkill? {
        val match = FRONTMATTER_REGEX.find(skillMdContent) ?: return null

        val frontmatter = match.groupValues[1]
        val body = match.groupValues[2].trim()

        val fields = parseFrontmatter(frontmatter)

        val name = fields["name"] ?: return null
        val description = fields["description"] ?: ""
        val whenToUse = fields["when_to_use"] ?: ""
        val allowedTools = parseListField(fields["allowed-tools"])

        return ParsedSkill(
            name = name.trim(),
            description = description.trim(),
            whenToUse = whenToUse.trim(),
            allowedTools = allowedTools,
            bodyMarkdown = body
        )
    }

    private fun parseFrontmatter(frontmatter: String): Map<String, String> {
        val fields = mutableMapOf<String, String>()
        val lines = frontmatter.split("\n")

        var currentKey: String? = null
        var currentValue = StringBuilder()
        var inMultiline = false

        for (line in lines) {
            val trimmed = line.trim()

            if (inMultiline) {
                if (trimmed.isNotEmpty() && !trimmed.startsWith(" ") && !trimmed.startsWith("-") && containsKeyValue(trimmed)) {
                    val key = currentKey
                    if (key != null) {
                        fields[key] = currentValue.toString().trim()
                    }
                    inMultiline = false
                    currentKey = null
                    currentValue = StringBuilder()
                    processKeyValueLine(trimmed, fields)
                } else {
                    currentValue.append(line).append("\n")
                }
                continue
            }

            processKeyValueLine(trimmed, fields)
        }

        if (currentKey != null && currentValue.isNotEmpty()) {
            fields[currentKey] = currentValue.toString().trim()
        }

        return fields
    }

    private fun processKeyValueLine(line: String, fields: MutableMap<String, String>) {
        if (line.isEmpty() || line.startsWith("#")) return

        val colonIndex = line.indexOf(":")
        if (colonIndex == -1) return

        val key = line.substring(0, colonIndex).trim()
        val value = line.substring(colonIndex + 1).trim()

        if (value.isEmpty()) {
            currentKey = key
            currentValue = StringBuilder()
            inMultiline = true
        } else {
            fields[key] = stripQuotes(value)
        }
    }

    private var currentKey: String? = null
    private var currentValue = StringBuilder()
    private var inMultiline = false

    private fun containsKeyValue(line: String): Boolean {
        val colonIndex = line.indexOf(":")
        return colonIndex > 0 && !line.substring(0, colonIndex).contains(" ")
    }

    private fun stripQuotes(value: String): String {
        val trimmed = value.trim()
        if ((trimmed.startsWith("\"") && trimmed.endsWith("\"")) ||
            (trimmed.startsWith("'") && trimmed.endsWith("'"))) {
            return trimmed.substring(1, trimmed.length - 1)
        }
        return trimmed
    }

    private fun parseListField(value: String?): List<String> {
        if (value.isNullOrBlank()) return emptyList()
        return value.split(",")
            .map { it.trim() }
            .filter { it.isNotEmpty() }
            .map { stripQuotes(it) }
    }

    fun toSkillMarkdown(skill: LuxifySkill): String {
        val toolsList = skill.allowedTools.joinToString(", ")
        return buildString {
            appendLine("---")
            appendLine("name: ${skill.name}")
            appendLine("description: ${skill.description}")
            appendLine("when_to_use: ${skill.whenToUse}")
            appendLine("allowed-tools: $toolsList")
            appendLine("---")
            appendLine()
            append(skill.bodyMarkdown)
        }
    }
}