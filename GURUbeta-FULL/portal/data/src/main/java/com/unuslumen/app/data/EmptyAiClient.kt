package com.unuslumen.app.data

import ai.koog.agents.core.tools.ToolDescriptor
import ai.koog.prompt.dsl.ModerationResult
import ai.koog.prompt.dsl.Prompt
import ai.koog.prompt.executor.clients.LLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message

object EmptyAiClient : LLMClient {

    override suspend fun execute(
        prompt: Prompt,
        model: LLModel,
        tools: List<ToolDescriptor>
    ): List<Message.Response> = throw UnsupportedOperationException()

    override suspend fun moderate(
        prompt: Prompt,
        model: LLModel
    ): ModerationResult = throw UnsupportedOperationException()

    override fun llmProvider(): LLMProvider = object: LLMProvider("empty", "empty") {}

    override fun close() = Unit

}