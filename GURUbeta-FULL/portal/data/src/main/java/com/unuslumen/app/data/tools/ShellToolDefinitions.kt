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

object ShellToolDefinitions : ToolSetRegistration {
    const val EXECUTE_SHELL = "executeShellCommand"; const val READ_FILE = "readFile"; const val WRITE_FILE = "writeFile"
    const val LIST_DIRECTORY = "listDirectory"; const val GET_DEVICE_INFO = "getDeviceInfo"
    const val PAIR_ADB = "pairAdbDevice"

    /**
     * The capability audit, named for what it actually reports.
     *
     * IT USED TO BE `checkAdbStatus`, and the name was a lie in the direction that
     * matters: it answers two separate questions, one of which is accessibility, and
     * naming it after ADB told a normal user that a power-user transport was the thing
     * they were being asked about. ADB and Shizuku stay power-user surfaces; this tool
     * is the ordinary capability check and must not read as an ADB prompt.
     */
    const val CHECK_ACCESSIBILITY_STATUS = "checkAccessibilityStatus"
    const val GET_BUSYBOX_HELP = "getBusyboxHelp"; const val BUSYBOX_EXEC = "busyboxExec"
    const val PYTHON_EXEC = "executePythonScript"; const val FILE_SYSTEM_ACCESS = "checkFileSystemAccess"
    const val REQUEST_ALL_FILES_ACCESS = "requestAllFilesAccess"
    const val ADB_EXEC = "adbExec"

    override val definitions = listOf(
        /*
         * THE DESCRIPTION MUST MATCH THE DEVICE, not the best case.
         *
         * Issue 40: this used to say "Uses ADB ... NO limits", which is true only
         * on a device with wireless debugging paired. On a stock phone the
         * executor falls back to app UID, where /proc/net/arp, dumpsys and
         * `settings get` all return Permission denied. A model reading "NO
         * limits" attempted those, got denials, and treated them as transient.
         *
         * Every result now reports the tier that actually ran, so this claim is
         * checkable after the fact rather than taken on trust.
         */
        ToolDefinition(
            name = EXECUTE_SHELL,
            description = "Execute a shell command on the device at the best available tier, and " +
                "every result reports which tier served it. ROOT: everything. ADB shell via " +
                "Wireless Debugging: most system reads and writes. APP_UID: this app only, so no " +
                "/proc/net, no dumpsys, no settings and no other app's data. On APP_UID a " +
                "Permission denied is permanent and must not be retried.",
            category = "shell",
            parameters = listOf(
                ToolParameter("command", ToolParameterType.ShellCommand, true, "The shell command to execute"),
                ToolParameter("forceRoot", ToolParameterType.Boolean, false, "Force root shell via su. Fails honestly when there is no root."),
            ),
            permissions = emptyList(),
        ),
        ToolDefinition(name = READ_FILE, description = "Read a file from the device filesystem. No size limits.", category = "shell", parameters = listOf(ToolParameter("path", ToolParameterType.String, true, "Full path to the file")), permissions = emptyList()),
        ToolDefinition(name = WRITE_FILE, description = "Write content to a file on the device.", category = "shell", parameters = listOf(ToolParameter("path", ToolParameterType.String, true, "Full path"), ToolParameter("content", ToolParameterType.String, true, "Content to write")), permissions = emptyList()),
        ToolDefinition(name = LIST_DIRECTORY, description = "List contents of a directory.", category = "shell", parameters = listOf(ToolParameter("path", ToolParameterType.String, true, "Full path to the directory")), permissions = emptyList()),
        ToolDefinition(name = GET_DEVICE_INFO, description = "Get device information: manufacturer, model, Android version, SDK level, CPU ABI, storage info, RAM info, and root availability.", category = "shell", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = PAIR_ADB, description = "Pair with ADB Wireless Debugging using a pairing code.", category = "shell", parameters = listOf(ToolParameter("pairingCode", ToolParameterType.String, true, "6-digit pairing code"), ToolParameter("port", ToolParameterType.Integer, false, "Pairing port")), permissions = emptyList()),
        /*
         * THE DESCRIPTION NAMES BOTH ANSWERS, because the tool carries both.
         *
         * Accessibility is the one a normal install cares about: it is what lets GURU
         * drive the phone's own UI, and it needs no ADB, no root and no pairing. ADB is
         * reported as well because a power user asks this same question after pairing,
         * and hiding it would make the tool lie by omission. It is described as what it
         * is — an optional transport — rather than as the subject.
         */
        ToolDefinition(
            name = CHECK_ACCESSIBILITY_STATUS,
            description = "Check whether GURU can drive the phone's own interface, and what the " +
                "shell tier can reach. Reports the accessibility service as one of three states " +
                "(running, enabled but not bound, or disabled) so a service that is starting is " +
                "never reported as broken. Also reports whether a shell transport is available, " +
                "which is optional: accessibility works with no ADB, no root and no pairing, and " +
                "only system-level reads and writes need a higher tier. When a capability is out " +
                "of reach, the answer says which tier would be needed rather than implying the " +
                "device cannot do it.",
            category = "shell",
            parameters = emptyList(),
            permissions = emptyList(),
        ),
        ToolDefinition(name = GET_BUSYBOX_HELP, description = "List all available Unix utilities. Includes toybox applets and standalone binaries.", category = "shell", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = BUSYBOX_EXEC, description = "Execute a command using busybox/toybox. Provides curl, wget, grep, awk, sed, sqlite3, tar, and many more. curl and wget automatically route through Tor.", category = "shell", parameters = listOf(ToolParameter("command", ToolParameterType.ShellCommand, true, "The command to execute")), permissions = emptyList()),
        /*
         * THE DESCRIPTION STATES THE EGRESS BOUNDARY (issue 336's third action item).
         *
         * The old text was one sentence and said nothing about the network, so a model had no
         * way to know which requests were protected and the person was never told. That was the
         * umbrella issue's point: the protection was presented as unconditional and it was not.
         *
         * WHAT IS NOW TRUE, and each clause here was measured rather than assumed. urllib and
         * requests honour the proxy environment the framework injects. A raw
         * socket.create_connection is wrapped by a startup guard: with a proxy configured it
         * goes through SOCKS5, and without one a PUBLIC destination is refused rather than sent
         * from the real address. A private destination is always untouched, because proxying a
         * LAN address breaks it.
         *
         * WHAT IS STILL NOT COVERED is named rather than implied away, because the honest limit
         * is the useful part: a C extension calling connect(2) itself, or a statically linked
         * library with its own stack, bypasses any Python-level guard. A person deciding
         * whether to run a script deserves to know where the edge is.
         */
        ToolDefinition(
            name = PYTHON_EXEC,
            description = "Execute a Python script with full scripting capability. NETWORK: " +
                "urllib and requests route through Tor automatically. A raw " +
                "socket.create_connection is guarded at interpreter startup, so a public " +
                "address goes through the circuit when one is configured and is REFUSED rather " +
                "than sent from your real IP when none is. Private and local addresses are " +
                "never proxied. The known gap is compiled code that opens a socket itself, " +
                "such as a C extension calling connect directly, which no Python-level guard " +
                "can see.",
            category = "shell",
            parameters = listOf(ToolParameter("script", ToolParameterType.Script, true, "The Python script to execute")),
            permissions = emptyList(),
        ),
        ToolDefinition(name = FILE_SYSTEM_ACCESS, description = "Check the current file system access level. Returns what access tiers are available.", category = "shell", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = REQUEST_ALL_FILES_ACCESS, description = "Open the system settings page to request All Files Access.", category = "shell", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = ADB_EXEC, description = "Execute an ADB command using the bundled adb binary. Provides full ADB functionality: devices, shell, push, pull, install, uninstall, logcat, reboot, and more. The adb binary is extracted from assets on first use with all required shared libraries.", category = "shell", parameters = listOf(ToolParameter("command", ToolParameterType.String, true, "The ADB command to execute (without the 'adb' prefix). Examples: 'devices', 'shell ls /data/local/tmp', 'install /path/to/app.apk', 'logcat -d | head 50'")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = ShellToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}