// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
 * 2. Shizuku — shell UID (2000) over its binder, no pairing code needed
 * 3. ADB (Wireless Debugging) — shell UID (2000), needs pairing
 * 4. Runtime.exec() fallback — app UID (limited)
 *
 * Shizuku sits above ADB because the two are equally powerful and Shizuku is the
 * one that does not break when wireless debugging is switched off. Neither is
 * required: a device with neither still works at app UID and says so in the tier.
 */
class ShellExecutor(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val ADB_ENABLE_ATTEMPTS = 3
        private const val ADB_ENABLE_DELAY_MS = 2000L
    }

    /**
     * THE CONNECT PORT IS NOT A CONSTANT (issue 389).
     *
     * This used to build the client with `port = WIRELESS_DEBUGGING_PORT`, a fixed 5555, and that
     * number is not a wireless-debugging port on any modern device. Android listens on a random port
     * that changes every time the toggle is cycled, and the pairing dialog shows it. So a successful
     * pairing left this client dialling a port nothing was listening on, and the failure was
     * reported as "Wireless Debugging may not be enabled", which was a sentence about the wrong thing.
     *
     * It stays null until the pairing dialog or the mDNS record supplies the real number, and
     * [AdbClient] is rebuilt with it. A null port means "not known yet", which is a different and
     * honest state from "5555".
     */
    @Volatile
    private var adbConnectPort: Int? = null

    @Volatile
    private var adbClient: AdbClient = AdbClient(context = context)

    /**
     * Hand the real connect port to the ADB client, once something has read it from the dialog.
     *
     * Called after pairing, because pairing is the only moment the dialog is on screen and therefore
     * the only moment the number is available. The old client is replaced rather than mutated because
     * its port is a constructor argument.
     */
    fun setAdbConnectPort(port: Int?) {
        val usable = AdbTarget.connectPort(port) ?: return
        if (usable == adbConnectPort) return
        adbConnectPort = usable
        adbClient = AdbClient(port = usable, context = context)
        // The old client's answer was about the old port, so it is no longer evidence about this one.
        adbChecked = false
        Log.d(TAG, "ShellExecutor: ADB connect port set to $usable")
    }

    /** The connect port in use, or null when none has been read yet. */
    fun adbConnectPort(): Int? = adbConnectPort

    private val rootShell = RootShell(context)

    /**
     * The Shizuku channel.
     *
     * Held as a property rather than built per call, because the bridge caches a
     * bound UserService and a fresh bridge would re-bind on every command. Public
     * so a caller can read its three-state detection without going through execute.
     */
    val shizuku = com.unuslumen.app.util.shell.shizuku.ShizukuBridge(context)
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
     *
     * EVERY result now carries the tier that served it. Issue 40: the tool
     * contract claimed "NO limits" while the executor silently ran at app UID,
     * and a model reading that description reached for /proc/net/arp or dumpsys,
     * got Permission denied, and retried against a permanent wall because
     * nothing told it the tier was the problem.
     *
     * The tier is stamped at the point of execution, so it cannot drift from
     * what actually ran.
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
            if (result.success) return@withContext result.copy(tier = ShellTier.ROOT)
            Log.w(TAG, "ShellExecutor: root command failed, trying ADB")
        }

        // SHIZUKU SECOND, and before ADB.
        //
        // Both are shell UID 2000, so neither is more powerful than the other. The
        // order is about stability: Shizuku does not depend on wireless debugging
        // staying enabled or on a pairing key staying valid, so when both are up it
        // is the one less likely to fail between two calls. When Shizuku is absent
        // this costs one cheap binder ping and changes nothing.
        //
        // A Shizuku failure falls through to ADB rather than returning, because a
        // command that fails at one tier may succeed at another: a Shizuku bind can
        // be stale while ADB is healthy, and vice versa.
        if (shizuku.state().usable) {
            val result = shizuku.execute(command)
            if (result.success) return@withContext result.copy(tier = ShellTier.SHIZUKU)
            Log.w(TAG, "ShellExecutor: Shizuku command failed, trying ADB")
        }

        // ADB third
        if (!adbChecked) {
            adbAvailable = adbClient.isAdbAvailable()
            adbChecked = true
            Log.d(TAG, "ShellExecutor: ADB available=$adbAvailable")
        }

        if (adbAvailable) {
            val result = adbClient.executeCommand(command)
            if (result.success) return@withContext result.copy(tier = ShellTier.ADB)
            Log.w(TAG, "ShellExecutor: ADB command failed")
        } else {
            Log.d(TAG, "ShellExecutor: ADB not available")
        }

        // Fall back to Runtime.exec
        Log.d(TAG, "ShellExecutor: falling back to Runtime.exec for: $command")
        return@withContext executeViaRuntime(command).copy(tier = ShellTier.APP_UID)
    }

    /**
     * The tier the next execute() would most likely use, for reporting before a
     * call rather than after one.
     *
     * Deliberately labelled "likely": availability can change between this check
     * and the call, and the authoritative answer is the one on the result.
     */
    suspend fun describeCapabilityTier(): ShellTier {
        if (!rootChecked) {
            rootAvailable = rootShell.isRootAvailable()
            rootChecked = true
        }
        if (rootAvailable) return ShellTier.ROOT
        if (shizuku.state().usable) return ShellTier.SHIZUKU
        if (!adbChecked) {
            adbAvailable = adbClient.isAdbAvailable()
            adbChecked = true
        }
        return if (adbAvailable) ShellTier.ADB else ShellTier.APP_UID
    }

    /**
     * The Shizuku channel's three-state reading, for a tool that wants to report
     * what would unlock shell-level access rather than only that it is missing.
     *
     * Kept as a method on the executor so callers find it beside the ladder it
     * belongs to instead of importing the bridge themselves and guessing at it.
     */
    fun shizukuState(): com.unuslumen.app.util.shell.shizuku.ShizukuState = shizuku.state()

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
    suspend fun pairWithAdb(
        pairingCode: String,
        pairingPort: Int,
        /**
         * The address the pairing dialog advertises, which is the device's own Wi-Fi address.
         *
         * NULL MEANS "TRY LOOPBACK ONLY". The old code hardcoded `127.0.0.1`, which assumed adbd's
         * pairing server binds there. It may not, and dialling an address nothing listens on produces
         * exactly the ticket's symptom: a connect that succeeds against a different listener and a TLS
         * write that then dies with Broken pipe.
         */
        deviceWifiAddress: String? = null,
    ): AdbPairingClient.PairingResult = withContext(Dispatchers.IO) {
        val port = AdbTarget.pairingPort(pairingPort)
            ?: return@withContext AdbPairingClient.PairingResult(
                success = false,
                error = "\"$pairingPort\" is not a usable TCP port, so no pairing was attempted. " +
                    "Read the port from the 'Pair device with pairing code' dialog again.",
            )

        var lastResult: AdbPairingClient.PairingResult? = null
        /*
         * EVERY CANDIDATE IS TRIED, because the host is a device fact rather than something to assume.
         * One failed TCP connect costs a moment; assuming the wrong host costs the whole pairing.
         */
        for (host in AdbTarget.pairingCandidates(deviceWifiAddress)) {
            try {
                Log.d(TAG, "ShellExecutor: pairing with ADB at $host:$port")
                val keyPair = AdbKeyStore.getOrCreateKeyPair(context)
                val pairingClient = AdbPairingClient(
                    host = host,
                    port = port,
                    pairingCode = pairingCode,
                    rsaKeyPair = keyPair,
                )
                val result = pairingClient.start()
                lastResult = result
                if (result.success) {
                    isPaired = true
                    adbAvailable = true
                    adbChecked = true
                    Log.d(TAG, "ShellExecutor: ADB pairing succeeded at $host:$port")
                    return@withContext result
                }
                Log.w(TAG, "ShellExecutor: ADB pairing failed at $host:$port: ${result.error}")
            } catch (e: Exception) {
                Log.e(TAG, "ShellExecutor: ADB pairing error at $host:$port", e)
                lastResult = AdbPairingClient.PairingResult(
                    success = false,
                    error = AdbTarget.failureSentence(host, port, "pairing", e.message),
                )
            }
        }

        lastResult ?: AdbPairingClient.PairingResult(
            success = false,
            error = "No address was available to pair against on port $port.",
        )
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
                var connectPort: Int? = null
                var wifiAddress: String? = null
                val allText = mutableListOf<String>()
                collectAllText(rootNode, allText)

                /*
                 * THE DIALOG CARRIES THREE NUMBERS AND THIS USED TO READ ONE OF THEM.
                 *
                 * It shows an IP address, a pairing port beside the six-digit code, and a separate
                 * connect port further up the same screen. The old parse took the first port-shaped
                 * token as the pairing port and never read the connect port at all, so pairing could
                 * succeed and the ADB client still dialled 5555 (issue 389).
                 *
                 * The address is read too, because the pairing server is dialled at the address the
                 * dialog advertises rather than assumed to be loopback.
                 */
                val addressPattern = Regex("""\b(\d{1,3}(?:\.\d{1,3}){3})\b""")
                for (text in allText) {
                    val codeMatch = Regex("\\b(\\d{6})\\b").find(text)
                    if (codeMatch != null && pairingCode == null) {
                        pairingCode = codeMatch.groupValues[1]
                    }
                    if (wifiAddress == null) {
                        addressPattern.find(text)?.groupValues?.get(1)?.let { wifiAddress = it }
                    }

                    /*
                     * A LINE NAMING THE CONNECT PORT IS IDENTIFIED BY ITS WORDING, because the two
                     * numbers are indistinguishable as numbers. The pairing screen labels one
                     * "Pair with pairing code" and the other with the port wireless debugging is
                     * listening on, so the label is the only signal there is.
                     */
                    val labelledConnect = Regex(
                        """(?:connect|debugging|ip)\D*?(\d{4,5})""",
                        RegexOption.IGNORE_CASE,
                    ).find(text)
                    if (labelledConnect != null && connectPort == null) {
                        labelledConnect.groupValues[1].toIntOrNull()
                            ?.takeIf { AdbTarget.isPlausiblePort(it) }
                            ?.let { connectPort = it }
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

                // A connect port that only the unlabelled fallback found is not trusted as one; it is
                // more likely the pairing port seen twice.
                if (connectPort != null && connectPort == pairingPort) connectPort = null

                if (pairingCode == null) {
                    return@withContext AutoPairResult(
                        success = false,
                        error = "Could not read pairing code from dialog. Text found: ${allText.joinToString(", ").take(200)}"
                    )
                }

                Log.d(TAG, "AutoPairAdb: Found code=$pairingCode, port=$pairingPort")

                // Step 5: Pair using the discovered code and port, at the address the dialog showed
                val result = if (pairingPort != null) {
                    pairWithAdb(pairingCode, pairingPort, deviceWifiAddress = wifiAddress)
                } else {
                    autoPairWithAdb(pairingCode)
                }

                /*
                 * THE CONNECT PORT FROM THE SAME DIALOG, HANDED TO THE CLIENT (issue 389).
                 *
                 * Pairing alone leaves the ADB tier dead unless the client knows the port wireless
                 * debugging is listening on, and that number exists only while this dialog is on
                 * screen. Reading it here is the only chance there is.
                 */
                if (connectPort != null) setAdbConnectPort(connectPort)

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
                    pairingPort = pairingPort,
                    connectPort = connectPort,
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
        val pairingPort: Int? = null,
        /**
         * The port wireless debugging listens on, read from the same dialog (issue 389).
         *
         * Null when the dialog did not show one, which is a state worth reporting rather than
         * substituting 5555 for: a wrong port turns "the dialog gave me nothing" into a connect
         * against silence, and then into a sentence blaming wireless debugging.
         */
        val connectPort: Int? = null,
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
     *
     * Issue 44: anything that refuses on stdout and exits 0 (`dumpsys`, `pm
     * dump`) is marked failed here, at the boundary every tool passes through,
     * rather than in each tool. The exit code and both streams survive so a
     * caller can still read what actually happened.
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

            ShellDenialSignatures.apply(
                ShellResult(
                    exitCode = exitCode,
                    stdout = stdout,
                    stderr = stderr,
                    success = exitCode == 0,
                    tier = ShellTier.APP_UID,
                ),
                ShellTier.APP_UID,
            )
        } catch (e: Exception) {
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to execute: ${e.message}",
                success = false,
                tier = ShellTier.APP_UID,
            )
        }
    }
}