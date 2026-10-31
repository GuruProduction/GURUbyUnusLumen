// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell.shizuku

import android.app.Service
import android.content.Intent
import android.os.IBinder
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit

/**
 * ShizukuShellService — the process that actually holds shell UID.
 *
 * WHY A SERVICE AND NOT A PROCESS BUILDER. Shizuku runs this class in its own
 * `app_process` JVM as UID 2000. Running Runtime.exec from the app's own process
 * would inherit the app's UID and the whole point would be lost: the commands that
 * matter here are exactly the ones the app UID cannot run.
 *
 * WHY IT CANNOT CHEAT. This class has no reference to a Context and does nothing
 * except execute what it is given, because the moment a privileged channel starts
 * making decisions it becomes a way to escalate through a narrow hole. The app
 * decides; this runs.
 *
 * The `newProcess` API was removed upstream, so this is bound with
 * `Shizuku.bindUserService` and kept alive by Shizuku rather than by the app.
 */
class ShizukuShellService : Service() {

    private val binder = object : IShizukuShell.Stub() {

        /**
         * Run one command and report exactly what happened.
         *
         * A non-zero exit is returned rather than thrown: a command that ran and
         * failed is a different fact from a command that never ran, and the caller
         * has to be able to tell them apart. Both streams are drained concurrently
         * before waitFor, which is what stops a process blocking on a full pipe
         * while the reader waits for it to exit.
         */
        override fun execute(command: String, timeoutSeconds: Int): ShellOutput {
            if (command.isBlank()) {
                return ShellOutput(
                    exitCode = -1,
                    stdout = "",
                    stderr = "No command was given, so nothing ran.",
                )
            }
            return try {
                val process = ProcessBuilder("sh", "-c", command)
                    .redirectErrorStream(false)
                    .start()

                val stdout = StringBuilder()
                val stderr = StringBuilder()

                val outThread = Thread {
                    BufferedReader(InputStreamReader(process.inputStream)).use { reader ->
                        stdout.append(reader.readText())
                    }
                }
                val errThread = Thread {
                    BufferedReader(InputStreamReader(process.errorStream)).use { reader ->
                        stderr.append(reader.readText())
                    }
                }
                outThread.start()
                errThread.start()

                val finished = process.waitFor(timeoutSeconds.toLong(), TimeUnit.SECONDS)
                if (!finished) {
                    process.destroyForcibly()
                    outThread.join(1_000)
                    errThread.join(1_000)
                    return ShellOutput(
                        exitCode = -1,
                        stdout = stdout.toString().trim(),
                        stderr = "The command did not finish within $timeoutSeconds seconds " +
                            "and was stopped, so its result is incomplete.",
                    )
                }

                outThread.join(1_000)
                errThread.join(1_000)

                ShellOutput(
                    exitCode = process.exitValue(),
                    stdout = stdout.toString().trim(),
                    stderr = stderr.toString().trim(),
                )
            } catch (error: Exception) {
                ShellOutput(
                    exitCode = -1,
                    stdout = "",
                    stderr = "The command could not be started here: " +
                        (error.message ?: "unknown error"),
                )
            }
        }
    }

    override fun onBind(intent: Intent?): IBinder = binder
}
