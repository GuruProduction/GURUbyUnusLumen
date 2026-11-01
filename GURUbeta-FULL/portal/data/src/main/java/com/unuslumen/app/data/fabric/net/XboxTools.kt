// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultData
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import com.unuslumen.app.data.tools.senses.emitPayload
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.reflect.KClass

/**
 * The Xbox tools, so a model can actually reach a console.
 *
 * WHY THESE EXIST AS TOOLS RATHER THAN ONLY AS CLASSES. A class that nothing registers is a class
 * the model cannot call, which is the same as not having it. Before this file, GURU could not see
 * a console at all: no tool name in the registry meant no way to ask.
 *
 * TWO TOOLS, because a console needs two verbs: find the ones on this network, and drive one.
 */

@Serializable
data class XboxDiscoverResult(
    val success: Boolean,
    val consoles: List<XboxView> = emptyList(),
    val count: Int = 0,
    val note: String? = null,
    val error: String? = null,
) : ToolResultData

@Serializable
data class XboxView(
    val address: String,
    val name: String,
    /** Whether the console reports that clients without an Xbox Live sign-in are allowed. */
    val allowsAnonymous: Boolean,
    /** Whether the console issued the Live ID that power commands need. */
    val hasLiveId: Boolean,
    val note: String,
)

@Serializable
data class XboxControlResult(
    val success: Boolean,
    val console: String = "",
    val command: String = "",
    /** Each stage of the handshake, so a failure says exactly where it stopped. */
    val stages: List<String> = emptyList(),
    val error: String? = null,
) : ToolResultData

object XboxToolDefinitions : ToolSetRegistration {

    const val XBOX_DISCOVER = "xboxDiscover"
    const val XBOX_CONTROL = "xboxControl"

    override val definitions = listOf(
        ToolDefinition(
            name = XBOX_DISCOVER,
            description = "Find the Xbox consoles on this wifi network. Reports each console's " +
                "address, its name, whether it allows connections from a device that is not " +
                "signed in to Xbox Live, and whether it issued the id that power commands need. " +
                "A console answers only while it is awake or in standby with its remote features " +
                "on, so an empty list means nothing answered rather than no console existing.",
            category = "network",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = XBOX_CONTROL,
            description = "Drive an Xbox console on this network. Commands: press a button " +
                "(a, b, x, y, guide, menu, view, a direction, either bumper, either stick click), " +
                "pull either trigger, move either stick, media play/pause/stop, power on, power " +
                "off, or status. Reports every stage of the connection so a refusal says exactly " +
                "where it stopped rather than failing silently. Launching a game by name is not " +
                "available: Microsoft removed that message in 2019, so a game is started by " +
                "navigating with the direction buttons.",
            category = "network",
            parameters = listOf(
                ToolParameter(
                    "console", ToolParameterType.String, true,
                    "Console address or name, as xboxDiscover reported it.",
                ),
                ToolParameter(
                    "command", ToolParameterType.String, true,
                    "press, trigger, stick, media, power on, power off, or status.",
                    enumValues = listOf("press", "trigger", "stick", "media", "power on", "power off", "status"),
                ),
                ToolParameter(
                    "button", ToolParameterType.String, false,
                    "For press: a, b, x, y, guide, menu, view, up, down, left, right, lb, rb, l3, r3.",
                ),
                ToolParameter(
                    "value", ToolParameterType.Float, false,
                    "For trigger: travel from 0 to 1. For stick: the X or Y position from -1 to 1.",
                ),
                ToolParameter(
                    "side", ToolParameterType.String, false,
                    "For trigger or stick: left or right. Default right for a trigger, left for a stick.",
                    enumValues = listOf("left", "right"),
                ),
                ToolParameter(
                    "media", ToolParameterType.String, false,
                    "For media: play, pause or stop.",
                    enumValues = listOf("play", "pause", "stop"),
                ),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = XboxToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}

class XboxToolExecutor(
    private val console: XboxConsole = XboxConsole(),
) : ToolExecutor {

    /** A single-Context constructor, because every domain executor is built that way. */
    constructor(context: Context) : this(XboxConsole())

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            XboxToolDefinitions.XBOX_DISCOVER -> discover()
            XboxToolDefinitions.XBOX_CONTROL -> control(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    /**
     * Find the consoles on the network.
     *
     * A console that allows anonymous clients gets a note saying so, because that is the one
     * setting a person has to turn on for GURU to control it, and naming it is the difference
     * between a working feature and a mystery.
     */
    private suspend fun discover(): ToolExecutionResult {
        val found = console.discover()

        val r = XboxDiscoverResult(
            success = found.isNotEmpty(),
            consoles = found.map { tv ->
                XboxView(
                    address = tv.address,
                    name = tv.name ?: "Xbox",
                    allowsAnonymous = tv.allowsAnonymous,
                    hasLiveId = !tv.liveId.isNullOrBlank(),
                    note = when {
                        !tv.allowsAnonymous ->
                            "This console does not allow unsigned-in devices. To control it from " +
                                "here, turn on its setting that allows connections from any device."
                        tv.liveId.isNullOrBlank() ->
                            "The console answered but issued no id, so power commands cannot be sent " +
                                "to it."
                        else ->
                            "Ready: it allows connections without a sign-in and issued its id."
                    },
                )
            },
            count = found.size,
            note = if (found.isEmpty()) {
                "No console answered. A console answers discovery only while it is awake, or in " +
                    "standby with its remote features switched on."
            } else {
                null
            },
            error = if (found.isEmpty()) {
                "No Xbox answered on this network. Check it is switched on and that its remote " +
                    "features are enabled in its own settings."
            } else {
                null
            },
        )
        return emitPayload(json, json.encodeToString(XboxDiscoverResult.serializer(), r), r, r.success, r.error)
    }

    /**
     * Drive one console.
     *
     * Every command that needs the encrypted channel runs the handshake first, because a console
     * will not accept a press until a session exists. The stages are reported so a refusal names
     * the step that failed.
     */
    private suspend fun control(args: Map<String, Any?>): ToolExecutionResult {
        val query = (args["console"] as? String)?.trim()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'console'")
        val rawCommand = (args["command"] as? String)?.trim()?.lowercase()?.ifBlank { null }
            ?: return ToolExecutionResult.error("Missing 'command'")

        val found = console.discover()
        val match = found.firstOrNull {
            it.address == query ||
                it.name?.contains(query, ignoreCase = true) == true ||
                it.uuid?.equals(query, ignoreCase = true) == true
        } ?: return fail(
            query,
            rawCommand,
            emptyList(),
            "No console matching '$query' answered. Run xboxDiscover with the console awake.",
        )

        // Power on is the one command that needs no session, because it is a plain packet.
        if (rawCommand == "power on" || rawCommand == "poweron") {
            val result = console.powerOn(match)
            return finish(match, rawCommand, emptyList(), result)
        }

        if (rawCommand == "status") {
            return finish(
                match,
                rawCommand,
                emptyList(),
                XboxConsole.Result(
                    true,
                    "${match.name ?: "Xbox"} at ${match.address}: " +
                        if (match.allowsAnonymous) {
                            "accepts unsigned-in clients and issued its id."
                        } else {
                            "does not accept unsigned-in clients, so its setting has to change " +
                                "before it can be controlled."
                        },
                ),
            )
        }

        val handshake = console.openSession(match)
        val stages = handshake.stages.map { stage ->
            "${if (stage.ok) "ok" else "failed"} ${stage.name}: ${stage.detail}"
        }
        if (!handshake.connected) {
            return fail(match.address, rawCommand, stages, handshake.sentence())
        }

        val outcome: XboxConsole.Result = when (rawCommand) {
            "press" -> {
                val word = (args["button"] as? String)?.trim()?.ifBlank { null }
                    ?: return fail(match.address, rawCommand, stages, "Missing 'button' for press.")
                val button = XboxGamepad.Button.fromRaw(word) ?: XboxGamepad.direction(word)
                if (button == null) {
                    return fail(
                        match.address,
                        rawCommand,
                        stages,
                        "'$word' is not a button. Valid: " +
                            XboxGamepad.Button.entries.joinToString(", ") { it.label } + ".",
                    )
                }
                console.pressButton(handshake, button)
            }
            "trigger" -> {
                val travel = (args["value"] as? Number)?.toFloat() ?: 1f
                val right = !(args["side"] as? String).equals("left", ignoreCase = true)
                console.pullTrigger(handshake, travel, right)
            }
            "stick" -> {
                val x = (args["value"] as? Number)?.toFloat() ?: 0f
                val right = (args["side"] as? String).equals("right", ignoreCase = true)
                console.moveStick(handshake, x, 0f, right)
            }
            "media" -> {
                val word = (args["media"] as? String)?.trim()?.ifBlank { null }
                    ?: return fail(match.address, rawCommand, stages, "Missing 'media' for media.")
                val code = console.mediaCommandFor(word)
                    ?: return fail(match.address, rawCommand, stages, "'$word' is not play, pause or stop.")
                console.mediaCommand(handshake, code)
            }
            "power off", "poweroff" -> {
                console.powerOff(handshake, match)
            }
            else -> return fail(
                match.address,
                rawCommand,
                stages,
                "Unknown command '$rawCommand'. Use press, trigger, stick, media, power on, " +
                    "power off or status.",
            )
        }

        return finish(match, rawCommand, stages, outcome)
    }

    private fun finish(
        console: XboxConsole.Console,
        command: String,
        stages: List<String>,
        result: XboxConsole.Result,
    ): ToolExecutionResult {
        val r = XboxControlResult(
            success = result.sent,
            console = console.name ?: console.address,
            command = command,
            stages = stages,
            error = if (result.sent) null else result.reason,
        )
        return emitPayload(json, json.encodeToString(XboxControlResult.serializer(), r), r, r.success, r.error)
    }

    private fun fail(
        console: String,
        command: String,
        stages: List<String>,
        reason: String,
    ): ToolExecutionResult {
        val r = XboxControlResult(
            success = false,
            console = console,
            command = command,
            stages = stages,
            error = reason,
        )
        return emitPayload(json, json.encodeToString(XboxControlResult.serializer(), r), r, false, reason)
    }
}
