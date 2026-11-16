// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.screen.ScreenCaptureEngine
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The screen tools: a picture of the display and a recording of it.
 *
 * WHAT WAS WRONG (issues 41 and 43). `takeScreenshot` shelled out to `screencap`, which
 * needs root or shell UID, so on every ordinary install it failed with an error that named
 * root and ADB as though those were the intended mechanisms. Neither was needed:
 * MediaProjection is what the platform offers to any app the person consents for, and it
 * was already working in this same app through `captureScreen`.
 *
 * `recordScreen` shelled out to `screenrecord` with the same root problem, and it also
 * reported `success = true` on a zero-byte file because its guard was `file.exists()`.
 *
 * Both now go through ScreenCaptureEngine, which is the one place that owns the projection,
 * the frame grab and the encoder. There is no shell path left in either tool. A shell
 * fallback here would be worse than useless: it would still fail on the phones that matter
 * and it would make a working mechanism look like it needed a privileged one.
 */
class ScreenToolExecutor(
    private val context: Context,
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult =
        when (toolName) {
            ScreenToolDefinitions.TAKE_SCREENSHOT -> takeScreenshot(args)
            ScreenToolDefinitions.RECORD_SCREEN -> recordScreen(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }

    // ------------------------------------------------------------------
    // Screenshot
    // ------------------------------------------------------------------

    private suspend fun takeScreenshot(args: Map<String, Any?>): ToolExecutionResult {
        val prefix = (args["filename"] as? String)?.trim()?.takeIf { it.isNotEmpty() } ?: "screenshot"

        return when (val outcome = ScreenCaptureEngine.screenshot(context, prefix)) {
            is ScreenCaptureEngine.ScreenshotOutcome.Captured ->
                success(ScreenShotResult(true, outcome.path, outcome.sizeBytes, null))

            is ScreenCaptureEngine.ScreenshotOutcome.Refused ->
                failure(ScreenShotResult(false, "", 0, outcome.reason))

            is ScreenCaptureEngine.ScreenshotOutcome.NoFrame ->
                failure(ScreenShotResult(false, "", 0, outcome.reason))

            is ScreenCaptureEngine.ScreenshotOutcome.Failed ->
                failure(ScreenShotResult(false, "", 0, outcome.reason))
        }
    }

    // ------------------------------------------------------------------
    // Recording
    // ------------------------------------------------------------------

    private suspend fun recordScreen(args: Map<String, Any?>): ToolExecutionResult {
        val action = (args["action"] as? String)?.lowercase()
            ?: return ToolExecutionResult.error(
                "This call needs an 'action' of 'start' or 'stop'.",
            )

        val duration = ScreenRecordingCheck.durationSeconds((args["duration"] as? Number)?.toInt())

        return when (action) {
            "start" -> {
                // A duration is clamped to what the encoder can sustain. ScreenRecordingCheck
                // owns that rule because it already owned it for the shell path, and moving
                // the rule here rather than deleting it keeps the clamp in one tested place.
                when (val outcome = ScreenCaptureEngine.startRecording(context, duration)) {
                    is ScreenCaptureEngine.RecordingOutcome.Started ->
                        success(
                            ScreenShotResult(
                                true,
                                outcome.path,
                                0,
                                null,
                            ),
                        )

                    is ScreenCaptureEngine.RecordingOutcome.AlreadyRecording ->
                        failure(
                            ScreenShotResult(
                                false,
                                outcome.path,
                                0,
                                "A recording is already running to ${outcome.path}. Call stop " +
                                    "first; starting another would silently abandon that one.",
                            ),
                        )

                    is ScreenCaptureEngine.RecordingOutcome.Refused ->
                        failure(ScreenShotResult(false, "", 0, outcome.reason))

                    is ScreenCaptureEngine.RecordingOutcome.Failed ->
                        failure(ScreenShotResult(false, "", 0, outcome.reason))

                    // Not reachable from start, but the sealed type is exhausted rather than
                    // given a catch-all that would hide a future outcome.
                    is ScreenCaptureEngine.RecordingOutcome.Stopped ->
                        failure(ScreenShotResult(false, outcome.path, outcome.sizeBytes, "Unexpected state."))

                    ScreenCaptureEngine.RecordingOutcome.NotRecording ->
                        failure(ScreenShotResult(false, "", 0, "Unexpected state."))
                }
            }

            "stop" -> when (val outcome = ScreenCaptureEngine.stopRecording()) {
                is ScreenCaptureEngine.RecordingOutcome.Stopped ->
                    success(ScreenShotResult(true, outcome.path, outcome.sizeBytes, null))

                ScreenCaptureEngine.RecordingOutcome.NotRecording ->
                    failure(
                        ScreenShotResult(
                            false,
                            "",
                            0,
                            "No recording was started by this app run, so there is nothing to " +
                                "stop and no file to return. Call start first.",
                        ),
                    )

                is ScreenCaptureEngine.RecordingOutcome.Failed ->
                    failure(ScreenShotResult(false, "", 0, outcome.reason))

                is ScreenCaptureEngine.RecordingOutcome.Refused ->
                    failure(ScreenShotResult(false, "", 0, outcome.reason))

                is ScreenCaptureEngine.RecordingOutcome.Started ->
                    failure(ScreenShotResult(false, outcome.path, 0, "Unexpected state."))

                is ScreenCaptureEngine.RecordingOutcome.AlreadyRecording ->
                    failure(ScreenShotResult(false, outcome.path, 0, "Unexpected state."))
            }

            else -> ToolExecutionResult.error(
                "The action must be 'start' or 'stop', and '$action' is neither.",
            )
        }
    }

    // ------------------------------------------------------------------
    // Results
    // ------------------------------------------------------------------

    /**
     * A successful result, with the file checked rather than assumed.
     *
     * A tool that reports a path must report one that exists and holds something, because
     * that is the exact lie issue 43 was filed for. Both callers above already know the byte
     * count, so this only guards against a path that vanished between the write and the
     * report.
     */
    private fun success(payload: ScreenShotResult): ToolExecutionResult {
        val file = File(payload.path)
        if (payload.success && (!file.isFile || file.length() <= 0L)) {
            val failed = ScreenShotResult(
                success = false,
                path = payload.path,
                sizeBytes = 0,
                error = "The capture reported success and no file holds data at ${payload.path}.",
            )
            return ToolExecutionResult(
                rawJson = json.encodeToString(ScreenShotResult.serializer(), failed),
                resultData = failed,
                resultObject = null,
                success = false,
                error = failed.error,
            )
        }

        return ToolExecutionResult(
            rawJson = json.encodeToString(ScreenShotResult.serializer(), payload),
            resultData = payload,
            resultObject = null,
            success = payload.success,
            error = payload.error,
        )
    }

    private fun failure(payload: ScreenShotResult): ToolExecutionResult = ToolExecutionResult(
        rawJson = json.encodeToString(ScreenShotResult.serializer(), payload),
        resultData = payload,
        resultObject = null,
        success = false,
        error = payload.error,
    )
}
