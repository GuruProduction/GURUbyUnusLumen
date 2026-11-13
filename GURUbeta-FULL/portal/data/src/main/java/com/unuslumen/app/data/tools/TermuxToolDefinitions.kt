// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.registry.ToolDefinition
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tools.registry.ToolParameter
import com.unuslumen.app.data.tools.registry.ToolParameterType
import com.unuslumen.app.data.tools.registry.ToolResultExtractor
import com.unuslumen.app.data.tools.registry.ToolSetRegistration
import kotlin.reflect.KClass

object TermuxToolDefinitions : ToolSetRegistration {
    const val TERMUX_INIT = "termuxInit"; const val TERMUX_EXEC = "termuxExec"; const val TERMUX_INSTALL = "termuxInstall"
    const val TERMUX_STATUS = "termuxStatus"; const val TERMUX_HAS_COMMAND = "termuxHasCommand"; const val TERMUX_DIAGNOSE = "termuxDiagnose"

    override val definitions = listOf(
        ToolDefinition(name = TERMUX_INIT, description = "Initialize the bundled Termux Linux environment AND the reverse engineering toolset.", category = "termux", parameters = emptyList(), permissions = emptyList()),
        /*
         * THE DESCRIPTION STATES THE EGRESS BOUNDARY (issue 336).
         *
         * This said only that it runs a command, so nothing told a model or a person whether
         * the network traffic inside the prefix was protected. It was not: the prefix had no
         * proxy environment at all, so curl, git, pip, npm and apt left from the device's real
         * address while every other tool rode the circuit (issue 335).
         *
         * WHAT IS NOW TRUE. buildEnv exports ALL_PROXY, HTTP_PROXY and HTTPS_PROXY as socks5h
         * on Tor's SOCKS port, plus the lower-case spellings, so a tool that honours the
         * convention inherits the circuit without being told. socks5h rather than socks5 is
         * deliberate: the h makes the PROXY resolve the name, so a DNS lookup cannot leak the
         * destination even though the connection is tunnelled.
         *
         * WHAT IS STILL NOT COVERED is named: a program that ignores proxy variables, and any
         * binary with its own network stack, still goes direct. That is the honest edge of an
         * environment-based boundary and a person should know where it is.
         */
        ToolDefinition(
            name = TERMUX_EXEC,
            description = "Execute a shell command inside the bundled Termux Linux environment. " +
                "NETWORK: the environment exports socks5h proxy variables pointing at Tor, so " +
                "curl, git, pip, npm and apt route through the circuit when they honour proxy " +
                "settings, and the resolving socks5h form stops a DNS lookup from leaking the " +
                "destination. A program that ignores proxy variables, or a binary with its own " +
                "network stack, still goes direct, so for guaranteed routing use busyboxExec's " +
                "curl or webFetch instead.",
            category = "termux",
            parameters = listOf(
                ToolParameter("command", ToolParameterType.ShellCommand, true, "Command to run"),
                ToolParameter("workingDir", ToolParameterType.String, false, "Working directory"),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(name = TERMUX_INSTALL, description = "Install packages inside the bundled Termux environment using apt.", category = "termux", parameters = listOf(ToolParameter("packages", ToolParameterType.String, true, "Space-separated packages")), permissions = emptyList()),
        ToolDefinition(name = TERMUX_STATUS, description = "Get the current status of the bundled Termux environment.", category = "termux", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = TERMUX_HAS_COMMAND, description = "Check if a specific command or binary exists in the Termux environment.", category = "termux", parameters = listOf(ToolParameter("command", ToolParameterType.String, true, "Command name")), permissions = emptyList()),
        ToolDefinition(name = TERMUX_DIAGNOSE, description = "Run a full diagnostic on the bundled Termux environment.", category = "termux", parameters = emptyList(), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = TermuxToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}