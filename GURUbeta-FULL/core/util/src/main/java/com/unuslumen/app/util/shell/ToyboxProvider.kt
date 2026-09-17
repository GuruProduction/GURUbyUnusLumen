package com.unuslumen.app.util.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Manages the custom-built toybox binary and additional Unix tool binaries
 * that are not available in Android's system toybox.
 *
 * All binaries are packaged as native libraries in the APK under jniLibs/arm64-v8a/.
 * Android extracts them to nativeLibraryDir which has a different SELinux label
 * (nativedir_file) that allows execution. This is the only reliable way to ship
 * and execute native binaries on modern Android without root.
 *
 * Binaries are named with the libguru_ prefix and .so suffix so Android's
 * package installer extracts them properly. The provider maps friendly tool
 * names to their .so filenames.
 *
 * Tools bundled:
 *   toybox  — custom toybox 0.8.11 with 321 applets (wget, crontab, crond, git, etc.)
 *   curl    — curl 8.8.0 (HTTP/FTP, no TLS)
 *   sqlite3 — SQLite 3.46.0 with FTS4/FTS5/JSON1/RTREE
 *   jq      — jq 1.7.1 with builtin oniguruma
 *   zip     — ZIP archive creator (zlib contrib/minizip)
 *   unzip   — ZIP archive extractor (zlib contrib/minizip)
 *   make    — GNU Make 4.4
 *   git     — Git 2.45.0 (minimal build)
 *   ssh     — SSH client (dropbear 2024.85, statically linked)
 *   scp     — Secure copy (dropbear 2024.85, statically linked)
 *   rsync   — rsync 3.3.0
 *   tmux    — tmux 3.4
 *   openssl — OpenSSL 3.3.1 CLI
 */
class ToyboxProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"

        /**
         * Maps friendly tool names to their .so filenames in jniLibs.
         * Android requires the lib prefix and .so suffix for native library extraction.
         */
        private val TOOL_TO_SO = mapOf(
            "toybox"  to "libguru_toybox.so",
            "curl"    to "libguru_curl.so",
            "sqlite3" to "libguru_sqlite3.so",
            "jq"      to "libguru_jq.so",
            "zip"     to "libguru_zip.so",
            "unzip"   to "libguru_unzip.so",
            "make"    to "libguru_make.so",
            "git"     to "libguru_git.so",
            "ssh"     to "libguru_ssh.so",
            "scp"     to "libguru_scp.so",
            "rsync"   to "libguru_rsync.so",
            "tmux"    to "libguru_tmux.so",
            "openssl" to "libguru_openssl.so",
            "helloworld" to "libguru_helloworld.so"
        )
    }

    private var initialized = false
    private val available = mutableSetOf<String>()
    private var nativeLibDir: String = ""

    /**
     * Directory in app storage where we create symlinks to the toybox binary.
     * Toybox is a multi-call binary: it uses argv[0] (the basename of the path
     * it was called with) to determine which applet to run. When the binary is
     * named libguru_toybox.so (required for jniLibs packaging), the basename
     * "libguru_toybox.so" is not a valid applet name, so toybox doesn't know
     * what to run.
     *
     * The fix is creating symlinks in a writable directory. A symlink named
     * "wget" pointing to libguru_toybox.so makes argv[0] basename "wget",
     * which toybox recognises and dispatches correctly. We also create a
     * "toybox" symlink so the `toybox <applet> <args>` form works too.
     */
    private val symlinkDir: File
        get() = File(context.filesDir, "toybox-bin")

    /**
     * Cron spool directory in app storage. Android has no /var/spool/cron,
     * so we create our own spool directory and pass it to crond/crontab
     * via the -c flag.
     */
    private val cronSpoolDir: File
        get() = File(context.filesDir, "cron-spool")

    /**
     * Initialise by checking which tools are available in nativeLibraryDir
     * and creating symlinks for toybox applets.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext available.isNotEmpty()

        try {
            nativeLibDir = context.applicationInfo.nativeLibraryDir
            Log.d(TAG, "ToyboxProvider: nativeLibraryDir = $nativeLibDir")

            for ((toolName, soName) in TOOL_TO_SO) {
                val binaryFile = File(nativeLibDir, soName)
                if (binaryFile.exists() && binaryFile.canExecute()) {
                    available.add(toolName)
                    Log.d(TAG, "ToyboxProvider: $toolName available at ${binaryFile.absolutePath}")
                } else if (binaryFile.exists()) {
                    if (binaryFile.setExecutable(true, false)) {
                        available.add(toolName)
                        Log.d(TAG, "ToyboxProvider: $toolName available (set executable) at ${binaryFile.absolutePath}")
                    } else {
                        Log.w(TAG, "ToyboxProvider: $toolName exists but cannot set executable")
                    }
                } else {
                    Log.w(TAG, "ToyboxProvider: $toolName not found at ${binaryFile.absolutePath}")
                }
            }

            // Create symlinks for toybox applets so the multi-call binary dispatches correctly
            if (isToyboxAvailable()) {
                createToyboxSymlinks()
            }

            // Create symlinks for standalone binaries (ssh, curl, git, etc.)
            createStandaloneSymlinks()

            // Create cron spool directory
            cronSpoolDir.mkdirs()

            initialized = true
            Log.d(TAG, "ToyboxProvider: initialized — ${available.size}/${TOOL_TO_SO.size} tools: ${available.sorted().joinToString()}")
            available.isNotEmpty()
        } catch (e: Exception) {
            Log.e(TAG, "ToyboxProvider: initialization failed", e)
            initialized = true
            false
        }
    }

    /**
     * Create symlinks in app storage for each toybox applet.
     * Each symlink is named after the applet (e.g. "wget", "grep", "awk")
     * and points to the libguru_toybox.so binary. When executed, toybox
     * reads the basename of argv[0] to determine which applet to run.
     *
     * Also creates a "toybox" symlink so the `toybox <applet> <args>`
     * invocation form works.
     */
    private fun createToyboxSymlinks() {
        val toyboxSo = File(nativeLibDir, "libguru_toybox.so")
        if (!toyboxSo.exists()) return

        symlinkDir.mkdirs()

        // Clear all existing symlinks first. On reinstall, Android generates a new
        // hashed directory path for nativeLibraryDir, so old symlinks point to a
        // dead path. We must recreate every symlink on every app launch.
        symlinkDir.listFiles()?.forEach { file ->
            try {
                file.delete()
            } catch (_: Exception) {}
        }

        // Create the "toybox" symlink
        val toyboxLink = File(symlinkDir, "toybox")
        try {
            android.system.Os.symlink(toyboxSo.absolutePath, toyboxLink.absolutePath)
            Log.d(TAG, "ToyboxProvider: created symlink toybox -> ${toyboxSo.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "ToyboxProvider: failed to create toybox symlink: ${e.message}")
        }

        // Create symlinks for each applet
        val applets = listOf(
            "crontab", "crond", "awk", "grep", "fgrep", "egrep",
            "tar", "find", "xargs", "tr", "sort", "wc", "tee", "nc", "netcat",
            "netstat", "hexdump", "xxd", "base64", "base32", "diff", "patch",
            "cp", "mv", "rm", "ls", "cat", "head", "tail", "ps", "kill",
            "sleep", "touch", "mkdir", "rmdir", "ln", "chmod", "chown",
            "which", "mount", "umount", "df", "du", "free", "uptime",
            "hostname", "ifconfig", "ping", "vmstat", "stat", "file",
            "strings", "od", "nl", "cut", "paste", "fold", "fmt", "expand",
            "unexpand", "seq", "test", "true", "false", "echo", "printf",
            "env", "id", "who", "whoami", "uname", "date", "cal", "basename",
            "dirname", "realpath", "readlink", "pwd", "clear", "reset",
            "help", "yes", "factor", "tsort", "comm", "cmp", "cksum", "sum",
            "sha1sum", "sha256sum", "sha512sum", "md5sum", "crc32",
            "uuencode", "uudecode", "iconv", "getconf", "nproc", "printenv",
            "ulimit", "nohup", "nice", "renice", "ionice", "timeout",
            "flock", "setsid", "mktemp", "mkfifo", "mknod", "cpio",
            "unshare", "nsenter", "sysctl", "logname", "groups", "getopt",
            "rev", "tac", "shred", "shuf", "watch", "w", "pmap", "pwdx",
            "readelf", "readahead", "uuidgen", "pwgen", "mcookie",
            "blkid", "blockdev", "lsattr", "chattr", "mountpoint",
            "swapon", "swapoff", "mkswap", "losetup", "fsync", "fsfreeze",
            "blkdiscard", "freeramdisk", "devmem", "eject", "reboot",
            "pivot_root", "switch_root", "chroot", "oneit", "openvt",
            "deallocvt", "chvt", "insmod", "rmmod", "lsmod", "modinfo",
            "acpi", "hwclock", "rtcwake", "watchdog", "inotifyd", "sntp",
            "ftpget", "ftpput", "httpd", "tunctl", "rfkill", "microcom",
            "gpiod", "i2cdetect", "i2cdump", "i2cget", "i2cset",
            "i2ctransfer", "gzip", "gunzip", "zcat", "bzcat", "bunzip2",
            "install", "dos2unix", "unix2dos", "count", "ascii",
            "fallocate", "truncate", "pgrep", "pkill", "killall",
            "killall5", "nbd_client", "nbd_server", "partprobe", "sha3sum",
            "sha224sum", "sha384sum", "sha512_224sum", "sha512_256sum",
            "host", "dnsdomainname", "chrt", "taskset", "uclampset",
            "iorenice", "iotop", "top", "mix", "makedevs", "login",
            "link", "unlink", "rename", "sync"
        )

        var created = 0
        for (applet in applets.distinct()) {
            val link = File(symlinkDir, applet)
            try {
                android.system.Os.symlink(toyboxSo.absolutePath, link.absolutePath)
                created++
            } catch (e: Exception) {
                // Symlink might already exist or applet not in build
            }
        }
        Log.d(TAG, "ToyboxProvider: created $created applet symlinks in ${symlinkDir.absolutePath}")
    }

    /**
     * Create symlinks for standalone tool binaries (ssh, scp, rsync, git,
     * curl, sqlite3, jq, make, openssl, tmux, zip, unzip) in the symlink
     * directory so they can be found by name in the shell.
     *
     * These binaries are packaged as libguru_*.so files in nativeLibraryDir.
     * They work when called by full path, but the shell cannot find them
     * by name without symlinks in a directory on PATH.
     */
    private fun createStandaloneSymlinks() {
        symlinkDir.mkdirs()

        var created = 0
        for ((toolName, soName) in TOOL_TO_SO) {
            // Skip toybox itself, it gets its own symlink in createToyboxSymlinks
            if (toolName == "toybox") continue

            val binaryFile = File(nativeLibDir, soName)
            if (!binaryFile.exists()) {
                Log.w(TAG, "ToyboxProvider: standalone binary $soName not found, skipping symlink")
                continue
            }

            val link = File(symlinkDir, toolName)
            try {
                // Remove existing symlink/file first
                if (link.exists()) link.delete()
                android.system.Os.symlink(binaryFile.absolutePath, link.absolutePath)
                created++
                Log.d(TAG, "ToyboxProvider: created standalone symlink $toolName -> " + binaryFile.absolutePath)
            } catch (e: Exception) {
                Log.w(TAG, "ToyboxProvider: failed to create $toolName symlink: " + e.message)
            }
        }
        Log.d(TAG, "ToyboxProvider: created $created standalone tool symlinks in " + symlinkDir.absolutePath)
    }    /**
     * Check if a specific tool is available.
     */
    fun isAvailable(tool: String): Boolean = available.contains(tool)

    /**
     * Check if toybox itself is available (provides 321 applets).
     */
    fun isToyboxAvailable(): Boolean = available.contains("toybox")

    /**
     * Get the full path to a specific tool binary.
     * For standalone tools, returns the .so path in nativeLibraryDir.
     * For toybox applets, returns the symlink path in the symlink directory.
     */
    fun getPath(tool: String): String {
        // Standalone tools are in nativeLibraryDir
        val soName = TOOL_TO_SO[tool]
        if (soName != null) {
            return File(nativeLibDir, soName).absolutePath
        }
        // Toybox applets are symlinks in the symlink directory
        return File(symlinkDir, tool).absolutePath
    }

    /**
     * Get the path to the toybox binary itself (via symlink, not the .so).
     */
    fun getToyboxPath(): String = File(symlinkDir, "toybox").absolutePath

    /**
     * Get the symlink directory where applet symlinks live.
     */
    fun getSymlinkDir(): String = symlinkDir.absolutePath

    /**
     * Get the cron spool directory path. Used for crontab/crond since
     * Android has no /var/spool/cron.
     */
    fun getCronSpoolDir(): String = cronSpoolDir.absolutePath

    /**
     * Build a command array for executing a tool.
     */
    fun buildExecCommand(tool: String, args: String = ""): List<String> {
        val path = getPath(tool)
        return if (args.isBlank()) {
            listOf(path)
        } else {
            listOf(path) + args.split(Regex("\\s+"))
        }
    }

    /**
     * Wrap a toybox applet command.
     * Example: wrapToybox("wget http://example.com")
     * Returns: "/path/to/libguru_toybox.so wget http://example.com"
     */
    fun wrapToybox(command: String): String {
        return "${getToyboxPath()} $command"
    }

    /**
     * Get a list of all available toybox applets by running "toybox --list".
     */
    suspend fun getToyboxApplets(): List<String> = withContext(Dispatchers.IO) {
        if (!isToyboxAvailable()) return@withContext emptyList()

        try {
            val process = Runtime.getRuntime().exec(arrayOf(getToyboxPath(), "--list"))
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                output.lines().filter { it.isNotBlank() }
            } else {
                Log.w(TAG, "ToyboxProvider: toybox --list failed with exit code $exitCode")
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ToyboxProvider: failed to list applets", e)
            emptyList()
        }
    }

    /**
     * Get the list of all available tools.
     */
    fun getAvailableTools(): Map<String, String> {
        val result = mutableMapOf<String, String>()
        for (tool in available) {
            result[tool] = "standalone"
        }
        return result
    }

    /**
     * Test if a tool works by running it with --version or --help.
     */
    suspend fun testTool(tool: String): Boolean = withContext(Dispatchers.IO) {
        if (!available.contains(tool)) return@withContext false

        try {
            val path = getPath(tool)
            val flag = when (tool) {
                "toybox" -> "--version"
                "sqlite3" -> ".version"
                "curl" -> "--version"
                "jq" -> "--version"
                "make" -> "--version"
                "git" -> "--version"
                "ssh" -> "-V"
                "rsync" -> "--version"
                "tmux" -> "-V"
                "openssl" -> "version"
                "zip" -> "-v"
                "unzip" -> "-v"
                "scp" -> "-V"
                else -> "--help"
            }

            val process = Runtime.getRuntime().exec(arrayOf(path, flag))
            val exitCode = process.waitFor()
            process.inputStream.bufferedReader().readText().isNotEmpty() ||
                process.errorStream.bufferedReader().readText().isNotEmpty()
        } catch (e: Exception) {
            false
        }
    }
}