package com.unuslumen.app.data

import ai.koog.prompt.dsl.prompt
import ai.koog.prompt.llm.LLMCapability
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.params.LLMParams
import com.unuslumen.app.domain.model.AiMessage
import com.unuslumen.app.preferences.domain.model.AiProvider
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.model.intPreferencesKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.format
import kotlinx.datetime.format.DayOfWeekNames
import kotlinx.datetime.format.char
import kotlin.uuid.Uuid


fun List<AiMessage>.buildChatPrompt(
    systemMessage: String = "",
    tools: List<ai.koog.agents.core.tools.ToolDescriptor> = emptyList(),
    toolChoice: LLMParams.ToolChoice? = if (tools.isNotEmpty()) LLMParams.ToolChoice.Auto else null,
) = prompt("chat_prompt", LLMParams(toolChoice = toolChoice)) {
    system(systemMessage)
    forEach { message ->
        when (message) {
            is AiMessage.UserMessage -> user(message.content + message.attachmentsText)
            is AiMessage.AssistantMessage -> assistant(message.content)
            is AiMessage.StreamingAssistant -> assistant(message.partialContent)
            is AiMessage.StreamingToolCall -> { /* streaming tool calls are not sent to LLM */ }
            is AiMessage.ToolCall -> {
                if (message.thoughtSignature != null) {
                    message(
                        Message.Reasoning(
                            encrypted = message.thoughtSignature,
                            content = "",
                            metaInfo = ResponseMetaInfo.Empty
                        )
                    )
                }
                tool {
                    call(
                        id = message.id,
                        tool = message.name,
                        content = message.rawContent
                    )
                    result(
                        id = message.id,
                        tool = message.name,
                        content = message.resultRawContent
                    )
                }
            }
            is AiMessage.PortalMessage -> {
                // Portal messages are visual-only, not sent to the LLM
            }
        }
    }
}


fun Message.Tool.Call.toAiMessage(
    toolCallResult: Result<AiMessage.ToolCall>,
    thoughtSignature: String? = null
): AiMessage {
    return toolCallResult.getOrNull()?.copy(thoughtSignature = thoughtSignature)
        ?: AiMessage.ToolCall(
            uuid = Uuid.random().toString(),
            id = id,
            name = tool,
            rawContent = content,
            resultRawContent = toolCallResult.exceptionOrNull()
                ?.getRootCause()
                ?.toString()
                ?: "Error executing tool",
            time = nowMillis(),
            isFailed = true,
            thoughtSignature = thoughtSignature
        )
}

fun String.toLLModel(provider: AiProvider, withTools: Boolean, contextWindow: Long, maxOutputTokens: Long): LLModel {
    val llmProvider = provider.toLLMProvider()
    return LLModel(
        provider = llmProvider,
        id = this,
        capabilities = buildList {
            if (withTools) {
                add(LLMCapability.Tools)
                add(LLMCapability.ToolChoice)
            }
            // Vision is granted to EVERY connected model: assembled screen frames and
            // tool captures must ride the wire to any model the user connects. The
            // provider itself errors on models without vision — visible and honest,
            // rather than silently stripping the image in the app layer.
            add(LLMCapability.Vision.Image)
            add(LLMCapability.Completion)
        },
        contextLength = contextWindow,
        maxOutputTokens = maxOutputTokens,
    )
}

fun AiProvider.toLLMProvider(): LLMProvider = when (this) {
    AiProvider.UnusLumen -> object : LLMProvider("UnusLumen", "https://api.unuslumen.com") {}
    AiProvider.OpenAI -> LLMProvider.OpenAI
    AiProvider.Anthropic -> LLMProvider.Anthropic
    AiProvider.Google -> LLMProvider.Google
    AiProvider.XAI -> object : LLMProvider("Grok", "https://api.x.ai") {}
    AiProvider.Ollama -> LLMProvider.Ollama
    AiProvider.OpenAICompat -> object : LLMProvider("OpenAI-compatible", "user endpoint") {}
    AiProvider.None -> object : LLMProvider("None", "") {}
}

fun Message.Assistant.toNewAssistantMessage(thinkingTokens: String = "") = AiMessage.AssistantMessage(
    uuid = Uuid.random().toString(),
    content = content,
    time = nowMillis(),
    thinkingTokens = thinkingTokens,
)

internal fun nowMillis() = System.currentTimeMillis()
private val currentTimeZone = TimeZone.currentSystemDefault()
internal fun currentLocalDateTime(): LocalDateTime {
    val javaInstant = java.time.Instant.ofEpochMilli(System.currentTimeMillis())
    val javaLocal = javaInstant.atZone(java.time.ZoneId.systemDefault()).toLocalDateTime()
    return LocalDateTime(
        year = javaLocal.year,
        month = kotlinx.datetime.Month.entries[javaLocal.monthValue - 1],
        day = javaLocal.dayOfMonth,
        hour = javaLocal.hour,
        minute = javaLocal.minute,
        second = javaLocal.second,
        nanosecond = javaLocal.nano
    )
}
internal const val llmDateTimeFormatUnicode = "HH:mm dd-MM-yyyy"
internal val llmDateTimeWithDayNameFormat = LocalDateTime.Format {
    hour(); char(':'); minute();
    char(' ');
    dayOfWeek(DayOfWeekNames.ENGLISH_FULL);
    char(' ');
    day(); char('-'); monthNumber(); char('-'); year()
}
internal val llmDateTimeFormat = LocalDateTime.Format {
    hour(); char(':'); minute();
    char(' ');
    day(); char('-'); monthNumber(); char('-'); year()
}
internal fun String.parseDateTimeFromLLM() = runCatching {
    val parsed = LocalDateTime.parse(this, llmDateTimeFormat)
    val javaLocal = java.time.LocalDateTime.of(parsed.year, java.time.Month.of(parsed.month.ordinal + 1), parsed.day, parsed.hour, parsed.minute)
    val javaInstant = javaLocal.atZone(java.time.ZoneId.systemDefault()).toInstant()
    javaInstant.toEpochMilli()
}.getOrNull()

fun buildChatSystemMessage(
    toolsEnabled: Boolean = true,
    humanName: String = "",
    activeSkills: List<com.unuslumen.app.domain.model.LuxifySkill> = emptyList(),
    serverPrompt: String? = null
): String {
    val prompt = serverPrompt
        ?: throw IllegalStateException("Server prompt not available — the app cannot function without prompts from the server")
    return buildString {
        appendLine(prompt.replace("\${human}", humanName))
        append("Current date & time: ")
        appendLine(currentLocalDateTime().format(llmDateTimeWithDayNameFormat))
        append("Time zone: "); append(currentTimeZone)
    }
}

suspend fun buildDynamicChatSystemMessage(
    toolsEnabled: Boolean = true,
    humanName: String = "",
    assembledPrompts: com.unuslumen.app.domain.model.AssembledPrompts?,
    activeSkills: List<com.unuslumen.app.domain.model.LuxifySkill> = emptyList(),
    serverPrompt: String? = null
): String {
    val prompt = serverPrompt
        ?: throw IllegalStateException("Server prompt not available — the app cannot function without prompts from the server")
    return buildString {
        appendLine(prompt.replace("\${human}", humanName))
        append("Current date & time: ")
        appendLine(currentLocalDateTime().format(llmDateTimeWithDayNameFormat))
        append("Time zone: "); append(currentTimeZone)
    }
}

fun Throwable.getRootCause(): Throwable {
    var rootCause: Throwable? = this
    while (rootCause?.cause != null) {
        rootCause = rootCause.cause
    }
    return rootCause ?: this
}