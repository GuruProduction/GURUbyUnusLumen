// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
import kotlinx.coroutines.CancellationException
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

/**
 * The Ollama-format streaming client, and the endings a response can have.
 *
 * ISSUE 307: this client used to treat EVERY ending as success. `readLine()`
 * returning null broke the loop, the `flow { }` builder completed normally, and
 * the agent loop read that as the model having finished its turn. When the
 * connection dropped after a partial answer with a tool call still pending, the
 * user watched GURU stop mid-task with no error and no message, and the tool call
 * the model had asked for was discarded.
 *
 * THE RULE NOW: only `done: true` may end a response successfully. Every other
 * ending throws, so it reaches the repository's retry ladder and its fallback
 * models, and if those exhaust, the user is told the reply was cut short instead
 * of watching the app go quiet.
 *
 * There is one exception, and it is deliberate: a [CancellationException] is
 * never caught, wrapped or converted. A stop button that produces an error
 * message is a worse bug than the one being fixed.
 */
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
        val requestId = java.util.UUID.randomUUID().toString().take(8)

        android.util.Log.d("guru", "STREAM_START request=$requestId url=$url body=${requestJson.length}chars")
        // The first and last slice of the body are logged because a malformed
        // history shows up there and nowhere else. They are truncated so the
        // log stays readable and no tool result text is dumped wholesale.
        android.util.Log.d("guru", "STREAM_BODY_HEAD request=$requestId ${requestJson.take(200)}")
        android.util.Log.d("guru", "STREAM_BODY_TAIL request=$requestId ${requestJson.takeLast(200)}")

        val response: HttpResponse = httpClient.post(url) {
            byoApiKey?.takeIf { it.isNotBlank() }?.let {
                header("Authorization", "Bearer $it")
            }
            setBody(TextContent(requestJson, ContentType.Application.Json))
        }

        android.util.Log.d("guru", "STREAM_HTTP request=$requestId status=${response.status.value}")

        // THE STATUS GATE. Ktor does not throw on a non-2xx status by default,
        // so before this gate a 500 arriving as plain text parsed as nothing,
        // produced zero frames and no error at all: the send simply vanished
        // with no reply and no message. A failure that cannot be seen cannot be
        // retried, so every non-success status becomes a real LLMClientException
        // carrying the server's own words. This is what makes the 500 path
        // reach the retry ladder and the user-visible message instead of dying
        // silently at end-of-stream.
        if (response.status.value !in 200..299) {
            val bodyText = try {
                response.bodyAsChannel().toInputStream().bufferedReader().use { it.readText() }
            } catch (e: Exception) {
                ""
            }
            val serverWords = bodyText.take(600).trim()
            android.util.Log.e(
                "guru",
                "STREAM_HTTP_ERROR request=$requestId status=${response.status.value} " +
                    "body=${serverWords.ifBlank { "<empty>" }}"
            )
            throw LLMClientException(
                "unuslumen",
                "Server error (${response.status.value})." +
                    (if (serverWords.isNotBlank()) " $serverWords" else "")
            )
        }

        val channel: ByteReadChannel = response.bodyAsChannel()
        val inputStream = channel.toInputStream()
        val reader = BufferedReader(InputStreamReader(inputStream))

        var index = 0
        var lineCount = 0

        /** The one fact that decides whether this response may be reported as finished. */
        var receivedDone = false
        var doneReason: String? = null

        val pendingToolCalls = mutableMapOf<Int, ToolCallAccumulator>()

        try {
            while (true) {
                val line = reader.readLine() ?: break
                lineCount++
                if (line.isBlank()) continue

                /*
                 * PARSING IS SEPARATE FROM PROCESSING, DELIBERATELY, AND THIS IS
                 * THE SECOND HALF OF ISSUE 307.
                 *
                 * The old shape wrapped BOTH in one try/catch, so an exception
                 * thrown by `emit()` — a downstream collector failing — was caught
                 * and logged as though the server had sent a bad line, and the
                 * loop carried on reading. That violates Flow exception
                 * transparency: a consumer that throws must be able to stop the
                 * producer, and a cancellation must never be mistaken for input.
                 *
                 * So only the parse is guarded, and a line that will not parse is
                 * now reported rather than swallowed. A reply that cannot be read
                 * past a point is not a reply that finished.
                 */
                val chunk = try {
                    json.parseToJsonElement(line).jsonObject
                } catch (e: Exception) {
                    android.util.Log.e(
                        "guru",
                        "STREAM_MALFORMED request=$requestId line=$lineCount " +
                            "raw=${line.take(200)} error=${e.javaClass.simpleName}: ${e.message}"
                    )
                    throw LLMClientException(
                        "unuslumen",
                        StreamCompletion.message(
                            StreamCompletion.Ending.MALFORMED,
                            linesRead = lineCount,
                            pendingToolCalls = pendingToolCalls.size,
                            textLength = 0,
                        ),
                    )
                }

                val errorField = chunk["error"]?.jsonPrimitive?.contentOrNull
                if (errorField != null) {
                    android.util.Log.e("guru", "STREAM_SERVER_ERROR request=$requestId line=$lineCount error=$errorField")
                    android.util.Log.e("guru", "STREAM_SERVER_ERROR_LINE request=$requestId ${line.take(500)}")
                    throw LLMClientException("unuslumen", "Server error: $errorField")
                }

                val done = try {
                    chunk["done"]?.jsonPrimitive?.contentOrNull == "true"
                } catch (e: Exception) {
                    android.util.Log.w("guru", "STREAM_BAD_DONE_FLAG request=$requestId line=$lineCount error=${e.message}")
                    false
                }

                val message = chunk["message"]?.jsonObject
                if (message != null) {
                    val content = message["content"]?.jsonPrimitive?.contentOrNull ?: ""
                    val thinking = message["thinking"]?.jsonPrimitive?.contentOrNull

                    if (thinking != null && thinking.isNotBlank()) {
                        emit(StreamFrame.ReasoningDelta(thinking, null, index))
                    }

                    val toolCalls = message["tool_calls"]?.jsonArray
                    if (toolCalls != null && toolCalls.isNotEmpty()) {
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
                        android.util.Log.d(
                            "guru",
                            "STREAM_TOOL_ACCUMULATED request=$requestId line=$lineCount " +
                                "pending=${pendingToolCalls.size} names=${pendingToolCalls.values.map { it.name }}",
                        )
                    }

                    if (content.isNotBlank()) {
                        if (done) {
                            emit(StreamFrame.TextComplete(content, index))
                        } else {
                            emit(StreamFrame.TextDelta(content, index))
                        }
                    }
                }

                if (done) {
                    receivedDone = true
                    doneReason = chunk["done_reason"]?.jsonPrimitive?.contentOrNull

                    /*
                     * THE TOOL CALLS ARE EMITTED HERE, AND ONLY HERE, BECAUSE THIS
                     * IS THE ONLY POINT AT WHICH THEY ARE KNOWN TO BE COMPLETE.
                     *
                     * Their arguments arrive in fragments across many chunks, so
                     * emitting earlier would hand over a partial JSON object. The
                     * old consequence was that a stream closing one chunk early
                     * discarded a fully-formed intent: the model asked for a tool
                     * and nothing ran.
                     *
                     * That is now impossible rather than merely handled, because
                     * reaching this branch is the ONLY way this flow ends without
                     * throwing, and throwing is what the repository retries.
                     */
                    for ((_, accumulator) in pendingToolCalls.toSortedMap()) {
                        val finalArgs = accumulator.completeArguments ?: run {
                            val accumulated = accumulator.argumentsBuilder.toString()
                            if (accumulated.isNotBlank()) {
                                try {
                                    val parsed = json.parseToJsonElement(accumulated)
                                    if (parsed is JsonObject) {
                                        accumulated
                                    } else {
                                        android.util.Log.w("guru", "STREAM_TOOL_ARGS_NOT_OBJECT request=$requestId tool=${accumulator.name} raw=${accumulated.take(100)}")
                                        "{}"
                                    }
                                } catch (e: Exception) {
                                    android.util.Log.w("guru", "STREAM_TOOL_ARGS_INCOMPLETE request=$requestId tool=${accumulator.name} raw=${accumulated.take(100)}")
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
                            android.util.Log.w("guru", "STREAM_TOOL_ARGS_INVALID request=$requestId tool=${accumulator.name} raw=${finalArgs.take(100)}")
                            "{}"
                        }

                        android.util.Log.d("guru", "STREAM_TOOL_COMPLETE request=$requestId tool=${accumulator.name} args=${safeArgs.length}chars")
                        emit(StreamFrame.ToolCallComplete(accumulator.id, accumulator.name, safeArgs, index))
                    }
                    pendingToolCalls.clear()

                    android.util.Log.d("guru", "STREAM_DONE request=$requestId lines=$lineCount reason=$doneReason")
                    emit(StreamFrame.End(doneReason, ResponseMetaInfo.Empty))
                    break
                }

                index++
            }
        } catch (e: CancellationException) {
            // A deliberate stop, by the user or by a newer send. It propagates
            // untouched and is never reported as an error; the repository catches
            // CancellationException explicitly for the same reason.
            android.util.Log.d("guru", "STREAM_CANCELLED request=$requestId lines=$lineCount")
            throw e
        } finally {
            reader.close()
        }

        /*
         * THE WHOLE OF ISSUE 307, IN SIX LINES.
         *
         * `break` out of the read loop is reached either by `done` above, or by
         * the stream ending. Before this check the two were indistinguishable to
         * everything downstream, and the second was reported as a finished turn.
         *
         * A Kotlin Flow cannot express "I ended because my input ran out" as
         * different from "I ended because I was finished", so the flow has to
         * throw. Throwing is what makes the repository retry the same request,
         * hand over to a fallback model, and, when all of that fails, tell the
         * user the reply was cut short instead of going quiet.
         */
        if (!receivedDone) {
            val pending = pendingToolCalls.size
            android.util.Log.e(
                "guru",
                "STREAM_EOF request=$requestId lines=$lineCount done=false pendingTools=$pending",
            )
            throw LLMClientException(
                "unuslumen",
                StreamCompletion.message(
                    StreamCompletion.Ending.TRUNCATED,
                    linesRead = lineCount,
                    pendingToolCalls = pending,
                    textLength = 0,
                ),
            )
        }

        android.util.Log.d("guru", "STREAM_END request=$requestId lines=$lineCount status=complete")
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

            messages@ for ((msgIdx, msg) in prompt.messages.withIndex()) {
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
                        // A tool_call with no function name is unrenderable by
                        // the upstream chat template, and the host answers the
                        // WHOLE request with HTTP 500. That is the restored-chat
                        // defect: history rebuilt from rows that never stored a
                        // tool name. This is the last gate before the wire, so
                        // an unnamed call is dropped here rather than shipped.
                        if (msg.tool.isBlank()) {
                            android.util.Log.w(
                                "guru",
                                "Dropping nameless tool call from the wire body (id=${msg.id}); " +
                                    "the upstream rejects an empty function name with a 500"
                            )
                            continue@messages
                        }
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
                        // A result whose call was dropped has no partner, and an
                        // orphaned tool result is rejected by the same hosts that
                        // reject an unnamed call. Pair it by tool name, which is
                        // the only identity a result carries.
                        if (msg.tool.isBlank()) {
                            android.util.Log.w(
                                "guru",
                                "Dropping orphaned tool result from the wire body (tool name blank)"
                            )
                            continue@messages
                        }
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
