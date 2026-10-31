// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
            TermuxToolDefinitions.TERMUX_STATUS -> {
                // Install on demand before answering. termuxStatus used to report
                // "not available" purely because a freshly constructed provider had
                // a false latch, even with a verified prefix on disk. A status call
                // is a read, and a read should not be able to claim the app cannot
                // do something it can install itself.
                if (!termuxProvider.isAvailable()) { runCatching { termuxProvider.initialize() } }
                if (!termuxProvider.isAvailable()) {
                    val r = TermuxStatusResult(false, "", "", "", "", 0, emptyMap())
                    ToolExecutionResult.success(r, json.encodeToString(TermuxStatusResult.serializer(), r))
                } else {
                    val binDir = File(termuxProvider.getBinPath())
                    val binCount = if (binDir.exists()) binDir.listFiles()?.size ?: 0 else 0

                    /*
                     * EVERY ENTRY IS ACTUALLY EXECUTED, which is the whole of
                     * issue 175's second half.
                     *
                     * The old map was `hasCommand(name)`, i.e. File.exists() &&
                     * canRead(), so it answered `bash: true` for a prefix whose
                     * binaries could not load at all. A status field that reports
                     * a binary as available without running it is not a status
                     * field. probeCommand distinguishes ABSENT from
                     * WILL_NOT_LOAD from RUNS and attaches the REAL output, so a
                     * caller can tell a missing tool from a broken prefix.
                     */
                    val names = listOf(
                        "bash", "apt", "git", "curl", "python3", "node",
                        "gcc", "make", "nmap", "ssh", "ffmpeg",
                    )
                    val tools = LinkedHashMap<String, Boolean>()
                    val states = LinkedHashMap<String, String>()
                    val details = LinkedHashMap<String, String>()
                    for (name in names) {
                        val probe = termuxProvider.probeCommand(name)
                        tools[name] = probe.state == TermuxProvider.CommandProbe.RUNS
                        states[name] = probe.state.name
                        if (probe.state != TermuxProvider.CommandProbe.RUNS) {
                            details[name] = probe.output
                        }
                    }

                    val r = TermuxStatusResult(
                        initialized = true,
                        rootPath = termuxProvider.getRootPath(),
                        binPath = termuxProvider.getBinPath(),
                        homePath = termuxProvider.getHomePath(),
                        libPath = termuxProvider.getLibPath(),
                        binCount = binCount,
                        availableTools = tools,
                        toolStates = states,
                        toolDetails = details,
                    )
                    ToolExecutionResult.success(r, json.encodeToString(TermuxStatusResult.serializer(), r))
                }
            }
            TermuxToolDefinitions.TERMUX_HAS_COMMAND -> {
                val cmd = args["command"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'command'")
                val err = ensureReady()
                if (err != null) {
                    val r = TermuxHasCommandResult(cmd, false, err)
                    return@withContext ToolExecutionResult.success(r, json.encodeToString(TermuxHasCommandResult.serializer(), r))
                }
                // Issue 175: "available" has to mean the binary RAN. The old
                // answer was File.exists(), so a prefix whose loader was broken
                // reported every tool present while none of them worked.
                val probe = termuxProvider.probeCommand(cmd)
                val message = when (probe.state) {
                    TermuxProvider.CommandProbe.RUNS -> "'$cmd' available (${probe.path})"
                    TermuxProvider.CommandProbe.ABSENT -> "'$cmd' not installed: ${probe.output}"
                    TermuxProvider.CommandProbe.WILL_NOT_LOAD ->
                        "'$cmd' exists at ${probe.path} but will not run (exit ${probe.exitCode}): ${probe.output}"
                }
                val r = TermuxHasCommandResult(cmd, probe.state == TermuxProvider.CommandProbe.RUNS, message)
                ToolExecutionResult.success(r, json.encodeToString(TermuxHasCommandResult.serializer(), r))
            }
            TermuxToolDefinitions.TERMUX_DIAGNOSE -> {
                val err = ensureReady()
                if (err != null) {
                    val r = TermuxDiagnoseResult(false, 0, 0, false, err, "", false, "Environment not ready", listOf("Failed: $err"))
                    ToolExecutionResult.success(r, json.encodeToString(TermuxDiagnoseResult.serializer(), r))
                } else {
                    val binDir = File(termuxProvider.getBinPath())
                    val allBins = binDir.listFiles()?.toList() ?: emptyList()
                    val binCount = allBins.size
                    val execCount = allBins.count { it.canExecute() }
                    val linkerPath = termuxProvider.getLinkerPath()
                    val bashTest = termuxProvider.execBash("echo BASH_WORKS")
                    val bashWorks = bashTest.exitCode == 0 && bashTest.stdout.contains("BASH_WORKS")

                    /*
                     * APT IS MEASURED IN THREE STATES, not as a boolean.
                     *
                     * Issue 4 was the first half: a check that accepted "the
                     * binary ran" reported a package manager that died with exit
                     * 100 on every operation as working.
                     *
                     * Issue 30 was the second half: the replacement collapsed
                     * every failure into the fixed string "apt-get not present
                     * in the prefix", including the case where apt-get was
                     * present and healthy but the PROBE lacked APT_CONFIG, so apt
                     * could not parse its own configuration and exited 100. The
                     * message named a cause the probe had never observed.
                     *
                     * The probe now returns ABSENT, WILL_NOT_LOAD or RUNS with
                     * the real output attached, so this states only what it saw.
                     */
                    val probe = termuxProvider.probeApt()
                    val aptPresent = probe.state != TermuxProvider.AptProbe.ABSENT
                    val aptTest =
                        if (probe.state == TermuxProvider.AptProbe.RUNS) termuxProvider.execAptUpdate() else null
                    val aptWorking = aptTest != null && aptTest.exitCode == 0
                    val aptDetail = when (probe.state) {
                        TermuxProvider.AptProbe.ABSENT -> probe.output
                        TermuxProvider.AptProbe.WILL_NOT_LOAD -> probe.output.take(500)
                        TermuxProvider.AptProbe.RUNS ->
                            aptTest?.let { (it.stderr.ifBlank { it.stdout }).take(500) } ?: "apt update was not run"
                    }

                    val diagnostics = mutableListOf<String>()
                    if (binCount == 0) diagnostics.add("No binaries")
                    if (!bashWorks) diagnostics.add("bash failed: ${bashTest.stderr.take(200)}")
                    when {
                        probe.state == TermuxProvider.AptProbe.ABSENT ->
                            diagnostics.add("apt-get is absent from ${termuxProvider.getBinPath()}: $aptDetail")
                        probe.state == TermuxProvider.AptProbe.WILL_NOT_LOAD ->
                            diagnostics.add("apt-get exists but will not run (exit ${probe.exitCode}): $aptDetail")
                        !aptWorking ->
                            diagnostics.add("apt-get runs but apt update failed: $aptDetail")
                    }
                    if (!aptPresent) {
                        // Kept so the shape of the result does not change, but the
                        // text above is what a reader should trust.
                        diagnostics.add("aptPresent=false")
                    }

                    val r = TermuxDiagnoseResult(
                        environmentReady = true,
                        binCount = binCount,
                        executableCount = execCount,
                        aptWorking = aptWorking,
                        aptError = if (aptWorking) null else aptDetail,
                        linkerPath = linkerPath,
                        bashWorks = bashWorks,
                        bashError = if (bashWorks) null else bashTest.stderr.take(200),
                        diagnostics = diagnostics,
                    )
                    ToolExecutionResult.success(r, json.encodeToString(TermuxDiagnoseResult.serializer(), r))
                }
            }
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }
}