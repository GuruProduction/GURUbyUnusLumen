package com.unuslumen.app.presentation.components

import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.domain.model.AiMessageAttachment
import com.unuslumen.app.domain.model.ToolCallResultObject

/**
 * Converts AiMessage objects to HTML strings for rendering inside the PortalCanvas.
 * Each message type becomes an HTML segment that gets appended to the canvas.
 *
 * Markdown rendering happens in the browser via marked.js.
 * Syntax highlighting via highlight.js.
 * Math via KaTeX.
 * Diagrams via Mermaid.
 *
 * During streaming, partial content is rendered as plain text (no markdown)
 * for speed. On finalization, full markdown processing is applied.
 */
object ChatToHtml {

    var userDisplayName: String = "You"
    var chatConfig: ChatTextConfig = ChatTextConfig.DEFAULT

    private const val DEFAULT_GURU_FONT = "Georgia, 'Times New Roman', serif"
    private const val DEFAULT_USER_FONT = "Inter, -apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, sans-serif"
    private const val MONO_FONT = "'Courier New', monospace"

    private fun guruFont(): String = chatConfig.guruFont ?: DEFAULT_GURU_FONT
    private fun userFont(): String = chatConfig.userFont ?: DEFAULT_USER_FONT
    private fun guruColour(): String? = chatConfig.guruColour
    private fun userColour(): String? = chatConfig.userColour
    private fun guruScale(): String =
        if (chatConfig.guruFontScale != null && chatConfig.guruFontScale != 1.0f)
            "font-size: ${chatConfig.guruFontScale}em;"
        else ""
    private fun userScale(): String =
        if (chatConfig.userFontScale != null && chatConfig.userFontScale != 1.0f)
            "font-size: ${chatConfig.userFontScale}em;"
        else ""

    /**
     * Convert any AiMessage to its HTML representation for the canvas.
     */
    fun toHtml(message: AiMessage): String = when (message) {
        is AiMessage.UserMessage -> userMessageToHtml(message)
        is AiMessage.AssistantMessage -> assistantMessageToHtml(message)
        is AiMessage.StreamingAssistant -> streamingAssistantToHtml(message)
        is AiMessage.StreamingToolCall -> streamingToolCallToHtml(message)
        is AiMessage.ToolCall -> toolCallToHtml(message)
        is AiMessage.PortalMessage -> portalMessageToHtml(message)
    }

    /**
     * Get the message type class for the canvas DOM element.
     */
    fun messageType(message: AiMessage): String = when (message) {
        is AiMessage.UserMessage -> "user"
        is AiMessage.AssistantMessage -> "assistant" + outcomeClass(message.content)
        is AiMessage.StreamingAssistant -> "assistant streaming"
        is AiMessage.StreamingToolCall -> "toolcall streaming"
        is AiMessage.ToolCall -> "toolcall"
        is AiMessage.PortalMessage -> "portal-content"
    }

    /**
     * Compute the final message type for a finalised assistant message.
     * Used by PortalScreen when calling finalizeMessage so the outcome class
     * is computed from the complete text, not from stale streaming frames.
     */
    fun finalAssistantType(content: String): String = "assistant" + outcomeClass(content)

    private val failureKeywords = listOf(
        "failed", "error", "couldn't", "can't", "unable to", "didn't work",
        "went wrong", "mistake", "sorry", "apolog", "bug", "crash", "broken",
        "not working", "couldn't complete", "something went wrong"
    )

    private val successKeywords = listOf(
        "done", "completed", "success", "successfully", "sorted", "finished",
        "confirmed", "created", "updated", "deleted", "sent", "saved", "set"
    )

    private fun outcomeClass(content: String): String {
        val lower = content.lowercase()
        if (failureKeywords.any { lower.contains(it) }) return " outcome-failed"
        if (successKeywords.any { lower.contains(it) }) return " outcome-success"
        return ""
    }

    /**
     * User message: styled div with markdown content and attachments.
     */
    private fun userMessageToHtml(message: AiMessage.UserMessage): String {
        val content = escapeHtml(message.content)
        val attachmentsHtml = if (message.attachments.isNotEmpty()) {
            // Two or more visual thumbnails collapse into a compact grid that
            // stays together as one block; a single thumb renders big and alone
            val visualCount = message.attachments.count { isVisualAttachment(it) }
            val gridClass = if (visualCount >= 2) " attachments-grid attachments-grid-$visualCount" else ""
            "<div class=\"attachments$gridClass\">" + message.attachments.joinToString("") { attachmentToHtml(it) } + "</div>"
        } else ""

        val resolvedUserColour = userColour() ?: ""
        val resolvedFont = userFont()
        val scaleCss = userScale()
        val label = userDisplayName.ifBlank { "YOU" }

        return """
            ${metaRowHtml(label, message.time, "right")}
            <div class="markdown-content user-text" style="font-family: '$resolvedFont'; color: $resolvedUserColour; $scaleCss">$content</div>
            $attachmentsHtml
        """.trimIndent()
    }

    /**
     * Assistant message (final): full markdown rendering, thinking blocks, portal content.
     */
    private fun assistantMessageToHtml(message: AiMessage.AssistantMessage): String {
        val thinkingHtml = if (message.thinkingTokens.isNotBlank()) {
            "<details class=\"thinking-block\"><summary>Thinking...</summary><div class=\"markdown-content\">${escapeHtml(message.thinkingTokens)}</div></details>"
        } else ""

        // Extract portal content from the message
        val portalContent = extractPortalContent(message.content)
        var markdownContent = stripPortalContent(message.content)
        // Strip interview markers so raw tags and JSON never appear in the canvas
        markdownContent = stripLuxifyMarkers(markdownContent)

        val resolvedGuruColour = guruColour() ?: ""
        val resolvedFont = guruFont()
        val scaleCss = guruScale()

        val contentHtml = if (markdownContent.isNotBlank()) {
            "<div class=\"markdown-content\" style=\"font-family: '$resolvedFont'; color: $resolvedGuruColour; $scaleCss\">${escapeHtml(markdownContent).replace("\n", "&#10;")}</div>"
        } else ""

        val portalHtml = if (portalContent != null) {
            val cssTag = if (portalContent.css.isNotBlank()) "<style>${portalContent.css}</style>" else ""
            val jsTag = if (portalContent.js.isNotBlank()) "<script>${portalContent.js}</script>" else ""
            "<div class=\"portal-content\">${cssTag}${portalContent.html}${jsTag}</div>"
        } else ""

        // If there is no visible content at all (no text, no thinking, no portal),
        // return empty string so no DOM element gets created for this message.
        if (contentHtml.isBlank() && thinkingHtml.isBlank() && portalHtml.isBlank()) {
            return ""
        }

        return """
            ${metaRowHtml("GURU", message.time, "left", message.uuid)}
            $thinkingHtml
            $contentHtml
            $portalHtml
        """.trimIndent()
    }

    /**
     * Streaming assistant: raw text with cursor, no markdown processing.
     * The canvas handles the lightweight text insertion via updateInProgress.
     */
    private fun streamingAssistantToHtml(message: AiMessage.StreamingAssistant): String {
        val thinkingHtml = if (message.partialThinking.isNotBlank()) {
            "<details class=\"thinking-block\"><summary>Thinking...</summary><div class=\"thinking-content\">${escapeHtml(message.partialThinking)}</div></details>"
        } else ""

        val cleanContent = stripLuxifyMarkers(message.partialContent)
        val resolvedFont = guruFont()
        val resolvedGuruColour = guruColour() ?: ""
        val scaleCss = guruScale()

        return """
            ${metaRowHtml("GURU", message.time, "left", message.uuid)}
            $thinkingHtml
            <div class="streaming-content" style="font-family: '$resolvedFont'; color: $resolvedGuruColour; $scaleCss">${escapeHtml(cleanContent)}</div>
            <span class="streaming-cursor"></span>
        """.trimIndent()
    }

    /**
     * Streaming tool call: shows the tool being selected in real time.
     */
    private fun streamingToolCallToHtml(message: AiMessage.StreamingToolCall): String {
        val displayName = toolDisplayName(message.toolName)
        return """
            <div class="status-dot running"></div>
            <span>$displayName</span>
            <span style="opacity:0.5">${escapeHtml(message.partialContent.take(60))}</span>
        """.trimIndent()
    }

    /**
     * Tool call (complete): shows tool name, status, result preview, clickable.
     */
    private fun toolCallToHtml(message: AiMessage.ToolCall): String {
        val displayName = toolDisplayName(message.name)
        val statusClass = if (message.isFailed) "failed" else "done"
        val resultPreview = if (message.resultRawContent.isNotBlank()) {
            formatResultPreview(message.resultRawContent, 80)
        } else ""

        val resultCardsHtml = message.resultObject?.let { resultObjectToHtml(it) } ?: ""

        return """
            <div class="toolcall-trigger" data-uuid="${message.uuid}">
                <div class="status-dot $statusClass"></div>
                <span>$displayName</span>
                ${if (resultPreview.isNotBlank()) "<span style=\"opacity:0.5\">$resultPreview</span>" else ""}
            </div>
            $resultCardsHtml
        """.trimIndent()
    }

    /**
     * Portal message: raw HTML/CSS/JS rendered directly.
     */
    private fun portalMessageToHtml(message: AiMessage.PortalMessage): String {
        val css = if (message.css.isNotBlank()) "<style>${message.css}</style>" else ""
        val js = if (message.js.isNotBlank()) "<script>${message.js}</script>" else ""
        return "$css${message.html}$js"
    }

    /**
     * Convert tool call result objects to HTML cards with click handlers.
     */
    private fun resultObjectToHtml(result: ToolCallResultObject): String = when (result) {
        is ToolCallResultObject.Notes -> result.notes.joinToString("") { note ->
            "<div class=\"result-card\" data-note-id=\"${note.id}\"><div class=\"card-title\">${escapeHtml(note.title)}</div></div>"
        }

        is ToolCallResultObject.Tasks -> result.tasks.joinToString("") { task ->
            "<div class=\"result-card\" data-task-id=\"${task.id}\"><div class=\"card-title\">${escapeHtml(task.title)}</div>${if (task.description.isNotBlank()) "<div class=\"card-subtitle\">${escapeHtml(task.description.take(80))}</div>" else ""}</div>"
        }

        is ToolCallResultObject.CalendarEvents -> result.events.joinToString("") { event ->
            "<div class=\"result-card\" data-event-id=\"${event.id}\"><div class=\"card-title\">${escapeHtml(event.title)}</div></div>"
        }

        is ToolCallResultObject.WebResults -> result.results.joinToString("") { item ->
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(item.title)}</div><div class=\"card-subtitle\">${escapeHtml(item.snippet.take(100))}</div></div>"
        }

        is ToolCallResultObject.Portal -> {
            val css = if (result.css.isNotBlank()) "<style>${result.css}</style>" else ""
            val js = if (result.js.isNotBlank()) "<script>${result.js}</script>" else ""
            "<div class=\"portal-content\">$css${result.html}$js</div>"
        }

        is ToolCallResultObject.MemoryFacts -> result.facts.joinToString("") { fact ->
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(fact)}</div></div>"
        }

        is ToolCallResultObject.Plans -> result.plans.joinToString("") { plan ->
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(plan.title)}</div><div class=\"card-subtitle\">${plan.completedSteps}/${plan.stepCount} steps</div></div>"
        }

        is ToolCallResultObject.Bookmarks -> result.bookmarks.joinToString("") { bookmark ->
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(bookmark.title)}</div><div class=\"card-subtitle\">${escapeHtml(bookmark.url.take(60))}</div></div>"
        }

        is ToolCallResultObject.JournalEntries -> result.entries.joinToString("") { entry ->
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(entry.title)}</div></div>"
        }

        is ToolCallResultObject.Alarms -> result.alarms.joinToString("") { alarm ->
            "<div class=\"result-card\"><div class=\"card-title\">Alarm</div></div>"
        }

        is ToolCallResultObject.Settings -> result.settings.entries.joinToString("") { (key, value) ->
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(key)}</div><div class=\"card-subtitle\">${escapeHtml(value)}</div></div>"
        }

        is ToolCallResultObject.FileResults -> result.files.joinToString("") { file ->
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(file)}</div></div>"
        }

        is ToolCallResultObject.Sound -> {
            val info = result.soundInfo
            val title = when (info.type) {
                "volume" -> "Volume"
                "ringer_mode" -> "Ringer Mode"
                "speak" -> "Text-to-Speech"
                "vibrate" -> "Vibration"
                "ringtone" -> "Ringtone"
                else -> "Sound"
            }
            "<div class=\"result-card\"><div class=\"card-title\">$title</div>${info.error?.let { "<div class=\"card-subtitle\">${escapeHtml(it)}</div>" } ?: ""}</div>"
        }

        is ToolCallResultObject.Media -> {
            val info = result.mediaInfo
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(info.title ?: info.type)}</div>${info.error?.let { "<div class=\"card-subtitle\">${escapeHtml(it)}</div>" } ?: ""}</div>"
        }

        is ToolCallResultObject.SmartHome -> {
            val info = result.homeInfo
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(info.device ?: info.type)}</div>${info.error?.let { "<div class=\"card-subtitle\">${escapeHtml(it)}</div>" } ?: ""}</div>"
        }

        is ToolCallResultObject.Weather -> {
            val info = result.weatherInfo
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(info.location)}</div>" +
            (info.temperature?.let { "<div class=\"card-subtitle\">${it}°C ${info.condition ?: ""}</div>" } ?: "") +
            (info.error?.let { "<div class=\"card-subtitle\">${escapeHtml(it)}</div>" } ?: "") + "</div>"
        }

        is ToolCallResultObject.Places -> result.places.joinToString("") { place ->
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(place.name)}</div><div class=\"card-subtitle\">${escapeHtml(place.address)}</div></div>"
        }

        is ToolCallResultObject.GitHub -> {
            val info = result.githubInfo
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(info.title ?: info.type)}</div>" +
            (info.error?.let { "<div class=\"card-subtitle\">${escapeHtml(it)}</div>" } ?: "") + "</div>"
        }

        is ToolCallResultObject.Trello -> {
            val info = result.trelloInfo
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(info.name ?: info.type)}</div></div>"
        }

        is ToolCallResultObject.Notion -> {
            val info = result.notionInfo
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(info.title ?: info.type)}</div></div>"
        }

        is ToolCallResultObject.Voice -> {
            val info = result.voiceInfo
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(info.text?.take(60) ?: info.type)}</div></div>"
        }

        is ToolCallResultObject.Communication -> {
            val info = result.commInfo
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(info.type)}</div>" +
            (info.error?.let { "<div class=\"card-subtitle\">${escapeHtml(it)}</div>" } ?: "") + "</div>"
        }

        is ToolCallResultObject.System -> {
            val info = result.systemInfo
            "<div class=\"result-card\"><div class=\"card-title\">${info.type}</div>" +
            (info.error?.let { "<div class=\"card-subtitle\">${escapeHtml(it)}</div>" } ?: "") + "</div>"
        }

        is ToolCallResultObject.Email -> {
            val info = result.emailInfo
            "<div class=\"result-card\"><div class=\"card-title\">${info.type}</div>" +
            (info.error?.let { "<div class=\"card-subtitle\">${escapeHtml(it)}</div>" } ?: "") + "</div>"
        }

        is ToolCallResultObject.Camera -> {
            val info = result.cameraInfo
            "<div class=\"result-card\"><div class=\"card-title\">${info.type}</div>" +
            (info.error?.let { "<div class=\"card-subtitle\">${escapeHtml(it)}</div>" } ?: "") + "</div>"
        }

        is ToolCallResultObject.ClosePortal -> ""

        is ToolCallResultObject.SkillLoaded -> {
            if (result.success) {
                val toolCount = result.toolsAllowed.size
                val toolPlural = if (toolCount == 1) "tool" else "tools"
                """
                <div class="skill-loaded">
                    <div class="skill-loaded-icon">&#9889;</div>
                    <div class="skill-loaded-content">
                        <div class="skill-loaded-name">${escapeHtml(result.skillName)}</div>
                        <div class="skill-loaded-status">Successfully loaded skill &middot; $toolCount $toolPlural allowed</div>
                    </div>
                </div>
                """.trimIndent()
            } else {
                """
                <div class="skill-loaded skill-loaded-failed">
                    <div class="skill-loaded-icon">&#9888;</div>
                    <div class="skill-loaded-content">
                        <div class="skill-loaded-name">${escapeHtml(result.skillName)}</div>
                        <div class="skill-loaded-status">Failed to load${result.error?.let { ": ${escapeHtml(it)}" } ?: ""}</div>
                    </div>
                </div>
                """.trimIndent()
            }
        }

        is ToolCallResultObject.ToolResults -> result.results.joinToString("") { tr ->
            "<div class=\"result-card\"><div class=\"card-title\">${escapeHtml(tr.toolName)}</div></div>"
        }
    }

    /**
     * Get the message type class for a grouped tool-call card.
     * The DOM container gets `class="message toolcall-group"` so new CSS rules can
     * target the grouped card styling without touching the old `.message.toolcall`
     * CSS used by the legacy single-call renderer.
     */
    fun groupedMessageType(): String = "toolcall-group"

    /**
     * Render a run of tool-call messages (completed ToolCalls + in-flight
     * StreamingToolCalls) as ONE grouped inline expandable box. No matter what
     * tool call it is, no matter how they arrived, if more than one tool call
     * happens in succession they all live inside this single card.
     *
     * Tier 1 (collapsed card — default): Header with English summary from
     * `summariseGroup()` (only counts completed ToolCalls), overall status
     * indicator, count badge when more than one call, and arrow. Tap to expand.
     *
     * Tier 2 (card expanded, rows collapsed): Each row = one tool call (either
     * completed ToolCall or in-flight StreamingToolCall). Shows category icon
     * slot, status dot, display name, short preview. Row header tap expands the
     * row.
     *
     * Tier 3 (row expanded, full verbose): Scrollable card with the full verbose
     * output for that one tool call. Connected to the row header by a faint
     * vertical line. Tap the header again to collapse back to tier 2.
     */
    fun groupedToHtml(run: List<AiMessage>, isStreaming: Boolean): String {
        if (run.isEmpty()) return ""

        // Filter to just the tool-call-shaped messages we know how to render.
        val items = run.mapNotNull { msg ->
            when (msg) {
                is AiMessage.ToolCall -> {
                    val running = isStreaming && msg.resultRawContent.isBlank() && msg.resultObject == null
                    GroupedRowItem(
                        uuid = msg.uuid,
                        name = msg.name,
                        displayName = toolDisplayName(msg.name),
                        category = toolCategory(msg.name),
                        isRunning = running,
                        isFailed = msg.isFailed,
                        resultObject = msg.resultObject,
                        resultRawContent = msg.resultRawContent,
                        shortPreview = when {
                            msg.isFailed -> "Failed"
                            running -> "Running…"
                            msg.resultRawContent.isNotBlank() -> escapeHtml(formatResultPreview(msg.resultRawContent, 100))
                            else -> "Completed"
                        },
                    )
                }
                is AiMessage.StreamingToolCall -> GroupedRowItem(
                    uuid = msg.uuid,
                    name = msg.toolName,
                    displayName = toolDisplayName(msg.toolName),
                    category = toolCategory(msg.toolName),
                    isRunning = true,
                    isFailed = false,
                    resultObject = null,
                    resultRawContent = "",
                    shortPreview = if (msg.partialContent.isNotBlank()) escapeHtml(msg.partialContent.take(80)) else "Starting…",
                )
                else -> null
            }
        }

        if (items.isEmpty()) return ""

        // Summary is built from completed ToolCalls only. If none are done yet
        // (everything still streaming), fall back to a generic running phrase so
        // the header reads naturally while calls are in flight.
        val completedToolCalls = run.filterIsInstance<AiMessage.ToolCall>()
        val summary = if (completedToolCalls.isNotEmpty()) {
            summariseGroup(completedToolCalls)
        } else {
            val displayNames = items.map { it.displayName }
            when (displayNames.distinct().size) {
                1 -> "${displayNames[0]}…"
                2 -> "${displayNames[0]} and ${displayNames[1]}…"
                else -> "${displayNames[0]}, ${displayNames[1]}, and ${displayNames.size - 2} more…"
            }
        }

        val anyRunning = items.any { it.isRunning }
        val anyFailed = items.any { it.isFailed }
        val statusClass = when {
            anyRunning -> "running"
            anyFailed -> "failed"
            else -> "done"
        }
        val countBadge = if (items.size > 1) {
            "<span class=\"grouped-count-badge\">${items.size}</span>"
        } else ""

        val rowsHtml = items.joinToString("") { item ->
            val rowStatus = when {
                item.isFailed -> "failed"
                item.isRunning -> "running"
                else -> "done"
            }
            val verboseContent = when {
                item.isRunning -> "<div class=\"row-verbose-running\">Executing…</div>"
                item.resultObject != null -> expandedResultObjectToHtml(item.resultObject)
                item.resultRawContent.isNotBlank() ->
                    "<div class=\"row-verbose-raw\">${escapeHtml(formatResultPreview(item.resultRawContent, 500))}</div>"
                else -> "<div class=\"row-verbose-empty\">No result data</div>"
            }
            """
            <div class="grouped-tool-row" data-uuid="${escapeHtml(item.uuid)}">
                <div class="row-header">
                    <div class="grouped-row-icon" data-category="${escapeHtml(item.category)}"></div>
                    <div class="grouped-row-status $rowStatus"></div>
                    <span class="row-name">${escapeHtml(item.displayName)}</span>
                    <span class="row-preview">${item.shortPreview}</span>
                    <span class="row-arrow">▾</span>
                </div>
                <div class="row-deep-body">
                    <div class="row-deep-body-inner">
                        $verboseContent
                    </div>
                </div>
            </div>
            """.trimIndent()
        }

        return """
            <div class="toolcall-group-card $statusClass">
                <div class="toolcall-group-header">
                    <div class="grouped-card-status $statusClass"></div>
                    <span class="grouped-summary">${escapeHtml(summary)}</span>
                    $countBadge
                    <span class="grouped-expand-arrow">▾</span>
                </div>
                <div class="grouped-list-body">
                    $rowsHtml
                </div>
            </div>
        """.trimIndent()
    }

    private data class GroupedRowItem(
        val uuid: String,
        val name: String,
        val displayName: String,
        val category: String,
        val isRunning: Boolean,
        val isFailed: Boolean,
        val resultObject: ToolCallResultObject?,
        val resultRawContent: String,
        val shortPreview: String,
    )

    /**
     * Render the FULL content of a tool result object for the expanded state.
     * Distinct from `resultObjectToHtml` which produces truncated cards for
     * the legacy single-call renderer. That function stays untouched.
     */
    private fun expandedResultObjectToHtml(result: ToolCallResultObject): String = when (result) {
        is ToolCallResultObject.Notes -> result.notes.joinToString("") { note ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(note.title) + "</div>" +
            (note.content.takeIf { it.isNotBlank() }?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Tasks -> result.tasks.joinToString("") { task ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(task.title) + "</div>" +
            (task.description.takeIf { it.isNotBlank() }?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            (task.subTasks.takeIf { it.isNotEmpty() }?.joinToString("") { sub ->
                "<div class=\"expanded-result-subtask\">" + escapeHtml(sub.title) + "</div>"
            } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.CalendarEvents -> result.events.joinToString("") { event ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(event.title) + "</div>" +
            (event.location?.takeIf { it.isNotBlank() }?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            (event.description?.takeIf { it.isNotBlank() }?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.JournalEntries -> result.entries.joinToString("") { entry ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(entry.title) + "</div>" +
            (entry.content.takeIf { it.isNotBlank() }?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Bookmarks -> result.bookmarks.joinToString("") { bookmark ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(bookmark.title) + "</div>" +
            "<div class=\"expanded-result-body\">" + escapeHtml(bookmark.url) + "</div>" +
            (bookmark.description.takeIf { it.isNotBlank() }?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.MemoryFacts -> result.facts.joinToString("") { fact ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-body\">" + escapeHtml(fact) + "</div>" +
            "</div>"
        }

        is ToolCallResultObject.Plans -> result.plans.joinToString("") { plan ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(plan.title) + "</div>" +
            "<div class=\"expanded-result-body\">" + plan.completedSteps + " / " + plan.stepCount + " steps complete</div>" +
            "</div>"
        }

        is ToolCallResultObject.WebResults -> result.results.joinToString("") { item ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(item.title) + "</div>" +
            "<div class=\"expanded-result-body\">" + escapeHtml(item.url) + "</div>" +
            "<div class=\"expanded-result-body\">" + escapeHtml(item.snippet) + "</div>" +
            "</div>"
        }

        is ToolCallResultObject.Alarms -> result.alarms.joinToString("") { alarm ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">Alarm</div>" +
            "</div>"
        }

        is ToolCallResultObject.Settings -> result.settings.entries.joinToString("") { (key, value) ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(key) + "</div>" +
            "<div class=\"expanded-result-body\">" + escapeHtml(value) + "</div>" +
            "</div>"
        }

        is ToolCallResultObject.FileResults -> result.files.joinToString("") { file ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(file) + "</div>" +
            "</div>"
        }

        is ToolCallResultObject.Sound -> {
            val info = result.soundInfo
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">Sound</div>" +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Media -> {
            val info = result.mediaInfo
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(info.title ?: info.type) + "</div>" +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.SmartHome -> {
            val info = result.homeInfo
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(info.device ?: info.type) + "</div>" +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Weather -> {
            val info = result.weatherInfo
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(info.location) + "</div>" +
            (info.temperature?.let { "<div class=\"expanded-result-body\">" + it + "°C " + escapeHtml(info.condition ?: "") + "</div>" } ?: "") +
            (info.humidity?.let { "<div class=\"expanded-result-body\">Humidity: " + it + "%</div>" } ?: "") +
            (info.wind?.let { "<div class=\"expanded-result-body\">Wind: " + it + " m/s</div>" } ?: "") +
            (info.forecast.joinToString("") { day ->
                "<div class=\"expanded-result-subtask\">" + day.date + ": " + day.maxTemp + "° / " + day.minTemp + "° — " + escapeHtml(day.condition) + "</div>"
            }) +
            (info.alerts.joinToString("") { alert ->
                "<div class=\"expanded-result-subtask\">" + escapeHtml(alert.severity) + ": " + escapeHtml(alert.headline) + "</div>"
            }) +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Places -> result.places.joinToString("") { place ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(place.name) + "</div>" +
            "<div class=\"expanded-result-body\">" + escapeHtml(place.address) + "</div>" +
            (place.rating?.let { "<div class=\"expanded-result-body\">Rating: " + it + "</div>" } ?: "") +
            (place.isOpen?.let { "<div class=\"expanded-result-body\">" + (if (it) "Open now" else "Closed") + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.GitHub -> {
            val info = result.githubInfo
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(info.title ?: info.type) + "</div>" +
            (info.url?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            (info.items.joinToString("") { item ->
                "<div class=\"expanded-result-subtask\">" + escapeHtml(item.title) + " — " + escapeHtml(item.state) + "</div>"
            }) +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Trello -> {
            val info = result.trelloInfo
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(info.name ?: info.type) + "</div>" +
            (info.items.joinToString("") { item ->
                "<div class=\"expanded-result-subtask\">" + escapeHtml(item.name) + " — " + escapeHtml(item.type) + "</div>"
            }) +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Notion -> {
            val info = result.notionInfo
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(info.title ?: info.type) + "</div>" +
            (info.content?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            (info.items.joinToString("") { item ->
                "<div class=\"expanded-result-subtask\">" + escapeHtml(item.title) + "</div>"
            }) +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Voice -> {
            val info = result.voiceInfo
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">Voice</div>" +
            (info.text?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Communication -> {
            val info = result.commInfo
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(info.type) + "</div>" +
            (info.platform?.let { "<div class=\"expanded-result-body\">Platform: " + escapeHtml(it) + "</div>" } ?: "") +
            (info.recipient?.let { "<div class=\"expanded-result-body\">To: " + escapeHtml(it) + "</div>" } ?: "") +
            (info.status?.let { "<div class=\"expanded-result-body\">Status: " + escapeHtml(it) + "</div>" } ?: "") +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.System -> {
            val info = result.systemInfo
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + info.type + "</div>" +
            (info.battery?.let { b ->
                "<div class=\"expanded-result-body\">Battery: " + b.level + "% (" + escapeHtml(b.status) + ", " + b.temperature + "°)</div>"
            } ?: "") +
            (info.storage?.let { s ->
                "<div class=\"expanded-result-body\">Storage: " + s.percentUsed + "% used</div>"
            } ?: "") +
            (info.memory?.let { m ->
                "<div class=\"expanded-result-body\">Memory: " + m.percentUsed + "% used</div>"
            } ?: "") +
            (info.device?.let { d ->
                "<div class=\"expanded-result-body\">" + escapeHtml(d.manufacturer) + " " + escapeHtml(d.model) + " (Android " + escapeHtml(d.androidVersion) + ")</div>"
            } ?: "") +
            (info.security?.let { sec ->
                "<div class=\"expanded-result-body\">" + (if (sec.isSecure) "Secure" else "Issues found") + "</div>" +
                sec.issues.joinToString("") { i -> "<div class=\"expanded-result-subtask\">" + escapeHtml(i) + "</div>" }
            } ?: "") +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Email -> {
            val info = result.emailInfo
            val emails = info.emails.joinToString("") { email ->
                "<div class=\"expanded-result-subtask\">" +
                "<strong>" + escapeHtml(email.subject) + "</strong><br>" +
                "From: " + escapeHtml(email.from) + "<br>" +
                escapeHtml(email.preview) +
                "</div>"
            }
            val detail = info.email?.let { d ->
                "<div class=\"expanded-result-subtask\">" +
                "<strong>" + escapeHtml(d.subject) + "</strong><br>" +
                "From: " + escapeHtml(d.from) + " → To: " + escapeHtml(d.to) + "<br>" +
                escapeHtml(d.body) +
                "</div>"
            } ?: ""
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + info.type + "</div>" +
            emails + detail +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Camera -> {
            val info = result.cameraInfo
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + info.type + "</div>" +
            (info.imagePath?.let { "<div class=\"expanded-result-body\">Image: " + escapeHtml(it) + "</div>" } ?: "") +
            (info.videoPath?.let { "<div class=\"expanded-result-body\">Video: " + escapeHtml(it) + "</div>" } ?: "") +
            (info.cameras.joinToString("") { cam ->
                "<div class=\"expanded-result-subtask\">" + escapeHtml(cam.name) + " — " + escapeHtml(cam.status) + "</div>"
            }) +
            (info.error?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it) + "</div>" } ?: "") +
            "</div>"
        }

        is ToolCallResultObject.Portal -> {
            val css = if (result.css.isNotBlank()) "<style>${result.css}</style>" else ""
            val js = if (result.js.isNotBlank()) "<script>${result.js}</script>" else ""
            "<div class=\"portal-content\">$css${result.html}$js</div>"
        }

        is ToolCallResultObject.ClosePortal -> ""

        is ToolCallResultObject.SkillLoaded -> {
            if (result.success) {
                val toolCount = result.toolsAllowed.size
                val toolPlural = if (toolCount == 1) "tool" else "tools"
                """
                <div class="skill-loaded">
                    <div class="skill-loaded-icon">&#9889;</div>
                    <div class="skill-loaded-content">
                        <div class="skill-loaded-name">${escapeHtml(result.skillName)}</div>
                        <div class="skill-loaded-status">Successfully loaded skill &middot; $toolCount $toolPlural allowed</div>
                    </div>
                </div>
                """.trimIndent()
            } else {
                """
                <div class="skill-loaded skill-loaded-failed">
                    <div class="skill-loaded-icon">&#9888;</div>
                    <div class="skill-loaded-content">
                        <div class="skill-loaded-name">${escapeHtml(result.skillName)}</div>
                        <div class="skill-loaded-status">Failed to load${result.error?.let { ": ${escapeHtml(it)}" } ?: ""}</div>
                    </div>
                </div>
                """.trimIndent()
            }
        }

        is ToolCallResultObject.ToolResults -> result.results.joinToString("") { tr ->
            "<div class=\"expanded-result-card\">" +
            "<div class=\"expanded-result-title\">" + escapeHtml(tr.toolName) + "</div>" +
            (tr.result.takeIf { it.isNotBlank() }?.let { "<div class=\"expanded-result-body\">" + escapeHtml(it.take(500)) + "</div>" } ?: "") +
            "</div>"
        }
    }

    /**
     * Convert an attachment to HTML for the user message bubble.
     * Image files render as a tappable thumbnail backed by the cached local file —
     * the canvas WebView loads it via file:// and a tap fires attachment_image_tap
     * to native, which opens the fullscreen viewer. Video files render as a
     * first-frame still (extracted on attach, thumbnailPath) with a play badge.
     * Every other file type renders as a styled chip: gold type badge (PDF,
     * DOCX, MP3...), filename, and human-readable size, warm paper styling.
     */
    private fun attachmentToHtml(attachment: AiMessageAttachment): String = when (attachment) {
        is AiMessageAttachment.Note -> "<span class=\"attachment-chip\">Note: ${escapeHtml(attachment.note.title)}</span>"
        is AiMessageAttachment.Task -> "<span class=\"attachment-chip\">Task: ${escapeHtml(attachment.task.title)}</span>"
        is AiMessageAttachment.CalenderEvents -> "<span class=\"attachment-chip\">Calendar Events</span>"
        is AiMessageAttachment.File -> {
            // Capture into a local first: Kotlin cannot smart cast the optional
            // thumbnailPath across the module boundary even after a null check
            val thumb = attachment.thumbnailPath
            when {
                attachment.mimeType.startsWith("image/", ignoreCase = true) -> imageThumbHtml(
                    srcPath = attachment.cachedPath,
                    tapPath = attachment.cachedPath,
                    name = attachment.fileName
                )
                attachment.mimeType.startsWith("video/", ignoreCase = true) && thumb != null ->
                    videoThumbHtml(
                        thumbnailPath = thumb,
                        videoPath = attachment.cachedPath,
                        name = attachment.fileName
                    )
                else -> styledFileChipHtml(
                    fileName = attachment.fileName,
                    mimeType = attachment.mimeType,
                    sizeBytes = attachment.sizeBytes,
                    tapPath = attachment.cachedPath
                )
            }
        }
    }

    /**
     * Non-visual file chip for the canvas: gold type badge, filename, size.
     * Sits in the right-aligned .attachments flex row like the image thumbs.
     */
    private fun styledFileChipHtml(fileName: String, mimeType: String, sizeBytes: Long, tapPath: String? = null): String {
        val nameEscaped = escapeHtml(fileName)
        val tapAttr = tapPath?.let {
            " data-attachment-path=\"${escapeHtml(it)}\""
        } ?: ""
        return "<div class=\"attachment-file-chip\"$tapAttr>" +
            "<span class=\"attachment-file-badge\">${escapeHtml(fileBadgeLabel(fileName, mimeType))}</span>" +
            "<span class=\"attachment-file-info\">" +
            "<span class=\"attachment-file-name\">$nameEscaped</span>" +
            "<span class=\"attachment-file-size\">${formatFileSize(sizeBytes)}</span>" +
            "</span>" +
            "</div>"
    }

    /**
     * A shared thumbnail block used for both images and video stills.
     * If the load ever fails the img hides and the filename fallback un-hides.
     */
    private fun thumbShellHtml(srcPath: String, tapPath: String, name: String, extraClass: String, badgeHtml: String): String {
        val path = escapeHtml(tapPath)
        val nameEscaped = escapeHtml(name)
        val url = encodeFileUrl(srcPath)
        return "<div class=\"attachment-image-wrap$extraClass\" data-attachment-path=\"$path\">" +
            "<img class=\"attachment-image\" src=\"$url\" alt=\"$nameEscaped\" onerror=\"this.style.display='none';this.nextElementSibling.style.display='inline-flex'\" />" +
            badgeHtml +
            "<span class=\"attachment-image-fallback\">$nameEscaped</span>" +
            "</div>"
    }

    private fun imageThumbHtml(srcPath: String, tapPath: String, name: String): String =
        thumbShellHtml(srcPath, tapPath, name, extraClass = "", badgeHtml = "")

    /**
     * Video thumbnail: first-frame still plus a small play badge overlay so
     * users can tell at a glance it moves. Tap carries the VIDEO path to native
     * so the fullscreen viewer plays it, not the still.
     */
    private fun videoThumbHtml(thumbnailPath: String, videoPath: String, name: String): String {
        val badge = "<span class=\"attachment-video-badge\">&#9654;</span>"
        // Payload is "video:" + the video file path so native routes the tap
        // to the player. Absolute paths never contain "video:" so the prefix
        // is unambiguous.
        return "<div class=\"attachment-image-wrap attachment-video\" data-attachment-path=\"video:${escapeHtml(videoPath)}\">" +
            "<img class=\"attachment-image\" src=\"${encodeFileUrl(thumbnailPath)}\" alt=\"${escapeHtml(name)}\" onerror=\"this.style.display='none';this.nextElementSibling.style.display='inline-flex'\" />" +
            badge +
            "<span class=\"attachment-image-fallback\">${escapeHtml(name)}</span>" +
            "</div>"
    }

    /**
     * True when an attachment will render as a visual thumbnail (image, or
     * video with a extracted first-frame still). Used to count grid members.
     */
    private fun isVisualAttachment(attachment: AiMessageAttachment): Boolean = when (attachment) {
        is AiMessageAttachment.File -> attachment.mimeType.startsWith("image/", ignoreCase = true) ||
            (attachment.mimeType.startsWith("video/", ignoreCase = true) && attachment.thumbnailPath != null)
        else -> false
    }

    /**
     * Build a file:// URL from an absolute path, percent-encoding each segment
     * so spaces and special characters in cached filenames never break the src.
     */
    private fun encodeFileUrl(path: String): String {
        val encoded = path.split("/").filter { it.isNotBlank() }.joinToString("/") { segment ->
            java.net.URLEncoder.encode(segment, "UTF-8").replace("+", "%20")
        }
        return "file:///$encoded"
    }

    /**
     * Extract portal content from assistant message content.
     * Supports ```portal code fences and <!-- PORTAL: --> comments.
     */
    private data class ExtractedPortal(
        val html: String,
        val css: String = "",
        val js: String = "",
        val height: Int? = null,
        val interactive: Boolean = true
    )

    private fun extractPortalContent(content: String): ExtractedPortal? {
        val portalFenceRegex = Regex("""```portal\s*\n(.*?)```""", RegexOption.DOT_MATCHES_ALL)
        val fenceMatch = portalFenceRegex.find(content)
        if (fenceMatch != null) {
            return parsePortalBody(fenceMatch.groupValues[1].trim())
        }

        val commentRegex = Regex("""<!--\s*PORTAL:\s*(.*?)\s*-->""", RegexOption.DOT_MATCHES_ALL)
        val commentMatch = commentRegex.find(content)
        if (commentMatch != null) {
            return try {
                val jsonObj = org.json.JSONObject(commentMatch.groupValues[1].trim())
                ExtractedPortal(
                    html = jsonObj.optString("html", ""),
                    css = jsonObj.optString("css", ""),
                    js = jsonObj.optString("js", ""),
                    height = jsonObj.optInt("height", -1).takeIf { it > 0 },
                    interactive = jsonObj.optBoolean("interactive", true)
                )
            } catch (_: Exception) { null }
        }
        return null
    }

    private fun parsePortalBody(body: String): ExtractedPortal {
        var css = ""
        var js = ""
        var height: Int? = null
        var interactive = true
        var html = body

        val frontMatterRegex = Regex("""^---\s*\n(.*?)\n---\s*\n(.*)""", RegexOption.DOT_MATCHES_ALL)
        val fmMatch = frontMatterRegex.find(body)
        if (fmMatch != null) {
            val frontMatter = fmMatch.groupValues[1]
            html = fmMatch.groupValues[2].trim()

            // Parse YAML frontmatter supporting both simple key: value and
            // YAML block scalars (key: | followed by indented multiline content)
            val lines = frontMatter.lines()
            var i = 0
            while (i < lines.size) {
                val line = lines[i]
                val trimmed = line.trim()
                if (trimmed.isEmpty()) { i++; continue }

                when {
                    trimmed.startsWith("css:") -> {
                        val inlineVal = trimmed.removePrefix("css:").trim()
                        if (inlineVal == "|" || inlineVal == ">") {
                            // YAML block scalar - collect indented lines below
                            val sb = StringBuilder()
                            i++
                            while (i < lines.size && (lines[i].startsWith("  ") || lines[i].trim().isEmpty())) {
                                sb.append(lines[i].replaceFirst("  ", "")).append("\n")
                                i++
                            }
                            css = sb.toString().trim()
                            continue
                        } else {
                            css = inlineVal.trimSurrounding('"')
                        }
                    }
                    trimmed.startsWith("js:") -> {
                        val inlineVal = trimmed.removePrefix("js:").trim()
                        if (inlineVal == "|" || inlineVal == ">") {
                            val sb = StringBuilder()
                            i++
                            while (i < lines.size && (lines[i].startsWith("  ") || lines[i].trim().isEmpty())) {
                                sb.append(lines[i].replaceFirst("  ", "")).append("\n")
                                i++
                            }
                            js = sb.toString().trim()
                            continue
                        } else {
                            js = inlineVal.trimSurrounding('"')
                        }
                    }
                    trimmed.startsWith("height:") -> {
                        height = trimmed.removePrefix("height:").trim().toIntOrNull()
                    }
                    trimmed.startsWith("interactive:") -> {
                        interactive = trimmed.removePrefix("interactive:").trim().lowercase() == "true"
                    }
                }
                i++
            }
        }
        return ExtractedPortal(html = html, css = css, js = js, height = height, interactive = interactive)
    }

    private fun stripPortalContent(content: String): String {
        var result = Regex("""```portal\s*\n.*?```""", RegexOption.DOT_MATCHES_ALL).replace(content, "")
        result = Regex("""<!--\s*PORTAL:\s*.*?\s*-->""", RegexOption.DOT_MATCHES_ALL).replace(result, "")
        return result.trim()
    }

    /**
     * Strip Luxify interview markers from content so raw tags and JSON
     * never appear in the canvas. The interview UI renders natively on top.
     */
    private fun stripLuxifyMarkers(content: String): String {
        var result = Regex("""\[LUXIFY_QUESTION\].*?\[/LUXIFY_QUESTION\]""", RegexOption.DOT_MATCHES_ALL).replace(content, "")
        result = Regex("""\[LUXIFY_SKILL\].*?\[/LUXIFY_SKILL\]""", RegexOption.DOT_MATCHES_ALL).replace(result, "")
        return result.trim()
    }

    /**
     * Check if content is purely Luxify interview markers with nothing else.
     * Used to skip rendering empty message bubbles when the interview UI
     * handles the content natively.
     */
    fun isLuxifyOnly(content: String): Boolean {
        val stripped = stripLuxifyMarkers(content)
        return stripped.isBlank()
    }

    /**
     * Format an epoch-millis timestamp into a short HH:mm string for the meta-row.
     */
    private fun formatTimestamp(time: Long): String {
        val sdf = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return sdf.format(java.util.Date(time))
    }

    /**
     * Build the meta-row HTML: label + timestamp on one line, hairline underneath.
     * alignment is "left" for GURU, "right" for the user.
     */
    private fun metaRowHtml(label: String, time: Long, alignment: String, messageUuid: String? = null): String {
        val escapedLabel = escapeHtml(label.uppercase())
        val ts = escapeHtml(formatTimestamp(time))
        val uuidAttr = messageUuid?.let { " data-message-uuid=\"${escapeHtml(it)}\"" } ?: ""
        return """
            <div class="meta-row" style="text-align: $alignment;"$uuidAttr>
                <span class="meta-label">$escapedLabel</span><span class="meta-time">$ts</span>
            </div>
            <div class="meta-hairline" style="${if (alignment == "right") "margin-left: auto;" else ""}"></div>
        """.trimIndent()
    }

    /**
     * Escape HTML special characters to prevent injection.
     */
    private fun escapeHtml(text: String): String {
        return text
            .replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
            .replace("'", "&#39;")
    }

    /**
     * Short uppercase badge label for a file: PDF, DOCX, MP3, ZIP...
     * Derived from the extension first (most accurate), falling back to the
     * MIME subtype, falling back to a generic FILE.
     */
    private fun fileBadgeLabel(fileName: String, mimeType: String): String {
        val ext = fileName.substringAfterLast('.', "").uppercase()
        if (ext.isNotBlank() && ext.length <= 5 && ext.all { it.isLetterOrDigit() }) return ext
        val subtype = mimeType.substringAfterLast('/', "").uppercase()
        if (subtype.isNotBlank() && subtype != "OCTET-STREAM" && subtype.length <= 5) return subtype
        return "FILE"
    }

    /**
     * Human-readable size: bytes -> B / KB / MB / GB with one decimal above 1 KB.
     */
    private fun formatFileSize(sizeBytes: Long): String {
        if (sizeBytes < 0) return ""
        if (sizeBytes < 1024) return "${sizeBytes} B"
        val kb = sizeBytes / 1024.0
        if (kb < 1024) return "%.1f KB".format(kb)
        val mb = kb / 1024.0
        if (mb < 1024) return "%.1f MB".format(mb)
        return "%.1f GB".format(mb / 1024.0)
    }

    private fun String.trimSurrounding(char: Char): String {
        if (length >= 2 && first() == char && last() == char) {
            return substring(1, length - 1)
        }
        return this
    }
}