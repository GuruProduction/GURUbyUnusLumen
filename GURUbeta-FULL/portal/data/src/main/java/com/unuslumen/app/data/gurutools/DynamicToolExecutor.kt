package com.unuslumen.app.data.gurutools

import com.unuslumen.app.data.di.ToolRegistryHolder
import com.unuslumen.app.data.tor.TorEgress
import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.model.ToolImplementation
import com.unuslumen.app.domain.model.ToolStep
import io.ktor.client.HttpClient
import io.ktor.client.request.delete
import io.ktor.client.request.get
import io.ktor.client.request.head
import io.ktor.client.request.patch
import io.ktor.client.request.post
import io.ktor.client.request.put
import io.ktor.client.request.setBody
import io.ktor.client.request.header
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.contentType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.koin.core.annotation.Factory
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.regex.Pattern

/**
 * Executes Guru-defined tools by composing existing tools or executing shell commands/webhooks.
 *
 * Uses lazy Koin injection for [ToolRegistryHolder] to break the circular dependency:
 * ToolRegistryHolder → AutomationToolSet → AutomationRepository → DynamicToolExecutor → ToolRegistryHolder
 */
@Factory
class DynamicToolExecutor : KoinComponent {
    private val toolRegistryHolder: ToolRegistryHolder by inject()
    private val toolRegistry get() = toolRegistryHolder.toolRegistry
    private val json = Json { ignoreUnknownKeys = true }
    private val variablePattern = Pattern.compile("\\$\\{([^}]+)\\}")

    /**
     * Webhook egress rides the same sovereign policy as every other HTTP path
     * in the app: the user's local/LAN targets go direct, everything else goes
     * through Tor, and when Tor is down the call fails closed instead of
     * leaking clearnet. Same no-timeout AGI posture as the LLM clients.
     */
    private val httpClient: HttpClient = TorEgress.httpClient()

    /**
     * Execute a Guru-defined tool with the given parameters.
     */
    suspend fun execute(tool: GuruDefinedTool, params: Map<String, Any?>): JsonElement {
        return when (val impl = tool.implementation) {
            is ToolImplementation.Composition -> executeComposition(impl, params)
            is ToolImplementation.ShellCommand -> executeShellCommand(impl, params)
            is ToolImplementation.Webhook -> executeWebhook(impl, params)
        }
    }

    /**
     * Execute a composition of existing tools.
     */
    private suspend fun executeComposition(
        composition: ToolImplementation.Composition,
        params: Map<String, Any?>
    ): JsonElement {
        val context = mutableMapOf<String, Any?>("params" to params)
        var lastResult: JsonElement = JsonPrimitive("")

        for (step in composition.steps) {
            val resolvedParams = resolveStepParams(step, context)
            val result = executeStep(step.tool, resolvedParams)
            context[step.output] = result
            lastResult = result
        }

        return lastResult
    }

    /**
     * Resolve parameters for a step, interpolating variables.
     */
    private fun resolveStepParams(step: ToolStep, context: Map<String, Any?>): Map<String, Any?> {
        return step.params.mapValues { (_, value) ->
            resolveVariables(value, context)
        }
    }

    /**
     * Resolve variables in a string like "${params.location}" or "${searchResult.url}".
     */
    private fun resolveVariables(template: String, context: Map<String, Any?>): Any? {
        val matcher = variablePattern.matcher(template)
        
        if (!matcher.find()) {
            // No variables, return as-is
            return template
        }
        
        // If the entire string is a single variable, return the value directly
        matcher.reset()
        if (matcher.matches()) {
            val varPath = matcher.group(1) ?: return template
            return resolveVariablePath(varPath, context)
        }

        // Multiple variables or mixed content, build a string
        matcher.reset()
        val result = StringBuffer()
        while (matcher.find()) {
            val varPath = matcher.group(1) ?: continue
            val value = resolveVariablePath(varPath, context)
            matcher.appendReplacement(result, value?.toString() ?: "")
        }
        matcher.appendTail(result)
        return result.toString()
    }

    /**
     * Resolve a variable path like "params.location" or "searchResult.url".
     */
    private fun resolveVariablePath(path: String, context: Map<String, Any?>): Any? {
        val parts = path.split(".")
        var current: Any? = context
        
        for (part in parts) {
            current = when (current) {
                is Map<*, *> -> current[part]
                is JsonElement -> {
                    if (current is kotlinx.serialization.json.JsonObject) {
                        current[part]
                    } else {
                        null
                    }
                }
                else -> null
            }
            if (current == null) return null
        }
        
        return current
    }

    /**
     * Execute a single tool step.
     */
    private suspend fun executeStep(toolName: String, params: Map<String, Any?>): JsonElement {
        val tool = toolRegistry.tools.find { it.descriptor.name == toolName }
            ?: throw IllegalArgumentException("Tool not found: $toolName")

        val args = tool.decodeArgs(
            kotlinx.serialization.json.Json.parseToJsonElement(
                kotlinx.serialization.json.Json.encodeToString(
                    kotlinx.serialization.serializer<Map<String, Any?>>(),
                    params.filterValues { it != null }
                )
            ).jsonObject
        )
        val result = tool.execute(args)
        return tool.encodeResult(result)
    }

    /**
     * Execute a shell command using Runtime.exec().
     * For root-level access, commands are executed via the shell with full environment.
     */
    private suspend fun executeShellCommand(
        command: ToolImplementation.ShellCommand,
        params: Map<String, Any?>
    ): JsonElement = withContext(Dispatchers.IO) {
        // Resolve variables in command
        val resolvedCommand = resolveVariables(command.command, mapOf("params" to params)) as? String
            ?: command.command
        
        // Resolve variables in params
        val resolvedParams = command.params.map { 
            resolveVariables(it, mapOf("params" to params))?.toString() ?: it
        }
        
        // Build full command
        val fullCommand = if (resolvedParams.isNotEmpty()) {
            "$resolvedCommand ${resolvedParams.joinToString(" ")}"
        } else {
            resolvedCommand
        }
        
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCommand))
            val stdout = BufferedReader(InputStreamReader(process.inputStream)).readText()
            val stderr = BufferedReader(InputStreamReader(process.errorStream)).readText()
            val exitCode = process.waitFor()
            
            if (exitCode == 0) {
                JsonPrimitive(stdout.trim())
            } else {
                JsonPrimitive("Error (exit $exitCode): ${stderr.trim().ifBlank { stdout.trim() }}")
            }
        } catch (e: Exception) {
            JsonPrimitive("Shell execution failed: ${e.message}")
        }
    }

    /**
     * Execute a webhook call using Ktor HTTP client.
     */
    private suspend fun executeWebhook(
        webhook: ToolImplementation.Webhook,
        params: Map<String, Any?>
    ): JsonElement {
        // Resolve variables in URL
        val resolvedUrl = resolveVariables(webhook.url, mapOf("params" to params)) as? String
            ?: webhook.url
        
        // Resolve headers
        val resolvedHeaders = webhook.headers.mapValues { (_, value) ->
            resolveVariables(value, mapOf("params" to params))?.toString() ?: value
        }
        
        return try {
            val response: io.ktor.client.statement.HttpResponse = when (webhook.method.uppercase()) {
                "GET" -> httpClient.get(resolvedUrl) {
                    resolvedHeaders.forEach { (key, value) -> header(key, value) }
                }
                "POST" -> httpClient.post(resolvedUrl) {
                    resolvedHeaders.forEach { (key, value) -> header(key, value) }
                    contentType(ContentType.Application.Json)
                    setBody(params["body"]?.toString() ?: "")
                }
                "PUT" -> httpClient.put(resolvedUrl) {
                    resolvedHeaders.forEach { (key, value) -> header(key, value) }
                    contentType(ContentType.Application.Json)
                    setBody(params["body"]?.toString() ?: "")
                }
                "DELETE" -> httpClient.delete(resolvedUrl) {
                    resolvedHeaders.forEach { (key, value) -> header(key, value) }
                }
                "PATCH" -> httpClient.patch(resolvedUrl) {
                    resolvedHeaders.forEach { (key, value) -> header(key, value) }
                    contentType(ContentType.Application.Json)
                    setBody(params["body"]?.toString() ?: "")
                }
                "HEAD" -> httpClient.head(resolvedUrl) {
                    resolvedHeaders.forEach { (key, value) -> header(key, value) }
                }
                else -> throw IllegalArgumentException("Unsupported HTTP method: ${webhook.method}")
            }
            
            val body = response.bodyAsText()
            val status = response.status.value
            JsonPrimitive("HTTP $status: ${body.take(2000)}")
        } catch (e: Exception) {
            JsonPrimitive("Webhook execution failed: ${e.message}")
        }
    }
}