// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.diagnostics

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.registry.permission.DomainToolModule
import com.unuslumen.app.data.tools.registry.permission.ShellAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.koin.core.module.Module
import org.koin.dsl.module
import kotlin.reflect.KClass

/**
 * Vendor diagnostics and factory test: two capabilities that are usually not there.
 *
 * THE HONESTY RULE THIS DOMAIN EXISTS FOR. DIAGNOSTIC and FACTORY_TEST are vendor permissions.
 * A retail phone does not carry the binaries that use them, and even a phone that does will not
 * grant the permission to an ordinary app. So the most likely correct answer to both tools is
 * "this device does not have it", and a tool that reported a generic failure instead would send
 * a person looking for a setting that does not exist.
 *
 * Every result therefore carries a classified outcome rather than a boolean, and the sentence
 * for an absence is written in one place so both tools say it the same way.
 */

@Serializable
data class DiagnosticsResult(
    val success: Boolean,
    val tool: String,
    /** RAN, ABSENT or REFUSED, from the output rather than the exit code. */
    val outcome: String,
    val shellAvailable: Boolean = false,
    val shellState: String? = null,
    val command: String? = null,
    val exitCode: Int? = null,
    val outputLines: List<String> = emptyList(),
    val decidingLine: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

object DiagnosticsDomainDefinitions : ToolSetRegistration {
    const val DIAGNOSTIC_MODE = "diagnosticMode"
    const val FACTORY_TEST = "factoryTest"

    /**
     * The commands each tool tries, in order.
     *
     * THERE IS NO SINGLE VENDOR DIAGNOSTIC COMMAND. It differs per manufacturer and most retail
     * builds have none of them, so rather than invent one this tries the names that exist across
     * the vendor builds that ship anything at all, and reports absence honestly when every one
     * of them is missing. A command that is present and refuses is a different answer and is
     * classified as such.
     */
    private val diagnosticProbes = listOf(
        "dumpsys vendor.diagnostic",
        "getprop persist.vendor.diag.enable",
    )

    private val factoryProbes = listOf(
        "am start -a android.intent.action.FACTORY_TEST",
    )

    override val definitions = listOf(
        ToolDefinition(
            name = DIAGNOSTIC_MODE,
            description = "Ask the device for its own vendor diagnostic information. This is a " +
                "vendor capability: most retail phones carry no diagnostic service at all, and " +
                "the result says the device does not have it rather than reporting a failure, " +
                "because no grant and no setup would change that. Where a vendor build does expose " +
                "one, this reads it through the shell UID and returns the output. The outcome is " +
                "decided from the output rather than the exit code, since these commands exit zero " +
                "while printing a refusal, and a refused privilege is reported separately from an " +
                "absent one.",
            category = "diagnostics",
            parameters = listOf(
                ToolParameter(
                    "command", ToolParameterType.String, false,
                    "A specific vendor diagnostic command to run instead of the defaults. Only " +
                        "used when you know the device's vendor exposes one.",
                ),
            ),
            // NOT DECLARED. DIAGNOSTIC is vendor-only and unholdable, so a declaration would make
            // the dispatcher refuse every call before the shell check, and the honest answer would
            // never be reached.
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = FACTORY_TEST,
            description = "Ask the device whether it exposes the vendor factory test entry, which " +
                "is a manufacturer-only capability on almost every retail phone. On nearly every " +
                "device this reports that the capability is not present, which is the correct " +
                "answer rather than a failure: FACTORY_TEST is unobtainable even with the shell UID " +
                "on a normal build. Where a vendor build does expose it, the launch is issued " +
                "through the shell UID and the result says the launch happened rather than that a " +
                "test suite is running, because nothing can read back what is on the screen.",
            category = "diagnostics",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = DiagnosticsDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null

    /** The default probes, so the executor and a test read the same list. */
    fun diagnosticCommands(custom: String?): List<String> =
        custom?.trim()?.takeIf { it.isNotEmpty() }?.let { listOf(it) } ?: diagnosticProbes

    fun factoryTestCommand(): String = factoryProbes.first()
}

class DiagnosticsDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            DiagnosticsDomainDefinitions.DIAGNOSTIC_MODE -> diagnosticMode(args)
            DiagnosticsDomainDefinitions.FACTORY_TEST -> factoryTest()
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Vendor diagnostics
    // ------------------------------------------------------------------

    private suspend fun diagnosticMode(args: Map<String, Any?>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val custom = args["command"] as? String
            runProbes(
                tool = DiagnosticsDomainDefinitions.DIAGNOSTIC_MODE,
                what = "a vendor diagnostic service",
                commands = DiagnosticsDomainDefinitions.diagnosticCommands(custom),
            )
        }

    // ------------------------------------------------------------------
    // Factory test
    // ------------------------------------------------------------------

    private suspend fun factoryTest(): ToolExecutionResult = withContext(Dispatchers.IO) {
        runProbes(
            tool = DiagnosticsDomainDefinitions.FACTORY_TEST,
            what = "the vendor factory test entry",
            commands = listOf(DiagnosticsDomainDefinitions.factoryTestCommand()),
        )
    }

    // ------------------------------------------------------------------
    // The one path both tools take
    // ------------------------------------------------------------------

    /**
     * Try each command until one of them produces real output.
     *
     * ABSENT AND REFUSED ARE KEPT APART. If every probe is missing, the device does not have the
     * capability and that is the answer. If any probe ran and was refused, the capability exists
     * and this build cannot use it, which is a different sentence and a different fix, namely
     * none. Reporting a refused privilege as absence would be as wrong as reporting absence as a
     * failure.
     */
    private suspend fun runProbes(
        tool: String,
        what: String,
        commands: List<String>,
    ): ToolExecutionResult {
        val shellState = runCatching { ShellAccess.state(context) }.getOrNull()
        if (shellState == null || !shellState.usable) {
            return emit(
                DiagnosticsResult(
                    success = false,
                    tool = tool,
                    outcome = "UNKNOWN",
                    shellAvailable = false,
                    shellState = shellState?.name,
                    error = "Nothing was run. This needs the shell UID even to find out whether the " +
                        "device has the capability, and " +
                        (shellState?.sentence() ?: "the shell channel could not be read at all."),
                ),
            )
        }

        var lastRefusal: DiagnosticsVerdict.Verdict? = null
        var lastCommand: String? = null
        var lastExit: Int? = null

        for (command in commands) {
            lastCommand = command
            val result = runCatching { ShellAccess.bridge(context).execute(command) }.getOrNull()
                ?: continue
            lastExit = result.exitCode

            val verdict = DiagnosticsVerdict.classify(result.exitCode, result.stdout, result.stderr)
            when (verdict.outcome) {
                DiagnosticsVerdict.Outcome.RAN -> {
                    return emit(
                        DiagnosticsResult(
                            success = true,
                            tool = tool,
                            outcome = verdict.outcome.name,
                            shellAvailable = true,
                            shellState = shellState.name,
                            command = command,
                            exitCode = result.exitCode,
                            outputLines = verdict.lines.take(DiagnosticsVerdict.OUTPUT_LINE_LIMIT),
                            note = "Read ${verdict.lines.size} output lines from the device's own " +
                                "$what. The outcome was decided from the output, not the exit code.",
                        ),
                    )
                }

                DiagnosticsVerdict.Outcome.REFUSED -> {
                    // Remembered rather than returned at once: a later probe might run, and a real
                    // reading is worth more than the first refusal.
                    lastRefusal = verdict
                }

                DiagnosticsVerdict.Outcome.ABSENT -> Unit
            }
        }

        if (lastRefusal != null) {
            return failure(
                DiagnosticsResult(
                    success = false,
                    tool = tool,
                    outcome = DiagnosticsVerdict.Outcome.REFUSED.name,
                    shellAvailable = true,
                    shellState = shellState.name,
                    command = lastCommand,
                    exitCode = lastExit,
                    outputLines = lastRefusal.lines.take(DiagnosticsVerdict.OUTPUT_LINE_LIMIT),
                    decidingLine = lastRefusal.decidingLine,
                    error = DiagnosticsVerdict.absenceSentence(
                        what = what,
                        outcome = DiagnosticsVerdict.Outcome.REFUSED,
                        detail = lastRefusal.decidingLine,
                    ),
                ),
            )
        }

        return failure(
            DiagnosticsResult(
                success = false,
                tool = tool,
                outcome = DiagnosticsVerdict.Outcome.ABSENT.name,
                shellAvailable = true,
                shellState = shellState.name,
                command = lastCommand,
                exitCode = lastExit,
                error = DiagnosticsVerdict.absenceSentence(
                    what = what,
                    outcome = DiagnosticsVerdict.Outcome.ABSENT,
                ),
            ),
        )
    }

    private fun emit(payload: DiagnosticsResult): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(DiagnosticsResult.serializer(), payload),
        resultData = payload,
        resultObject = null,
        success = payload.success,
        error = payload.error,
    )

    private fun failure(payload: DiagnosticsResult): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(DiagnosticsResult.serializer(), payload),
        resultData = payload,
        resultObject = null,
        success = false,
        error = payload.error,
    )
}

object DiagnosticsDomainModule : DomainToolModule {
    override val id: String = "diagnostics"

    override val registrations: List<ToolSetRegistration> = listOf(DiagnosticsDomainDefinitions)

    override fun bindings(): Module = module {
        factory { DiagnosticsDomainExecutor(get()) }
    }
}
