package com.unuslumen.app.util.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Provides access to toybox, the Android system's built-in multi-binary.
 *
 * Toybox is maintained by Google and ships with Android at /system/bin/toybox.
 * It provides grep, sed, find, tar, awk, sort, wc, xargs, cat, tr, base64,
 * and many other standard Unix utilities. Being a 64-bit system binary
 * compiled for the device's native architecture, it has none of the
 * issues that plagued the bundled busybox approach (wrong architecture,
 * missing shared libraries, hardcoded prefixes).
 *
 * No extraction, no assets, no linker workarounds. Just call it.
 */
class BusyboxProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val TOYBOX_PATH = "/system/bin/toybox"
    }

    private var initialized = false
    private var available = false

    /**
     * Check if toybox is available on the system.
     * Safe to call multiple times; only does work on first call.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext available

        try {
            val toybox = File(TOYBOX_PATH)
            if (toybox.exists() && toybox.canExecute()) {
                Log.d(TAG, "BusyboxProvider: toybox found at $TOYBOX_PATH")
                available = true
            } else {
                // Fallback: check if it's accessible via PATH
                val process = Runtime.getRuntime().exec(arrayOf("which", "toybox"))
                val output = process.inputStream.bufferedReader().readText().trim()
                val exitCode = process.waitFor()
                if (exitCode == 0 && output.isNotEmpty()) {
                    Log.d(TAG, "BusyboxProvider: toybox found via which at $output")
                    available = true
                } else {
                    Log.w(TAG, "BusyboxProvider: toybox not found on this device")
                    available = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "BusyboxProvider: initialization failed", e)
            available = false
        }

        initialized = true
        available
    }

    /**
     * Check if toybox is available and ready to use.
     */
    fun isAvailable(): Boolean = available

    /**
     * Test if toybox is working by running a simple command.
     */
    suspend fun testBusybox(): Boolean = withContext(Dispatchers.IO) {
        if (!available) return@withContext false

        try {
            val process = Runtime.getRuntime().exec(arrayOf(TOYBOX_PATH, "echo", "test"))
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()
            val success = exitCode == 0 && output.trim() == "test"
            if (success) {
                Log.d(TAG, "BusyboxProvider: toybox test successful")
            } else {
                Log.w(TAG, "BusyboxProvider: toybox test failed with exit code $exitCode")
            }
            success
        } catch (e: Exception) {
            Log.w(TAG, "BusyboxProvider: toybox test failed", e)
            false
        }
    }

    /**
     * Get the full path to the toybox binary.
     */
    fun getPath(): String = TOYBOX_PATH

    /**
     * Get the linker path. Not needed for toybox since it's a system
     * binary that executes directly, but kept for API compatibility.
     */
    fun getLinkerPath(): String = ""

    /**
     * Build the command array for executing a toybox command.
     * Returns a list suitable for ProcessBuilder.
     * Example: ["toybox", "wget", "http://example.com"]
     */
    fun buildExecCommand(command: String): List<String> {
        val parts = command.split(Regex("\\s+"))
        return listOf(TOYBOX_PATH) + parts
    }

    /**
     * Get the library path. Not needed for toybox, kept for API compatibility.
     */
    fun getLibPath(): String = ""

    /**
     * Get the environment variables needed for execution.
     * Toybox is a system binary, no special env needed.
     */
    fun getExecEnv(): Map<String, String> = emptyMap()

    /**
     * Wrap a command to use toybox.
     * Example: "wget http://example.com" becomes "toybox wget http://example.com"
     *
     * Note: toybox applets are also available as individual symlinks in
     * /system/bin/ (e.g. /system/bin/grep, /system/bin/sed), so most
     * commands already work without the toybox prefix. This wrapper
     * ensures we use the explicit toybox path for consistency.
     */
    fun wrapCommand(command: String): String {
        if (!available) {
            Log.w(TAG, "BusyboxProvider: wrapCommand called but toybox is not available")
        }
        return "$TOYBOX_PATH $command"
    }

    /**
     * Get a list of available toybox applets by running "toybox --list".
     */
    suspend fun getAvailableApplets(): List<String> = withContext(Dispatchers.IO) {
        if (!available) return@withContext emptyList()

        try {
            val process = Runtime.getRuntime().exec(arrayOf(TOYBOX_PATH, "--list"))
            val output = process.inputStream.bufferedReader().readText()
            val exitCode = process.waitFor()

            if (exitCode == 0) {
                val applets = output.lines().filter { it.isNotBlank() }
                Log.d(TAG, "BusyboxProvider: found ${applets.size} toybox applets")
                applets
            } else {
                Log.w(TAG, "BusyboxProvider: toybox --list failed with exit code $exitCode")
                emptyList()
            }
        } catch (e: Exception) {
            Log.w(TAG, "BusyboxProvider: failed to list applets", e)
            emptyList()
        }
    }
}