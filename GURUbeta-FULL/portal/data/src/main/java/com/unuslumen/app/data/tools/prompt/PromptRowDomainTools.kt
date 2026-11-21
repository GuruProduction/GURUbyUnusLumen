// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.prompt

import com.unuslumen.app.data.prompt.PromptRowStore
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.database.entity.PromptRowEntity
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * The prompt stack, as rows a model can read and one it can change.
 *
 * WHY ROWS AND NOT THE ASSEMBLED STRING. `getSystemPrompt` returns the whole blob, so a
 * model can see an instruction is wrong and cannot find the row that says it. Every edit
 * then becomes a guess at a paragraph offset. These verbs address a row by its stable slug
 * instead, which is the handle the server itself uses.
 *
 * A ROW THAT EXISTS IS NOT A ROW THAT ASSEMBLES. That distinction is the one this surface
 * has to keep, because the alternative is a list that reads as "this is what GURU is told"
 * when half of it never reaches the model. An always-on row assembles; a keyword-triggered
 * row assembles only when a keyword fires; an inactive row never does.
 */
object PromptRowDomainDefinitions : ToolSetRegistration {
    const val LIST_ROWS = "listPromptRows"
    const val GET_ROW = "getPromptRow"
    const val UPDATE_ROW = "updatePromptRow"
    const val RESET_OVERRIDE = "resetPromptOverride"
    const val LIST_ASSEMBLING = "listAssemblingPrompts"

    override val definitions = listOf(
        ToolDefinition(
            name = LIST_ROWS,
            description = "List every prompt row this device holds: its slug, name, category, delivery mode, " +
                "trigger keywords, version, and whether the owner has edited it. The slug is what every " +
                "other prompt verb addresses, so read this first. A version number is what updatePromptRow " +
                "needs to prove its edit is based on the current text.",
            category = "prompts",
            parameters = listOf(
                ToolParameter(
                    "activeOnly", ToolParameterType.Boolean, required = false,
                    "true to list only rows the server has not withdrawn. Default false, so a withdrawn row is visible as withdrawn rather than absent.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = GET_ROW,
            description = "Read one prompt row in full by its slug: the complete text, its delivery mode and " +
                "keywords, its version, its history depth, and whether it is assembling right now. The " +
                "history depth tells you how many versions exist, so a change can be traced back rather " +
                "than guessed at.",
            category = "prompts",
            parameters = listOf(
                ToolParameter("slug", ToolParameterType.String, required = true, description = "The row's slug from listPromptRows"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = UPDATE_ROW,
            description = "Replace one prompt row's text. It requires the version you read, and refuses if the " +
                "row has changed since: that guard is what stops an edit built on old text silently " +
                "overwriting someone else's newer words. The previous text is kept as a version before the " +
                "new text lands, so the change is recoverable.",
            category = "prompts",
            parameters = listOf(
                ToolParameter("slug", ToolParameterType.String, required = true, description = "The row's slug"),
                ToolParameter("baseVersion", ToolParameterType.Integer, required = true, description = "The version you read from getPromptRow or listPromptRows"),
                ToolParameter("content", ToolParameterType.String, required = true, description = "The full replacement text, not a diff"),
                ToolParameter("name", ToolParameterType.String, required = false, description = "A new display name, when the rename is part of the edit"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = RESET_OVERRIDE,
            description = "Return an edited prompt row to the text the server last sent. The owner's override is " +
                "written into the version history first, so a reset that was a mistake can be recovered " +
                "rather than being the one action here that destroys work. A row with no override is " +
                "reported as already at its synced text rather than as a failure.",
            category = "prompts",
            parameters = listOf(
                ToolParameter("slug", ToolParameterType.String, required = true, description = "The row's slug"),
                ToolParameter("baseVersion", ToolParameterType.Integer, required = true, description = "The version you read"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = LIST_ASSEMBLING,
            description = "Which prompt rows actually reach the model on this device, and why the others do not. " +
                "An always-on row assembles every time; a keyword-triggered row assembles only when one of " +
                "its keywords fires, which this reports rather than claiming it is live; an inactive row " +
                "never assembles. Use it instead of assuming that a row existing means GURU is told it.",
            category = "prompts",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = PromptRowDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

// ----------------------------------------------------------------------
// Results
// ----------------------------------------------------------------------

@Serializable
data class PromptRowEntry(
    val slug: String,
    val name: String,
    val category: String,
    val deliveryMode: String,
    val triggerKeywords: List<String>,
    val version: Int,
    val isActive: Boolean,
    val isEdited: Boolean,
    /** ALWAYS, KEYWORD or NEVER. What this row does about reaching the model. */
    val assembling: String,
) : ToolResultData

@Serializable
data class PromptRowListResult(
    val success: Boolean,
    val total: Int,
    val rows: List<PromptRowEntry>,
    val message: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class PromptRowReadResult(
    val success: Boolean,
    val slug: String,
    val name: String = "",
    val category: String = "",
    val content: String = "",
    val deliveryMode: String = "",
    val triggerKeywords: List<String> = emptyList(),
    val version: Int = 0,
    val isActive: Boolean = false,
    val isEdited: Boolean = false,
    val assembling: String = "",
    val historyDepth: Int = 0,
    val error: String? = null,
) : ToolResultData

@Serializable
data class PromptRowWriteResult(
    val success: Boolean,
    val slug: String,
    val version: Int = 0,
    /** Set when the write was refused because the row moved on. */
    val expectedVersion: Int? = null,
    val actualVersion: Int? = null,
    val message: String = "",
    val error: String? = null,
) : ToolResultData

@Serializable
data class PromptAssemblingEntry(
    val slug: String,
    val name: String,
    val assembling: String,
    val reason: String,
) : ToolResultData

@Serializable
data class PromptAssemblingResult(
    val success: Boolean,
    val total: Int,
    val alwaysOn: Int,
    val keywordTriggered: Int,
    val never: Int,
    val rows: List<PromptAssemblingEntry>,
    val message: String,
) : ToolResultData

// ----------------------------------------------------------------------
// Executor
// ----------------------------------------------------------------------

class PromptRowDomainExecutor(
    private val context: android.content.Context,
) : ToolExecutor {

    /**
     * The store, resolved through Koin rather than held as a constructor argument.
     *
     * WHY THIS RATHER THAN INJECTION, when injection is the better habit. `PromptRowStore`
     * needs `guruDatabase`, and Koin owns the single Room instance every other writer in the
     * app uses. Taking the store as a constructor argument here would mean either this class
     * builds a second database handle or every caller has to pass the whole database in to
     * construct a tool. Resolving the store from the container that already owns the
     * connection is what keeps one connection rather than two.
     *
     * It is also the shape every device-senses domain uses: an executor takes the Context,
     * and the domain's own Koin bindings supply the rest. That is asserted by
     * SensesDomainHonestyTest, which builds every registered executor from its own bindings.
     */
    private val store: PromptRowStore
        get() = org.koin.java.KoinJavaComponent.getKoin().get()

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            PromptRowDomainDefinitions.LIST_ROWS -> listRows(args)
            PromptRowDomainDefinitions.GET_ROW -> getRow(args)
            PromptRowDomainDefinitions.UPDATE_ROW -> updateRow(args)
            PromptRowDomainDefinitions.RESET_OVERRIDE -> resetOverride(args)
            PromptRowDomainDefinitions.LIST_ASSEMBLING -> listAssembling()
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    /**
     * How a row behaves about reaching the model.
     *
     * AN INACTIVE ROW NEVER ASSEMBLES, whatever its delivery mode says. That check comes
     * first, because reporting a withdrawn always-on row as ALWAYS would be the exact
     * conflating of "exists" with "is live" this surface exists to avoid.
     */
    private fun assemblingOf(row: PromptRowEntity): String = when {
        !row.isActive -> "NEVER"
        row.deliveryMode == PromptRowEntity.DELIVERY_ALWAYS -> "ALWAYS"
        row.deliveryMode == PromptRowEntity.DELIVERY_KEYWORD -> "KEYWORD"
        else -> "NEVER"
    }

    /** The keyword list, decoded from the JSON array text the column holds. */
    private fun keywordsOf(row: PromptRowEntity): List<String> = runCatching {
        val element = Json.parseToJsonElement(row.triggerKeywordsJson)
        (element as? kotlinx.serialization.json.JsonArray)
            ?.mapNotNull { (it as? kotlinx.serialization.json.JsonPrimitive)?.content }
            .orEmpty()
    }.getOrDefault(emptyList())

    private suspend fun listRows(args: Map<String, Any?>): ToolExecutionResult {
        val activeOnly = args["activeOnly"] as? Boolean ?: false
        val rows = if (activeOnly) store.active() else store.all()
        val result = PromptRowListResult(
            success = true,
            total = rows.size,
            rows = rows.map { row ->
                PromptRowEntry(
                    slug = row.slug,
                    name = row.name,
                    category = row.category,
                    deliveryMode = row.deliveryMode,
                    triggerKeywords = keywordsOf(row),
                    version = row.version,
                    isActive = row.isActive,
                    isEdited = row.isEdited,
                    assembling = assemblingOf(row),
                )
            },
            message = if (rows.isEmpty()) {
                "No prompt rows are on this device. A row that is not here cannot be read or edited, so the " +
                    "stack has not synced yet. That is different from a stack that is genuinely empty."
            } else {
                null
            },
        )
        return success(result, PromptRowListResult.serializer())
    }

    private suspend fun getRow(args: Map<String, Any?>): ToolExecutionResult {
        val slug = (args["slug"] as? String)?.trim().orEmpty()
        if (slug.isEmpty()) {
            return failure(
                PromptRowReadResult(
                    success = false,
                    slug = "",
                    error = "Missing 'slug'. Call listPromptRows for the slugs this device holds.",
                ),
                PromptRowReadResult.serializer(),
            )
        }
        val row = store.bySlug(slug)
            ?: return failure(
                PromptRowReadResult(
                    success = false,
                    slug = slug,
                    error = "No prompt row called \"$slug\" is on this device. Call listPromptRows for the " +
                        "real slugs.",
                ),
                PromptRowReadResult.serializer(),
            )

        val result = PromptRowReadResult(
            success = true,
            slug = row.slug,
            name = row.name,
            category = row.category,
            content = row.content,
            deliveryMode = row.deliveryMode,
            triggerKeywords = keywordsOf(row),
            version = row.version,
            isActive = row.isActive,
            isEdited = row.isEdited,
            assembling = assemblingOf(row),
            historyDepth = store.historyDepth(row.id),
        )
        return success(result, PromptRowReadResult.serializer())
    }

    private suspend fun updateRow(args: Map<String, Any?>): ToolExecutionResult {
        val slug = (args["slug"] as? String)?.trim().orEmpty()
        if (slug.isEmpty()) {
            return failure(
                PromptRowWriteResult(success = false, slug = "", error = "Missing 'slug'."),
                PromptRowWriteResult.serializer(),
            )
        }
        val baseVersion = (args["baseVersion"] as? Number)?.toInt()
            ?: return failure(
                PromptRowWriteResult(
                    success = false,
                    slug = slug,
                    error = "Missing 'baseVersion': an edit has to name the version it was written against, " +
                        "or a change built on old text would silently replace newer words.",
                ),
                PromptRowWriteResult.serializer(),
            )
        val content = args["content"] as? String
        if (content.isNullOrBlank()) {
            return failure(
                PromptRowWriteResult(
                    success = false,
                    slug = slug,
                    error = "Missing 'content'. A prompt row cannot be made empty; if the row should stop " +
                        "applying, turn it inactive instead.",
                ),
                PromptRowWriteResult.serializer(),
            )
        }

        return when (val outcome = store.updateContent(slug, baseVersion, content, args["name"] as? String)) {
            is PromptRowStore.WriteOutcome.Missing -> failure(
                PromptRowWriteResult(
                    success = false,
                    slug = slug,
                    error = "No prompt row called \"$slug\" is on this device.",
                ),
                PromptRowWriteResult.serializer(),
            )

            is PromptRowStore.WriteOutcome.Stale -> failure(
                PromptRowWriteResult(
                    success = false,
                    slug = slug,
                    version = outcome.actualVersion,
                    expectedVersion = outcome.expectedVersion,
                    actualVersion = outcome.actualVersion,
                    error = "Nothing was written. \"$slug\" is at version ${outcome.actualVersion}, which is " +
                        "not the ${outcome.expectedVersion} this edit was based on, so the text has changed " +
                        "since it was read. Read the row again and redo the edit against what it says now.",
                ),
                PromptRowWriteResult.serializer(),
            )

            is PromptRowStore.WriteOutcome.Applied -> success(
                PromptRowWriteResult(
                    success = true,
                    slug = slug,
                    version = outcome.version,
                    message = "Written. Version ${outcome.previousVersion} is kept in history, so this change " +
                        "can be traced or reverted.",
                ),
                PromptRowWriteResult.serializer(),
            )
        }
    }

    private suspend fun resetOverride(args: Map<String, Any?>): ToolExecutionResult {
        val slug = (args["slug"] as? String)?.trim().orEmpty()
        if (slug.isEmpty()) {
            return failure(
                PromptRowWriteResult(success = false, slug = "", error = "Missing 'slug'."),
                PromptRowWriteResult.serializer(),
            )
        }
        val baseVersion = (args["baseVersion"] as? Number)?.toInt()
            ?: return failure(
                PromptRowWriteResult(success = false, slug = slug, error = "Missing 'baseVersion'."),
                PromptRowWriteResult.serializer(),
            )

        return when (val outcome = store.resetToSynced(slug, baseVersion)) {
            is PromptRowStore.WriteOutcome.Missing -> failure(
                PromptRowWriteResult(
                    success = false,
                    slug = slug,
                    error = "No prompt row called \"$slug\" is on this device, or it has no synced text to " +
                        "return to.",
                ),
                PromptRowWriteResult.serializer(),
            )

            is PromptRowStore.WriteOutcome.Stale -> failure(
                PromptRowWriteResult(
                    success = false,
                    slug = slug,
                    version = outcome.actualVersion,
                    expectedVersion = outcome.expectedVersion,
                    actualVersion = outcome.actualVersion,
                    error = "Nothing was reset. \"$slug\" is at version ${outcome.actualVersion}, not the " +
                        "${outcome.expectedVersion} this reset was based on.",
                ),
                PromptRowWriteResult.serializer(),
            )

            is PromptRowStore.WriteOutcome.Applied -> success(
                PromptRowWriteResult(
                    success = true,
                    slug = slug,
                    version = outcome.version,
                    message = if (outcome.version == outcome.previousVersion) {
                        "That row has no local override, so it is already at the text the server sent."
                    } else {
                        "Reset to the server's text. Your override is kept in history rather than discarded."
                    },
                ),
                PromptRowWriteResult.serializer(),
            )
        }
    }

    private suspend fun listAssembling(): ToolExecutionResult {
        val rows = store.all()
        val entries = rows.map { row ->
            val assembling = assemblingOf(row)
            PromptAssemblingEntry(
                slug = row.slug,
                name = row.name,
                assembling = assembling,
                reason = when (assembling) {
                    "ALWAYS" -> "Delivered on every request."
                    "KEYWORD" -> "Delivered only when one of its keywords fires: " +
                        keywordsOf(row).joinToString(", ").ifBlank { "it has no keywords, so it never fires" }
                    else -> if (!row.isActive) {
                        "The server has withdrawn this row, so it does not assemble."
                    } else {
                        "Its delivery mode (${row.deliveryMode}) is not one that assembles."
                    }
                },
            )
        }
        val result = PromptAssemblingResult(
            success = true,
            total = entries.size,
            alwaysOn = entries.count { it.assembling == "ALWAYS" },
            keywordTriggered = entries.count { it.assembling == "KEYWORD" },
            never = entries.count { it.assembling == "NEVER" },
            rows = entries,
            message = when {
                entries.isEmpty() ->
                    "No prompt rows are on this device, so nothing here is assembling. That is a sync state, " +
                        "not a statement about what GURU is told."
                entries.none { it.assembling == "ALWAYS" } ->
                    "No row is always-on. Every row here is either keyword-triggered or withdrawn."
                else ->
                    "${entries.count { it.assembling == "ALWAYS" }} row(s) assemble on every request. " +
                        "Keyword-triggered rows assemble only when a keyword fires, and are not counted as live."
            },
        )
        return success(result, PromptAssemblingResult.serializer())
    }

    private fun <T : ToolResultData> success(
        data: T,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, data),
        resultData = data,
        resultObject = null,
        success = true,
        error = null,
    )

    /**
     * A refusal is a SUCCESSFUL call carrying success=false.
     *
     * The tool did its job; the answer is no. Reporting it as an execution error would make
     * a stale-version refusal indistinguishable from a broken tool, and the caller has to
     * tell those apart to know whether to re-read the row or report a fault.
     */
    private fun <T : ToolResultData> failure(
        data: T,
        serializer: kotlinx.serialization.KSerializer<T>,
    ): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(serializer, data),
        resultData = data,
        resultObject = null,
        success = true,
        error = null,
    )
}

object PromptRowDomainModule : DomainToolModule {
    override val id: String = "prompt_rows"

    override val registrations: List<ToolSetRegistration> = listOf(PromptRowDomainDefinitions)

    override fun bindings(): Module = module {
        factory { PromptRowDomainExecutor(get<android.content.Context>()) }
    }
}
