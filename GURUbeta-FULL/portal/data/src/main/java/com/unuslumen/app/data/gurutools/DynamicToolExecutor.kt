// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.gurutools

import android.content.Context
import com.unuslumen.app.data.di.ToolRegistryHolder
import com.unuslumen.app.data.tor.TorEgress
import com.unuslumen.app.domain.model.GuruDefinedTool
import com.unuslumen.app.domain.model.ToolImplementation
import com.unuslumen.app.domain.model.ToolStatus
import com.unuslumen.app.domain.model.ToolStep
import com.unuslumen.app.domain.repository.GuruToolRepository
import com.unuslumen.app.util.shell.AppToolEnvironment
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
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
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
 *
 * THE THREE CONTRACT FAULTS THIS CLASS NOW GETS RIGHT, all found while verifying
 * build 3.40 and all in the same file:
 *
 *  - #303: a shell tool never received its parameters. `$1` and `${name}` both
 *    produced empty strings. [ShellCommandTemplate] now settles the contract and
 *    accepts every form a person writes.
 *  - #300: the shell ran with Android's PATH, so every binary the app ships was
 *    invisible, and stderr was discarded whenever the exit code was zero, so a
 *    command-not-found arrived as a successful blank. [AppToolEnvironment] is
 *    the environment now, and no stream is ever thrown away.
 *  - #298: a composition could only step on a BUILT-IN tool, because a
 *    GURU-defined tool never enters [ToolRegistry]. [executeStep] now falls
 *    through to the repository, guarded by [GuruToolChain] against a cycle.
 */
@Factory
class DynamicToolExecutor(private val context: Context) : KoinComponent {
    private val toolRegistryHolder: ToolRegistryHolder by inject()

    /**
     * The repository is resolved from the container ON FIRST USE, not injected.
     *
     * `GuruToolRepositoryImpl` takes a [DynamicToolExecutor] in its own
     * constructor, so an eager dependency here is a Koin cycle:
     * DynamicToolExecutor → GuruToolRepository → DynamicToolExecutor. The class
     * doc above records that the same cycle already forced
     * [ToolRegistryHolder] to be lazy, and issue 298 warns that the fall-through
     * would reintroduce it. Resolving at call time means construction needs
     * nothing and the cycle cannot form.
     */
    private val guruToolRepository: GuruToolRepository
        get() = org.koin.java.KoinJavaComponent.getKoin().get()

    private val toolRegistry get() = toolRegistryHolder.toolRegistry
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
    suspend fun execute(tool: GuruDefinedTool, params: Map<String, Any?>): JsonElement =
        execute(tool, params, chain = emptyList())

    /**
     * The recursive form, carrying the chain of names already being executed.
     *
     * [chain] is how a cycle is caught: a composition that reaches itself
     * (directly or through other tools) would otherwise recurse until the
     * process dies, and a StackOverflowError names nothing a reader can act on.
     */
    private suspend fun execute(
        tool: GuruDefinedTool,
        params: Map<String, Any?>,
        chain: List<String>,
    ): JsonElement {
        return when (val impl = tool.implementation) {
            is ToolImplementation.Composition -> executeComposition(tool, impl, params, chain)
            is ToolImplementation.ShellCommand -> executeShellCommand(tool, impl, params)
            is ToolImplementation.Webhook -> executeWebhook(impl, params)
        }
    }

    /**
     * The parameter names a tool declared, IN DECLARATION ORDER.
     *
     * That order is what `$1`, `$2` … address, so it is read from the tool's own
     * schema rather than guessed from the call's argument map. A map has no
     * order a caller can rely on, and using one would make `$1` mean whatever
     * the JSON parser happened to produce first.
     */
    private fun declaredParameterOrder(tool: GuruDefinedTool): List<String> =
        GuruToolSchemaMapper.parseParameters(tool.parameters).map { it.name }

    /**
     * Execute a composition of existing tools.
     */
    private suspend fun executeComposition(
        tool: GuruDefinedTool,
        composition: ToolImplementation.Composition,
        params: Map<String, Any?>,
        chain: List<String>,
    ): JsonElement {
        // The tool's own name opens the chain, so a step that reaches back to it
        // is caught on the first hop rather than one level down.
        val entered = when (val admission = GuruToolChain.admit(chain, tool.name)) {
            is GuruToolChain.Admission.Refused -> return errorResult(admission.reason)
            is GuruToolChain.Admission.Allowed -> admission.next
        }

        val context = mutableMapOf<String, Any?>("params" to params)
        var lastResult: JsonElement = JsonPrimitive("")

        for (step in composition.steps) {
            val resolvedParams = resolveStepParams(step, context)
            val result = executeStep(step.tool, resolvedParams, entered)
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
     *
     * A BUILT-IN IS TRIED FIRST and a GURU-DEFINED TOOL SECOND. That order is
     * deliberate and matches [GuruToolRepositoryImpl.isNameAvailable], which
     * refuses to let a GURU tool take a built-in's name: the two can never
     * collide, so the order is a statement of precedence rather than a tie-break.
     */
    private suspend fun executeStep(
        toolName: String,
        params: Map<String, Any?>,
        chain: List<String>,
    ): JsonElement {
        val builtIn = toolRegistry.tools.find { it.descriptor.name == toolName }
        if (builtIn != null) {
            val args = builtIn.decodeArgs(
                Json.parseToJsonElement(
                    Json.encodeToString(
                        kotlinx.serialization.serializer<Map<String, Any?>>(),
                        params.filterValues { it != null }
                    )
                ).jsonObject
            )
            val result = builtIn.execute(args)
            return builtIn.encodeResult(result)
        }

        // Fall through to the GURU-defined tools, which is the whole of #298: a
        // composition could chain a built-in and nothing else, so "build a small
        // tool, then build a bigger one out of it" had a hard ceiling at depth 1.
        val guruTool = guruToolRepository.getToolByName(toolName)
            ?: return errorResult(
                "Tool not found: $toolName. It is neither a built-in tool nor one you have " +
                    "defined. Use getMyTools to list the ones you have.",
            )

        if (guruTool.status != ToolStatus.APPROVED) {
            return errorResult(
                "Tool '$toolName' exists but is ${guruTool.status.name}, so this step was not " +
                    "run. Use enableTool to make it callable again.",
            )
        }

        val admitted = when (val admission = GuruToolChain.admit(chain, guruTool.name)) {
            is GuruToolChain.Admission.Refused -> return errorResult(admission.reason)
            is GuruToolChain.Admission.Allowed -> admission.next
        }
        return execute(guruTool, params, admitted)
    }

    /**
     * Execute a shell command in a real environment.
     *
     * WHAT WAS WRONG (#300, three separate faults in four lines):
     *
     *  1. `Runtime.getRuntime().exec(arrayOf("sh", "-c", fullCommand))` inherited
     *     Android's PATH, so `curl`, `python3`, `busybox` and every other binary
     *     the app ships were `command not found` while sitting on disk.
     *  2. stdout was read to EOF BEFORE stderr was touched. A child that fills
     *     the stderr pipe buffer blocks, and the parent is blocked reading
     *     stdout, which is the classic deadlock.
     *  3. `stderr` was discarded entirely when the exit code was 0, so a script
     *     whose last statement succeeded returned an empty string with no error
     *     at all — and `curl ...; echo EXIT=$?` returned the string "EXIT=127"
     *     as a successful result.
     *
     * All three are fixed here rather than in a caller, because a GURU-defined
     * tool has no other path to the shell.
     */
    private suspend fun executeShellCommand(
        tool: GuruDefinedTool,
        command: ToolImplementation.ShellCommand,
        params: Map<String, Any?>,
    ): JsonElement = coroutineScope {
        val declaredOrder = declaredParameterOrder(tool)

        // One render pass settles every accepted reference form and quotes each
        // value as a single literal word, so a parameter can never become shell
        // syntax. #303.
        val rendered = ShellCommandTemplate.render(command.command, params, declaredOrder)

        // The legacy positional tail. `command.params` is a list of extra
        // templates appended after the command; each is rendered by the same
        // contract so `$name` works there too, which it previously did not.
        val renderedTail = command.params.map { entry ->
            ShellCommandTemplate.render(entry, params, declaredOrder).command
        }

        val fullCommand = if (renderedTail.isNotEmpty()) {
            "${rendered.command} ${renderedTail.joinToString(" ")}"
        } else {
            rendered.command
        }

        val outcome = withContext(Dispatchers.IO) { runShell(fullCommand) }

        buildJsonObject {
            put("command", fullCommand)
            put("exitCode", outcome.exitCode)
            put("success", outcome.exitCode == 0)
            put("stdout", outcome.stdout)
            // ALWAYS present when it is non-empty, whatever the exit code. This
            // is the field whose absence made a `command not found` invisible.
            put("stderr", outcome.stderr)
            if (outcome.stdout.isBlank() && outcome.stderr.isNotBlank()) {
                // The one thing a model reads first: the output, and the reason
                // there is none. Without this a failed command and an empty one
                // look identical.
                put(
                    "output",
                    "No stdout. The command wrote this to stderr: ${outcome.stderr}",
                )
            } else if (outcome.stdout.isNotBlank()) {
                put("output", outcome.stdout)
            }
            if (rendered.positionalOutOfRange.isNotEmpty()) {
                put(
                    "note",
                    "Positional reference(s) ${rendered.positionalOutOfRange.joinToString(", ") { "\$$it" }} " +
                        "point past the ${declaredOrder.size} declared parameter(s), so they expanded " +
                        "to nothing.",
                )
            }
        }
    }

    /** One shell process, both streams drained concurrently, nothing discarded. */
    private suspend fun runShell(fullCommand: String): ShellOutcome = withContext(Dispatchers.IO) {
        try {
            val builder = ProcessBuilder("sh", "-c", fullCommand)
            // #300: the app's own binaries on PATH, with the inherited
            // environment kept rather than replaced.
            builder.environment().clear()
            builder.environment().putAll(AppToolEnvironment.env(context))
            builder.directory(context.filesDir)

            val process = builder.start()

            // Both streams are drained on their own dispatcher jobs. Reading
            // stdout to EOF first deadlocks as soon as the child fills the
            // stderr pipe, which a tool printing a screenful does easily.
            val stdoutDeferred = async { drain(process.inputStream) }
            val stderrDeferred = async { drain(process.errorStream) }
            val exitCode = process.waitFor()
            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()

            ShellOutcome(exitCode = exitCode, stdout = stdout, stderr = stderr)
        } catch (e: Exception) {
            ShellOutcome(
                exitCode = -1,
                stdout = "",
                stderr = "The command could not be started: ${e.javaClass.simpleName}: ${e.message}",
            )
        }
    }

    private fun drain(stream: java.io.InputStream): String =
        runCatching { BufferedReader(InputStreamReader(stream)).use { it.readText() } }
            .getOrDefault("")
            .trim()

    private data class ShellOutcome(
        val exitCode: Int,
        val stdout: String,
        val stderr: String,
    )

    /** A tool-shaped failure, so a caller sees a reason rather than an exception. */
    private fun errorResult(message: String): JsonElement =
        buildJsonObject { put("error", message) }

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
