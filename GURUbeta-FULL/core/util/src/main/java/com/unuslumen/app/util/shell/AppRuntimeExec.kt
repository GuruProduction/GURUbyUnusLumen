package com.unuslumen.app.util.shell

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Pure app-runtime binary executor for GURU's bundled tool binaries.
 *
 * ONE execution path: ProcessBuilder launches the binary directly by its
 * absolute path inside nativeLibraryDir, running as the app's own UID.
 * No root. No ADB. No su. No probing, no fallback ladder.
 *
 * Why this is sufficient: Android packages lib*.so files from jniLibs into
 * nativeLibraryDir, which carries the nativedir_file SELinux label — the one
 * filesystem location where the platform permits executing app-owned binaries
 * from the app UID, on every supported Android version. Direct argv execution
 * is the correct and only mechanism needed.
 *
 * Consumers: the vision stack (bundled tesseract OCR, bundled ffmpeg). These
 * calls never route through ShellExecutor's general ladder.
 */
object AppRuntimeExec {

    data class Result(
        val exitCode: Int,
        val stdout: String,
        val stderr: String
    ) {
        val success: Boolean get() = exitCode == 0
    }

    /**
     * Execute a bundled binary directly with argv (NOT through sh -c).
     * stdout and stderr are drained on concurrent threads so the process can
     * never deadlock on full pipe buffers, and a hard timeout guarantees the
     * calling tool always gets a result.
     *
     * @param binaryPath absolute path inside nativeLibraryDir
     * @param args argument list passed verbatim
     * @param env additional environment entries (e.g. TESSDATA_PREFIX)
     * @param timeoutSeconds hard cap before the process is killed forcibly
     */
    fun exec(
        binaryPath: String,
        args: List<String>,
        env: Map<String, String> = emptyMap(),
        timeoutSeconds: Long = 120L
    ): Result {
        require(File(binaryPath).exists()) { "binary missing: $binaryPath" }

        val process = ProcessBuilder(listOf(binaryPath) + args).apply {
            environment().apply {
                // Minimal env is enough for statically-linked binaries; these
                // entries exist for any library that probes standard locations.
                put("PATH", "/system/bin:/system/xbin:/vendor/bin")
                put("HOME", File(binaryPath).parentFile?.parent ?: "/data/local/tmp")
                putAll(env)
            }
        }.start()

        val stdoutBuilder = StringBuilder()
        val stderrBuilder = StringBuilder()

        val stdoutThread = Thread {
            process.inputStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    synchronized(stdoutBuilder) { stdoutBuilder.appendLine(line) }
                }
            }
        }
        val stderrThread = Thread {
            process.errorStream.bufferedReader().use { reader ->
                var line: String?
                while (reader.readLine().also { line = it } != null) {
                    synchronized(stderrBuilder) { stderrBuilder.appendLine(line) }
                }
            }
        }
        stdoutThread.start()
        stderrThread.start()

        val finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS)
        if (!finished) {
            process.destroyForcibly()
            stdoutThread.join(2000)
            stderrThread.join(2000)
            return Result(
                exitCode = -1,
                stdout = synchronized(stdoutBuilder) { stdoutBuilder.toString().trim() },
                stderr = synchronized(stderrBuilder) { stderrBuilder.toString().trim() }
                    .ifBlank { "[killed: timeout after ${timeoutSeconds}s]" }
            )
        }

        stdoutThread.join(2000)
        stderrThread.join(2000)

        return Result(
            exitCode = process.exitValue(),
            stdout = synchronized(stdoutBuilder) { stdoutBuilder.toString().trim() },
            stderr = synchronized(stderrBuilder) { stderrBuilder.toString().trim() }
        )
    }
}