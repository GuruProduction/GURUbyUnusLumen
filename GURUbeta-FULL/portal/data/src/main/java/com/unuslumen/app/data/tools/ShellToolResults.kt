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
@Serializable data class AdbStatusResult(val adbAvailable: Boolean, val adbPaired: Boolean, val accessibilityRunning: Boolean) : ToolResultData
@Serializable data class BusyboxHelpResult(val available: Boolean, val path: String, val appletCount: Int, val applets: List<String>) : ToolResultData
@Serializable data class PythonExecResult(val stdout: String, val stderr: String, val exitCode: Int, val success: Boolean) : ToolResultData
@Serializable data class PathAccessInfo(val path: String, val label: String, val exists: Boolean, val readable: Boolean, val writable: Boolean) : ToolResultData
@Serializable data class FileSystemAccessResult(val accessTier: String, val hasAllFilesAccess: Boolean, val adbAvailable: Boolean, val adbPaired: Boolean, val rootAvailable: Boolean, val appStoragePath: String, val externalStoragePath: String, val pathAccess: List<PathAccessInfo>, val restrictions: List<String>) : ToolResultData
@Serializable data class RequestAccessResult(val success: Boolean, val alreadyGranted: Boolean, val message: String) : ToolResultData