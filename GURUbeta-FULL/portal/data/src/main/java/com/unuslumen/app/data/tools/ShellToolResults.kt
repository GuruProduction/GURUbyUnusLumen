// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

/*
 * `tier` is which execution context actually served the command.
 *
 * Issue 40: the tool claimed "NO limits" while the executor ran at app UID, and
 * nothing reported it, so a caller read a permanent permission denial as a
 * transient failure and retried. Naming the tier on the result is what makes the
 * description's promise checkable against the run.
 *
 * `denial` is why the call was refused, in one actionable sentence, and is null
 * when no denial signature was found. Issue 44: `dumpsys` and `pm dump` print
 * "Permission Denial: …" to stdout and exit 0, so a total refusal used to arrive
 * as success=true with empty stderr. The executor now detects that and fills
 * this field, while exitCode and stdout stay exactly as observed.
 */
@Serializable data class ShellCommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val success: Boolean,
    val tier: String = "APP_UID",
    val tierCapabilities: String? = null,
    val denial: String? = null,
    /*
     * A refusal quoted inside the output rather than aimed at this command.
     *
     * A successful `logcat -d` carries other processes' "Permission denied"
     * lines by design, and calling that run failed would be the same dishonesty
     * as issue 44 reversed. So it lands here and `success` is untouched.
     */
    val advisory: String? = null,
) : ToolResultData
@Serializable data class ReadFileResult(val content: String, val exists: Boolean, val sizeBytes: Long, val error: String?) : ToolResultData
@Serializable data class WriteFileResult(val success: Boolean, val path: String, val bytesWritten: Long, val error: String?) : ToolResultData
@Serializable data class DirectoryEntry(val name: String, val type: String, val size: String, val modified: String) : ToolResultData
@Serializable data class ListDirectoryResult(val path: String, val entries: List<DirectoryEntry>, val error: String?) : ToolResultData
@Serializable data class DeviceInfoResult(val manufacturer: String, val model: String, val product: String, val androidVersion: String, val sdkLevel: Int, val cpuAbi: String, val rootAvailable: Boolean, val totalRamMb: Long, val availableRamMb: Long, val internalStorageTotalMb: Long?, val internalStorageFreeMb: Long?, val externalStorageTotalMb: Long?, val externalStorageFreeMb: Long?) : ToolResultData
@Serializable data class AdbPairResult(val success: Boolean, val error: String?) : ToolResultData
/*
 * `accessibilityRunning` is kept with its original meaning so nothing that reads
 * this field changes behaviour, but it is no longer the whole story.
 *
 * Issue 36: this one boolean could not distinguish "the human has not enabled
 * the service" from "it is enabled but not bound yet", and those need opposite
 * responses: one is a Settings trip, the other is a retry. `accessibilityState`
 * carries the distinction and `accessibilityEnabled` says whether the system has
 * the service switched on at all.
 *
 * accessibilityState is one of: DISABLED, ENABLED_NOT_BOUND, RUNNING.
 * A caller that only checks accessibilityRunning still works: it is exactly true
 * when accessibilityState is RUNNING.
 */
@Serializable data class AdbStatusResult(
    val adbAvailable: Boolean,
    val adbPaired: Boolean,
    val accessibilityRunning: Boolean,
    val accessibilityState: String = if (accessibilityRunning) "RUNNING" else "UNKNOWN",
    val accessibilityEnabled: Boolean = accessibilityRunning,
    val accessibilityDetail: String? = null,
) : ToolResultData
@Serializable data class BusyboxHelpResult(val available: Boolean, val path: String, val appletCount: Int, val applets: List<String>) : ToolResultData
/*
 * The outcome of running a Python script.
 *
 * Issue 175: `exitCode` was the only way to report how a run ended, and a run
 * that never started was reported as exit Code -1. That is a number a real
 * process can return, so a caller could not distinguish "the interpreter is not
 * on this device" from "your script called sys.exit(-1)". The two need opposite
 * responses: one is a packaging fault, the other is the script's own answer.
 *
 * `status` now carries that distinction, and it is always present:
 *
 *   EXITED     the interpreter ran and returned exitCode. exitCode is meaningful.
 *   LAUNCH_FAILED  no process was created at all, so exitCode is null rather
 *                  than a sentinel, and `reason` says which layer was missing.
 *   TIMED_OUT  the process started and was killed at the deadline. Partial
 *              stdout and stderr are still real and are kept.
 *
 * exitCode is nullable on purpose. A null cannot be mistaken for a real exit
 * code by arithmetic, comparison or a careless log line, which -1 could.
 */
@Serializable data class PythonExecResult(
    val stdout: String,
    val stderr: String,
    val exitCode: Int?,
    val success: Boolean,
    val status: String = "EXITED",
    val reason: String? = null,
) : ToolResultData
@Serializable data class PathAccessInfo(val path: String, val label: String, val exists: Boolean, val readable: Boolean, val writable: Boolean) : ToolResultData
@Serializable data class FileSystemAccessResult(val accessTier: String, val hasAllFilesAccess: Boolean, val adbAvailable: Boolean, val adbPaired: Boolean, val rootAvailable: Boolean, val appStoragePath: String, val externalStoragePath: String, val pathAccess: List<PathAccessInfo>, val restrictions: List<String>) : ToolResultData
@Serializable data class RequestAccessResult(val success: Boolean, val alreadyGranted: Boolean, val message: String) : ToolResultData