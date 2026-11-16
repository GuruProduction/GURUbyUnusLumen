// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

/**
 * The screen tools, described by the mechanism they actually use.
 *
 * WHY THE DESCRIPTIONS GREW. They said "take a screenshot" and nothing else, and the code
 * behind that sentence shelled out to `screencap`, which needs root or shell UID and fails
 * on every ordinary install. A description that names the consent mechanism would have
 * made that mismatch visible the moment somebody read it, which is the whole point of a
 * description being longer than its own name.
 *
 * THE CONSENT IS NOT A DECLARED PERMISSION. MediaProjection is granted by a dialog answered
 * in an Activity, not by a platform permission the dispatcher can check, so declaring one
 * here would make the pre-flight refuse every call on every phone. A tool that cannot
 * capture refuses through ScreenCaptureEngine's own sentence, which names the tool to call
 * first.
 */
object ScreenToolDefinitions : ToolSetRegistration {

    const val TAKE_SCREENSHOT = "takeScreenshot"
    const val RECORD_SCREEN = "recordScreen"

    override val definitions = listOf(
        ToolDefinition(
            name = TAKE_SCREENSHOT,
            description = "Take a picture of what is on the screen right now and return the file " +
                "path and its size. This uses the screen capture session the person consented to, " +
                "the same one captureScreen uses, so it needs that consent and no root and no ADB. " +
                "Without the consent it refuses and says to run requestScreenCapturePermission " +
                "first, which is a real instruction rather than a failure. A live projection that " +
                "produces no frame in two seconds is reported as no frame, which is a screen-off " +
                "problem rather than a permission one.",
            category = "screen",
            parameters = listOf(
                ToolParameter(
                    "filename", ToolParameterType.String, false,
                    "Prefix for the file name. Default 'screenshot'.",
                ),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(
            name = RECORD_SCREEN,
            description = "Record the screen to an MP4, or stop a recording and get the file. " +
                "Recording goes through the platform's own encoder fed by the consented screen " +
                "capture session, so it needs no root and no ADB, and the file is finalised when " +
                "it stops, which means it plays. 'start' takes a duration that acts as a ceiling: " +
                "the recording stops itself at that point, and 'stop' collects it early. A " +
                "recording that wrote no frames is reported as a failure with the empty file " +
                "removed, never as a success with a zero-byte path.",
            category = "screen",
            parameters = listOf(
                ToolParameter(
                    "action", ToolParameterType.Enum, true,
                    "'start' begins a recording, 'stop' finalises it and returns the file.",
                    listOf("start", "stop"),
                ),
                ToolParameter(
                    "duration", ToolParameterType.Integer, false,
                    "Seconds to record, for 'start', 1 to 180. Default 30. The recording stops " +
                        "itself at this point; call stop earlier to finish sooner.",
                ),
            ),
            permissions = emptyList(),
        ),
    )

    override fun executorClass(): KClass<out ToolExecutor> = ScreenToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}
