// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolResultData
import kotlinx.serialization.Serializable

@Serializable data class TermuxInitResult(val success: Boolean, val initialized: Boolean, val rootPath: String, val homePath: String, val binPath: String, val message: String) : ToolResultData
@Serializable data class TermuxExecResult(val success: Boolean, val exitCode: Int, val stdout: String, val stderr: String, val command: String) : ToolResultData
@Serializable data class TermuxInstallResult(val success: Boolean, val exitCode: Int, val output: String, val packages: String) : ToolResultData
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
) : ToolResultData
@Serializable data class TermuxHasCommandResult(val command: String, val available: Boolean, val message: String) : ToolResultData
@Serializable data class TermuxDiagnoseResult(val environmentReady: Boolean, val binCount: Int, val executableCount: Int, val aptWorking: Boolean, val aptError: String?, val linkerPath: String, val bashWorks: Boolean, val bashError: String?, val diagnostics: List<String>) : ToolResultData