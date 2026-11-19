// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.portal

/**
 * The YAML frontmatter a portal block may carry, parsed once for every surface
 * that renders one (issue 479).
 *
 * WHY THIS EXISTS AS A PURE OBJECT. Two parsers had grown up for the same
 * format, one in `FlatMessage` for the Compose renderer and one in `ChatToHtml`
 * for the canvas renderer, and they had drifted. The canvas one understood YAML
 * block scalars; the Compose one read the `|` as the value, so a portal written
 * in the documented format rendered with a single pipe character as its CSS and
 * its JS. The same message therefore looked correct in one renderer and dead in
 * the other, and which one a person saw depended on where they were looking.
 *
 * THE DOCUMENTED EXAMPLE IS THE CONTRACT. The system prompt tells every agent to
 * write:
 *
 * ```
 * ---
 * css: |
 *   .card { padding: 16px; }
 * js: |
 *   console.log("hi");
 * height: 400
 * ---
 * <div class="card">Hello</div>
 * ```
 *
 * so a block scalar has to work, and a quote-stripping rule written for
 * single-line values must not be applied to a block whose content legitimately
 * begins and ends with a quote character.
 */
object PortalFrontMatter {

    /** One parsed portal block: the body, and whatever the frontmatter declared. */
    data class Parsed(
        val html: String,
        val css: String = "",
        val js: String = "",
        val height: Int? = null,
        val interactive: Boolean = true,
    )

    private val FRONT_MATTER =
        Regex("""^---\s*\n(.*?)\n---\s*\n(.*)""", RegexOption.DOT_MATCHES_ALL)

    /**
     * Parse a portal body: an optional frontmatter block, then the HTML.
     *
     * A body with no frontmatter is all HTML, which is a legitimate portal.
     */
    fun parse(body: String): Parsed {
        val trimmedBody = body.trim()
        val match = FRONT_MATTER.find(trimmedBody)
            ?: return Parsed(html = trimmedBody)

        val frontMatter = match.groupValues[1]
        val html = match.groupValues[2].trim()

        var css = ""
        var js = ""
        var height: Int? = null
        var interactive = true

        val lines = frontMatter.lines()
        var index = 0
        while (index < lines.size) {
            val line = lines[index]
            val trimmed = line.trim()

            // A blank line is skipped at the top level. Inside a block scalar it
            // is content, and the block reader below is what reaches it.
            if (trimmed.isEmpty()) {
                index++
                continue
            }

            val key = trimmed.substringBefore(':', "").trim().lowercase()
            val inline = trimmed.substringAfter(':', "").trim()

            when (key) {
                "css", "js" -> {
                    val block = readBlockScalar(inline, lines, index)
                    if (block != null) {
                        val (value, next) = block
                        if (key == "css") css = value else js = value
                        index = next
                        continue
                    }
                    val value = unquote(inline)
                    if (key == "css") css = value else js = value
                }
                "height" -> height = inline.toIntOrNull()
                "interactive" -> interactive = inline.lowercase() == "true"
            }
            index++
        }

        return Parsed(html = html, css = css, js = js, height = height, interactive = interactive)
    }

    /**
     * Read a block scalar if the value is a block indicator, else return null.
     *
     * `|` keeps newlines, `>` folds them, and the `-`/`+` chomping hints are
     * accepted. Everything after the indicator on the same line is a comment in
     * YAML and is ignored, which is why `| # the styles` reads as a plain block.
     *
     * THE BASELINE IS THE FIRST CONTENT LINE'S INDENTATION, not a fixed two
     * spaces. A block indented by four, or by a tab, is the same shape, and a
     * fixed width meant a block written at any other width either lost its
     * leading whitespace or kept it.
     *
     * The block ends at the first line that is neither blank nor indented to at
     * least that baseline, which is what stops `height:` being swallowed by the
     * css above it.
     */
    private fun readBlockScalar(
        inlineValue: String,
        lines: List<String>,
        keyIndex: Int,
    ): Pair<String, Int>? {
        val indicator = inlineValue.substringBefore(' ').substringBefore('\t')
        val isLiteral = indicator.startsWith("|")
        val isFolded = indicator.startsWith(">")
        if (!isLiteral && !isFolded) return null

        var cursor = keyIndex + 1
        // Blank lines between the indicator and the content belong to the block.
        val collected = mutableListOf<String>()
        var baseline = -1

        while (cursor < lines.size) {
            val raw = lines[cursor]
            if (raw.isBlank()) {
                collected.add("")
                cursor++
                continue
            }
            val indent = raw.indexOfFirst { !it.isWhitespace() }
            if (indent < 0) {
                collected.add("")
                cursor++
                continue
            }
            if (baseline < 0) baseline = indent
            if (indent < baseline) break
            collected.add(raw.drop(baseline))
            cursor++
        }

        // Trailing blank lines are the separator from the next key, not content.
        while (collected.isNotEmpty() && collected.last().isBlank()) collected.removeAt(collected.size - 1)

        val value = if (isFolded) {
            // A folded scalar joins wrapped lines with spaces and keeps a single
            // newline for each run of blank lines, which is YAML's rule and what
            // `>` means to a person who wrote it.
            buildString {
                var blankRun = 0
                collected.forEach { text ->
                    if (text.isBlank()) {
                        blankRun++
                    } else {
                        if (blankRun > 0) append("\n".repeat(blankRun)) else if (isNotEmpty()) append(' ')
                        append(text)
                        blankRun = 0
                    }
                }
            }
        } else {
            collected.joinToString("\n")
        }

        return value to cursor
    }

    /**
     * Strip one matching pair of surrounding double quotes.
     *
     * ONLY FOR A SINGLE-LINE VALUE. A block scalar is raw CSS or JS, and either
     * can legitimately begin and end with a quote character; stripping it there
     * would quietly change the program.
     */
    private fun unquote(value: String): String =
        if (value.length >= 2 && value.first() == '"' && value.last() == '"') {
            value.substring(1, value.length - 1)
        } else {
            value
        }
}
