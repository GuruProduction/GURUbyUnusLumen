// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.BufferedReader
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader

/**
 * GitProvider — runs the Termux git binary for repository work on the device.
 *
 * THE BINARY. GURU ships Termux git 2.56.0 as the asset
 * git-termux/git-termux.zip. TermuxProvider.installGitRuntime() extracts it
 * into filesDir/termux/usr/ — bin/git, the real libexec/git-core/git-remote-http
 * helper (plus its git-remote-https symlink) and share/git-core/templates — so
 * git finally has an HTTP transport. The previous libguru_git.so was a 2.45.0
 * build with no transport linked and no helper in the APK, which is why every
 * network operation died at exit 139 when git tried to exec the missing helper.
 *
 * WHY THE LINKER. /data is mounted noexec on modern Android, so app-storage
 * ELFs cannot be exec'd directly. Everything here launches the binary as
 * `/system/bin/linker64 <git> <args>` — exactly how TermuxProvider.execInTermux
 * runs it — and exports LD_PRELOAD=libtermux-exec so git's OWN child processes
 * (the git-remote-https helper, git fetch-pack, git credential) are wrapped in
 * the linker too. Without that preload git's internal execvp of a helper in
 * app storage fails with EACCES and the transport dies.
 *
 * SECURITY POSTURE (three laws, no exceptions):
 *  1. The GitHub credential NEVER touches disk, argv, or a log line. It rides
 *     the git child process environment only and dies with the process. Auth is
 *     delivered through git's shell-function credential helper ("!f() {...}").
 *  2. Every NETWORK operation (clone, fetch, pull, push) fails closed when Tor
 *     is down. Local operations (status, add, commit) run without Tor because
 *     they touch nothing off-device.
 *  3. Commands run as THIS app's UID directly via ProcessBuilder. They do not
 *     route through the root-first ShellExecutor, so .git directories always
 *     belong to the app and stay writable by the app.
 *
 * AUTH BRIDGE. Two environment channels: the token as GURU_GIT_CREDENTIAL, and
 * GIT_CONFIG_COUNT numbered pairs (native to git 2.31+, and present in 2.56)
 * which carry credential.helper plus any per-call identity values. git expands
 * the helper's $GURU_GIT_CREDENTIAL from ITS environment at call time, so the
 * value never enters a file, a URL, or this process's arguments.
 */
class GitProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"

        /** Repos live under app-private storage; nothing here touches shared storage. */
        private const val REPOS_DIRNAME = "git-repos"

        /**
         * The shell-function credential helper. git strips the leading "!" and
         * runs the rest through /system/bin/sh. The function prints the fixed
         * GitHub username and the token carried in GURU_GIT_CREDENTIAL.
         */
        const val CREDENTIAL_HELPER_SHELL =
            "!f() { echo username=x-access-token; echo \"password=\u0024GURU_GIT_CREDENTIAL\"; }; f"

        /** Environment key holding the transient GitHub token for the git child process. */
        const val ENV_CREDENTIAL = "GURU_GIT_CREDENTIAL"

        /** Wall clock floors. Local reads are quick; clones over Tor are slow and get room. */
        const val DEFAULT_TIMEOUT_MS = 120_000L
        const val NETWORK_TIMEOUT_MS = 600_000L

        /** Cap per stream so one runaway command cannot exhaust the process heap. */
        private const val MAX_OUTPUT_CHARS = 1_500_000
    }

    /** Termux prefix root, as installed by TermuxProvider. */
    private val termuxRoot: File
        get() = File(context.filesDir, "termux")

    /** The Termux git entry point installed into the prefix bin/. */
    private val gitBinary: File?
        get() = File(termuxRoot, "usr/bin/git").takeIf { it.isFile && it.length() > 0 }

    /** git's exec path: the helpers (git-remote-http, fetch-pack, credential…) beside the binary. */
    private val gitExecPath: File
        get() = File(termuxRoot, "usr/libexec/git-core")

    /** git's clone templates. */
    private val gitTemplateDir: File
        get() = File(termuxRoot, "usr/share/git-core/templates")

    /** The Termux prefix's own libraries — libcurl, openssl, pcre2 and everything else git names. */
    private val termuxLibDir: File
        get() = File(termuxRoot, "usr/lib")

    /** /data is noexec; every app-storage ELF is launched through the system linker. */
    private val linker: String
        get() {
            val is64Bit = System.getProperty("os.arch")?.contains("64") ?: true
            return if (is64Bit) "/system/bin/linker64" else "/system/bin/linker"
        }

    /** Base folder for all cloned repositories. */
    val reposDir: File
        get() = File(context.filesDir, REPOS_DIRNAME)

    /** One shared git HOME: no credential is ever persisted, so repos need no private HOMEs. */
    val gitHome: File
        get() = File(reposDir, ".git-home").apply { mkdirs() }

    /** Path of the installed Termux git entry point. */
    fun gitPath(): String = File(termuxRoot, "usr/bin/git").absolutePath

    /** True when the Termux git binary is installed in the prefix. */
    fun isGitAvailable(): Boolean = gitBinary != null

    /** Why git cannot run, or null when the tree is complete. Never invented. */
    private fun missingRuntimeReason(): String? {
        if (gitBinary == null) {
            return "The Termux git runtime is not installed at ${gitPath()}. " +
                "Open the Termux tools once to finish app setup, then retry."
        }
        if (!gitExecPath.isDirectory) {
            return "git helpers are missing: ${gitExecPath.absolutePath} does not exist. " +
                "The Termux install is incomplete; it will re-run on the next app start."
        }
        val remoteHttps = File(gitExecPath, "git-remote-https")
        if (!remoteHttps.exists()) {
            return "git's HTTPS transport helper is missing: ${remoteHttps.absolutePath}. " +
                "The Termux install is incomplete; it will re-run on the next app start."
        }
        if (!gitTemplateDir.isDirectory) {
            return "git's template directory is missing: ${gitTemplateDir.absolutePath}. " +
                "The Termux install is incomplete; it will re-run on the next app start."
        }
        return null
    }

    /**
     * Run one git invocation. Null credential means a local-only operation. A
     * non-blank credential attaches the auth helper env contract to THIS child
     * process only. requireTor guards network operations and fails closed.
     */
    suspend fun runGit(
        args: List<String>,
        workingDir: File?,
        credential: String? = null,
        extraConfig: Map<String, String> = emptyMap(),
        requireTor: Boolean = false,
        torProxyUrl: String? = null,
        timeoutMs: Long = DEFAULT_TIMEOUT_MS
    ): ShellResult = withContext(Dispatchers.IO) {
        val missing = missingRuntimeReason()
        if (missing != null) {
            return@withContext ShellResult(exitCode = -1, stdout = "", stderr = missing, success = false)
        }
        if (requireTor && torProxyUrl.isNullOrBlank()) {
            return@withContext ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Tor is not running. git network operations never leave GURU over clearnet. Bring Tor up and retry.",
                success = false
            )
        }

        val gitPath = gitBinary?.absolutePath
        if (gitPath == null) {
            // missingRuntimeReason() already covered this; keep the compiler and
            // any future refactor honest rather than dereferencing a null.
            return@withContext ShellResult(
                exitCode = -1, stdout = "",
                stderr = "The Termux git runtime is not installed at ${gitPath()}.",
                success = false
            )
        }

        val runDir = (workingDir ?: reposDir).apply { mkdirs() }
        if (!runDir.isDirectory) {
            return@withContext ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Working directory does not exist: ${runDir.absolutePath}",
                success = false
            )
        }

        // /data is noexec: the system linker loads the ELF, and its arguments
        // start with the binary's real path so git sees a proper argv[0].
        val command = mutableListOf(linker, gitPath)
        command.addAll(args)

        try {
            val pb = ProcessBuilder(command)
            pb.directory(runDir)
            pb.redirectErrorStream(false)

            val env = pb.environment()
            // Never inherit stale config or tokens of any kind from the parent process.
            env.remove(ENV_CREDENTIAL)
            env.keys.filter {
                it.startsWith("GIT_CONFIG_KEY_") || it.startsWith("GIT_CONFIG_VALUE_") ||
                    it.startsWith("GIT_CONFIG_COUNT")
            }.forEach { env.remove(it) }

            // One Tor route only: strip any inherited proxy-ish variable so the
            // child can never carry a second, stale egress path underneath the
            // http.proxy config this class sets.
            env.keys.filter { it.lowercase().endsWith("_proxy") }.forEach { env.remove(it) }

            // Standard git environment so it behaves like a real terminal git.
            env["HOME"] = gitHome.absolutePath
            env["GIT_TERMINAL_PROMPT"] = "0" // never hang on an interactive stdin prompt; fail instead
            env["LC_ALL"] = "C" // stable parseable output text, no locale surprises

            // WHERE THE HELPERS LIVE. git finds git-remote-https, fetch-pack,
            // credential and the rest under GIT_EXEC_PATH; the templates it
            // copies into a fresh repo live under GIT_TEMPLATE_DIR. Both are
            // installed tree paths, not guesses.
            env["GIT_EXEC_PATH"] = gitExecPath.absolutePath
            env["GIT_TEMPLATE_DIR"] = gitTemplateDir.absolutePath

            // WHERE THE LIBRARIES LIVE. Both ELFs name libcurl, libssl,
            // libcrypto, libpcre2-8, libz, libiconv and friends; their RPATH
            // was patched to this same directory at install time, and this
            // variable keeps that true even if a patch was refused.
            env["LD_LIBRARY_PATH"] = termuxLibDir.absolutePath

            // WHY THE PRELOAD MATTERS: git spawns the transport helper and the
            // fetch-pack/credential children itself. Those live on noexec /data,
            // so the plain execvp git performs would fail with EACCES. The
            // Termux preload intercepts that exec and re-launches the child
            // through /system/bin/linker64, which is why the binary itself is
            // started through the linker above. Only load it when the file is
            // really there — an absent LD_PRELOAD breaks every linked launch.
            val preload = File(termuxLibDir, "libtermux-exec-ld-preload.so")
            if (preload.exists()) {
                env["LD_PRELOAD"] = preload.absolutePath
            } else {
                env.remove("LD_PRELOAD")
            }

            // A terminal-style PATH over the Termux prefix so git's own lookups
            // (and the shell that runs the credential helper) resolve inside
            // the installed tree first.
            env["PATH"] = "${File(termuxRoot, "usr/bin").absolutePath}:${termuxLibDir.absolutePath}:/system/bin"

            // CA chain: provision the bundle from the APK's own assets on first
            // use. Nothing guarantees an earlier tool run already copied it, and
            // git with no trustworthy CA against Android's cert layout dies
            // mid-TLS. System CA dir rides along as the fallback path too.
            val caCert = provisionCaBundle()
            if (caCert != null) env["GIT_SSL_CAINFO"] = caCert.absolutePath
            if (File("/system/etc/security/cacerts").isDirectory) {
                env["GIT_SSL_CAPATH"] = "/system/etc/security/cacerts"
            }

            val configPairs = mutableListOf<Pair<String, String>>()
            configPairs.add("safe.directory" to "*")
            caCert?.let { configPairs.add("http.sslCAInfo" to it.absolutePath) }

            if (requireTor && !torProxyUrl.isNullOrBlank()) {
                // Tor routing through exactly ONE channel: git's own http.proxy
                // config. The URL may be http:// (Tor's HTTP CONNECT tunnel) or
                // socks5h:// (Tor's SOCKS listener, DNS inside Tor); libcurl
                // handles both, so the caller's live-listener choice is passed
                // through verbatim. The previous six environment variables are
                // gone deliberately: a single boring route, nothing racing it.
                configPairs.add("http.proxy" to torProxyUrl)
            }

            if (credential != null && credential.isNotBlank()) {
                env[ENV_CREDENTIAL] = credential
                configPairs.add("credential.helper" to CREDENTIAL_HELPER_SHELL)
            }

            for ((key, value) in extraConfig) {
                if (key.isBlank()) continue
                configPairs.add(key to value)
            }

            var index = 0
            for ((key, value) in configPairs) {
                    env["GIT_CONFIG_KEY_$index"] = key
                    env["GIT_CONFIG_VALUE_$index"] = value
                    index++
            }
            if (index > 0) env["GIT_CONFIG_COUNT"] = index.toString()

            val process = pb.start()

            // Drain both pipes concurrently so a chatty git can never block on a full pipe.
            val stdoutDeferred = async { readCapped(process.inputStream) }
            val stderrDeferred = async { readCapped(process.errorStream) }

            val exit = withTimeoutOrNull(timeoutMs) {
                withContext(Dispatchers.IO) { process.waitFor() }
            }
            if (exit == null) {
                process.destroyForcibly()
                // Give the drain coroutines a moment to hit EOF, then report the timeout honestly.
                val stdout = runCatching { withTimeoutOrNull(5_000L) { stdoutDeferred.await() } }.getOrNull() ?: ""
                val stderr = runCatching { withTimeoutOrNull(5_000L) { stderrDeferred.await() } }.getOrNull() ?: ""
                Log.w(TAG, "GitProvider: git timed out after ${timeoutMs}ms :: ${args.joinToString(" ")}")
                return@withContext ShellResult(
                    exitCode = -1,
                    stdout = stdout.trim(),
                    stderr = ("git exceeded ${timeoutMs / 1000}s and was stopped." +
                        if (stderr.isBlank()) "" else "\n$stderr").trim(),
                    success = false
                )
            }

            val stdout = stdoutDeferred.await()
            val stderr = stderrDeferred.await()
            val success = exit == 0
            if (!success) Log.w(TAG, "GitProvider: git exited $exit :: ${args.joinToString(" ")}")
            ShellResult(exitCode = exit, stdout = stdout.trim(), stderr = stderr.trim(), success = success)
        } catch (e: Exception) {
            ShellResult(
                exitCode = -1,
                stdout = "",
                stderr = "Failed to run git: ${e.message}",
                success = false
            )
        }
    }

    /**
     * Read a stream up to the cap, ALWAYS draining to EOF so the child never
     * stalls, and append an honest truncation marker only when text was cut.
     */
    private suspend fun readCapped(stream: InputStream): String = withContext(Dispatchers.IO) {
        val text = StringBuilder()
        var truncated = false
        try {
            BufferedReader(InputStreamReader(stream)).use { reader ->
                val chunk = CharArray(16_384)
                while (true) {
                    val read = reader.read(chunk)
                    if (read < 0) break
                    if (text.length >= MAX_OUTPUT_CHARS) {
                        truncated = true
                        continue // keep draining, stop collecting
                    }
                    val allowed = minOf(read, MAX_OUTPUT_CHARS - text.length)
                    text.append(chunk, 0, allowed)
                    if (allowed < read) {
                        truncated = true
                    }
                }
            }
        } catch (_: Exception) {
        }
        if (truncated) {
            text.append("\n… [output truncated; captured the first $MAX_OUTPUT_CHARS characters]")
        }
        text.toString()
    }

    /** Safe clone target under reposDir: owner and name stripped of separators. */
    fun cloneTargetPath(repository: String): File? {
        val parts = repository.trim().split("/")
        if (parts.size != 2 || parts.any { it.isBlank() }) return null
        val owner = parts[0].replace(Regex("[^A-Za-z0-9._-]"), "_")
        val name = parts[1].removeSuffix(".git").replace(Regex("[^A-Za-z0-9._-]"), "_")
        if (owner.isBlank() || name.isBlank()) return null
        return File(File(reposDir, owner).absolutePath, name).absoluteFile
    }

    /**
     * Validate that a repo path is a real directory inside THIS app's private
     * storage. Canonical path comparison denies any traversal trick outright.
     */
    fun resolveRepo(repoPath: String): File? {
        if (repoPath.isBlank()) return null
        return try {
            val repo = File(repoPath.trim()).canonicalFile
            val base = context.filesDir.canonicalFile
            if (!repo.exists() || !repo.isDirectory) return null
            if (!repo.startsWith(base)) return null
            repo
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Make sure filesDir/cacert.pem exists. First copy wins; if the bundled
     * asset fails to copy, whatever an earlier tool run placed there stays.
     * Returns the file only when it genuinely exists afterwards.
     */
    private fun provisionCaBundle(): File? {
        val caCert = File(context.filesDir, "cacert.pem")
        if (caCert.exists() && caCert.length() > 0) return caCert
        return try {
            context.assets.open("cacert.pem").use { input ->
                java.io.FileOutputStream(caCert).use { output -> input.copyTo(output) }
            }
            if (caCert.exists() && caCert.length() > 0) caCert else null
        } catch (e: Exception) {
            Log.w(TAG, "GitProvider: cacert.pem provisioning failed: ${e.message}")
            caCert.takeIf { it.exists() && it.length() > 0 }
        }
    }
}
