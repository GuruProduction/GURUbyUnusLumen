// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

/**
 * What the prefix health probe must run so that its answer is the answer the tool would give.
 *
 * WHY THIS EXISTS (issue 334). `termuxStatus` reported
 *
 *     prefixExecutes: true
 *     prefixHealth: "every probed binary ran"
 *
 * on the same device, in the same session, where `termuxExec {"command": "git --version"}`
 * returned `Permission denied`. Both readings were honest about what they measured and the two
 * measured different things: the probe launched each binary DIRECTLY through `/system/bin/linker64`,
 * which works, while `termuxExec` forks through `bash`, which is where libtermux-exec refuses when a
 * variable is missing. The probe therefore asserted the component was healthy while every
 * user-visible command failed, which is worse than no probe at all.
 *
 * The rule this file makes enforceable: **a health check must exercise the path the tool takes.**
 * If a probe and a tool disagree about the same command, the probe is wrong by definition.
 *
 * The three things the tool's own path adds, and therefore the three the probe has to include:
 *
 *  1. A FORK. `linker64 <binary> --version` is one process. `bash -c '<binary> --version'` is two,
 *     and the parent's environment is what libtermux-exec inspects before it will redirect the
 *     child. That is the exact layer #329 was in.
 *  2. THE PREFIX ENVIRONMENT. The tool builds it with [TermuxProvider.buildEnv]; a probe with a
 *     hand-made environment is measuring the harness, which is the mistake I made five times in
 *     one session elsewhere.
 *  3. THE REAL BINARY NAME. Resolving `git` through the same search path `termuxExec` uses, rather
 *     than a path a caller happened to know.
 */
object PrefixProbe {

    /**
     * One probe, as the arguments a shell would run.
     *
     * @param name the command as a person would type it, so a failure names what they typed.
     * @param script the exact shell text, which forks where the tool forks.
     * @param expect the text that proves the command RAN, not merely that it exited 0.
     */
    data class Probe(
        val name: String,
        val script: String,
        val expect: String,
    )

    /**
     * The probes that cover the three ways this class of fault presents.
     *
     * Deliberately small and deliberately forking. Each is a command a person would type, run
     * through `bash -c` so the fork is present, and each asserts on text rather than an exit code
     * because issue 211 recorded `success: true, exitCode: 0` with completely empty stdout.
     *
     *  - `echo` proves the shell itself works, which is the shim and the interpreter in one step.
     *  - `git --version` is the binary that failed on hardware while the probe said it was fine.
     *  - `apt-get --version` covers a binary needing a config tree as well as libraries.
     */
    val probes: List<Probe> = listOf(
        Probe("echo", "echo GURU_PREFIX_ALIVE", "GURU_PREFIX_ALIVE"),
        Probe("git", "git --version", "git version"),
        // "apt" not "apt ": a trailing space is fragile against trimming and against whatever
        // word the version line happens to start with.
        Probe("apt-get", "apt-get --version", "apt"),
    )

    /**
     * What one probe showed.
     *
     * [ran] requires the expected text, never the exit code alone. [sentence] is always populated
     * so a caller never has to invent a reason for a failure.
     */
    data class Result(
        val name: String,
        /** The command as a person would type it, for the sentence. */
        val script: String,
        val exitCode: Int,
        val output: String,
        val ran: Boolean,
        val sentence: String,
    )

    /**
     * Decide whether a probe ran from its real output.
     *
     * THE ORDER IS THE POINT, and it is what issue 211 is about: output first, exit code second.
     * A command that exits 0 and prints nothing did not do its job, and a command that prints the
     * expected text has done its job whatever it exited with — `git --version` is not a tool that
     * promises a particular status, and neither is `apt-get --version`.
     *
     * A LOAD FAILURE IS NEVER A RUN even when the text happens to appear, because a binary the
     * loader refused has not executed a line of its own code.
     */
    fun decide(probe: Probe, exitCode: Int, output: String): Result {
        val combined = output.trim()
        val loadFailure = loadFailureMarkers.any { combined.contains(it, ignoreCase = true) }
        val sawExpected = combined.contains(probe.expect)
        val ran = !loadFailure && sawExpected

        val sentence = when {
            loadFailure -> {
                val line = combined.lineSequence().firstOrNull { it.isNotBlank() }?.take(160).orEmpty()
                "`${probe.script}` did not run: the loader refused it ($line). " +
                    "This is a fault in the prefix, not in the command."
            }
            !sawExpected && combined.isEmpty() ->
                "`${probe.script}` produced no output and exited $exitCode, which is not evidence " +
                    "it ran. An empty success is the shape issue 211 recorded."
            !sawExpected ->
                "`${probe.script}` exited $exitCode and did not print \"${probe.expect}\", so it " +
                    "did not do its job. It said: ${combined.lineSequence().firstOrNull()?.take(160)}"
            else ->
                "`${probe.script}` ran and printed \"${probe.expect}\"."
        }
        return Result(probe.name, probe.script, exitCode, combined, ran, sentence)
    }

    /**
     * The shapes that mean the loader or kernel refused the process outright.
     *
     * The same vocabulary the rest of this package uses, so one definition covers the prefix rather
     * than each call site inventing its own list.
     */
    val loadFailureMarkers: List<String> = listOf(
        "CANNOT LINK EXECUTABLE",
        "not found: needed by main executable",
        "Permission denied",
        "Exec format error",
        "No such file or directory",
        "command not found",
    )

    /**
     * The verdict across every probe, and it is deliberately stricter than "all ran".
     *
     * A prefix is healthy when the SHELL works and at least one real command does. Requiring all
     * three would report a prefix broken because apt is not installed, which is a different fact
     * and not a fault in the exec layer. Requiring none is the state that must never render as
     * healthy, because that is the case where the probe and the tool disagreed.
     */
    data class Verdict(
        val allRan: Boolean,
        val results: List<Result>,
        val sentence: String,
    )

    fun verdict(results: List<Result>): Verdict {
        val shell = results.firstOrNull { it.name == "echo" }
        val commands = results.filter { it.name != "echo" }
        val allRan = shell?.ran == true && commands.any { it.ran }

        val broken = results.filterNot { it.ran }
        val sentence = when {
            allRan && broken.isEmpty() -> "Every probe ran through the same path a tool takes."
            allRan -> "The shell and at least one command ran. Not working: " +
                broken.joinToString(", ") { it.name }
            else -> results.filterNot { it.ran }.joinToString(" ") { it.sentence }
        }
        return Verdict(allRan, results, sentence)
    }
}
