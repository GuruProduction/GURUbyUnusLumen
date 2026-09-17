package com.unuslumen.app.data

import ai.koog.prompt.executor.clients.ConnectionTimeoutConfig
import ai.koog.prompt.executor.clients.LLMClient
import io.ktor.client.HttpClient

// Wrapper around the Koog framework's built-in client for Ollama-compatible APIs.
// The Koog class name is contained inside this file only. The rest of the app
// imports UnusLumenClient and never sees the underlying library name.
val unusLumenProvider = ai.koog.prompt.llm.LLMProvider.Ollama

class UnusLumenClient(
    baseUrl: String = "",
    baseClient: HttpClient? = null,
    timeoutConfig: ConnectionTimeoutConfig? = null,
) : LLMClient {
    private val delegate: LLMClient = run {
        val timeouts = timeoutConfig ?: ConnectionTimeoutConfig()
        if (baseUrl.isBlank()) {
            if (baseClient != null) ai.koog.prompt.executor.ollama.client.OllamaClient(baseClient = baseClient, timeoutConfig = timeouts)
            else ai.koog.prompt.executor.ollama.client.OllamaClient()
        } else {
            if (baseClient != null) ai.koog.prompt.executor.ollama.client.OllamaClient(baseUrl, baseClient = baseClient, timeoutConfig = timeouts)
            else ai.koog.prompt.executor.ollama.client.OllamaClient(baseUrl, timeoutConfig = timeouts)
        }
    }

    override fun llmProvider() = delegate.llmProvider()
    override suspend fun execute(prompt: ai.koog.prompt.dsl.Prompt, model: ai.koog.prompt.llm.LLModel, tools: List<ai.koog.agents.core.tools.ToolDescriptor>) = delegate.execute(prompt, model, tools)
    override fun executeStreaming(prompt: ai.koog.prompt.dsl.Prompt, model: ai.koog.prompt.llm.LLModel, tools: List<ai.koog.agents.core.tools.ToolDescriptor>) = delegate.executeStreaming(prompt, model, tools)
    override suspend fun moderate(prompt: ai.koog.prompt.dsl.Prompt, model: ai.koog.prompt.llm.LLModel) = delegate.moderate(prompt, model)
    override fun close() = delegate.close()
}