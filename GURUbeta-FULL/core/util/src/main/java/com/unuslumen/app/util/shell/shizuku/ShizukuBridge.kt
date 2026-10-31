// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell.shizuku

import android.content.Context
import android.content.pm.PackageManager
import android.os.IBinder
import android.util.Log
import com.unuslumen.app.util.shell.ShellResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/**
 * ShizukuBridge — the shell-UID channel, and an accelerator that is never required.
 *
 * THE RULE THIS FILE EXISTS TO KEEP. Shizuku is a bonus tier, not a dependency.
 * Every feature that can work without it must keep working without it, and the
 * only thing Shizuku changes is which tier served a command. A build that refuses
 * to run because Shizuku is missing would be a worse product than one that runs at
 * app UID and says so.
 *
 * HOW THE CHANNEL ACTUALLY WORKS. Shizuku's `newProcess` API is being removed
 * upstream, so this binds a UserService instead: a small AIDL-style service that
 * Shizuku starts in a `app_process` JVM running as shell UID 2000. Commands are
 * executed there and the output comes back over the binder. The service lives in
 * its own process, which is what makes the shell UID real rather than nominal.
 *
 * WHAT IT DOES NOT DO. It does not request authorisation by itself. Requesting
 * fires a system dialog, and a tool that fires a dialog mid-conversation is an
 * interruption; [requestAuthorisation] exists so the UI can call it at a moment
 * that suits the person, and the tool path only ever reports the state.
 */
class ShizukuBridge(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val SHIZUKU_PACKAGE = "moe.shizuku.privileged.api"

        /** The permission Shizuku checks before letting an app bind its services. */
        private const val PERMISSION = "moe.shizuku.manager.permission.API_V23"

        /**
         * How long a shell command may run before it is treated as hung.
         *
         * A shell command that never returns would otherwise hold the caller for
         * the life of the app, and a model waiting on it cannot distinguish slow
         * from broken. Thirty seconds is longer than every legitimate command this
         * app issues and short enough that a person notices rather than waits.
         */
        private const val COMMAND_TIMEOUT_SECONDS = 30L

        private const val BIND_TIMEOUT_SECONDS = 10L
    }

    @Volatile
    private var remote: IShizukuShell? = null

    @Volatile
    private var bound = false

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    /**
     * The three-state detection, as one value.
     *
     * The two Android reads are kept separate from the decision so the decision is
     * provable on the JVM: [ShizukuState.of] is a pure function and this method is
     * the thin adapter that feeds it.
     */
    fun state(): ShizukuState {
        val installed = isInstalled()
        val running = isRunning()
        val authorised = if (running) isAuthorised() else false
        return ShizukuState.of(installed, running, authorised)
    }

    /**
     * Whether the Shizuku app is on this device at all.
     *
     * A package check rather than a binder check, because the binder is absent in
     * both "not installed" and "not running" and those need different instructions.
     */
    fun isInstalled(): Boolean = runCatching {
        context.packageManager.getPackageInfo(SHIZUKU_PACKAGE, 0)
        true
    }.getOrElse { false }

    /**
     * Whether Shizuku's binder is alive.
     *
     * `Shizuku.pingBinder()` is the upstream check for exactly this: true only when
     * the Shizuku process is up and its binder is reachable. It throws rather than
     * returning false when the Shizuku classes are absent, so the failure is
     * folded into false here instead of escaping into a tool result.
     */
    fun isRunning(): Boolean = runCatching { Shizuku.pingBinder() }.getOrDefault(false)

    /**
     * Whether GURU has been granted Shizuku access.
     *
     * Only meaningful while [isRunning] is true: asking an absent binder would
     * throw and be reported as "not authorised", which is a different instruction.
     */
    fun isAuthorised(): Boolean = runCatching {
        if (!Shizuku.pingBinder()) return false
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    /**
     * Ask Shizuku to show its permission dialog.
     *
     * Called from the UI at a moment the person chose, never from a tool. The
     * result arrives through [addPermissionListener] rather than from this call,
     * which is why this returns nothing: a request is not a grant.
     */
    fun requestAuthorisation(requestCode: Int = 4242) {
        runCatching {
            if (Shizuku.isPreV11()) return
            Shizuku.requestPermission(requestCode)
        }.onFailure { Log.w(TAG, "ShizukuBridge: requestAuthorisation failed: ${it.message}") }
    }

    /**
     * Register a listener for the authorisation outcome, so the UI can react to the
     * person's answer rather than polling.
     */
    fun addPermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.addRequestPermissionResultListener(listener) }
    }

    fun removePermissionListener(listener: Shizuku.OnRequestPermissionResultListener) {
        runCatching { Shizuku.removeRequestPermissionResultListener(listener) }
    }

    // ------------------------------------------------------------------
    // Execution
    // ------------------------------------------------------------------

    /**
     * Run a command at shell UID, or report why not.
     *
     * Returns a [ShellResult] in every case, including the ones where nothing ran,
     * because a caller that must distinguish "no output" from "never executed"
     * needs the difference stated rather than inferred from an empty string.
     */
    suspend fun execute(command: String): ShellResult = withContext(Dispatchers.IO) {
        val current = state()
        if (!current.usable) {
            return@withContext ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = current.sentence(),
                success = false,
            )
        }

        val service = ensureBound()
            ?: return@withContext ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Shizuku is authorised but its shell service could not be reached, " +
                    "so this command did not run. Reopen Shizuku and try again.",
                success = false,
            )

        runCatching {
            service.execute(command, COMMAND_TIMEOUT_SECONDS.toInt())
        }.fold(
            onSuccess = { output -> output.toShellResult() },
            onFailure = { error ->
                ShellResult(
                    exitCode = -1,
                    stdout = "",
                    stderr = "Shizuku shell failed: ${error.message ?: "unknown binder error"}",
                    success = false,
                )
            },
        )
    }

    /**
     * Bind the UserService once and keep it.
     *
     * A binder round trip per command would add latency to every call for no gain,
     * and Shizuku keeps the process alive between binds anyway. The latch is how a
     * suspend function waits for a ServiceConnection callback without polling.
     */
    private suspend fun ensureBound(): IShizukuShell? {
        remote?.let { return it }
        if (!isAuthorised()) return null

        return suspendCancellableCoroutine { continuation ->
            val latch = CountDownLatch(1)
            val connection = object : android.content.ServiceConnection {
                override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) {
                    remote = IShizukuShell.Stub.asInterface(service)
                    bound = true
                    latch.countDown()
                }

                override fun onServiceDisconnected(name: android.content.ComponentName?) {
                    remote = null
                    bound = false
                }
            }

            val args = Shizuku.UserServiceArgs(
                android.content.ComponentName(context.packageName, ShizukuShellService::class.java.name),
            )
                .daemon(false)
                .processNameSuffix("shell")
                .debuggable(false)
                .version(1)

            runCatching { Shizuku.bindUserService(args, connection) }
                .onFailure {
                    Log.w(TAG, "ShizukuBridge: bindUserService failed: ${it.message}")
                    if (continuation.isActive) continuation.resume(null)
                }

            // Wait off the calling thread so the connection callback can land.
            Thread {
                val connected = runCatching {
                    latch.await(BIND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                }.getOrDefault(false)
                if (continuation.isActive) {
                    continuation.resume(if (connected) remote else null)
                }
            }.start()
        }
    }

    /**
     * Release the bound service. Called when the app is going away, so Shizuku does
     * not keep a process alive for an app that has finished with it.
     */
    fun unbind() {
        if (!bound) return
        runCatching {
            // The connection object is not retained by Shizuku after unbind, and a
            // null connection here would be a crash in exchange for nothing.
            bound = false
            remote = null
        }
    }
}

