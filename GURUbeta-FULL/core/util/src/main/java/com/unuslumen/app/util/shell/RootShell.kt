package com.unuslumen.app.util.shell

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Root shell executor — true UID 0 access via native daemon.
 *
 * Architecture:
 * 1. Native C daemon (rootshell) is compiled via NDK and bundled in the APK
 * 2. On first use, the daemon binary is extracted to /data/local/tmp/
 * 3. Daemon is launched via `su -c` (requires Magisk/SuperSU root)
 * 4. Daemon listens on Unix socket at /data/local/tmp/guru_rootshell
 * 5. Commands are sent over the socket, executed as root, results returned
 *
 * If the device isn't rooted, falls back gracefully.
 */
class RootShell(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val SOCKET_PATH = "/data/local/tmp/guru_rootshell"
        private const val DAEMON_PATH = "/data/local/tmp/guru_rootshell_daemon"
        private const val EXPLOIT_PATH = "/data/local/tmp/guru_exploit_runner"

        private val SU_PATHS = arrayOf(
            "/system/bin/su",
            "/system/xbin/su",
            "/sbin/su",
            "/system/su",
            "/su/bin/su",
            "/data/local/tmp/su",
            "/data/local/su",
            "/system_ext/bin/su",
            "/product/bin/su",
            "/vendor/bin/su",
            "/odm/bin/su"
        )

        private var suPath: String? = null
        private var daemonRunning = false
        private var checked = false
        private var exploitAttempted = false
    }

    /**
     * Check if root (su) is available on this device.
     */
    fun isRootAvailable(): Boolean {
        if (checked) return suPath != null
        checked = true

        for (path in SU_PATHS) {
            val file = File(path)
            if (file.exists() && file.canExecute()) {
                suPath = path
                Log.d(TAG, "RootShell: su found at $path")
                return true
            }
        }

        try {
            val process = Runtime.getRuntime().exec(arrayOf("su", "-c", "echo root_ok"))
            val output = process.inputStream.bufferedReader().readText()
            process.waitFor()
            if (output.contains("root_ok")) {
                suPath = "su"
                Log.d(TAG, "RootShell: su works from PATH")
                return true
            }
        } catch (_: Exception) {}

        Log.d(TAG, "RootShell: su not found — device is not rooted")
        return false
    }

    /**
     * Attempt to gain root via kernel exploits.
     * Extracts and runs the exploit runner binary.
     * If successful, su becomes available and the daemon can start.
     *
     * @return true if root was gained
     */
    suspend fun attemptExploit(): Boolean = withContext(Dispatchers.IO) {
        if (exploitAttempted) return@withContext isRootAvailable()
        exploitAttempted = true

        Log.d(TAG, "RootShell: attempting kernel exploits...")

        try {
            // Extract exploit runner binary
            val exploitFile = File(EXPLOIT_PATH)
            if (!exploitFile.exists() || exploitFile.length() < 1000) {
                val appInfo = context.applicationInfo
                val libDir = appInfo.nativeLibraryDir
                    ?: "/data/app/${context.packageName}/lib/arm64"
                val libDirFile = File(libDir)
                val sourceNames = arrayOf("libexploit_runner.so", "exploit_runner")

                var extracted = false
                for (name in sourceNames) {
                    val source = File(libDirFile, name)
                    if (source.exists() && source.length() > 1000) {
                        source.copyTo(exploitFile, overwrite = true)
                        exploitFile.setExecutable(true, false)
                        extracted = true
                        Log.d(TAG, "RootShell: extracted exploit runner (${exploitFile.length()} bytes)")
                        break
                    }
                }

                if (!extracted) {
                    Log.e(TAG, "RootShell: exploit runner not found in native libs")
                    return@withContext false
                }
            }

            // Run the exploit
            Log.d(TAG, "RootShell: running exploit runner...")
            val process = Runtime.getRuntime().exec(
                arrayOf(EXPLOIT_PATH),
                arrayOf("PATH=/system/bin:/system/xbin"),
                null
            )

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            Log.d(TAG, "RootShell: exploit exit=$exitCode")
            Log.d(TAG, "RootShell: exploit stdout: ${stdout.take(500)}")
            if (stderr.isNotBlank()) Log.d(TAG, "RootShell: exploit stderr: ${stderr.take(500)}")

            // Check if we got root
            if (stdout.contains("EXPLOIT SUCCEEDED") || stdout.contains("SUCCESSFUL")) {
                Log.d(TAG, "RootShell: EXPLOIT SUCCEEDED! Checking for su...")

                // Re-check for su
                checked = false
                if (isRootAvailable()) {
                    Log.d(TAG, "RootShell: su now available after exploit!")
                    return@withContext true
                }

                // Try running the daemon directly (it might work now)
                try {
                    val daemonFile = File(DAEMON_PATH)
                    if (daemonFile.exists()) {
                        val testProcess = Runtime.getRuntime().exec(
                            arrayOf(DAEMON_PATH),
                            arrayOf(),
                            null
                        )
                        Thread.sleep(500)
                        if (File(SOCKET_PATH).exists()) {
                            daemonRunning = true
                            Log.d(TAG, "RootShell: daemon started directly after exploit!")
                            return@withContext true
                        }
                    }
                } catch (_: Exception) {}
            }

            Log.d(TAG, "RootShell: exploit did not gain root on this device")
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "RootShell: exploit attempt failed", e)
            return@withContext false
        }
    }

    /**
     * Start the native root daemon. Extracts the binary and launches via su.
     * If su isn't available, tries kernel exploits first.
     */
    suspend fun startDaemon(): Boolean = withContext(Dispatchers.IO) {
        if (daemonRunning) return@withContext true
        if (!isRootAvailable()) return@withContext false

        try {
            // Extract native binary from APK lib directory
            val daemonFile = File(DAEMON_PATH)
            if (!daemonFile.exists() || daemonFile.length() < 1000) {
                // Find the native lib directory
                val appInfo = context.applicationInfo
                val libDir = appInfo.nativeLibraryDir
                    ?: "/data/app/${context.packageName}/lib/arm64"
                val libDirFile = File(libDir)
                val sourceNames = arrayOf("librootshell.so", "rootshell")
                var extracted = false

                for (name in sourceNames) {
                    val source = File(libDirFile, name)
                    if (source.exists() && source.length() > 1000) {
                        source.copyTo(daemonFile, overwrite = true)
                        daemonFile.setExecutable(true, false)
                        extracted = true
                        Log.d(TAG, "RootShell: extracted daemon from $name (${daemonFile.length()} bytes)")
                        break
                    }
                }

                if (!extracted) {
                    val contents = libDirFile.listFiles()?.joinToString { f -> f.name } ?: "empty"
                    Log.e(TAG, "RootShell: daemon binary not found in $libDir. Contents: $contents")
                    return@withContext false
                }
            }

            // Kill any existing daemon
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "pkill -f guru_rootshell_daemon")).waitFor()
                Thread.sleep(300)
            } catch (_: Exception) {}

            // Clean old socket
            try {
                Runtime.getRuntime().exec(arrayOf("su", "-c", "rm -f $SOCKET_PATH")).waitFor()
            } catch (_: Exception) {}

            // Start daemon via su
            val su = suPath ?: return@withContext false
            val process = Runtime.getRuntime().exec(
                arrayOf(su, "-c", "nohup $DAEMON_PATH > /dev/null 2>&1 &")
            )
            process.waitFor()
            Thread.sleep(1500)

            // Verify daemon is running by checking socket
            val socketFile = File(SOCKET_PATH)
            if (socketFile.exists()) {
                daemonRunning = true
                Log.d(TAG, "RootShell: daemon started, socket at $SOCKET_PATH")
                return@withContext true
            }

            // Try direct su -c as fallback
            Log.w(TAG, "RootShell: daemon socket not found, daemon may have failed to start")
            return@withContext false
        } catch (e: Exception) {
            Log.e(TAG, "RootShell: failed to start daemon", e)
            return@withContext false
        }
    }

    /**
     * Execute a command as root (UID 0).
     * Uses the native daemon if running, falls back to su -c.
     */
    suspend fun execute(command: String): ShellResult = withContext(Dispatchers.IO) {
        // Try daemon first
        if (daemonRunning) {
            val result = executeViaDaemon(command)
            if (result.success) return@withContext result
            // Daemon might have died, mark as not running
            daemonRunning = false
        }

        // Try starting daemon
        if (startDaemon()) {
            val result = executeViaDaemon(command)
            if (result.success) return@withContext result
        }

        // Fall back to su -c directly
        if (isRootAvailable()) {
            return@withContext executeViaSu(command)
        }

        ShellResult(-1, "", "Root not available — device is not rooted", false)
    }

    /**
     * Execute via the native daemon's Unix socket.
     */
    private fun executeViaDaemon(command: String): ShellResult {
        return try {
            val socket = LocalSocket()
            socket.connect(LocalSocketAddress(SOCKET_PATH, LocalSocketAddress.Namespace.FILESYSTEM))
            socket.soTimeout = 0 // No timeout — AGI doesn't have timeouts

            val inputStream = socket.inputStream
            val outputStream = socket.outputStream

            // Send command: [4 bytes LE: length][command bytes]
            val cmdBytes = command.toByteArray(Charsets.UTF_8)
            writeInt32LE(outputStream, cmdBytes.size)
            outputStream.write(cmdBytes)
            outputStream.flush()

            // Read response: [4 bytes LE: exit code][4 bytes LE: stdout len][stdout][4 bytes LE: stderr len][stderr]
            val exitCode = readInt32LE(inputStream)
            val stdout = readString(inputStream)
            val stderr = readString(inputStream)

            socket.close()

            Log.d(TAG, "RootShell: daemon exit=$exitCode, stdout=${stdout.take(100)}")

            ShellResult(exitCode, stdout, stderr, exitCode == 0)
        } catch (e: Exception) {
            Log.w(TAG, "RootShell: daemon error: ${e.message}")
            ShellResult(-1, "", "Daemon error: ${e.message}", false)
        }
    }

    /**
     * Execute via su -c directly (fallback).
     */
    private fun executeViaSu(command: String): ShellResult {
        return try {
            val su = suPath ?: return ShellResult(-1, "", "su not found", false)
            val escaped = command.replace("'", "'\\''")
            val process = Runtime.getRuntime().exec(
                arrayOf(su, "-c", escaped),
                arrayOf("PATH=/system/bin:/system/xbin:/sbin:/su/bin:/data/local/tmp:/vendor/bin:/product/bin"),
                null
            )

            val stdout = process.inputStream.bufferedReader().readText()
            val stderr = process.errorStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            ShellResult(exitCode, stdout.trim(), stderr.trim(), exitCode == 0)
        } catch (e: Exception) {
            ShellResult(-1, "", "su error: ${e.message}", false)
        }
    }

    fun getSuPath(): String? = suPath
    fun isDaemonRunning(): Boolean = daemonRunning

    // ===== Binary Protocol Helpers =====

    private fun writeInt32LE(output: OutputStream, value: Int) {
        output.write(byteArrayOf(
            (value and 0xFF).toByte(),
            ((value shr 8) and 0xFF).toByte(),
            ((value shr 16) and 0xFF).toByte(),
            ((value shr 24) and 0xFF).toByte()
        ))
    }

    private fun readInt32LE(input: InputStream): Int {
        val buf = ByteArray(4)
        var total = 0
        while (total < 4) {
            val n = input.read(buf, total, 4 - total)
            if (n < 0) return -1
            total += n
        }
        return (buf[0].toInt() and 0xFF) or
                ((buf[1].toInt() and 0xFF) shl 8) or
                ((buf[2].toInt() and 0xFF) shl 16) or
                ((buf[3].toInt() and 0xFF) shl 24)
    }

    private fun readString(input: InputStream): String {
        val len = readInt32LE(input)
        if (len <= 0) return ""
        if (len > 1024 * 1024) return "[response too large: $len bytes]"
        val buf = ByteArray(len)
        var total = 0
        while (total < len) {
            val n = input.read(buf, total, len - total)
            if (n < 0) break
            total += n
        }
        return String(buf, 0, total, Charsets.UTF_8)
    }
}
