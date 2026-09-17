package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class InstalledPackage(val apkPath: String, val packageName: String) : ToolResultData
@Serializable data class InstalledPackagesResult(val packages: List<InstalledPackage>) : ToolResultData
@Serializable data class PullApkResult(val success: Boolean, val packageName: String, val apkPath: String, val outputPath: String, val sizeBytes: Long, val error: String?) : ToolResultData
@Serializable data class ExtractionResult(val success: Boolean, val outputDir: String, val fileCount: Int, val error: String?, val additionalInfo: String? = null) : ToolResultData
@Serializable data class ManifestResult(val success: Boolean, val packageName: String, val versionName: String, val versionCode: Int, val permissions: List<String>, val activities: List<String>, val services: List<String>, val receivers: List<String>, val providers: List<String>, val rawContent: String, val methodUsed: String, val error: String?) : ToolResultData
@Serializable data class DecompileResult(val success: Boolean, val outputDir: String, val methodsSummary: String, val javaFiles: Int, val smaliFiles: Int, val extractedFiles: Int, val stringsPreview: String?, val error: String?) : ToolResultData
@Serializable data class ModApkResult(val success: Boolean, val outputPath: String, val sizeBytes: Long = 0, val error: String?, val note: String? = null) : ToolResultData
@Serializable data class SignApkResult(val success: Boolean, val signedPath: String, val method: String, val error: String?) : ToolResultData