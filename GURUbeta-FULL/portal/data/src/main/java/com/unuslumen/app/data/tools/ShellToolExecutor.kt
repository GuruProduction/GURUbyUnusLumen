package com.unuslumen.app.data.tools

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.provider.Settings
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import com.unuslumen.app.data.tor.TorManager
import com.unuslumen.app.util.shell.ShellExecutor
import com.unuslumen.app.util.shell.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.net.InetSocketAddress

class ShellToolExecutor(
    private val context: Context,
    private val fileAccessGuard: FileAccessGuard,
    private val torManager: TorManager
) : ToolExecutor {
    private val json = Json { ignoreUnknownKeys = true }
    private val shellExecutor = ShellExecutor(context)
    private var rootAvailable: Boolean? = null
    private var hasManageExternalStorage: Boolean? = null

    private fun hasFullStorageAccess(): Boolean {
        hasManageExternalStorage?.let { return it }
        val granted = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Environment.isExternalStorageManager() else true
        hasManageExternalStorage = granted; return granted
    }

    override suspend fun execute(toolName: String, args: Map<String, Any?>): ToolExecutionResult {
        return when (toolName) {
            ShellToolDefinitions.EXECUTE_SHELL -> executeShellCommand(args)
            ShellToolDefinitions.READ_FILE -> readFile(args)
            ShellToolDefinitions.WRITE_FILE -> writeFile(args)
            ShellToolDefinitions.LIST_DIRECTORY -> listDirectory(args)
            ShellToolDefinitions.GET_DEVICE_INFO -> getDeviceInfo()
            ShellToolDefinitions.PAIR_ADB -> pairAdb(args)
            ShellToolDefinitions.CHECK_ADB_STATUS -> checkAdbStatus()
            ShellToolDefinitions.GET_BUSYBOX_HELP -> getBusyboxHelp()
            ShellToolDefinitions.BUSYBOX_EXEC -> busyboxExec(args)
            ShellToolDefinitions.PYTHON_EXEC -> executePython(args)
            ShellToolDefinitions.FILE_SYSTEM_ACCESS -> checkFileSystemAccess()
            ShellToolDefinitions.REQUEST_ALL_FILES_ACCESS -> requestAllFilesAccess()
            ShellToolDefinitions.ADB_EXEC -> adbExec(args)
            else -> ToolExecutionResult.error("Unknown tool: $toolName")
        }
    }

    private suspend fun executeShellCommand(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val command = args["command"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'command'")
        val forceRoot = (args["forceRoot"] as? Boolean) ?: false
        if (fileAccessGuard.checkSedBlocked(command)) { val r = ShellCommandResult(-1, "", "BLOCKED: sed is not allowed. Use editFile tool.", false); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) }
        var effectiveCommand = command; var redirected = false
        if (!forceRoot && !hasFullStorageAccess() && (command.contains("/sdcard/") || command.contains("/storage/emulated/"))) {
            val appStorage = context.filesDir.absolutePath; effectiveCommand = command.replace(Regex("""/storage/emulated/0/"""), "$appStorage/").replace(Regex("""/sdcard/"""), "$appStorage/"); if (effectiveCommand != command) redirected = true
        }
        val result = shellExecutor.execute(effectiveCommand, forceRoot)
        if (!result.success && !redirected && !forceRoot && !hasFullStorageAccess() && (result.stderr.contains("Permission denied", ignoreCase = true) || result.stderr.contains("EPERM", ignoreCase = true) || result.stderr.contains("Operation not permitted", ignoreCase = true))) {
            if (command.contains("/sdcard/") || command.contains("/storage/emulated/")) { val appStorage = context.filesDir.absolutePath; val retryCommand = command.replace(Regex("""/storage/emulated/0/"""), "$appStorage/").replace(Regex("""/sdcard/"""), "$appStorage/"); val retryResult = shellExecutor.execute(retryCommand, forceRoot); val r = ShellCommandResult(retryResult.exitCode, retryResult.stdout + if (retryResult.success) "\n[Redirected /sdcard → $appStorage]" else "", retryResult.stderr, retryResult.success); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) }
        }
        val annotation = if (redirected && !hasFullStorageAccess() && result.success) "\n[Redirected /sdcard → ${context.filesDir.absolutePath}]" else ""
        val r = ShellCommandResult(result.exitCode, result.stdout + annotation, result.stderr, result.success); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r))
    }

    private suspend fun readFile(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val path = args["path"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'path'")
        try { val file = File(path); if (!file.exists()) { val r = ReadFileResult("", false, 0, "File does not exist: $path"); return@withContext ToolExecutionResult.success(r, json.encodeToString(ReadFileResult.serializer(), r)) }; if (!file.canRead()) { val r = ReadFileResult("", true, file.length(), "Cannot read (permission denied): $path"); return@withContext ToolExecutionResult.success(r, json.encodeToString(ReadFileResult.serializer(), r)) }; if (file.isDirectory) { val r = ReadFileResult("", true, file.length(), "Path is a directory: $path. Use listDirectory."); return@withContext ToolExecutionResult.success(r, json.encodeToString(ReadFileResult.serializer(), r)) }; val content = file.readText(); fileAccessGuard.recordFileRead(path, content); val r = ReadFileResult(content, true, file.length(), null); ToolExecutionResult.success(r, json.encodeToString(ReadFileResult.serializer(), r)) } catch (e: Exception) { val r = ReadFileResult("", false, 0, "Error: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(ReadFileResult.serializer(), r)) }
    }

    private suspend fun writeFile(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val path = args["path"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'path'"); val content = args["content"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'content'")
        val effectivePath = if (!hasFullStorageAccess() && (path.startsWith("/sdcard/") || path.startsWith("/storage/emulated/"))) { val relativePath = path.removePrefix("/sdcard/").removePrefix("/storage/emulated/0/"); "${context.filesDir.absolutePath}/$relativePath" } else path
        try { val file = File(effectivePath); file.parentFile?.mkdirs(); file.writeText(content); val r = WriteFileResult(true, file.absolutePath, file.length(), if (effectivePath != path) "Redirected from $path to app storage" else null); ToolExecutionResult.success(r, json.encodeToString(WriteFileResult.serializer(), r)) } catch (e: Exception) { try { val tempFile = File(context.cacheDir, "write_temp_${System.currentTimeMillis()}.tmp"); tempFile.writeText(content); val shellResult = shellExecutor.execute("cp \"${tempFile.absolutePath}\" \"$effectivePath\" 2>/dev/null && rm \"${tempFile.absolutePath}\" 2>/dev/null"); tempFile.delete(); if (shellResult.success) { val written = File(effectivePath).let { if (it.exists()) it.length() else 0L }; val r = WriteFileResult(true, effectivePath, written, null); ToolExecutionResult.success(r, json.encodeToString(WriteFileResult.serializer(), r)) } else { val heredocResult = shellExecutor.execute("cat > \"$effectivePath\" << 'GURU_EOF'\n$content\nGURU_EOF"); if (heredocResult.success) { val written = File(effectivePath).let { if (it.exists()) it.length() else 0L }; val r = WriteFileResult(true, effectivePath, written, null); ToolExecutionResult.success(r, json.encodeToString(WriteFileResult.serializer(), r)) } else { val r = WriteFileResult(false, effectivePath, 0, "Write failed: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(WriteFileResult.serializer(), r)) } } } catch (shellEx: Exception) { val r = WriteFileResult(false, effectivePath, 0, "Write failed: ${e.message}. Shell: ${shellEx.message}"); ToolExecutionResult.success(r, json.encodeToString(WriteFileResult.serializer(), r)) } }
    }

    private suspend fun listDirectory(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val path = args["path"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'path'")
        try { val dir = File(path); if (!dir.exists()) { val r = ListDirectoryResult(path, emptyList(), "Directory does not exist: $path"); return@withContext ToolExecutionResult.success(r, json.encodeToString(ListDirectoryResult.serializer(), r)) }; if (!dir.isDirectory) { val r = ListDirectoryResult(path, emptyList(), "Path is not a directory: $path"); return@withContext ToolExecutionResult.success(r, json.encodeToString(ListDirectoryResult.serializer(), r)) }; if (!dir.canRead()) { val r = ListDirectoryResult(path, emptyList(), "Cannot read (permission denied): $path"); return@withContext ToolExecutionResult.success(r, json.encodeToString(ListDirectoryResult.serializer(), r)) }; val entries = dir.listFiles()?.map { file -> val type = when { file.isDirectory -> "DIR"; file.isFile -> "FILE"; else -> "OTHER" }; val size = if (file.isFile) formatSize(file.length()) else "-"; val modified = java.text.SimpleDateFormat("yyyy-MM-dd HH:mm", java.util.Locale.US).format(java.util.Date(file.lastModified())); DirectoryEntry(file.name, type, size, modified) }?.sortedBy { "${if (it.type == "DIR") "0" else "1"}${it.name.lowercase()}" } ?: emptyList(); val r = ListDirectoryResult(dir.absolutePath, entries, null); ToolExecutionResult.success(r, json.encodeToString(ListDirectoryResult.serializer(), r)) } catch (e: Exception) { val r = ListDirectoryResult(path, emptyList(), "Error: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(ListDirectoryResult.serializer(), r)) }
    }

    private fun getDeviceInfo(): ToolExecutionResult {
        val r = DeviceInfoResult(Build.MANUFACTURER, Build.MODEL, Build.PRODUCT, Build.VERSION.RELEASE, Build.VERSION.SDK_INT, Build.SUPPORTED_ABIS?.joinToString(", ") ?: "unknown", isRootAvailable(), getTotalRam(), getAvailableRam(), getStorageInfo(Environment.getDataDirectory())?.first, getStorageInfo(Environment.getDataDirectory())?.second, Environment.getExternalStorageDirectory()?.let { getStorageInfo(it)?.first }, Environment.getExternalStorageDirectory()?.let { getStorageInfo(it)?.second }); return ToolExecutionResult.success(r, json.encodeToString(DeviceInfoResult.serializer(), r))
    }

    private suspend fun pairAdb(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val pairingCode = args["pairingCode"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'pairingCode'"); val port = (args["port"] as? Number)?.toInt()
        try { val result = if (port != null && port > 0) shellExecutor.pairWithAdb(pairingCode, port) else shellExecutor.autoPairWithAdb(pairingCode); val r = AdbPairResult(result.success, result.error); ToolExecutionResult.success(r, json.encodeToString(AdbPairResult.serializer(), r)) } catch (e: Exception) { val r = AdbPairResult(false, "Pairing error: ${e.message}"); ToolExecutionResult.success(r, json.encodeToString(AdbPairResult.serializer(), r)) }
    }

    private suspend fun checkAdbStatus(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val r = AdbStatusResult(shellExecutor.isAdbAvailable(), shellExecutor.isAdbPaired(), shellExecutor.isAccessibilityRunning()); return@withContext ToolExecutionResult.success(r, json.encodeToString(AdbStatusResult.serializer(), r))
    }

    private suspend fun adbExec(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val command = args["command"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'command'")
        // Strip any -L flag from the command. This Termux-built adb binary
        // (version 35.0.2) does not support the -L flag for specifying the
        // server socket. The adb server uses the default tcp:localhost:5037
        // socket automatically. Token-based filtering handles all formats:
        // "-L tcp:...", "-L=tcp:...", "-Ltcp:...", standalone "-L", etc.
        val tokens = command.trim().split(Regex("\\s+")).toMutableList()
        var i = 0
        while (i < tokens.size) {
            if (tokens[i] == "-L") {
                tokens.removeAt(i)
                if (i < tokens.size) tokens.removeAt(i)
            } else if (tokens[i].startsWith("-L=") || tokens[i].startsWith("-L") && tokens[i].length > 2 && tokens[i][2] != '-') {
                tokens.removeAt(i)
            } else {
                i++
            }
        }
        val cleanCommand = tokens.joinToString(" ").trim()
        if (cleanCommand.isBlank()) { val r = ShellCommandResult(-1, "", "Empty command after stripping -L flag.", false); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) }
        if (!shellExecutor.reTools.isInitialized) shellExecutor.reTools.initialize()
        if (!shellExecutor.reTools.isAdbAvailable()) { val r = ShellCommandResult(-1, "", "ADB binary not available. The adb binary could not be extracted from assets.", false); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) }
        val fullCommand = shellExecutor.reTools.wrapAdb(cleanCommand)
        val result = shellExecutor.execute(fullCommand)
        val r = ShellCommandResult(result.exitCode, result.stdout, result.stderr, result.success); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r))
    }

    private suspend fun getBusyboxHelp(): ToolExecutionResult = withContext(Dispatchers.IO) {
        if (!shellExecutor.busybox.isAvailable()) shellExecutor.busybox.initialize(); if (!shellExecutor.toybox.isToyboxAvailable()) shellExecutor.toybox.initialize()
        val toyboxApplets = if (shellExecutor.toybox.isToyboxAvailable()) shellExecutor.toybox.getToyboxApplets() else emptyList()
        val symlinkApplets = try { java.io.File(shellExecutor.toybox.getSymlinkDir()).listFiles()?.filter { it.name != "toybox" }?.map { it.name } ?: emptyList() } catch (_: Exception) { emptyList() }
        val systemApplets = if (shellExecutor.busybox.isAvailable()) shellExecutor.busybox.getAvailableApplets() else emptyList()
        val standalone = shellExecutor.toybox.getAvailableTools().keys.filter { it != "toybox" }.sorted()
        val allTools = (toyboxApplets + symlinkApplets + systemApplets + standalone).distinct().sorted()
        val r = BusyboxHelpResult(allTools.isNotEmpty(), if (shellExecutor.toybox.isToyboxAvailable()) shellExecutor.toybox.getToyboxPath() else shellExecutor.busybox.getPath(), allTools.size, allTools); ToolExecutionResult.success(r, json.encodeToString(BusyboxHelpResult.serializer(), r))
    }

    private suspend fun busyboxExec(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val command = args["command"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'command'")
        if (fileAccessGuard.checkSedBlocked(command)) { val r = ShellCommandResult(-1, "", "BLOCKED: sed is not allowed.", false); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) }
        val needsTorProxy = command.contains("curl") || command.contains("wget")
        if (needsTorProxy) { if (!torManager.isReady.value) { val r = ShellCommandResult(-1, "", "Tor is not running.", false); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) }; val socksPort = (torManager.getSocksProxy().address() as? InetSocketAddress)?.port ?: 9050; if (!shellExecutor.toybox.isToyboxAvailable()) shellExecutor.toybox.initialize(); val caCertPath = File(context.filesDir, "cacert.pem"); if (!caCertPath.exists()) { try { context.assets.open("cacert.pem").use { input -> FileOutputStream(caCertPath).use { output -> input.copyTo(output) } } } catch (_: Exception) {} }; val symlinkPath = shellExecutor.toybox.getSymlinkDir(); val nativeLibPath = context.applicationInfo.nativeLibraryDir; val httpPort = (torManager.getProxy().address() as? InetSocketAddress)?.port ?: 8118; val isWget = command.contains("wget"); val caCert = "CURL_CA_BUNDLE=${caCertPath.absolutePath} SSL_CERT_FILE=${caCertPath.absolutePath}"; val proxyEnv = if (isWget) "http_proxy=http://127.0.0.1:$httpPort https_proxy=http://127.0.0.1:$httpPort $caCert" else "ALL_PROXY=socks5h://127.0.0.1:$socksPort HTTP_PROXY=socks5h://127.0.0.1:$socksPort HTTPS_PROXY=socks5h://127.0.0.1:$socksPort $caCert"; val fullCommand = "export $proxyEnv; PATH=$symlinkPath:$nativeLibPath:\$PATH; $command"; val result = shellExecutor.execute(fullCommand); val r = ShellCommandResult(result.exitCode, result.stdout, result.stderr, result.success); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) }
        if (!shellExecutor.busybox.isAvailable()) shellExecutor.busybox.initialize()
        val hasShellOperators = command.contains("|") || command.contains(">>") || command.contains("&&") || command.contains("||") || command.contains(";")
        if (hasShellOperators) { if (!shellExecutor.toybox.isToyboxAvailable()) shellExecutor.toybox.initialize(); val symlinkPath = shellExecutor.toybox.getSymlinkDir(); val nativeLibPath = shellExecutor.toybox.getSymlinkDir(); val fullCommand = "PATH=$symlinkPath:$nativeLibPath:\$PATH; $command"; val result = shellExecutor.execute(fullCommand); val r = ShellCommandResult(result.exitCode, result.stdout, result.stderr, result.success); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) }
        val parts = command.trim().split(Regex("\\s+"), limit = 2); val toolName = parts.firstOrNull() ?: ""; val toolArgs = if (parts.size > 1) parts[1] else ""
        val standaloneTools = setOf("curl", "wget", "sqlite3", "jq", "zip", "unzip", "make", "git", "ssh", "scp", "rsync", "tmux", "openssl"); val cronTools = setOf("crontab", "crond"); val toyboxApplets = setOf("crontab", "crond", "awk", "grep", "tar", "find", "xargs", "tr", "sort", "wc", "tee", "nc", "netcat", "netstat", "hexdump", "xxd", "base64", "diff", "patch", "cp", "mv", "rm", "ls", "cat", "head", "tail", "ps", "kill", "sleep", "touch", "mkdir", "rmdir", "ln", "chmod", "chown", "which", "mount", "umount", "df", "du", "free", "uptime", "hostname", "ifconfig", "ping", "vmstat", "stat", "file", "strings", "od", "nl", "cut", "paste", "fold", "fmt", "expand", "unexpand", "seq", "test", "true", "false", "echo", "printf", "env", "id", "who", "whoami", "uname", "date", "cal", "basename", "dirname", "realpath", "readlink", "pwd", "clear", "reset", "help", "yes", "factor", "tsort", "comm", "cmp", "cksum", "sum", "sha1sum", "sha256sum", "sha512sum", "md5sum", "crc32", "base32", "uuencode", "uudecode", "iconv", "getconf", "nproc", "printenv", "ulimit", "nohup", "nice", "renice", "ionice", "timeout", "flock", "setsid", "mktemp", "mkfifo", "mknod", "cpio", "unshare", "nsenter", "sysctl", "logname", "groups", "getopt", "rev", "tac", "shred", "shuf", "watch", "w", "pmap", "pwdx", "readelf", "readahead", "uuidgen", "pwgen", "mcookie", "blkid", "blockdev", "lsattr", "chattr", "mountpoint", "swapon", "swapoff", "mkswap", "losetup", "fsync", "fsfreeze", "blkdiscard", "freeramdisk", "devmem", "eject", "reboot", "pivot_root", "switch_root", "chroot", "oneit", "openvt", "deallocvt", "chvt", "insmod", "rmmod", "lsmod", "modinfo", "acpi", "hwclock", "rtcwake", "watchdog", "inotifyd", "sntp", "ftpget", "ftpput", "httpd", "tunctl", "rfkill", "microcom", "gpiod", "i2cdetect", "i2cdump", "i2cget", "i2cset", "i2ctransfer", "gzip", "gunzip", "zcat", "bzcat", "bunzip2", "install", "dos2unix", "unix2dos", "pgrep", "pkill", "killall", "top", "chrt", "taskset")
        if (standaloneTools.contains(toolName) || toyboxApplets.contains(toolName) || cronTools.contains(toolName)) { if (!shellExecutor.toybox.isAvailable("toybox")) shellExecutor.toybox.initialize(); if (standaloneTools.contains(toolName) && shellExecutor.toybox.isAvailable(toolName)) { val toolPath = shellExecutor.toybox.getPath(toolName); val fullCommand = if (toolArgs.isBlank()) toolPath else "$toolPath $toolArgs"; val result = shellExecutor.execute(fullCommand); val r = ShellCommandResult(result.exitCode, result.stdout, result.stderr, result.success); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) }; if (cronTools.contains(toolName) && shellExecutor.toybox.isToyboxAvailable()) { val cronSpool = shellExecutor.toybox.getCronSpoolDir(); val tmpDir = context.cacheDir.absolutePath + "/tmp"; java.io.File(tmpDir).mkdirs(); val appletPath = shellExecutor.toybox.getSymlinkDir() + "/" + toolName; val fullCommand = if (toolName == "crond") { if (toolArgs.isBlank()) "export TMPDIR=$tmpDir; $appletPath -c $cronSpool" else "export TMPDIR=$tmpDir; $appletPath -c $cronSpool $toolArgs" } else { if (toolArgs.isBlank()) "export TMPDIR=$tmpDir; $appletPath -c $cronSpool" else "export TMPDIR=$tmpDir; $appletPath -c $cronSpool $toolArgs" }; val result = shellExecutor.execute(fullCommand); val r = ShellCommandResult(result.exitCode, result.stdout, result.stderr, result.success); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) }; if (toyboxApplets.contains(toolName) && shellExecutor.toybox.isToyboxAvailable()) { val appletPath = shellExecutor.toybox.getSymlinkDir() + "/" + toolName; val fullCommand = if (toolArgs.isBlank()) appletPath else "$appletPath $toolArgs"; val result = shellExecutor.execute(fullCommand); val r = ShellCommandResult(result.exitCode, result.stdout, result.stderr, result.success); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) } }
        if (!shellExecutor.busybox.isAvailable()) { val r = ShellCommandResult(-1, "", "Neither toybox nor busybox available.", false); return@withContext ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r)) }
        val fullCommand = shellExecutor.busybox.wrapCommand(command); val result = shellExecutor.execute(fullCommand); val r = ShellCommandResult(result.exitCode, result.stdout, result.stderr, result.success); ToolExecutionResult.success(r, json.encodeToString(ShellCommandResult.serializer(), r))
    }

    private suspend fun executePython(args: Map<String, Any?>): ToolExecutionResult = withContext(Dispatchers.IO) {
        val script = args["script"] as? String ?: return@withContext ToolExecutionResult.error("Missing 'script'")
        val scriptLower = script.lowercase()
        if (fileAccessGuard.checkSedBlocked(scriptLower)) { val r = PythonExecResult("", "BLOCKED: sed not allowed in Python.", -1, false); return@withContext ToolExecutionResult.success(r, json.encodeToString(PythonExecResult.serializer(), r)) }
        if (scriptLower.contains("sed") && (scriptLower.contains("os.system") || scriptLower.contains("subprocess") || scriptLower.contains("popen"))) { val r = PythonExecResult("", "BLOCKED: sed not allowed via Python subprocess.", -1, false); return@withContext ToolExecutionResult.success(r, json.encodeToString(PythonExecResult.serializer(), r)) }
        try { if (!shellExecutor.python.isAvailable()) shellExecutor.python.initialize(); if (!shellExecutor.python.isAvailable()) { val r = PythonExecResult("", "Python not available on this device.", -1, false); return@withContext ToolExecutionResult.success(r, json.encodeToString(PythonExecResult.serializer(), r)) }; val scriptFile = File(context.cacheDir, "guru_script_${System.currentTimeMillis()}.py"); scriptFile.writeText(script); val execCmd = shellExecutor.python.buildExecCommand(scriptFile.absolutePath); val pb = ProcessBuilder(execCmd); pb.environment().putAll(shellExecutor.python.getPythonEnv()); pb.redirectErrorStream(false); val process = pb.start(); val stdout = async { BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() } }; val stderr = async { BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() } }; process.waitFor(); scriptFile.delete(); val exitCode = process.exitValue(); val r = PythonExecResult(stdout.await().trim(), stderr.await().trim(), exitCode, exitCode == 0); ToolExecutionResult.success(r, json.encodeToString(PythonExecResult.serializer(), r)) } catch (e: Exception) { val r = PythonExecResult("", "Python execution failed: ${e.message}. Try busybox.", -1, false); ToolExecutionResult.success(r, json.encodeToString(PythonExecResult.serializer(), r)) }
    }

    private suspend fun checkFileSystemAccess(): ToolExecutionResult = withContext(Dispatchers.IO) {
        val appStoragePath = context.filesDir.absolutePath; val externalStoragePath = Environment.getExternalStorageDirectory()?.absolutePath ?: "/sdcard"; val hasAllFilesAccess = hasFullStorageAccess(); val adbAvailable = shellExecutor.isAdbAvailable(); val adbPaired = shellExecutor.isAdbPaired(); val rootAvail = isRootAvailable()
        val pathAccess = mutableListOf<PathAccessInfo>()
        pathAccess.add(probePath(appStoragePath, "App storage (private)")); pathAccess.add(probePath(externalStoragePath, "Shared storage root (/sdcard)"))
        val downloadsDir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS); if (downloadsDir != null) pathAccess.add(probePath(downloadsDir.absolutePath, "Downloads directory"))
        pathAccess.add(probePath("/data/data/com.android.settings", "Other app data (requires root/ADB)")); pathAccess.add(probePath("/system/bin", "System binaries (read-only)")); pathAccess.add(probePath("/data/local/tmp", "Temp directory (ADB shell writable)")); pathAccess.add(probePath("/proc", "Process filesystem"))
        val accessTier = when { rootAvail -> "ROOT"; adbPaired -> "ADB_SHELL"; hasAllFilesAccess -> "ALL_FILES_ACCESS"; else -> "APP_STORAGE_ONLY" }
        val restrictions = mutableListOf<String>(); if (!hasAllFilesAccess && !adbPaired && !rootAvail) { restrictions.add("Scoped storage active. /sdcard writes redirected."); restrictions.add("Cannot access other apps' /data/data."); restrictions.add("Pair ADB for shell-level access.") }; if (hasAllFilesAccess && !adbPaired && !rootAvail) { restrictions.add("All Files Access granted. Full shared storage access."); restrictions.add("ADB not paired. Pair for shell-level access.") }; if (!adbPaired) restrictions.add("ADB not paired.")
        val r = FileSystemAccessResult(accessTier, hasAllFilesAccess, adbAvailable, adbPaired, rootAvail, appStoragePath, externalStoragePath, pathAccess, restrictions); return@withContext ToolExecutionResult.success(r, json.encodeToString(FileSystemAccessResult.serializer(), r))
    }

    private suspend fun requestAllFilesAccess(): ToolExecutionResult = withContext(Dispatchers.Main) {
        hasManageExternalStorage = null; val alreadyGranted = hasFullStorageAccess()
        if (alreadyGranted) { val r = RequestAccessResult(true, true, "All Files Access already granted."); return@withContext ToolExecutionResult.success(r, json.encodeToString(RequestAccessResult.serializer(), r)) }
        try { if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) { val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply { setData(Uri.parse("package:${context.packageName}")); addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }; context.startActivity(intent); val r = RequestAccessResult(true, false, "Opened settings. User must toggle permission."); ToolExecutionResult.success(r, json.encodeToString(RequestAccessResult.serializer(), r)) } else { val r = RequestAccessResult(true, true, "On Android 10 and below, access is granted at install time."); ToolExecutionResult.success(r, json.encodeToString(RequestAccessResult.serializer(), r)) } } catch (e: Exception) { try { val fallback = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }; context.startActivity(fallback); val r = RequestAccessResult(true, false, "Opened general settings."); ToolExecutionResult.success(r, json.encodeToString(RequestAccessResult.serializer(), r)) } catch (e2: Exception) { val r = RequestAccessResult(false, false, "Failed: ${e2.message}"); ToolExecutionResult.success(r, json.encodeToString(RequestAccessResult.serializer(), r)) } }
    }

    private fun isRootAvailable(): Boolean { rootAvailable?.let { return it }; val rooted = try { val p = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo root_test")); p.waitFor(); p.exitValue() == 0 } catch (e: Exception) { arrayOf("/system/bin/su", "/system/xbin/su", "/sbin/su", "/system/su", "/su/bin/su").any { File(it).exists() } }; rootAvailable = rooted; return rooted }
    private fun getTotalRam(): Long = try { File("/proc/meminfo").readLines().find { it.startsWith("MemTotal") }?.replace(Regex("[^0-9]"), "")?.toLongOrNull()?.div(1024) ?: -1 } catch (_: Exception) { -1 }
    private fun getAvailableRam(): Long = try { File("/proc/meminfo").readLines().find { it.startsWith("MemAvailable") }?.replace(Regex("[^0-9]"), "")?.toLongOrNull()?.div(1024) ?: -1 } catch (_: Exception) { -1 }
    private fun getStorageInfo(path: File): Pair<Long, Long>? = try { val stat = StatFs(path.absolutePath); val total = stat.blockCountLong * stat.blockSizeLong / (1024 * 1024); val free = stat.availableBlocksLong * stat.blockSizeLong / (1024 * 1024); total to free } catch (_: Exception) { null }
    private fun formatSize(bytes: Long): String = when { bytes < 1024 -> "$bytes B"; bytes < 1024 * 1024 -> "${bytes / 1024} KB"; bytes < 1024 * 1024 * 1024 -> "${bytes / (1024 * 1024)} MB"; else -> "${bytes / (1024 * 1024 * 1024)} GB" }
    private fun probePath(path: String, label: String): PathAccessInfo { val file = File(path); val exists = file.exists(); val readable = if (exists) file.canRead() else false; val writable = if (exists) file.canWrite() else false; return PathAccessInfo(path, label, exists, readable, writable) }
}
