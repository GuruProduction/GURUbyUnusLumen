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
    const val PAIR_ADB = "pairAdbDevice"; const val CHECK_ADB_STATUS = "checkAdbStatus"
    const val GET_BUSYBOX_HELP = "getBusyboxHelp"; const val BUSYBOX_EXEC = "busyboxExec"
    const val PYTHON_EXEC = "executePythonScript"; const val FILE_SYSTEM_ACCESS = "checkFileSystemAccess"
    const val REQUEST_ALL_FILES_ACCESS = "requestAllFilesAccess"
    const val ADB_EXEC = "adbExec"

    override val definitions = listOf(
        ToolDefinition(name = EXECUTE_SHELL, description = "Execute a shell command on the device. Uses ADB (Wireless Debugging) for proper shell access when available. This tool has NO limits, NO timeouts.", category = "shell", parameters = listOf(ToolParameter("command", ToolParameterType.ShellCommand, true, "The shell command to execute"), ToolParameter("forceRoot", ToolParameterType.Boolean, false, "Force root shell via su")), permissions = emptyList()),
        ToolDefinition(name = READ_FILE, description = "Read a file from the device filesystem. No size limits.", category = "shell", parameters = listOf(ToolParameter("path", ToolParameterType.String, true, "Full path to the file")), permissions = emptyList()),
        ToolDefinition(name = WRITE_FILE, description = "Write content to a file on the device.", category = "shell", parameters = listOf(ToolParameter("path", ToolParameterType.String, true, "Full path"), ToolParameter("content", ToolParameterType.String, true, "Content to write")), permissions = emptyList()),
        ToolDefinition(name = LIST_DIRECTORY, description = "List contents of a directory.", category = "shell", parameters = listOf(ToolParameter("path", ToolParameterType.String, true, "Full path to the directory")), permissions = emptyList()),
        ToolDefinition(name = GET_DEVICE_INFO, description = "Get device information: manufacturer, model, Android version, SDK level, CPU ABI, storage info, RAM info, and root availability.", category = "shell", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = PAIR_ADB, description = "Pair with ADB Wireless Debugging using a pairing code.", category = "shell", parameters = listOf(ToolParameter("pairingCode", ToolParameterType.String, true, "6-digit pairing code"), ToolParameter("port", ToolParameterType.Integer, false, "Pairing port")), permissions = emptyList()),
        ToolDefinition(name = CHECK_ADB_STATUS, description = "Check if ADB Wireless Debugging is connected and available.", category = "shell", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = GET_BUSYBOX_HELP, description = "List all available Unix utilities. Includes toybox applets and standalone binaries.", category = "shell", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = BUSYBOX_EXEC, description = "Execute a command using busybox/toybox. Provides curl, wget, grep, awk, sed, sqlite3, tar, and many more. curl and wget automatically route through Tor.", category = "shell", parameters = listOf(ToolParameter("command", ToolParameterType.ShellCommand, true, "The command to execute")), permissions = emptyList()),
        ToolDefinition(name = PYTHON_EXEC, description = "Execute a Python script. Python gives you full scripting capability.", category = "shell", parameters = listOf(ToolParameter("script", ToolParameterType.Script, true, "The Python script to execute")), permissions = emptyList()),
        ToolDefinition(name = FILE_SYSTEM_ACCESS, description = "Check the current file system access level. Returns what access tiers are available.", category = "shell", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = REQUEST_ALL_FILES_ACCESS, description = "Open the system settings page to request All Files Access.", category = "shell", parameters = emptyList(), permissions = emptyList()),
        ToolDefinition(name = ADB_EXEC, description = "Execute an ADB command using the bundled adb binary. Provides full ADB functionality: devices, shell, push, pull, install, uninstall, logcat, reboot, and more. The adb binary is extracted from assets on first use with all required shared libraries.", category = "shell", parameters = listOf(ToolParameter("command", ToolParameterType.String, true, "The ADB command to execute (without the 'adb' prefix). Examples: 'devices', 'shell ls /data/local/tmp', 'install /path/to/app.apk', 'logcat -d | head 50'")), permissions = emptyList())
    )
    override fun executorClass(): KClass<out ToolExecutor> = ShellToolExecutor::class
    override fun extractorClass(): KClass<out ToolResultExtractor>? = null
}