package com.unuslumen.app.data.repository

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClientException
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.ResponseMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.unuslumen.app.data.LlmConfig
import com.unuslumen.app.data.multimodal.ImageTurnAssembler
import com.unuslumen.app.data.tools.registry.ToolDefinition
import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.ContentType
import io.ktor.http.contentType
import io.ktor.http.content.TextContent
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.jvm.javaio.toInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.io.InputStreamReader

private data class ToolCallAccumulator(
    val id: String,
    val name: String,
    val argumentsBuilder: StringBuilder = StringBuilder(),
    var completeArguments: String? = null
)

class UnusLumenStreamingClient(
    private val baseUrl: String,
    private val httpClient: HttpClient,
    /**
     * BYO model connect: the user's own API key for local/OpenAI-compatible
     * endpoints they connected themselves. Rides one Authorization header on
     * this client's requests only; stays on device, never logged, never sent
     * anywhere except the endpoint the user configured.
     */
    private val byoApiKey: String? = null,
    private val json: Json = Json { ignoreUnknownKeys = true },
) {
    /**
     * Multimodal image blocks assembled for the CURRENT request, keyed by tool-call id
     * (or "user" for the user message). Set by AiRepositoryImpl before each call.
     */
    @Volatile
    var assembledImages: Map<String, List<ImageTurnAssembler.ImageBlock>> = emptyMap()

    /** Sentinel key matching ImageTurnAssembler.USER_KEY for the user-message attach. */
    private val userImageKey = "user"
    private val toolSchemaGenerator = com.unuslumen.app.data.tools.registry.ToolSchemaSerializer()

    fun executeStreamingWithTools(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
        toolDefinitions: List<com.unuslumen.app.data.tools.registry.ToolDefinition> = emptyList(),
        llmConfig: LlmConfig
    ): Flow<StreamFrame> = flow {
        val baseUrlTrimmed = baseUrl.trimEnd('/')
        val url = "$baseUrlTrimmed/api/chat"
        val requestJson = buildRequestJson(prompt, model, tools, toolDefinitions, llmConfig)

        android.util.Log.d("guru", "Stream request to: $url, body length: ${requestJson.length} chars")
        android.util.Log.d("guru", "Stream request body starts with: ${requestJson.take(200)}")
        android.util.Log.d("guru", "Stream request body ends with: ${requestJson.takeLast(200)}")

        val response: HttpResponse = httpClient.post(url) {
            byoApiKey?.takeIf { it.isNotBlank() }?.let {
                header("Authorization", "Bearer $it")
            }
            setBody(TextContent(requestJson, ContentType.Application.Json))
        }

        android.util.Log.d("guru", "Stream HTTP response: ${response.status}")

        val channel: ByteReadChannel = response.bodyAsChannel()
        val inputStream = channel.toInputStream()
        val reader = BufferedReader(InputStreamReader(inputStream))

        var index = 0
        var lineCount = 0

        val pendingToolCalls = mutableMapOf<Int, ToolCallAccumulator>()

        try {
            while (true) {
                val line = reader.readLine() ?: break
                lineCount++
                if (line.isBlank()) continue

                try {
                    val chunk = json.parseToJsonElement(line).jsonObject

                    val errorField = chunk["error"]?.jsonPrimitive?.contentOrNull
                    if (errorField != null) {
                        android.util.Log.e("guru", "Stream error: $errorField")
                        android.util.Log.e("guru", "Error full line: ${line.take(500)}")
                        throw LLMClientException("unuslumen", "Server error: $errorField")
                    }

                    val done = try {
                        chunk["done"]?.jsonPrimitive?.contentOrNull == "true"
                    } catch (e: Exception) {
                        android.util.Log.w("guru", "Error reading done flag: ${e.message}")
                        false
                    }

                    try {
                        val message = chunk["message"]?.jsonObject
                        if (message != null) {
                            val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""
                            val thinking = message["thinking"]?.jsonPrimitive?.contentOrNull

                            if (thinking != null && thinking.isNotBlank()) {
                                emit(StreamFrame.ReasoningDelta(thinking, null, index))
                            }

                            val toolCalls = message["tool_calls"]?.jsonArray
                            if (toolCalls != null && toolCalls.isNotEmpty()) {
                                android.util.Log.d("guru", "Raw tool_calls JSON: ${toolCalls.toString().take(500)}")
                                for ((tcIndex, toolCall) in toolCalls.withIndex()) {
                                    val tc = toolCall.jsonObject
                                    val tcId = tc["id"]?.jsonPrimitive?.contentOrNull
                                        ?: "call_${System.currentTimeMillis()}_$tcIndex"
                                    val function = tc["function"]?.jsonObject ?: tc
                                    val tcName = function["name"]?.jsonPrimitive?.contentOrNull ?: ""

                                    val argsElement = function["arguments"]

                                    when (argsElement) {
                                        is JsonObject -> {
                                            val argsStr = argsElement.toString()
                                            pendingToolCalls[tcIndex] = ToolCallAccumulator(
                                                id = tcId,
                                                name = tcName,
                                                completeArguments = argsStr
                                            )
                                            emit(StreamFrame.ToolCallDelta(tcId, tcName, argsStr, index))
                                        }
                                        is JsonPrimitive -> {
                                            val rawContent = argsElement.contentOrNull ?: ""

                                            val accumulator = pendingToolCalls.getOrPut(tcIndex) {
                                                ToolCallAccumulator(id = tcId, name = tcName)
                                            }

                                            accumulator.argumentsBuilder.append(rawContent)

                                            emit(StreamFrame.ToolCallDelta(tcId, tcName, accumulator.argumentsBuilder.toString(), index))

                                            val accumulated = accumulator.argumentsBuilder.toString()
                                            if (isCompleteJson(accumulated)) {
                                                accumulator.completeArguments = accumulated
                                            }
                                        }
                                        null -> {
                                            if (tcIndex !in pendingToolCalls) {
                                                pendingToolCalls[tcIndex] = ToolCallAccumulator(
                                                    id = tcId,
                                                    name = tcName,
                                                    completeArguments = "{}"
                                                )
                                            }
                                        }
                                        else -> {
                                            pendingToolCalls[tcIndex] = ToolCallAccumulator(
                                                id = tcId,
                                                name = tcName,
                                                completeArguments = argsElement.toString()
                                            )
                                        }
                                    }
                                }
                            }

                            if (content.isNotBlank()) {
                                if (done) {
                                    emit(StreamFrame.TextComplete(content, index))
                                } else {
                                    emit(StreamFrame.TextDelta(content, index))
                                }
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.w("guru", "Message processing error on line $lineCount (stream continues): ${e.message}")
                    }

                    if (done) {
                        for ((tcIndex, accumulator) in pendingToolCalls.toSortedMap()) {
                            val finalArgs = accumulator.completeArguments ?: run {
                                val accumulated = accumulator.argumentsBuilder.toString()
                                if (accumulated.isNotBlank()) {
                                    try {
                                        val parsed = json.parseToJsonElement(accumulated)
                                        if (parsed is JsonObject) {
                                            accumulated
                                        } else {
                                            android.util.Log.w("guru", "Tool call args parsed but not object: ${accumulated.take(100)}")
                                            "{}"
                                        }
                                    } catch (e: Exception) {
                                        android.util.Log.w("guru", "Tool call args incomplete JSON: ${accumulated.take(100)}, falling back to empty object")
                                        "{}"
                                    }
                                } else {
                                    "{}"
                                }
                            }

                            val safeArgs = try {
                                val parsed = json.parseToJsonElement(finalArgs)
                                if (parsed is JsonObject) finalArgs else "{}"
                            } catch (e: Exception) {
                                android.util.Log.w("guru", "Tool call final args not valid JSON: ${finalArgs.take(100)}, falling back to empty object")
                                "{}"
                            }

                            android.util.Log.d("guru", "Emitting ToolCallComplete: ${accumulator.name}, args=${safeArgs.take(200)}")
                            emit(StreamFrame.ToolCallComplete(accumulator.id, accumulator.name, safeArgs, index))
                        }
                        pendingToolCalls.clear()

                        emit(StreamFrame.End(chunk["done_reason"]?.jsonPrimitive?.contentOrNull, ResponseMetaInfo.Empty))
                        break
                    }

                    index++
                } catch (e: LLMClientException) {
                    android.util.Log.e("guru", "LLM error, rethrowing: ${e.message}")
                    throw e
                } catch (e: Exception) {
                    android.util.Log.w("guru", "Streaming parse error on line $lineCount: ${e.message}")
                }
            }
        } finally {
            reader.close()
            android.util.Log.d("guru", "Stream finished: $lineCount lines read, $index frames emitted")
        }
    }.flowOn(Dispatchers.IO)

    private fun isCompleteJson(str: String): Boolean {
        if (str.isBlank()) return false
        return try {
            json.parseToJsonElement(str)
            true
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Build a chat message in OLLAMA format. Content stays a PLAIN STRING and images
     * ride in a message-level "images" array of RAW base64 (no data: prefix, no
     * image_url objects). The backend gateway is a Go Ollama-format ChatRequest — it
     * rejects array content ("cannot unmarshal array into ... content of type string").
     */
    private fun buildMessageWithImages(
        role: String,
        content: String,
        images: List<ImageTurnAssembler.ImageBlock>?
    ): JsonObject {
        return if (images.isNullOrEmpty()) {
            buildJsonObject {
                put("role", JsonPrimitive(role))
                put("content", JsonPrimitive(content))
            }
        } else {
            val rawBase64 = images.map { block ->
                // Assembler produces "data:image/jpeg;base64,XXXX" — strip the prefix.
                if (block.dataUri.startsWith("data:") && block.dataUri.contains(";base64,")) {
                    block.dataUri.substringAfter(";base64,")
                } else block.dataUri
            }
            buildJsonObject {
                put("role", JsonPrimitive(role))
                put("content", JsonPrimitive(content))
                put("images", buildJsonArray {
                    for (raw in rawBase64) add(JsonPrimitive(raw))
                })
            }
        }
    }

    private fun buildRequestJson(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>,
        toolDefinitions: List<com.unuslumen.app.data.tools.registry.ToolDefinition>,
        llmConfig: LlmConfig
    ): String {
        val messagesArray = buildJsonArray {
            val systemMessage = prompt.messages.find { it is Message.System }
            if (systemMessage is Message.System) {
                add(buildJsonObject {
                    put("role", JsonPrimitive("system"))
                    put("content", JsonPrimitive(systemMessage.content))
                })
            }

            // Track user-message ordering so the FIRST user message carries assembled
            // user-attached images (ImageTurnAssembler attaches user images to
            // the current turn's user message).
            var userMessageSeen = false

            // Collect ALL tool-result image blocks from the assembled map. Koog's
            // Message.Tool.Result does not reliably preserve the original tool-call
            // id (and restored conversations have id=null), so per-id lookup misses
            // silently — the exact bug where GURU saw file paths with no content.
            // The Go Ollama-format gateway accepts image arrays on USER messages
            // (guaranteed) — so every image from the current turn is delivered on
            // the user message instead.
            val toolImagesFallback = buildList {
                for ((key, blocks) in assembledImages) {
                    if (key != userImageKey) addAll(blocks)
                }
            }

            // Anchor delivery to the LAST user message = the current turn. The
            // assembler produces blocks for THIS turn; delivering them on the
            // first (oldest) user message of a restored history would detach
            // them from the context they belong to.
            val lastUserIndex = prompt.messages.indexOfLast { it is Message.User }

            for ((msgIdx, msg) in prompt.messages.withIndex()) {
                when (msg) {
                    is Message.User -> {
                        val images = if (msgIdx == lastUserIndex) {
                            buildList {
                                assembledImages[userImageKey]?.let { addAll(it) }
                                addAll(toolImagesFallback)
                            }.ifEmpty { null }
                        } else null
                        userMessageSeen = true
                        if (images != null && images.isNotEmpty()) {
                            android.util.Log.d("guru", "Multimodal: delivering ${images.size} image block(s) on the user message (turn-anchored)")
                        }
                        add(buildMessageWithImages("user", msg.content, images))
                    }
                    is Message.Assistant -> add(buildJsonObject {
                        put("role", JsonPrimitive("assistant"))
                        put("content", JsonPrimitive(msg.content))
                    })
                    is Message.Tool.Call -> {
                        val argumentsObj = try {
                            json.parseToJsonElement(msg.content).jsonObject
                        } catch (e: Exception) {
                            android.util.Log.w("guru", "Tool call content not valid JSON object, sending as empty: ${msg.content.take(100)}")
                            buildJsonObject {}
                        }
                        val toolCallObj = buildJsonObject {
                            put("id", JsonPrimitive(msg.id ?: "call_${msg.hashCode()}"))
                            put("type", JsonPrimitive("function"))
                            put("function", buildJsonObject {
                                put("name", JsonPrimitive(msg.tool))
                                put("arguments", argumentsObj)
                            })
                        }
                        add(buildJsonObject {
                            put("role", JsonPrimitive("assistant"))
                            put("content", JsonPrimitive(""))
                            put("tool_calls", buildJsonArray { add(toolCallObj) })
                        })
                    }
                    is Message.Tool.Result -> {
                        // Tool-result text only. Images were moved to the user
                        // message above — the Go gateway's tool role carries a
                        // plain string content and tool images anchored here
                        // were the delivery failure.
                        add(buildMessageWithImages("tool", msg.content, null))
                    }
                    is Message.Reasoning -> {}
                    is Message.System -> {}
                }
            }
        }

        val requestObj = buildJsonObject {
            put("model", JsonPrimitive(model.id))
            put("messages", messagesArray)
            put("stream", JsonPrimitive(true))
            put("think", JsonPrimitive(llmConfig.thinkingLevel))

            if (tools.isNotEmpty()) {
                // Fix 1: real definitions win. AiRepositoryImpl passes the full registry
                // ToolDefinition list alongside the koog descriptors, so parameter schemas
                // carry names, types, required lists, and enums. Definitions are matched by
                // tool name; a descriptor without a matching definition keeps working as a
                // name+description-only tool (zero-arg fallback), never blocking dispatch.
                val definitionsByName = toolDefinitions.associateBy { it.name }
                val toolsArray = buildJsonArray {
                    for (tool in tools) {
                        val definition = definitionsByName[tool.name]
                            ?: ToolDefinition(tool.name, tool.description, "", emptyList())
                        val enhancedDescription = "Tool name: ${tool.name}. ${tool.description}"
                        add(buildJsonObject {
                            put("type", JsonPrimitive("function"))
                            put("function", buildJsonObject {
                                put("name", JsonPrimitive(tool.name))
                                put("description", JsonPrimitive(enhancedDescription))
                                put("parameters", toolSchemaGenerator.serializeParameters(definition))
                            })
                        })
                    }
                }
                put("tools", toolsArray)
            }

            put("options", buildJsonObject {
                put("thinking_effort", JsonPrimitive(llmConfig.thinkingLevel))
                put("temperature", JsonPrimitive(llmConfig.temperature))
                put("num_predict", JsonPrimitive(llmConfig.maxTokens))
                put("num_ctx", JsonPrimitive(llmConfig.contextWindow))
                put("top_p", JsonPrimitive(llmConfig.topP))
                put("top_k", JsonPrimitive(llmConfig.topK))
                put("repeat_penalty", JsonPrimitive(llmConfig.repeatPenalty))
                put("seed", JsonPrimitive(llmConfig.seed))
            })
        }

        return json.encodeToString(JsonObject.serializer(), requestObj)
    }
}