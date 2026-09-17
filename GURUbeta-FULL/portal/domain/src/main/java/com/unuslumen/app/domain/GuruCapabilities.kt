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
