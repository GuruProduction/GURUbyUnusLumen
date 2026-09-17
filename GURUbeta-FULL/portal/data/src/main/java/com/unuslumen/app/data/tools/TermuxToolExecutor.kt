package com.unuslumen.app.data.tools

import android.content.Context
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.util.shell.ShellExecutor
import com.unuslumen.app.util.shell.TermuxProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.File

class TermuxToolExecutor(private val context: Context) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val termuxProvider by lazy { TermuxProvider(context) }
    private val shellExecutor by lazy { ShellExecutor(context) }

    private suspend fun ensureReady(): String? = withContext(Dispatchers.IO) {
        if (!termuxProvider.isAvailable()) { termuxProvider.initialize() }
        if (!termuxProvider.isAvailable()) return@withContext "Termux not available"
        val reTools = shellExecutor.reTools; if (!reTools.isInitialized) { reTools.initialize() }
        null
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (toolName) {
            TermuxToolDefinitions.TERMUX_INIT -> { val err = ensureReady(); if (err != null) { val r = TermuxInitResult(false, false, "", "", "", err); ToolExecutionResult.success(r, json.encodeToString(TermuxInitResult.serializer(), r)) } else { val reTools = shellExecutor.reTools; val binCount = File(termuxProvider.getBinPath()).listFiles()?.size ?: 0; val r = TermuxInitResult(true, true, termuxProvider.getRootPath(), termuxProvider.getHomePath(), termuxProvider.getBinPath(), "Termux initialized: $binCount binaries. RE tools: aapt=${reTools.isAaptAvailable()} aapt2=${reTools.isAapt2Available()} apktool=${reTools.isApktoolAvailable()} jadx=${reTools.isJadxAvailable()}"); ToolExecutionResult.success(r, json.encodeToString(TermuxInitResult.serializer(), r)) } }
            TermuxToolDefinitions.TERMUX_EXEC -> { val cmd = args["command"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'command'"); val err = ensureReady(); if (err != null) { val r = TermuxExecResult(false, -1, "", err, cmd); ToolExecutionResult.success(r, json.encodeToString(TermuxExecResult.serializer(), r)) } else { val dir = args["workingDir"] as? String ?: termuxProvider.getHomePath(); val result = if (dir != termuxProvider.getHomePath()) termuxProvider.execBash("cd '$dir' 2>/dev/null; $cmd") else termuxProvider.execInTermux(cmd); val r = TermuxExecResult(result.exitCode == 0, result.exitCode, result.stdout, result.stderr, cmd); ToolExecutionResult.success(r, json.encodeToString(TermuxExecResult.serializer(), r)) } }
            TermuxToolDefinitions.TERMUX_INSTALL -> { val packages = args["packages"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'packages'"); val err = ensureReady(); if (err != null) { val r = TermuxInstallResult(false, -1, err, packages); ToolExecutionResult.success(r, json.encodeToString(TermuxInstallResult.serializer(), r)) } else { val result = termuxProvider.execAptInstall(packages); val output = if (result.stdout.length > 3000) result.stdout.takeLast(3000) else result.stdout; val r = TermuxInstallResult(result.exitCode == 0, result.exitCode, output.ifBlank { result.stderr }, packages); ToolExecutionResult.success(r, json.encodeToString(TermuxInstallResult.serializer(), r)) } }
            TermuxToolDefinitions.TERMUX_STATUS -> { if (!termuxProvider.isAvailable()) { val r = TermuxStatusResult(false, "", "", "", "", 0, emptyMap()); ToolExecutionResult.success(r, json.encodeToString(TermuxStatusResult.serializer(), r)) } else { val binDir = File(termuxProvider.getBinPath()); val binCount = if (binDir.exists()) binDir.listFiles()?.size ?: 0 else 0; val tools = mapOf("bash" to termuxProvider.hasCommand("bash"), "apt" to termuxProvider.hasCommand("apt"), "git" to termuxProvider.hasCommand("git"), "curl" to termuxProvider.hasCommand("curl"), "python3" to termuxProvider.hasCommand("python3"), "node" to termuxProvider.hasCommand("node"), "gcc" to termuxProvider.hasCommand("gcc"), "make" to termuxProvider.hasCommand("make"), "nmap" to termuxProvider.hasCommand("nmap"), "ssh" to termuxProvider.hasCommand("ssh"), "ffmpeg" to termuxProvider.hasCommand("ffmpeg")); val r = TermuxStatusResult(true, termuxProvider.getRootPath(), termuxProvider.getBinPath(), termuxProvider.getHomePath(), termuxProvider.getLibPath(), binCount, tools); ToolExecutionResult.success(r, json.encodeToString(TermuxStatusResult.serializer(), r)) } }
            TermuxToolDefinitions.TERMUX_HAS_COMMAND -> { val cmd = args["command"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'command'"); val available = termuxProvider.hasCommand(cmd); val r = TermuxHasCommandResult(cmd, available, if (available) "'$cmd' available" else "'$cmd' not installed"); ToolExecutionResult.success(r, json.encodeToString(TermuxHasCommandResult.serializer(), r)) }
            TermuxToolDefinitions.TERMUX_DIAGNOSE -> { val err = ensureReady(); if (err != null) { val r = TermuxDiagnoseResult(false, 0, 0, false, err, "", false, "Environment not ready", listOf("Failed: $err")); ToolExecutionResult.success(r, json.encodeToString(TermuxDiagnoseResult.serializer(), r)) } else { val binDir = File(termuxProvider.getBinPath()); val allBins = binDir.listFiles()?.toList() ?: emptyList(); val binCount = allBins.size; val execCount = allBins.count { it.canExecute() }; val linkerPath = termuxProvider.getLinkerPath(); val bashTest = termuxProvider.execBash("echo BASH_WORKS"); val bashWorks = bashTest.exitCode == 0 && bashTest.stdout.contains("BASH_WORKS"); val aptTest = termuxProvider.execBash("apt update 2>&1 | head -5"); val aptWorking = aptTest.exitCode == 0 || aptTest.stdout.contains("Reading package lists") || aptTest.stdout.contains("All packages are up to date"); val diagnostics = mutableListOf<String>(); if (binCount == 0) diagnostics.add("No binaries"); if (!bashWorks) diagnostics.add("bash failed: ${bashTest.stderr.take(200)}"); if (!aptWorking) diagnostics.add("apt failed: ${aptTest.stderr.ifBlank { aptTest.stdout }.take(200)}"); val r = TermuxDiagnoseResult(true, binCount, execCount, aptWorking, if (!aptWorking) aptTest.stderr.ifBlank { aptTest.stdout }.take(500) else null, linkerPath, bashWorks, if (!bashWorks) bashTest.stderr.take(200) else null, diagnostics); ToolExecutionResult.success(r, json.encodeToString(TermuxDiagnoseResult.serializer(), r)) } }
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }
}