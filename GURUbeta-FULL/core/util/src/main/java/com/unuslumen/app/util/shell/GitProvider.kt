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

    /**
     * Lazily ensure the Termux prefix and its git runtime exist.
     *
     * WHY THIS IS HERE RATHER THAN AT THE CALL SITE. The prefix used to be
     * extracted only when the user ran a termux* tool, so the git family reported
     * "The Termux git runtime is not installed … open the Termux tools once to
     * finish app setup" on a fresh install. That asked the user to perform a setup
     * step the app is perfectly capable of doing itself, and it made git a feature
     * that only worked after visiting a screen most users never open.
     *
     * GitProvider now guarantees its own runtime. Boot also calls the same
     * initialize(), so this is normally a no-op by the time anything asks; it
     * exists so the first git call works even on a cold process that has not
     * finished boot work yet. Idempotent inside TermuxProvider (marker-gated on a
     * git that actually printed a version).
     */
    private suspend fun ensureRuntime() {
        if (gitBinary != null) return
        runCatching {
            val installed = TermuxProvider(context).initialize()
            if (!installed) {
                Log.w(TAG, "GitProvider: Termux prefix initialise reported unavailable")
            }
        }.onFailure {
            Log.w(TAG, "GitProvider: Termux prefix initialise failed: ${it.message}")
        }
    }

    /** Why git cannot run, or null when the tree is complete. Never invented. */
    private fun missingRuntimeReason(): String? {
        if (gitBinary == null) {
            return "The Termux git runtime could not be installed at ${gitPath()}. " +
                "The app installs it from its own assets; if this persists the APK is " +
                "missing git-termux.zip or the prefix could not be written."
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
        // Guarantee the runtime before reporting it absent.
        ensureRuntime()

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

            // WHERE THE HELPERS LIVE. GIT_EXEC_PATH is where git LOOKS for its
            // built-ins, but the remote-helper lookup does NOT use it: for
            // `https://` git runs `git remote-https` through a plain PATH search,
            // and the only git-remote-https in this tree is under
            // libexec/git-core. With libexec off PATH the lookup failed with
            // "fatal: unable to find remote helper for 'https'", which is exactly
            // the symptom every git network tool reported.
            //
            // So libexec/git-core joins PATH ahead of bin. That is also where
            // upstream Termux puts it, and it is why a real Termux install works
            // and this prefix did not.
            env["GIT_EXEC_PATH"] = gitExecPath.absolutePath
            // Only point GIT_TEMPLATE_DIR at a directory that exists. The bundled
            // git-termux.zip carries only bin/, libexec/ and share/git-core/
            // templates — this prefix has no share/git-core/templates because
            // TermuxProvider never extracts one there. Setting the variable to a
            // missing directory made every clone print
            // "warning: templates not found in /data/data/com.termux/files/usr/share/git-core/templates",
            // a path from the upstream package that does not exist here. Unset
            // means git uses its built-in defaults and stays quiet.
            if (gitTemplateDir.isDirectory) {
                env["GIT_TEMPLATE_DIR"] = gitTemplateDir.absolutePath
            }

            // WHERE LIBCURL LOOKS FOR CAs. The Termux build of libcurl has
            // /data/data/com.termux/files/usr/etc/tls/cert.pem compiled in, a path
            // that does not exist in this app's sandbox. Without a redirect every
            // HTTPS operation died with "error adding trust anchors from file:
            // /data/data/com.termux/files/usr/etc/tls/cert.pem" before a single
            // byte left the device. SSL_CERT_FILE is libcurl's own override and
            // takes precedence over the compiled default; GIT_SSL_CAINFO below
            // covers git's own TLS layer.
            val certInPrefix = File(termuxRoot, "usr/etc/tls/cert.pem")
            if (certInPrefix.exists() && certInPrefix.length() > 0) {
                env["SSL_CERT_FILE"] = certInPrefix.absolutePath
                env["CA_CERT_FILE"] = certInPrefix.absolutePath
            }

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
            // THE PRELOAD IS LOAD-BEARING, AND IT NEEDS ITS OWN INPUTS.
            //
            // The measured failure without these was:
            //   fatal: cannot exec 'remote-https': Permission denied
            // EACCES, not ENOENT, which means the kernel refused an exec of a real
            // file in app storage. That is Android's W^X rule (targetSdk 29+): a
            // process may not exec a binary from its own data dir. git spawns
            // `git-remote-https` for every https remote, so the helper exec is
            // denied and no byte ever leaves the device.
            //
            // libtermux-exec exists to route exactly that exec through
            // /system/bin/linker64 instead. It only does so when it can decide the
            // linker is usable, and `strings libtermux-exec-ld-preload.so` shows
            // the inputs it reads:
            //   TERMUX__PREFIX, TERMUX__ROOTFS         where the prefix lives
            //   TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE  which interception mode
            //   ANDROID__BUILD_VERSION_SDK            whether W^X applies at all
            //   TERMUX__SE_PROCESS_CONTEXT            the SELinux domain, which on
            //                                         10+ decides linker exec
            // With those unset it loads, decides not to intercept, and every child
            // exec is refused. Setting LD_PRELOAD alone looks correct and is inert.
            //
            // ROOTFS is the termux root (the parent of usr), PREFIX is usr itself.
            // The preload uses rootfs directly rather than deriving it.
            val preload = File(termuxLibDir, "libtermux-exec-ld-preload.so")
            if (preload.exists()) {
                env["LD_PRELOAD"] = preload.absolutePath
                env["TERMUX__PREFIX"] = File(termuxRoot, "usr").absolutePath
                env["TERMUX__ROOTFS"] = termuxRoot.absolutePath
                env["TERMUX_APP__LEGACY_DATA_DIR"] = context.filesDir.parentFile?.absolutePath
                    ?.let { File(it, context.packageName).absolutePath }
                    ?: context.filesDir.absolutePath
                // An INTEGER, not a word. The binary logs
                // "Warning: Ignoring invalid system_linker_exec_mode value and
                // using '1' instead" when it is given a string, so the value has
                // to be a decimal digit. 1 is the direct linker mode.
                env["TERMUX_EXEC__SYSTEM_LINKER_EXEC__MODE"] = "1"
                // TERMUX_APP__DATA_DIR is what the preload uses to decide whether
                // a target path is inside app data at all (its
                // is_exe_under_termux_app_data_dir flag). Without it the prefix
                // comparisons have nothing to compare against.
                env["TERMUX_APP__DATA_DIR"] = context.filesDir.parentFile?.absolutePath
                    ?.let { File(it, context.packageName).absolutePath }
                    ?: context.filesDir.absolutePath
                env["ANDROID__BUILD_VERSION_SDK"] = android.os.Build.VERSION.SDK_INT.toString()
                // The SELinux context, read here rather than letting the preload
                // read /proc/self/attr/current between fork and exec, which is
                // reported to deadlock forked children. git forks constantly, so
                // passing it in removes that window rather than gambling on it.
                runCatching {
                    File("/proc/self/attr/current").readText().trim().trim('\u0000')
                }.getOrNull()?.takeIf { it.isNotBlank() }?.let {
                    env["TERMUX__SE_PROCESS_CONTEXT"] = it
                }
            } else {
                env.remove("LD_PRELOAD")
            }

            // A terminal-style PATH over the Termux prefix so git's own lookups
            // (and the shell that runs the credential helper) resolve inside the
            // installed tree first. libexec/git-core comes FIRST because the
            // remote-helper lookup is a plain PATH search, not a GIT_EXEC_PATH
            // lookup: git-remote-https lives there and nowhere else.
            env["PATH"] = "${gitExecPath.absolutePath}:${File(termuxRoot, "usr/bin").absolutePath}:${termuxLibDir.absolutePath}:/system/bin"

            // CA chain: provision the bundle from the APK's own assets on first
            // use. Nothing guarantees an earlier tool run already copied it, and
            // git with no trustworthy CA against Android's cert layout dies
            // mid-TLS. System CA dir rides along as the fallback path too.
            // The prefix's OWN bundle is preferred: it is the one libcurl was built
            // against and the one SSL_CERT_FILE above points at, so git's TLS layer
            // and libcurl's agree instead of loading two different trust stores.
            // The app-asset bundle stays the fallback, and Android's system store
            // the last resort.
            val prefixCert = File(termuxRoot, "usr/etc/tls/cert.pem")
            val caCert = if (prefixCert.exists() && prefixCert.length() > 0) {
                prefixCert
            } else {
                provisionCaBundle()
            }
            if (caCert != null) {
                env["GIT_SSL_CAINFO"] = caCert.absolutePath
                env["SSL_CERT_FILE"] = caCert.absolutePath
            }
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
                // BOTH keys, deliberately. git routes an https:// URL through
                // libcurl, which reads https.proxy for https URLs and http.proxy
                // for http ones; setting only http.proxy works on the builds
                // tested here but is not guaranteed across curl versions. A
                // measured test on this device showed that with NEITHER key set
                // git connects straight to github.com's IP over clearnet, so a
                // single missing key is a real leak rather than a cosmetic gap.
                configPairs.add("http.proxy" to torProxyUrl)
                configPairs.add("https.proxy" to torProxyUrl)
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
            if (!success) {
                // Log stderr, not just the exit code. The exit code alone says a
                // command failed and nothing about why; that is how a 255 in
                // 250ms got mistaken for a network fault when it was a local
                // setup error. The message is what makes a failure actionable.
                Log.w(
                    TAG,
                    "GitProvider: git exited $exit :: ${args.joinToString(" ")} :: " +
                        stderr.take(600).replace("\n", " | ")
                )
            }
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
