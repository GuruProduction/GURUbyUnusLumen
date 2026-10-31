// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.util.shell.ShellExecutor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class ScreenToolExecutor(
    private val context: Context
) : ToolExecutor {

    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            ScreenToolDefinitions.TAKE_SCREENSHOT -> captureScreen()
            ScreenToolDefinitions.RECORD_SCREEN -> screenRecord(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun captureScreen(): ToolExecutionResult = withContext(Dispatchers.IO) {
        try {
            val screenshotPath = "${context.cacheDir.absolutePath}/screenshot_${System.currentTimeMillis()}.png"
            // Route through ShellExecutor: root > ADB > Runtime fallback. A raw
            // Runtime.exec("screencap") runs as the app UID which is denied screencap
            // access on virtually every device, which is why takeScreenshot always
            // failed until capture permission was granted through the other tool.
            val result = shellExecutor.execute("screencap -p '$screenshotPath'")
            val file = File(screenshotPath)
            if (result.success && file.exists() && file.length() > 0) {
                val r = ScreenShotResult(true, screenshotPath, file.length(), null)
                ToolExecutionResult.success(r, json.encodeToString(ScreenShotResult.serializer(), r))
            } else {
                val detail = if (result.stderr.isNotBlank()) " (${result.stderr.take(150)})" else ""
                val r = ScreenShotResult(
                    false, "", 0,
                    "screencap failed even with root/ADB (exit ${result.exitCode})$detail"
                )
                ToolExecutionResult.success(r, json.encodeToString(ScreenShotResult.serializer(), r))
            }
        } catch (e: Exception) {
            val r = ScreenShotResult(false, "", 0, "Screenshot error: ${e.message}")
            ToolExecutionResult.success(r, json.encodeToString(ScreenShotResult.serializer(), r))
        }
    }

    /**
     * Start and stop screen recording, honestly.
     *
     * ISSUE 43: `stop` answered success with a path to a ZERO-BYTE file, because
     * the guard was `recording.exists()` and an empty file exists. It also found
     * its file by globbing the cache for the newest `screenrecord_*`, so it could
     * hand back a file from an earlier session. Start reported success without
     * checking the recorder had even launched.
     *
     * Three changes, each covering one of those:
     *  - the process this call started is tracked, so `stop` only ever reports a
     *    file THIS session created;
     *  - success requires length() > 0, via ScreenRecordingCheck;
     *  - `start` fails when the recorder died without writing a frame, and says
     *    what screenrecord printed.
     */
    private var recordingProcess: Process? = null
    private var recordingPath: String? = null

    private suspend fun screenRecord(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val action = args["action"] as? String
            ?: return@withContext ToolExecutionResult.error("Missing 'action' parameter")
        val duration = ScreenRecordingCheck.durationSeconds((args["duration"] as? Number)?.toInt())

        try {
            when (action.lowercase()) {
                "start" -> {
                    // A previous recording that was never collected must not be
                    // silently abandoned; report it rather than starting over it.
                    val live = recordingProcess
                    if (live != null && live.isAlive) {
                        val existing = recordingPath
                        val r = ScreenShotResult(
                            false, existing ?: "", 0,
                            "A recording is already running for ${existing ?: "an unknown path"}. " +
                                "Call stop first.",
                        )
                        return@withContext ToolExecutionResult.success(r, json.encodeToString(ScreenShotResult.serializer(), r))
                    }

                    val path = "${context.cacheDir.absolutePath}/screenrecord_${System.currentTimeMillis()}.mp4"
                    // Launch and KEEP the handle. The old code backgrounded this
                    // with `&` inside sh -c and threw the handle away, which is
                    // why it could only find the file by guessing.
                    val process = ProcessBuilder("screenrecord", "--time-limit", duration.toString(), path)
                        .redirectErrorStream(true)
                        .start()
                    recordingProcess = process
                    recordingPath = path

                    // Give the encoder a moment to create and start filling the
                    // file. screenrecord creates it only once the display and
                    // encoder are configured, so this is a real readiness signal.
                    Thread.sleep(600)
                    val file = File(path)
                    val bytes = if (file.exists()) file.length() else 0L
                    val alive = process.isAlive

                    // Read whatever the recorder printed. A denied display or a
                    // missing encoder says so here and nowhere else.
                    val detail = runCatching {
                        val avail = process.inputStream.available()
                        if (avail > 0) {
                            val buf = ByteArray(minOf(avail, 400))
                            process.inputStream.read(buf)
                            String(buf, Charsets.UTF_8)
                        } else ""
                    }.getOrDefault("")

                    val failure = ScreenRecordingCheck.startFailure(alive, bytes, detail)
                    if (failure != null) {
                        recordingProcess = null
                        recordingPath = null
                        val r = ScreenShotResult(false, path, bytes, failure)
                        ToolExecutionResult.success(r, json.encodeToString(ScreenShotResult.serializer(), r))
                    } else {
                        val r = ScreenShotResult(true, path, bytes, ScreenRecordingCheck.startNote(alive, bytes, duration))
                        ToolExecutionResult.success(r, json.encodeToString(ScreenShotResult.serializer(), r))
                    }
                }
                else -> {
                    val path = recordingPath
                    val process = recordingProcess
                    if (path == null) {
                        val r = ScreenShotResult(false, "", 0, "No recording was started by this session. Call start first.")
                        return@withContext ToolExecutionResult.success(r, json.encodeToString(ScreenShotResult.serializer(), r))
                    }

                    // Ask the recorder to finish properly. screenrecord finalises
                    // the MP4 from its SIGINT/SIGHUP handler; a hard kill leaves a
                    // file whose index was never written, which has bytes and will
                    // not play.
                    var endedCleanly = true
                    if (process != null && process.isAlive) {
                        runCatching { process.destroy() } // SIGTERM
                        endedCleanly = process.waitFor(4, java.util.concurrent.TimeUnit.SECONDS)
                        if (!endedCleanly) {
                            process.destroyForcibly()
                        }
                    }
                    recordingProcess = null
                    recordingPath = null

                    val file = File(path)
                    val bytes = if (file.isFile) file.length() else 0L
                    val failure = ScreenRecordingCheck.stopFailure(
                        path = path,
                        fileExists = file.isFile,
                        recordedBytes = bytes,
                        endedCleanly = endedCleanly,
                        detail = "",
                    )
                    val r = if (failure == null) {
                        ScreenShotResult(true, path, bytes, null)
                    } else {
                        ScreenShotResult(false, path, bytes, failure)
                    }
                    ToolExecutionResult.success(r, json.encodeToString(ScreenShotResult.serializer(), r))
                }
            }
        } catch (e: Exception) {
            val result = ScreenShotResult(false, "", 0, "Screen record error: ${e.message}")
            ToolExecutionResult.success(result, json.encodeToString(ScreenShotResult.serializer(), result))
        }
    }
}