// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class ShellCommandResult(val exitCode: Int, val stdout: String, val stderr: String, val success: Boolean) : ToolResultData
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
@Serializable data class PythonExecResult(val stdout: String, val stderr: String, val exitCode: Int, val success: Boolean) : ToolResultData
@Serializable data class PathAccessInfo(val path: String, val label: String, val exists: Boolean, val readable: Boolean, val writable: Boolean) : ToolResultData
@Serializable data class FileSystemAccessResult(val accessTier: String, val hasAllFilesAccess: Boolean, val adbAvailable: Boolean, val adbPaired: Boolean, val rootAvailable: Boolean, val appStoragePath: String, val externalStoragePath: String, val pathAccess: List<PathAccessInfo>, val restrictions: List<String>) : ToolResultData
@Serializable data class RequestAccessResult(val success: Boolean, val alreadyGranted: Boolean, val message: String) : ToolResultData