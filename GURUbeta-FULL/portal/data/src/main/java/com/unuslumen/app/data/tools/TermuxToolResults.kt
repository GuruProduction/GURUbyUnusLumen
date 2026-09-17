package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class TermuxInitResult(val success: Boolean, val initialized: Boolean, val rootPath: String, val homePath: String, val binPath: String, val message: String) : ToolResultData
@Serializable data class TermuxExecResult(val success: Boolean, val exitCode: Int, val stdout: String, val stderr: String, val command: String) : ToolResultData
@Serializable data class TermuxInstallResult(val success: Boolean, val exitCode: Int, val output: String, val packages: String) : ToolResultData
@Serializable data class TermuxStatusResult(val initialized: Boolean, val rootPath: String, val binPath: String, val homePath: String, val libPath: String, val binCount: Int, val availableTools: Map<String, Boolean>) : ToolResultData
@Serializable data class TermuxHasCommandResult(val command: String, val available: Boolean, val message: String) : ToolResultData
@Serializable data class TermuxDiagnoseResult(val environmentReady: Boolean, val binCount: Int, val executableCount: Int, val aptWorking: Boolean, val aptError: String?, val linkerPath: String, val bashWorks: Boolean, val bashError: String?, val diagnostics: List<String>) : ToolResultData