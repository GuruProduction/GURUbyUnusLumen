// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.senses

import android.content.Context
import android.util.DisplayMetrics
import android.view.WindowManager
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
 * Input injection: taps, swipes, text and key presses without the accessibility service.
 *
 * WHY THIS EXISTS. Every other way GURU drives a screen goes through an accessibility service,
 * and that service is a Settings toggle the person has to switch on by hand. When they will not,
 * nothing can be tapped. INJECT_EVENTS reaches the input dispatcher directly at shell UID, so
 * this is the fallback that makes the capability exist for someone who never enables
 * accessibility, which is the state of the reference device today.
 *
 * WHAT IT IS HONEST ABOUT. An injected event is delivered to whatever is focused when it lands.
 * Nothing here can observe whether the tap hit the intended thing, so the result says the event
 * was dispatched and not that the screen changed. Claiming the latter is how a driver reports
 * success while tapping the wrong screen.
 */

@Serializable
data class InputInjectionResult(
    val success: Boolean,
    val action: String,
    /** The exact command that ran, so a failure is answerable rather than mysterious. */
    val command: String? = null,
    /** The shell's own exit code. Zero proves the command ran, not that it hit anything. */
    val exitCode: Int? = null,
    val shellAvailable: Boolean = false,
    val shellState: String? = null,
    val screenWidth: Int = 0,
    val screenHeight: Int = 0,
    val output: String? = null,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

object InputInjectionDefinitions : ToolSetRegistration {
    const val INJECT_INPUT = "injectInput"

    override val definitions = listOf(
        ToolDefinition(
            name = INJECT_INPUT,
            description = "Send a tap, swipe, text or key press to whatever is on screen, straight " +
                "to the input dispatcher rather than through the accessibility service. This is " +
                "the fallback when accessibility is switched off: it needs the shell UID through " +
                "Shizuku and needs no accessibility grant at all. It cannot see the screen, so it " +
                "reports that the event was dispatched rather than that anything changed; pair it " +
                "with a screenshot to confirm what actually happened. Coordinates outside the " +
                "screen are refused because the input command accepts them and silently does " +
                "nothing. Key names are the fixed list this tool documents, not arbitrary " +
                "KEYCODE strings.",
            category = "ui_automation",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true,
                    "TAP, SWIPE, TEXT or KEY.",
                    listOf("TAP", "SWIPE", "TEXT", "KEY"),
                ),
                ToolParameter("x", ToolParameterType.Integer, false, "TAP only: the horizontal point."),
                ToolParameter("y", ToolParameterType.Integer, false, "TAP only: the vertical point."),
                ToolParameter("startX", ToolParameterType.Integer, false, "SWIPE only: where it starts."),
                ToolParameter("startY", ToolParameterType.Integer, false, "SWIPE only: where it starts."),
                ToolParameter("endX", ToolParameterType.Integer, false, "SWIPE only: where it ends."),
                ToolParameter("endY", ToolParameterType.Integer, false, "SWIPE only: where it ends."),
                ToolParameter(
                    "durationMs", ToolParameterType.Integer, false,
                    "SWIPE only: how long the gesture takes, zero or more. Default 300.",
                ),
                ToolParameter(
                    "text", ToolParameterType.String, false,
                    "TEXT only: what to type. Must be a single line, because the input command " +
                        "cannot type a newline.",
                ),
                ToolParameter(
                    "key", ToolParameterType.String, false,
                    "KEY only: one of home, back, recents, menu, power, wakeup, sleep, enter, tab, " +
                        "space, escape, delete, backspace, forward_delete, search, camera, " +
                        "volume_up, volume_down, mute, play_pause, play, pause, next, previous, up, " +
                        "down, left, right, center, notification, settings, call, end_call, " +
                        "brightness_up, brightness_down.",
                ),
            ),
            // NOT DECLARED. INJECT_EVENTS is signature-level and can never be held by an
            // ordinary app, so a declaration would make the dispatcher refuse every call before
            // the shell check. The real gate is the shell UID, reported by the executor.
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = InputInjectionDomainExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class InputInjectionDomainExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            InputInjectionDefinitions.INJECT_INPUT -> injectInput(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    private suspend fun injectInput(args: Map<String, Any?>): ToolExecutionResult =
        withContext(Dispatchers.IO) {
            val action = ((args["action"] as? String) ?: "").uppercase()
            val metrics = displayMetrics()

            val built = when (action) {
                "TAP" -> InputInjection.tap(
                    x = (args["x"] as? Number)?.toInt(),
                    y = (args["y"] as? Number)?.toInt(),
                    width = metrics.first,
                    height = metrics.second,
                )

                "SWIPE" -> InputInjection.swipe(
                    startX = (args["startX"] as? Number)?.toInt(),
                    startY = (args["startY"] as? Number)?.toInt(),
                    endX = (args["endX"] as? Number)?.toInt(),
                    endY = (args["endY"] as? Number)?.toInt(),
                    durationMs = (args["durationMs"] as? Number)?.toInt(),
                    width = metrics.first,
                    height = metrics.second,
                )

                "TEXT" -> InputInjection.text(args["text"] as? String)

                "KEY" -> InputInjection.key(args["key"] as? String)

                else -> return@withContext failure(
                    InputInjectionResult(
                        success = false,
                        action = action,
                        error = "The action must be TAP, SWIPE, TEXT or KEY, and '$action' is none " +
                            "of them.",
                    ),
                )
            }

            val command = when (built) {
                is InputInjection.Result.Invalid -> return@withContext failure(
                    InputInjectionResult(
                        success = false,
                        action = action,
                        screenWidth = metrics.first,
                        screenHeight = metrics.second,
                        error = built.reason,
                    ),
                )

                is InputInjection.Result.Command -> built.command
            }

            // The shell state is read rather than assumed, because INJECT_EVENTS is unholdable
            // and the shell UID is the only real gate. Its three states need three different
            // instructions, which a boolean would collapse.
            val shellState = runCatching { ShellAccess.state(context) }.getOrNull()
            if (shellState == null || !shellState.usable) {
                return@withContext failure(
                    InputInjectionResult(
                        success = false,
                        action = action,
                        command = command,
                        shellAvailable = false,
                        shellState = shellState?.name,
                        screenWidth = metrics.first,
                        screenHeight = metrics.second,
                        error = "Nothing was injected. Driving the screen without accessibility " +
                            "needs the shell UID, and " +
                            (shellState?.sentence() ?: "the shell channel could not be read at all."),
                    ),
                )
            }

            val result = runCatching { ShellAccess.bridge(context).execute(command) }.getOrNull()
                ?: return@withContext failure(
                    InputInjectionResult(
                        success = false,
                        action = action,
                        command = command,
                        shellAvailable = true,
                        shellState = shellState.name,
                        screenWidth = metrics.first,
                        screenHeight = metrics.second,
                        error = "The shell accepted the command and threw while returning, so it is " +
                            "unknown whether the event was dispatched.",
                    ),
                )

            val output = (result.stdout.ifBlank { result.stderr }).trim().take(300)
            val dispatched = result.exitCode == 0

            val r = InputInjectionResult(
                success = dispatched,
                action = action,
                command = command,
                exitCode = result.exitCode,
                shellAvailable = true,
                shellState = shellState.name,
                screenWidth = metrics.first,
                screenHeight = metrics.second,
                output = output.ifBlank { null },
                note = if (dispatched) {
                    "The event was dispatched to the input system. This tool cannot see the " +
                        "screen, so it cannot tell what received it: take a screenshot afterwards " +
                        "to find out what actually changed."
                } else {
                    "The shell ran and exited ${result.exitCode}, so the event was not dispatched."
                },
                error = if (dispatched) null else {
                    "The input command exited ${result.exitCode} without dispatching the event."
                },
            )
            emit(r)
        }

    /**
     * The display size, which every coordinate is checked against.
     *
     * A tap outside the screen is accepted by the input command and does nothing, so the
     * bounds have to come from the platform rather than from the caller's belief. A size of
     * zero means it could not be read, and the command builder refuses rather than sending a
     * tap at a point it cannot validate.
     */
    private fun displayMetrics(): Pair<Int, Int> {
        val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as? WindowManager
            ?: return 0 to 0

        val metrics = DisplayMetrics()
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val bounds = windowManager.currentWindowMetrics.bounds
            metrics.widthPixels = bounds.width()
            metrics.heightPixels = bounds.height()
        } else {
            @Suppress("DEPRECATION")
            windowManager.defaultDisplay.getRealMetrics(metrics)
        }
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun emit(payload: InputInjectionResult): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(InputInjectionResult.serializer(), payload),
        resultData = payload,
        resultObject = null,
        success = payload.success,
        error = payload.error,
    )

    private fun failure(payload: InputInjectionResult): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(InputInjectionResult.serializer(), payload),
        resultData = payload,
        resultObject = null,
        success = false,
        error = payload.error,
    )
}

object InputInjectionDomainModule : DomainToolModule {
    override val id: String = "input_injection"

    override val registrations: List<ToolSetRegistration> = listOf(InputInjectionDefinitions)

    override fun bindings(): Module = module {
        factory { InputInjectionDomainExecutor(get()) }
    }
}
