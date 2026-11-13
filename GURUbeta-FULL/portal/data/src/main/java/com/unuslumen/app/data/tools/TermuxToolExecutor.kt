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

    /**
     * Bring the prefix up and prove it can execute, or say exactly what failed.
     *
     * WHY initialize() IS NOW ALWAYS CALLED (issue 317). This used to read
     * `if (!isAvailable()) initialize()`, so a prefix whose files were present
     * was never initialised again. `isAvailable` is a filesystem fact — "a
     * populated bin directory" — so a tree of 416 binaries that segfault on
     * every invocation answered "available", and the only code that can rebuild
     * a tree was never entered.
     *
     * initialize() is cheap once it has run: its latch returns immediately and
     * its execution probe is marker-gated by the package versionCode. So calling
     * it unconditionally costs nothing on a healthy device and is the only thing
     * that can notice a broken one.
     *
     * Afterwards [TermuxProvider.isUsable] keeps the two answers apart. "Termux
     * not available" for a prefix that is present and cannot run would be the
     * same collapse of two facts this class exists to prevent.
     */
    private suspend fun ensureReady(): String? = withContext(Dispatchers.IO) {
        /*
         * UNCONDITIONAL, and this is the whole of the second fault in issue 317.
         *
         * The previous revision of this fix wrote `if (!isInitialized())`, which
         * reads like "initialise if not already done" and is in fact "never
         * initialise". isInitialized() is `initialized || isAvailable()`, and
         * isAvailable() is "the bin directory has files" — true on every install
         * that ever extracted, including one where all 416 files are dead. So on
         * a real phone the call was skipped, initialize() never ran, the
         * execution probe never fired, and the status surface reported null for
         * prefixExecutes and prefixHealth because nothing had measured them.
         *
         * That is the same mistake as the original `if (!isAvailable())` one
         * layer down: both ask a filesystem question and treat the answer as
         * evidence that the tree works.
         *
         * initialize() is safe to call every time. Its own `initialized` latch
         * returns immediately once this instance has run, and its execution probe
         * is gated by a marker keyed to the package versionCode, so the repeat
         * cost on a healthy device is a marker read.
         */
        termuxProvider.initialize()
        if (!termuxProvider.isAvailable()) return@withContext "Termux not available"
        if (!termuxProvider.isUsable()) {
            return@withContext "Termux is installed but its binaries will not execute: " +
                termuxProvider.executionHealth().describe()
        }
        val reTools = shellExecutor.reTools; if (!reTools.isInitialized) { reTools.initialize() }
        null
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        when (toolName) {
            TermuxToolDefinitions.TERMUX_INIT -> { val err = ensureReady(); if (err != null) { val r = TermuxInitResult(false, false, "", "", "", err); ToolExecutionResult.success(r, json.encodeToString(TermuxInitResult.serializer(), r)) } else { val reTools = shellExecutor.reTools; val binCount = File(termuxProvider.getBinPath()).listFiles()?.size ?: 0; val r = TermuxInitResult(true, true, termuxProvider.getRootPath(), termuxProvider.getHomePath(), termuxProvider.getBinPath(), "Termux initialized: $binCount binaries. RE tools: aapt=${reTools.isAaptAvailable()} aapt2=${reTools.isAapt2Available()} apktool=${reTools.isApktoolAvailable()} jadx=${reTools.isJadxAvailable()}"); ToolExecutionResult.success(r, json.encodeToString(TermuxInitResult.serializer(), r)) } }
            TermuxToolDefinitions.TERMUX_EXEC -> { val cmd = args["command"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'command'"); val err = ensureReady(); if (err != null) { val r = TermuxExecResult(false, -1, "", err, cmd); ToolExecutionResult.success(r, json.encodeToString(TermuxExecResult.serializer(), r)) } else { val dir = args["workingDir"] as? String ?: termuxProvider.getHomePath(); val result = if (dir != termuxProvider.getHomePath()) termuxProvider.execBash("cd '$dir' 2>/dev/null; $cmd") else termuxProvider.execInTermux(cmd); val r = TermuxExecResult(result.exitCode == 0, result.exitCode, result.stdout, result.stderr, cmd); ToolExecutionResult.success(r, json.encodeToString(TermuxExecResult.serializer(), r)) } }
            TermuxToolDefinitions.TERMUX_INSTALL -> {
                val packages = args["packages"] as? String
                    ?: return@withContext ToolExecutionResult.error("Missing 'packages'")
                val err = ensureReady()
                if (err != null) {
                    val r = TermuxInstallResult(false, -1, err, packages)
                    ToolExecutionResult.success(r, json.encodeToString(TermuxInstallResult.serializer(), r))
                } else {
                    val result = termuxProvider.execAptInstall(packages)
                    val output = if (result.stdout.length > 3000) result.stdout.takeLast(3000) else result.stdout

                    /*
                     * MEASURE WHAT LANDED, DO NOT TRUST THE EXIT CODE (issue 31).
                     *
                     * dpkg exits 0 when its writes reached a writable tree through the
                     * compatibility root, and that is not the same question as "did this
                     * package's payload arrive where the app can run it". The ticket's figlet
                     * case is a clean exit, a binary on disk, and every font missing. So every
                     * package named on the command line is checked against its own .list and
                     * the result carries the answer.
                     *
                     * A name that dpkg has no record for is reported as incomplete rather than
                     * skipped, because "apt said it installed this and dpkg has never heard of
                     * it" is the strongest form of the fault, not a reason to stay quiet.
                     */
                    val requested = packages.split(Regex("\\s+"))
                        .map { it.trim() }
                        .filter { it.isNotEmpty() && !it.startsWith("-") }
                    val verdicts = requested.map { termuxProvider.checkInstall(it) }
                    val installed = verdicts.filter { it.complete }.map { it.packageName }
                    val incomplete = verdicts.filterNot { it.complete }.map { it.packageName }
                    val detail = verdicts.filterNot { it.complete }.map { it.sentence }

                    val r = TermuxInstallResult(
                        success = result.exitCode == 0 && incomplete.isEmpty(),
                        exitCode = result.exitCode,
                        output = output.ifBlank { result.stderr },
                        packages = packages,
                        packagesInstalled = installed,
                        packagesIncomplete = incomplete,
                        installDetail = detail,
                    )
                    ToolExecutionResult.success(r, json.encodeToString(TermuxInstallResult.serializer(), r))
                }
            }
            TermuxToolDefinitions.TERMUX_STATUS -> {
                // Install on demand before answering. termuxStatus used to report
                // "not available" purely because a freshly constructed provider had
                // a false latch, even with a verified prefix on disk. A status call
                // is a read, and a read should not be able to claim the app cannot
                // do something it can install itself.
                //
                // Issue 317: ALWAYS entered, unconditionally. The first revision
                // of this fix kept an `if (!isInitialized())` guard here, and
                // because isInitialized() is true whenever the bin directory has
                // files, a status call on a real install never initialised
                // anything and never measured anything. prefixExecutes and
                // prefixHealth were therefore null and vanished from the JSON,
                // which is exactly what the v3.45 report showed.
                //
                // A status call is the one that most has to be able to notice a
                // tree that is present and cannot run, so it never gets to skip
                // the probe. runCatching stays: a status read must report a
                // failure rather than throw out of the tool layer.
                runCatching { termuxProvider.initialize() }
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

                    /*
                     * The prefix-level verdict, stated separately from the eleven
                     * per-tool booleans (issue 317).
                     *
                     * A reader of the maps alone has to infer "the whole prefix is
                     * broken" from eleven separate failures, and the report that
                     * opened this issue was exactly that: a wall of false values
                     * with no sentence saying whether the install was broken or
                     * merely incomplete. prefixExecutes is that sentence, and
                     * prefixHealth is the observed reason rather than a guess.
                     */
                    val health = termuxProvider.executionHealth()

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
                        prefixExecutes = health.allRan,
                        prefixHealth = health.describe(),
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
                    val r = TermuxDiagnoseResult(
                        environmentReady = false,
                        binCount = 0,
                        executableCount = 0,
                        aptWorking = false,
                        aptError = err,
                        linkerPath = "",
                        bashWorks = false,
                        bashError = "Environment not ready",
                        diagnostics = listOf("Failed: $err"),
                    )
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

                    /*
                     * AND THE PACKAGES, MEASURED FROM THEIR OWN RECORDS (issue 31).
                     *
                     * "apt update exited 0" and "the package I asked for is usable" are two
                     * different facts, and the ticket is the case where the first was true and
                     * the second was false for every package on the device. Reading every
                     * installed package's .list is a few small text files, so a diagnosis can
                     * afford it and this is the only place that answers it.
                     *
                     * The incomplete list also drives a diagnostic line, because a person
                     * reading a diagnosis should not have to compare two arrays to notice that
                     * something they installed is missing its files.
                     */
                    val installs = termuxProvider.checkAllInstalls()
                    val installedPackages = installs.filter { it.complete }.map { it.packageName }
                    val incompletePackages = installs.filterNot { it.complete }.map { it.packageName }
                    val installDetail = installs.filterNot { it.complete }.map { it.sentence }
                    if (incompletePackages.isNotEmpty()) {
                        diagnostics.add(
                            "${incompletePackages.size} package(s) recorded as installed do not have " +
                                "every file on disk: ${incompletePackages.joinToString(", ")}",
                        )
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
                        packagesInstalled = installedPackages,
                        packagesIncomplete = incompletePackages,
                        installDetail = installDetail,
                    )
                    ToolExecutionResult.success(r, json.encodeToString(TermuxDiagnoseResult.serializer(), r))
                }
            }
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }
}