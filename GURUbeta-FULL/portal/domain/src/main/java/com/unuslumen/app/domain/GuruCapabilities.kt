// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain

object GuruCapabilities {

    val yourToolCallingFormat: String = ""

    val yourTools: String = ""

    val yourDatabase: String = ""

    val yourDeviceIntegration: String = ""

    val yourDeviceAccess: String = ""

    val yourShellAndPython: String = ""

    val yourScreenAndInput: String = ""

    val yourSystemControl: String = ""

    val yourSecurity: String = ""

    val yourSelfEvolution: String = ""

    val yourTaskManagement: String = ""

    val notePrompts: String = ""

    val fullCapabilities: String
        get() = listOf(
            yourToolCallingFormat,
            yourTools,
            yourDatabase,
            yourDeviceIntegration,
            yourDeviceAccess,
            yourShellAndPython,
            yourScreenAndInput,
            yourSystemControl,
            yourSecurity,
            yourSelfEvolution,
            yourTaskManagement
        ).joinToString("\n\n")

}
