// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class TermuxInitResult(val success: Boolean, val initialized: Boolean, val rootPath: String, val homePath: String, val binPath: String, val message: String) : ToolResultData
@Serializable data class TermuxExecResult(val success: Boolean, val exitCode: Int, val stdout: String, val stderr: String, val command: String) : ToolResultData
/*
 * AN INSTALL IS NOT SUCCESSFUL BECAUSE dpkg EXITED ZERO (issue 31).
 *
 * The ticket's whole complaint is a package that reported `install ok installed` and then could
 * not run: the binary landed through the compatibility root and its data files did not arrive at
 * all. Exit status cannot tell those apart, so this result carries the measurement instead.
 *
 * `packagesInstalled` and `packagesIncomplete` are what the caller acts on. `success` stays as
 * the tool's own gate but now requires both a clean exit AND every named package being recorded
 * with its payload on disk, so an install that exits 0 and delivers nothing reads as a failure
 * with a sentence saying what was missing.
 */
@Serializable data class TermuxInstallResult(
    val success: Boolean,
    val exitCode: Int,
    val output: String,
    val packages: String,
    /** Package names that are recorded and whose every declared path is on disk. */
    val packagesInstalled: List<String> = emptyList(),
    /** Package names recorded with paths missing, with what is absent named in `installDetail`. */
    val packagesIncomplete: List<String> = emptyList(),
    /** One sentence per package, from the records rather than from dpkg's exit status. */
    val installDetail: List<String> = emptyList(),
) : ToolResultData
/*
 * `availableTools` keeps its original meaning so nothing that reads it changes
 * behaviour, and it keeps its original type so an existing consumer still
 * parses.
 *
 * WHAT IT NOW MEANS, from issue 175. Each entry used to be `File.exists() &&
 * canRead()` — existence, not execution — so it reported `bash: true` for a
 * prefix in which bash could not run at all. Every entry is now the result of
 * RUNNING the binary, so `true` means it answered.
 *
 * `toolStates` is the same question answered with the three states
 * `termuxDiagnose` already uses for apt: ABSENT, WILL_NOT_LOAD, RUNS. A binary
 * that exists but will not load is a different thing from a missing one, and
 * collapsing them is what made the old field useless.
 *
 * `toolDetails` carries the real output for every tool that did not run, so the
 * caller never has to guess why. Empty for tools that run.
 *
 * `prefixExecutes` is the one fact those maps cannot express on their own
 * (issue 317): whether the prefix as a whole was proven to run. A tree can have
 * files, a non-zero binCount and even a working `sh` while its core binaries
 * segfault, and a reader looking at eleven booleans has no way to tell that from
 * a healthy install. It is nullable because "not measured yet" is a different
 * answer from "measured and broken", and reporting the first as the second is
 * the guessing this whole surface exists to remove.
 */
@Serializable data class TermuxStatusResult(
    val initialized: Boolean,
    val rootPath: String,
    val binPath: String,
    val homePath: String,
    val libPath: String,
    val binCount: Int,
    val availableTools: Map<String, Boolean>,
    val toolStates: Map<String, String> = emptyMap(),
    val toolDetails: Map<String, String> = emptyMap(),
    val prefixExecutes: Boolean? = null,
    val prefixHealth: String? = null,
    /**
     * What the egress boundary actually guarantees, in the words a person needs (issue 336).
     *
     * THE TICKET'S OWN MINIMUM TO SHIP: "at the moment the protection is presented as
     * unconditional and it is not". A status surface is where somebody looks when they want to know
     * whether they are safe, so this is where the honest sentence belongs. [EgressBoundary] holds the
     * reasoning; this carries it to the model.
     */
    val egressBoundary: String? = null,
) : ToolResultData
@Serializable data class TermuxHasCommandResult(val command: String, val available: Boolean, val message: String) : ToolResultData
/*
 * `packagesInstalled` and `packagesIncomplete` are the package-level half of the diagnosis
 * (issue 31). `aptWorking` is "apt exits 0", which the ticket proved is compatible with an
 * install that delivered nothing, so the diagnosis now carries the measured state of every
 * package the database records as installed. A prefix with a clean apt and three packages whose
 * payload is missing is not a healthy prefix, and this is the field that says so.
 */
@Serializable data class TermuxDiagnoseResult(
    val environmentReady: Boolean,
    val binCount: Int,
    val executableCount: Int,
    val aptWorking: Boolean,
    val aptError: String?,
    val linkerPath: String,
    val bashWorks: Boolean,
    val bashError: String?,
    val diagnostics: List<String>,
    val packagesInstalled: List<String> = emptyList(),
    val packagesIncomplete: List<String> = emptyList(),
    val installDetail: List<String> = emptyList(),
) : ToolResultData