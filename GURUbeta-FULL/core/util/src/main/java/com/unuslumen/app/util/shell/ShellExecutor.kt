package com.unuslumen.app.util.shell

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.util.Log
import android.view.accessibility.AccessibilityNodeInfo
import androidx.annotation.RequiresApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader

/**
 * Unified shell command executor that provides proper shell access on Android.
 *
 * Execution strategy:
 * 1. ROOT FIRST — native rootshell daemon (UID 0) via Unix socket
 * 2. ADB (Wireless Debugging) — shell UID (2000)
 * 3. Runtime.exec() fallback — app UID (limited)
 *
 * This gives Guru full OS-level device control.
 */
class ShellExecutor(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val WIRELESS_DEBUGGING_PORT = 5555
        private const val ADB_ENABLE_ATTEMPTS = 3
        private const val ADB_ENABLE_DELAY_MS = 2000L
    }

    private val rootShell = RootShell(context)
    private val adbClient = AdbClient(port = WIRELESS_DEBUGGING_PORT, context = context)
    val busybox = BusyboxProvider(context)
    val python = PythonProvider(context)
    val reTools = ReToolingProvider(context)
    val toybox = ToyboxProvider(context)
    val tesseract = TesseractProvider(context)
    private var adbChecked = false
    private var adbAvailable = false
    private var isPaired = false
    private var rootChecked = false
    private var rootAvailable = false

    /**
     * Execute a shell command with the best available method.
     * Root > ADB > Runtime fallback.
     */
    suspend fun execute(command: String, forceRoot: Boolean = false): ShellResult = withContext(Dispatchers.IO) {
        // Check root availability once
        if (!rootChecked) {
            rootAvailable = rootShell.isRootAvailable()
            rootChecked = true
            Log.d(TAG, "ShellExecutor: root available=$rootAvailable")
        }

        // ROOT FIRST — always try root if available
        if (rootAvailable) {
            val result = rootShell.execute(command)
            if (result.success) return@withContext result
            Log.w(TAG, "ShellExecutor: root command failed, trying ADB")
        }

        // ADB second
        if (!adbChecked) {
            adbAvailable = adbClient.isAdbAvailable()
            adbChecked = true
            Log.d(TAG, "ShellExecutor: ADB available=$adbAvailable")
        }

        if (adbAvailable) {
            val result = adbClient.executeCommand(command)
            if (result.success) return@withContext result
            Log.w(TAG, "ShellExecutor: ADB command failed")
        } else {
            Log.d(TAG, "ShellExecutor: ADB not available")
        }

        // Fall back to Runtime.exec
        Log.d(TAG, "ShellExecutor: falling back to Runtime.exec for: $command")
        return@withContext executeViaRuntime(command)
    }

    /**
     * Pair with the ADB daemon using a pairing code.
     * This is needed on Android 11+ when Wireless Debugging is enabled
     * but the device hasn't been paired yet.
     *
     * The user gets the pairing code from:
     * Settings > Developer Options > Wireless Debugging > Pair device with pairing code
     *
     * @param pairingCode The 6-digit pairing code shown on the device
     * @param pairingPort The port number shown alongside the pairing code
     * @return PairingResult indicating success or failure
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun pairWithAdb(pairingCode: String, pairingPort: Int): AdbPairingClient.PairingResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "ShellExecutor: pairing with ADB on port $pairingPort")
            val keyPair = AdbKeyStore.getOrCreateKeyPair(context)
            val pairingClient = AdbPairingClient(
                host = "127.0.0.1",
                port = pairingPort,
                pairingCode = pairingCode,
                rsaKeyPair = keyPair
            )
            val result = pairingClient.start()
            if (result.success) {
                isPaired = true
                adbAvailable = true
                adbChecked = true
                Log.d(TAG, "ShellExecutor: ADB pairing successful!")
            } else {
                Log.w(TAG, "ShellExecutor: ADB pairing failed: ${result.error}")
            }
            result
        } catch (e: Exception) {
            Log.e(TAG, "ShellExecutor: ADB pairing error", e)
            AdbPairingClient.PairingResult(success = false, error = "Pairing error: ${e.message}")
        }
    }

    /**
     * Auto-discover the pairing port via mDNS and pair.
     * This is the fully automated flow — the user just enters the pairing code.
     *
     * @param pairingCode The 6-digit pairing code shown on the device
     * @return PairingResult indicating success or failure
     */
    @RequiresApi(Build.VERSION_CODES.R)
    suspend fun autoPairWithAdb(pairingCode: String): AdbPairingClient.PairingResult = withContext(Dispatchers.IO) {
        try {
            Log.d(TAG, "ShellExecutor: auto-discovering pairing port via mDNS")
            val mdns = AdbMdnsDiscovery(context)
            val pairingPort = mdns.discoverPairingPort(timeoutMs = 0)
            mdns.stop()

            if (pairingPort == null) {
                Log.w(TAG, "ShellExecutor: could not discover pairing port via mDNS")
                return@withContext AdbPairingClient.PairingResult(
                    success = false,
                    error = "Could not find ADB pairing service. Make sure Wireless Debugging is enabled and you're on the pairing screen."
                )
            }

            Log.d(TAG, "ShellExecutor: discovered pairing port: $pairingPort")
            pairWithAdb(pairingCode, pairingPort)
        } catch (e: Exception) {
            Log.e(TAG, "ShellExecutor: auto-pair error", e)
            AdbPairingClient.PairingResult(success = false, error = "Auto-pair error: ${e.message}")
        }
    }

    /**
     * Check if ADB (Wireless Debugging) is currently available.
     */
    suspend fun isAdbAvailable(): Boolean {
        adbAvailable = adbClient.isAdbAvailable()
        adbChecked = true
        return adbAvailable
    }

    /**
     * Check if the device has been paired with ADB.
     */
    fun isAdbPaired(): Boolean = isPaired

    /**
     * Check if the Accessibility Service is running.
     */
    fun isAccessibilityRunning(): Boolean {
        return GuruAccessibilityService.isRunning.value
    }

    /**
     * Auto-pair ADB using the Accessibility Service.
     * Guru navigates to Settings > Developer Options > Wireless Debugging,
     * opens the pairing dialog, reads the code, and pairs automatically.
     * Then brings the user back to the app.
     */
    suspend fun autoPairAdb(): AutoPairResult {
        val service = GuruAccessibilityService.instance
            ?: return AutoPairResult(success = false, error = "Accessibility Service not running. Enable it in Settings > Accessibility > guru.")

        return withContext(Dispatchers.Main) {
            try {
                // Step 1: Open Developer Options
                Log.d(TAG, "AutoPairAdb: Opening Developer Options")
                val settingsIntent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(settingsIntent)
                kotlinx.coroutines.delay(1500)

                // Step 2: Click "Wireless debugging"
                Log.d(TAG, "AutoPairAdb: Looking for Wireless Debugging")
                var clicked = service.clickText("Wireless debugging", exact = false)
                if (!clicked) {
                    service.scrollDown()
                    kotlinx.coroutines.delay(500)
                    clicked = service.clickText("Wireless debugging", exact = false)
                }
                if (!clicked) {
                    return@withContext AutoPairResult(
                        success = false,
                        error = "Could not find 'Wireless debugging' in Developer Options."
                    )
                }
                kotlinx.coroutines.delay(1500)

                // Step 3: Click "Pair device with pairing code"
                Log.d(TAG, "AutoPairAdb: Looking for Pair device option")
                clicked = service.clickText("Pair device", exact = false)
                if (!clicked) {
                    return@withContext AutoPairResult(
                        success = false,
                        error = "Could not find 'Pair device with pairing code'. Wireless Debugging may not be enabled."
                    )
                }
                kotlinx.coroutines.delay(1000)

                // Step 4: Read pairing code and port from the dialog
                Log.d(TAG, "AutoPairAdb: Reading pairing code from dialog")
                val rootNode = service.rootInActiveWindow
                if (rootNode == null) {
                    return@withContext AutoPairResult(
                        success = false,
                        error = "Could not read screen. Accessibility Service may have lost focus."
                    )
                }

                var pairingCode: String? = null
                var pairingPort: Int? = null
                val allText = mutableListOf<String>()
                collectAllText(rootNode, allText)

                for (text in allText) {
                    val codeMatch = Regex("\\b(\\d{6})\\b").find(text)
                    if (codeMatch != null && pairingCode == null) {
                        pairingCode = codeMatch.groupValues[1]
                    }
                    val portMatch = Regex(":(\\d{4,5})\\b").find(text)
                    if (portMatch != null && pairingPort == null) {
                        pairingPort = portMatch.groupValues[1].toIntOrNull()
                    }
                    val standalonePortMatch = Regex("\\b(\\d{4,5})\\b").find(text)
                    if (standalonePortMatch != null && pairingPort == null && text.length <= 6) {
                        val port = standalonePortMatch.groupValues[1].toIntOrNull()
                        if (port != null && port > 1024 && port < 65536) {
                            pairingPort = port
                        }
                    }
                }

                if (pairingCode == null) {
                    return@withContext AutoPairResult(
                        success = false,
                        error = "Could not read pairing code from dialog. Text found: ${allText.joinToString(", ").take(200)}"
                    )
                }

                Log.d(TAG, "AutoPairAdb: Found code=$pairingCode, port=$pairingPort")

                // Step 5: Pair using the discovered code and port
                val result = if (pairingPort != null) {
                    pairWithAdb(pairingCode, pairingPort)
                } else {
                    autoPairWithAdb(pairingCode)
                }

                if (result.success) {
                    Log.d(TAG, "AutoPairAdb: Pairing successful!")
                    // Press back to close the pairing dialog
                    service.pressBack()
                    kotlinx.coroutines.delay(300)
                    service.pressBack()
                    kotlinx.coroutines.delay(300)

                    // Step 6: Bring the user back to the app
                    Log.d(TAG, "AutoPairAdb: Reopening guru app")
                    val appIntent = context.packageManager.getLaunchIntentForPackage(context.packageName)
                    if (appIntent != null) {
                        appIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                        context.startActivity(appIntent)
                    }
                }

                AutoPairResult(
                    success = result.success,
                    error = result.error,
                    pairingCode = pairingCode,
                    pairingPort = pairingPort
                )
            } catch (e: Exception) {
                Log.e(TAG, "AutoPairAdb: failed", e)
                AutoPairResult(success = false, error = "Auto-pair failed: ${e.message}")
            }
        }
    }

    private fun collectAllText(node: android.view.accessibility.AccessibilityNodeInfo, results: MutableList<String>) {
        node.text?.toString()?.let { results.add(it) }
        node.contentDescription?.toString()?.let { results.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectAllText(it, results) }
        }
    }

    /**
     * Result of auto-pair ADB operation.
     */
    data class AutoPairResult(
        val success: Boolean,
        val error: String? = null,
        val pairingCode: String? = null,
        val pairingPort: Int? = null
    )

    /**
     * Try to enable Wireless Debugging automatically.
     * Uses Accessibility Service to navigate settings and toggle it on.
     */
    private suspend fun tryEnableAdb(): Boolean {
        val service = GuruAccessibilityService.instance
        if (service == null) {
            Log.w(TAG, "ShellExecutor: Accessibility Service not running, can't auto-enable ADB")
            return false
        }

        repeat(ADB_ENABLE_ATTEMPTS) { attempt ->
            Log.d(TAG, "ShellExecutor: auto-enabling Wireless Debugging (attempt ${attempt + 1})")

            try {
                // Open Developer Options
                withContext(Dispatchers.Main) {
                    val intent = Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS)
                    intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    context.startActivity(intent)
                }
                delay(ADB_ENABLE_DELAY_MS)

                // Try to toggle Wireless Debugging via Accessibility
                withContext(Dispatchers.Main) {
                    // Click "Wireless debugging" text
                    service.clickText("Wireless debugging")
                }
                delay(1500)

                // If a confirmation dialog appears, click "Allow"
                withContext(Dispatchers.Main) {
                    service.clickText("Allow")
                }
                delay(1000)

                // Check if ADB is now available
                if (adbClient.isAdbAvailable()) {
                    adbAvailable = true
                    adbChecked = true
                    Log.d(TAG, "ShellExecutor: Wireless Debugging enabled successfully!")
                    // Go back to where we were
                    withContext(Dispatchers.Main) {
                        service.pressBack()
                        service.pressBack()
                    }
                    return true
                }
            } catch (e: Exception) {
                Log.w(TAG, "ShellExecutor: auto-enable attempt failed: ${e.message}")
            }
        }

        Log.w(TAG, "ShellExecutor: failed to auto-enable Wireless Debugging")
        return false
    }

    /**
     * Execute a command via Runtime.exec() -- limited to app's own UID.
     * Drains stdout and stderr concurrently to avoid waitFor() deadlock.
     */
    private suspend fun executeViaRuntime(command: String): ShellResult = coroutineScope {
        try {
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", command))

            // Drain stdout and stderr concurrently before calling waitFor().
            // If the subprocess writes enough output to fill the pipe buffer before
            // waitFor() is called, the process blocks. Reading both streams in parallel
            // prevents this classic deadlock.
            val stdoutDeferred = async {
                BufferedReader(InputStreamReader(process.inputStream)).use { it.readText() }
            }
            val stderrDeferred = async {
                BufferedReader(InputStreamReader(process.errorStream)).use { it.readText() }
            }

            val exitCode = process.waitFor()
            val stdout = stdoutDeferred.await().trim()
            val stderr = stderrDeferred.await().trim()

            ShellResult(
                exitCode = exitCode,
                stdout = stdout,
                stderr = stderr,
                success = exitCode == 0
            )
        } catch (e: Exception) {
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to execute: ${e.message}",
                success = false
            )
        }
    }
}