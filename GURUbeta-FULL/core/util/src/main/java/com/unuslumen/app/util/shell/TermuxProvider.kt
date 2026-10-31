// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.File
import java.io.FileOutputStream
import java.io.InputStreamReader
import java.util.zip.ZipFile

/**
 * Manages a bundled Termux Linux environment extracted from a bootstrap rootfs
 * archive embedded in the APK assets.
 *
 * DIRECTORY LAYOUT (final, after extract + canonicalise):
 *   The zip arrives with bin/, lib/, etc/ at termux ROOT; canonicalise moves
 *   every package dir inside usr/ so the final tree is Termux-standard:
 *   files/termux/home/     <- user home (stays at termux ROOT, never moves)
 *   files/termux/usr/      <- THE PREFIX. TERMUX_PREFIX resolves here
 *   files/termux/usr/bin/  <- all 250+ binaries (bash, apt, git, curl, etc.)
 *   files/termux/usr/lib/  <- all shared libraries (.so files)
 *   files/termux/usr/etc/  <- config files, apt sources, keyrings
 *
 * EXECUTION MODEL:
 *   We do NOT pipe commands through /system/bin/sh because Android's sandbox
 *   blocks the system shell from executing app-internal ELF binaries. Instead:
 *   - For simple commands: ProcessBuilder with env vars set
 *   - First word is the binary name, looked up in termux/usr/bin/
 *   - LD_LIBRARY_PATH includes termux/usr/lib/ so all .so deps resolve
 */
class TermuxProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val ASSET_BOOTSTRAP = "termux/termux-bootstrap.zip"
        private const val HOME_DIR = "home"

        /**
         * Termux git 2.56.0 runtime, packaged by fetch_git_termux.sh: bin/git,
         * the real libexec/git-core/git-remote-http helper, its
         * git-remote-https symlink, and share/git-core/templates. Every
         * library it needs (libcurl, libssl, libcrypto, libpcre2-8, libz,
         * libiconv, libnghttp2/3, libngtcp2, libssh2) already lives in this
         * prefix's lib/ from the bootstrap — the zip carries no libraries.
         */
        private const val ASSET_GIT_RUNTIME = "git-termux/git-termux.zip"

        /**
         * Done-marker for the git runtime install. Written ONLY after
         * bin/git really executes and prints a version, so a partial tree
         * retries on the next init.
         */
        private const val GIT_RUNTIME_MARKER = ".git-runtime-ok"

        /**
         * Version of the prefix-relocation pass. Bump it whenever the rewrite
         * rules change, so a tree installed by an older build re-runs the pass
         * rather than keeping the old, broken result forever.
         */
        private const val PREFIX_RELOCATION_VERSION = 1

        /** The prefix every shipped script and dpkg row was built against. */
        private const val UPSTREAM_FILES_ROOT = "/data/data/com.termux/files"
    }

    private val termuxRoot: File
        get() = File(context.filesDir, "termux")

    private val usrPrefix: File
        get() = File(termuxRoot, "usr")

    private val binDir: File
        get() = File(usrPrefix, "bin")
    private val libDir: File
        get() = File(usrPrefix, "lib")
    private val etcDir: File
        get() = File(usrPrefix, "etc")
    private val usrDir: File
        get() = usrPrefix
    private val homeDir: File
        get() = File(termuxRoot, HOME_DIR)
    private val tmpDir: File
        get() = File(usrPrefix, "tmp")

    private var initialized = false
    private var available = false
    private var symlinksCreated = false

    /** True only during a recovery re-extract pass this process runs deliberately. */
    @Volatile private var recoveryRebootstrap = false

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext available

        try {
            termuxRoot.mkdirs()

            // Corruption detection (the audit's stage-3 wiped tree): canonicalised tree
            // whose usr/bin holds zero executable files and whose SYMLINKS.txt is gone.
            // Old canonicaliser destroyed the payload (self-merge-then-delete through a
            // usr/bin -> ../bin symlink). Recovery: clean re-extract from the bundled
            // asset zip, which is the only copy of the dead binaries on the device.
            val binHasExecutables = binDir.exists() &&
                binDir.walkTopDown().any { it.isFile && it.canExecute() && it.length() > 0 }
            val treeWiped = binDir.exists() &&
                java.io.File(termuxRoot, "SYMLINKS.txt").exists().not() &&
                !binHasExecutables

            if (treeWiped) {
                Log.w(TAG, "TermuxProvider: wiped tree detected (no executable in usr/bin, SYMLINKS.txt consumed) — clean re-extract")
                fullCleanAndExtract()
            }

            val binHasFiles = binDir.exists() && binDir.listFiles()?.isNotEmpty() == true
            if (!binHasFiles) {
                Log.d(TAG, "TermuxProvider: extracting bootstrap from assets")
                if (!fullCleanAndExtract()) {
                    Log.w(TAG, "TermuxProvider: bootstrap not bundled or extraction failed")
                    initialized = true
                    return@withContext false
                }
            } else {
                Log.d(TAG, "TermuxProvider: found existing bootstrap, bin has ${binDir.listFiles()?.size ?: 0} files")
            }

            // Canonicalise the extracted tree into the Termux-standard layout.
            // The bootstrap zip delivers bin/, lib/, etc/ at termux ROOT; Termux ELFs
            // (bash, apt, dpkg) are compiled against a prefix ending in /usr and some
            // carry $ORIGIN-relative dependencies that want them under usr/. Every
            // top-level package dir (bin, lib, etc, share, var, libexec, ...) moves
            // under usr/ once. Runs on fresh extracts AND on legacy device trees
            // (devices that extracted before this fix) — no rebuild required.
            canonicaliseTreeLayout()

            // Bootstrap-wide symlinks apply AFTER canonicalisation: link names and
            // com.termux-absolute targets both resolve against the final usr/ tree.
            applyBootstrapSymlinks()

            // Ensure essential directories exist
            homeDir.mkdirs()
            usrDir.mkdirs()
            File(usrDir, "tmp").mkdirs()
            File(usrDir, "var").mkdirs()
            File(etcDir, "apt").mkdirs()

            // Fix permissions on all binaries and libraries
            binDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    if (!file.canExecute()) file.setExecutable(true, false)
                    if (!file.canRead()) file.setReadable(true, false)
                }
            }
            libDir.walkTopDown().forEach { file ->
                if (file.isFile) {
                    if (!file.canExecute()) file.setExecutable(true, false)
                    if (!file.canRead()) file.setReadable(true, false)
                }
            }

            // Copy bundled libs and tools (libexpat, libpng, zlib, patchelf, libc++_shared).
            // Every post-extract step is idempotent and NO step latches success unless its
            // outcome is real: copyBundledLibs skips existing non-empty files; library
            // symlinks skip existing links; RPATH patch runs only when patchelf verifies
            // as executable — and the latch below flips true only when the tree then
            // holds a REAL readable executable bash binary. A partially-run pass
            // re-runs in full on the next init (the audit's stage-5 latch defect).
            if (!symlinksCreated) {
                copyBundledLibs()
                createLibrarySymlinks()
                copyLibsToReToolsDir()
                patchElfBinaries()
                val bashBinary = File(binDir, "bash")
                symlinksCreated = bashBinary.exists() && bashBinary.length() > 0 && bashBinary.canRead()
                if (!symlinksCreated) {
                    Log.w(TAG, "TermuxProvider: post-install chain incomplete (bash missing) — will re-run next init")
                }
            }

            // Git runtime goes in AFTER copyBundledLibs/patchElfBinaries: the
            // patchelf it needs is copied by copyBundledLibs, and the RPATH it
            // points at is this prefix's lib/. Marker-gated on bin/git actually
            // running, so a tree half-extracted by a killed process retries.
            installGitRuntime()

            // Relocate every text file in the prefix onto the real prefix path. The
            // payload records /data/data/com.termux/files in script shebangs, dpkg's
            // status and .list rows, and apt's helper scripts. Left alone, apt-key's
            // shebang points at a shell that does not exist on this device, which is
            // what made apt unable to verify a single repository.
            relocatePrefixPaths()

            // The bootstrap's layout IS the real bin path (usr/bin) — no redirect
            // shims wanted. Substructure (tmp, var, apt dirs) above; done.

            // The mapping that lets dpkg install upstream-prefixed packages into
            // this tree. Built before apt config because apt references it.
            ensureCompatibilityRoot()

            // apt config — write at init so any termuxExec "apt ..." works
            initAptConfig()

            // Ensure apt transport methods + https variant exist under the prefix
            fixAptMethods()

            // Remove any old wrapper scripts that were created by previous versions
            // They don't work because Android SELinux blocks shebangs from app-private storage
            removeOldAptWrappers()

            available = binDir.exists() && binDir.listFiles()?.isNotEmpty() == true
            initialized = true
            Log.d(TAG, "TermuxProvider: initialized — available=$available, bin=${binDir.absolutePath}, lib=${libDir.absolutePath}")
        } catch (e: Exception) {
            Log.e(TAG, "TermuxProvider: initialization failed", e)
            initialized = true
        }
        available
    }

    /** Canonicalise root-level package dirs into the Termux-standard usr/ prefix layout. */
    private suspend fun canonicaliseTreeLayout() = withContext(Dispatchers.IO) {
        try { TermuxCanonicaliser.canonicalise(termuxRoot) } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: canonicalise failed: ${e.message}")
        }
    }

    /**
     * Clean recovery: wipe the termux root except home/, then re-extract the
     * bundled bootstrap. home/ is the human's own directory (their scripts,
     * notes); everything else rebuilds from assets which hold the full payload.
     */
    private fun fullCleanAndExtract(): Boolean {
        try {
            // Preserve home/
            val home = File(termuxRoot, HOME_DIR)
            val homeContents = home.listFiles()?.let { items ->
                items.filter { it.name != "termux" }.mapNotNull { file ->
                    try { file.absolutePath to file.isDirectory } catch (e: Exception) { null }
                }
            } ?: emptyList()
            // Wipe everything except the termux/home dir itself
            termuxRoot.listFiles()?.forEach { child ->
                if (child.name != HOME_DIR) {
                    when {
                        child.isDirectory && child.name != HOME_DIR -> {
                            if (child.absolutePath != home.absolutePath) {
                                child.deleteRecursively()
                            }
                        }
                        else -> child.delete()
                    }
                }
            }
            Log.d(TAG, "TermuxProvider: clean wipe done (home preserved); re-extracting")
            val extracted = extractBootstrap()
            if (extracted) {
                // Reset latches so this process re-runs every post-install chain step
                symlinksCreated = false
                Log.d(TAG, "TermuxProvider: clean re-extract completed")
            }
            return extracted
        } catch (e: Exception) {
            Log.e(TAG, "TermuxProvider: clean re-extract failed", e)
            return false
        }
    }

    // Lazy — detect linker64/32 once
    private val linker by lazy {
        val is64Bit = System.getProperty("os.arch")?.contains("64") ?: true
        if (is64Bit) "/system/bin/linker64" else "/system/bin/linker"
    }

    /**
     * Whether the prefix is installed, answered from DISK rather than from
     * this instance's own memory.
     *
     * THE DEFECT THIS FIXES, reported by GURU on a fresh v3.9 install. `available`
     * was an instance field defaulting to false and only set by [initialize] on
     * the SAME object. Application boot constructs one provider and installs the
     * prefix through it; TermuxToolExecutor constructs a SECOND provider, whose
     * latch was still false. termuxStatus therefore answered
     * {"success":false} and GURU confidently reported "Termux isn't installed"
     * while a verified prefix, bash and git 2.56.0 sat on disk.
     *
     * "Is it installed" is a fact about the filesystem, not about which object
     * asked. The latch stays for cheap short-circuiting inside [initialize], but
     * any caller asking the public question gets the real answer.
     */
    fun isAvailable(): Boolean {
        if (available) return true
        // A real prefix has a populated bin directory. Anything else is not
        // installed, and this cannot produce a false positive from a partial
        // extract because initialize() only clears the wiped-tree case when bin
        // is empty.
        val installed = binDir.isDirectory && binDir.listFiles()?.isNotEmpty() == true
        if (installed) available = true
        return installed
    }

    fun isInitialized(): Boolean = initialized || isAvailable()

    fun getRootPath(): String = termuxRoot.absolutePath
    fun getHomePath(): String = homeDir.absolutePath
    fun getTmpPath(): String = tmpDir.absolutePath
    fun getBinPath(): String = binDir.absolutePath
    fun getLibPath(): String = libDir.absolutePath
    fun getLinkerPath(): String = linker

    // RE tools directory — same level as termux root
    private val reToolsDir: File
        get() = File(context.filesDir, "re-tools")

    // PATH search order: termux usr/bin, re-tools, system
    private val searchPath: List<File>
        get() = listOf(binDir, reToolsDir, File("/system/bin"), File("/system/xbin"))

    // Shell metacharacters that signal "this needs bash -c"
    private val shellMetaChars = Regex("""[;|&<>]""")

    // Shared env map reused across all ProcessBuilder calls
    private fun buildEnv(): MutableMap<String, String> {
        val env = mutableMapOf(
            "PREFIX" to usrPrefix.absolutePath,
            "HOME" to homeDir.absolutePath,
            "TMPDIR" to tmpDir.absolutePath,
            "PATH" to "${binDir.absolutePath}:${reToolsDir.absolutePath}:/system/bin:/system/xbin",
            "LD_LIBRARY_PATH" to "${libDir.absolutePath}:${reToolsDir.absolutePath}/lib",
            // libtermux-exec reads these env vars to override its compiled-in defaults.
            "TERMUX__ROOTFS" to termuxRoot.absolutePath,
            "TERMUX__PREFIX" to usrPrefix.absolutePath,
            "TERMUX_PREFIX" to usrPrefix.absolutePath,
            "TERMUX_HOME" to homeDir.absolutePath,
            "TERMUX_APP__DATA_DIR" to context.filesDir.absolutePath,
            "TERMUX_APP__LEGACY_DATA_DIR" to "/data/data/${context.packageName}",
            "TERMUX_APP_PACKAGE" to context.packageName,
            "TERMUX_ANDROID_HOME" to homeDir.absolutePath
        )
        // LD_PRELOAD: load libtermux-exec ONLY when its file actually exists in the
        // canonicalised tree. Pointing LD_PRELOAD at an absent path makes Android's
        // dynamic linker refuse every linked launch with CANNOT LINK EXECUTABLE.
        val preload = File(libDir, "libtermux-exec-ld-preload.so")
        if (preload.exists()) {
            env["LD_PRELOAD"] = preload.absolutePath
        }
        // SSL/TLS: apt and curl need to find CA certificates
        val certPem = File(etcDir, "tls/cert.pem")
        if (certPem.exists()) {
            env["SSL_CERT_FILE"] = certPem.absolutePath
            env["CA_CERT_FILE"] = certPem.absolutePath
            env["REQUESTS_CA_BUNDLE"] = certPem.absolutePath
            env["GIT_SSL_CAINFO"] = certPem.absolutePath
        }
        /*
         * apt reads its configuration from a compiled-in com.termux path unless
         * APT_CONFIG names a different file. That path does not exist inside
         * this app's sandbox, so without this variable apt cannot find its own
         * parts directory and every invocation dies with:
         *
         *   W: Unable to read /data/data/com.termux/files/usr/etc/apt/apt.conf.d/
         *   E: Unable to determine a suitable packaging system type
         *
         * and exit code 100 — INCLUDING `apt-get --version`, which is the probe
         * [aptRuns] uses. That is the whole of issue 30: the probe ran apt
         * without the config, apt exited 100 before printing a version, and the
         * diagnosis path turned that into "apt-get not present in the prefix"
         * against a binary that was present and readable.
         *
         * [execAptInstall] and [execAptUpdate] already exported this inside
         * their own scripts, so the tool paths worked while a human typing
         * `apt update` in termuxExec got the broken default. Setting it here
         * makes the config a property of the environment rather than of two
         * hand-written scripts, which is where it belonged.
         */
        val aptConfig = File(File(etcDir, "apt"), "apt.conf.d/01-root.conf")
        if (aptConfig.exists()) {
            env["APT_CONFIG"] = aptConfig.absolutePath
        }
        return env
    }

    /**
     * Execute a command inside the Termux environment.
     *
     * Binary lookup searches PATH in order: termux/usr/bin, re-tools, system/bin, system/xbin.
     * Shell metacharacters (; | && || > <) trigger automatic bash -c wrapping.
     * Uses /system/bin/linker64 for binaries on noexec filesystems (/data),
     * direct execution for binaries on /system.
     */
    fun execInTermux(command: String): ShellResult {
        // Auto-detect shell syntax — semicolons, pipes, redirects, conditionals
        if (shellMetaChars.containsMatchIn(command)) {
            return execBash(command)
        }

        val parts = parseCommand(command)
        if (parts.isEmpty()) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "empty command", success = false)
        }

        val binaryName = parts[0]
        val args = if (parts.size > 1) parts.drop(1) else emptyList()
        val resolved = resolveBinary(binaryName)

        if (resolved == null) {
            return ShellResult(
                exitCode = -1, stdout = "",
                stderr = "command not found: $binaryName (searched termux/usr/bin, re-tools, /system/bin)",
                success = false
            )
        }

        val isOnSystem = resolved.startsWith("/system/")
        val processArgs = if (isOnSystem) {
            mutableListOf(resolved).also { it.addAll(args) }
        } else {
            // linker64 loads ELF from noexec /data, linker searches binary's dir for .so
            mutableListOf(linker, resolved).also { it.addAll(args) }
        }

        return execWithEnv(processArgs)
    }

    /**
     * Run a command through bash. For piped commands, shell scripts, or anything
     * with shell metacharacters.
     * linker64 /data/.../termux/bin/bash -c "..."
     */
    fun execBash(script: String): ShellResult {
        val bash = File(binDir, "bash")
        if (!bash.exists() || !bash.canRead()) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "bash not found in termux/usr/bin/", success = false)
        }
        return execWithEnv(listOf(linker, bash.absolutePath, "-c", script))
    }

    /**
     * Install packages via apt.
     *
     * apt is compiled against `/data/data/com.termux/files/usr`, so every path
     * it knows has to be redirected at this prefix. The redirect lives in TWO
     * places on purpose:
     *
     *  - `etc/apt/apt.conf.d/01-root.conf`, written by [initAptConfig], which is
     *    what any other caller running `apt` gets.
     *  - `APT_CONFIG` in the environment for THIS call, pointing at that file,
     *    so apt reads our configuration before any compiled-in default. Without
     *    it apt starts from the com.termux root, cannot find its parts
     *    directory, and prints "Unable to determine a suitable packaging system
     *    type" before a single -o flag is consulted.
     *
     * The two -o flags are kept for the paths apt resolves most eagerly, but the
     * config file is the mechanism now: a pile of -o arguments repeated per call
     * is how this drifted out of step in the first place.
     */
    fun execAptInstall(packages: String): ShellResult {
        val bash = File(binDir, "bash")
        if (!bash.exists() || !bash.canRead()) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "bash not found", success = false)
        }
        val prefix = usrPrefix.absolutePath
        val aptConfig = File(File(etcDir, "apt"), "apt.conf.d/01-root.conf").absolutePath
        val aptBinary = File(binDir, "apt").absolutePath
        val aptEnv = "export APT_CONFIG='$aptConfig'; " +
            "export PATH='${binDir.absolutePath}:/system/bin'; "
        // dpkg's own compiled-in admin dir is the com.termux path, so every
        // dpkg invocation inside apt gets an explicit one.
        val dpkgArgs = "-o Dir::Bin::dpkg='$prefix/bin/dpkg' " +
            "-o Dir::Bin::methods='$prefix/lib/apt/methods' " +
            "-o DPkg::Options::='--admindir=$prefix/var/lib/dpkg' " +
            "-o DPkg::Options::='--instdir=${termuxRoot.absolutePath}'"

        val fullScript = aptEnv + "\"$aptBinary\" $dpkgArgs update 2>&1; " +
            aptEnv + "\"$aptBinary\" $dpkgArgs install -y $packages 2>&1"
        return execWithEnv(listOf(linker, bash.absolutePath, "-c", fullScript))
    }

    /**
     * Whether apt can actually OPERATE, rather than merely existing.
     *
     * Issue 4 named this defect: `termuxDiagnose` reported `aptWorking: true`
     * because it only checked the binary executed, and a binary that runs and
     * then dies with exit 100 on every operation is not a working package
     * manager. Claiming otherwise is worse than saying nothing, because it
     * tells the model a capability exists when it does not.
     *
     * This asks apt to load and answer `--version`, which proves the ELF, its
     * libraries and dpkg's architecture are all readable. It does not prove a
     * repository is reachable; [execAptUpdate] is the test for that.
     */
    fun aptRuns(): Boolean = aptProbe().runs

    /**
     * What apt actually did when asked to identify itself.
     *
     * Issue 30 asked for this to stop being a bare boolean. "The binary is not
     * there" and "the binary is there and died" are different faults with
     * different fixes, and collapsing them let the diagnosis path report a
     * missing binary on a device where the binary was present, readable and
     * merely misconfigured. A boolean cannot carry that distinction, so this
     * carries the exit code and the real output instead and lets the caller
     * decide what to say.
     */
    data class AptProbe(
        /** The apt-get file exists and is readable. */
        val present: Boolean,
        /** The binary loaded and printed a version, exiting 0. */
        val runs: Boolean,
        /** Exit code from the probe, or null when the probe never ran. */
        val exitCode: Int?,
        /** First line of stderr (or stdout when stderr is empty), for the report. */
        val detail: String,
    )

    fun aptProbe(): AptProbe {
        val aptGet = File(binDir, "apt-get")
        if (!aptGet.exists() || !aptGet.canRead()) {
            return AptProbe(
                present = false,
                runs = false,
                exitCode = null,
                detail = "apt-get not found at ${aptGet.absolutePath}",
            )
        }
        val result = execWithEnv(listOf(linker, aptGet.absolutePath, "--version"))
        val runs = result.exitCode == 0 && result.stdout.contains("apt")
        val detail = if (runs) {
            result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
        } else {
            val message = result.stderr.ifBlank { result.stdout }.trim()
            if (message.isBlank()) "apt-get --version exited ${result.exitCode} with no output"
            else message.lineSequence().take(3).joinToString(" | ").trim()
        }
        return AptProbe(present = true, runs = runs, exitCode = result.exitCode, detail = detail)
    }

    /**
     * Run `apt update` and return the raw result.
     *
     * Separate from [execAptInstall] so a diagnosis path can report exactly what
     * apt said without implying an install happened, and so a caller can tell
     * "cannot reach the repository" from "cannot parse its own config" by
     * reading real output rather than a boolean.
     */
    fun execAptUpdate(): ShellResult {
        val bash = File(binDir, "bash")
        if (!bash.exists() || !bash.canRead()) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "bash not found", success = false)
        }
        val prefix = usrPrefix.absolutePath
        val aptConfig = File(File(etcDir, "apt"), "apt.conf.d/01-root.conf").absolutePath
        val script = "export APT_CONFIG='$aptConfig'; " +
            "export PATH='${binDir.absolutePath}:/system/bin'; " +
            "apt-get -o Dir::Bin::dpkg='$prefix/bin/dpkg' " +
            "-o Dir::Bin::methods='$prefix/lib/apt/methods' update 2>&1"
        return execWithEnv(listOf(linker, bash.absolutePath, "-c", script))
    }

    fun hasCommand(name: String): Boolean {
        return resolveBinary(name) != null
    }

    // --- Private execution helpers ---

    /**
     * Search for a binary across all known PATH directories.
     * Returns absolute path or null.
     */
    private fun resolveBinary(name: String): String? {
        for (dir in searchPath) {
            val f = File(dir, name)
            if (f.exists() && f.canRead()) return f.absolutePath
        }
        return null
    }

    /**
     * Shared ProcessBuilder execution with the standard Termux environment.
     */
    private fun execWithEnv(cmd: List<String>): ShellResult {
        return try {
            val pb = ProcessBuilder(cmd)
            pb.environment().putAll(buildEnv())
            pb.directory(homeDir)
            pb.redirectErrorStream(false)

            val proc = pb.start()
            val stdout = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val stderr = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
            val exitCode = proc.waitFor()

            ShellResult(
                exitCode = exitCode,
                stdout = stdout.trim(),
                stderr = stderr.trim(),
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

    // --- Private helpers ---

    /**
     * Parse a command string respecting single and double quotes.
     * "bash -c 'echo hello'" becomes ["bash", "-c", "echo hello"].
     */
    private fun parseCommand(raw: String): List<String> {
        val parts = mutableListOf<String>()
        val current = StringBuilder()
        var inSingle = false
        var inDouble = false
        var i = 0

        while (i < raw.length) {
            val c = raw[i]
            when {
                c == '\'' && !inDouble -> inSingle = !inSingle
                c == '"' && !inSingle -> inDouble = !inDouble
                c == '\\' && i + 1 < raw.length -> {
                    current.append(raw[i + 1])
                    i++
                }
                c.isWhitespace() && !inSingle && !inDouble -> {
                    if (current.isNotEmpty()) {
                        parts.add(current.toString())
                        current.clear()
                    }
                }
                else -> current.append(c)
            }
            i++
        }
        if (current.isNotEmpty()) parts.add(current.toString())

        return parts
    }

    /**
     * Create versioned library symlinks so the ELF loader can find them.
     * libreadline.so.8.3 -> libreadline.so.8
     * libncursesw.so.6.5 -> libncursesw.so.6
     * libhistory.so.8.3 -> libhistory.so.8
     * etc.
     */
    private fun createLibrarySymlinks() {
        if (!libDir.exists()) return

        var linkCount = 0
        libDir.listFiles()?.forEach { file ->
            if (!file.isFile) return@forEach
            val name = file.name

            // Three-segment versions (libbz2.so.1.0.8) deserve TWO more forms:
            // the two-segment name Termux ELF binaries link against
            // (libbz2.so.1.0), AND the plain major (libbz2.so.1).
            val fullVersionPattern = Regex("""^(.+\.so)\.(\d+)\.(\d+)\.(\d+.*)$""")
            fullVersionPattern.find(name)?.let { match3 ->
                val baseName3 = match3.groupValues[1]
                val majorOnly = "$baseName3.${match3.groupValues[2]}"
                val majorMinor = "$baseName3.${match3.groupValues[2]}.${match3.groupValues[3]}"
                for (variant in listOf(majorOnly, majorMinor)) {
                    val linkFile = File(libDir, variant)
                    if (!linkFile.exists()) {
                        try {
                            android.system.Os.symlink(name, linkFile.absolutePath)
                            linkCount++
                        } catch (_: Exception) {}
                    }
                }
                if (!File(libDir, baseName3).exists()) {
                    try {
                        android.system.Os.symlink(name, File(libDir, baseName3).absolutePath)
                        linkCount++
                    } catch (_: Exception) {}
                }
                return@forEach
            }

            // Match: libfoo.so.MAJOR.MINOR or libfoo.so.MAJOR.MINOR.PATCH
            val versionedPattern = Regex("""^(.+\.so)\.(\d+)\.(\d+.*)$""")
            val match = versionedPattern.find(name) ?: return@forEach
            val baseName = match.groupValues[1]       // libreadline.so
            val major = match.groupValues[2]          // 8
            val majorLink = "$baseName.$major"        // libreadline.so.8

            val majorLinkFile = File(libDir, majorLink)
            if (!majorLinkFile.exists()) {
                try {
                    android.system.Os.symlink(name, majorLinkFile.absolutePath)
                    linkCount++
                } catch (_: Exception) {}
            }

            // Also create libfoo.so link if it doesn't exist
            val plainLinkFile = File(libDir, baseName)
            if (!plainLinkFile.exists()) {
                try {
                    android.system.Os.symlink(name, plainLinkFile.absolutePath)
                    linkCount++
                } catch (_: Exception) {}
            }
        }

        if (linkCount > 0) {
            Log.d(TAG, "TermuxProvider: created $linkCount library symlinks in ${libDir.absolutePath}")
        }
    }

    /**
     * Copy bundled Termux library .so files and patchelf from APK assets into termux/.
     * After extraction, patches all native RE binaries' RPATH so the linker
     * searches our library directories without needing env vars or linker flags.
     */
    private fun copyBundledLibs() {
        val assetDir = "termux-libs"
        val libs: List<String> = try {
            context.assets.list(assetDir)?.toList() ?: emptyList()
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: failed to list bundled libs: ${e.message}")
            emptyList()
        }

        if (libs.isEmpty()) {
            Log.d(TAG, "TermuxProvider: no bundled libs in assets/$assetDir")
            return
        }

        var count = 0
        for (filename in libs) {
            // patchelf goes in bin/ so it's on PATH for patchElfBinaries()
            val dest = if (filename == "patchelf") {
                File(binDir, filename)
            } else {
                File(libDir, filename)
            }

            if (dest.exists() && dest.length() > 0) continue

            try {
                context.assets.open("$assetDir/$filename").use { input ->
                    FileOutputStream(dest).use { output ->
                        input.copyTo(output)
                    }
                }
                dest.setReadable(true, false)
                if (filename == "patchelf") {
                    dest.setExecutable(true, false)
                }
                count++
            } catch (e: Exception) {
                Log.w(TAG, "TermuxProvider: failed to copy bundled $filename: ${e.message}")
                if (dest.exists()) dest.delete()
            }
        }

        if (count > 0) {
            Log.d(TAG, "TermuxProvider: copied $count bundled files from assets/termux-libs/")
        }
    }

    /**
     * Patch RPATH on all native RE tool binaries so the linker finds our
     * libraries without needing LD_LIBRARY_PATH or --library-path flags.
     * Uses the bundled patchelf binary extracted into termux/bin/.
     */
    private fun patchElfBinaries() {
        val patchelfBin = File(binDir, "patchelf")
        if (!patchelfBin.exists() || !patchelfBin.canExecute()) {
            Log.w(TAG, "TermuxProvider: patchelf not available, skipping ELF RPATH patching")
            return
        }

        val rpath = "${libDir.absolutePath}:${reToolsDir.absolutePath}/lib"
        val nativeBins = listOf(
            File(reToolsDir, "aapt"),
            File(reToolsDir, "aapt2"),
            File(reToolsDir, "zipalign")
        )

        var patched = 0
        for (bin in nativeBins) {
            if (!bin.exists() || !bin.canRead()) continue

            try {
                val pb = ProcessBuilder(
                    linker, patchelfBin.absolutePath,
                    "--set-rpath", rpath,
                    bin.absolutePath
                )
                pb.environment().apply {
                    put("LD_LIBRARY_PATH", libDir.absolutePath)
                }
                val proc = pb.start()
                val stderr = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
                val exitCode = proc.waitFor()

                if (exitCode == 0) {
                    patched++
                    Log.d(TAG, "TermuxProvider: patchelf --set-rpath $rpath ${bin.name}")
                } else {
                    Log.w(TAG, "TermuxProvider: patchelf failed on ${bin.name}: $stderr")
                }
            } catch (e: Exception) {
                Log.w(TAG, "TermuxProvider: patchelf error on ${bin.name}: ${e.message}")
            }
        }

        // Also patch termux bash so it finds its own libs
        val bash = File(binDir, "bash")
        if (bash.exists() && bash.canRead()) {
            try {
                val pb = ProcessBuilder(
                    linker, patchelfBin.absolutePath,
                    "--set-rpath", libDir.absolutePath,
                    bash.absolutePath
                )
                pb.environment().apply {
                    put("LD_LIBRARY_PATH", libDir.absolutePath)
                }
                val proc = pb.start()
                val exitCode = proc.waitFor()
                if (exitCode == 0) patched++
            } catch (_: Exception) {}
        }

        if (patched > 0) {
            Log.d(TAG, "TermuxProvider: patched RPATH on $patched ELF binaries")
        }
    }

    /**
     * Install the bundled Termux git runtime into this prefix.
     *
     * Why the runtime lands here: the git tool family needs a git built with an
     * HTTP transport. Termux's git 2.56.0 links libcurl and ships the real
     * git-remote-http helper, and every library both binaries name as NEEDED
     * (libcurl, libssl, libcrypto, libpcre2-8, libz, libiconv, libnghttp2/3,
     * libngtcp2, libssh2) already exists in this prefix's lib/ from the
     * bootstrap. So the zip carries only bin/git, that one helper, and the
     * clone templates.
     *
     * Idempotent and honest about failure:
     *  - extraction skips any file already present with content, so an
     *    interrupted pass resumes rather than restarting;
     *  - the marker is written only after bin/git has actually executed and
     *    printed a version, so a half-installed tree retries on the next init;
     *  - a missing asset, missing patchelf or a git that will not run leaves
     *    the marker absent and logs what is missing; GitProvider then reports
     *    the absence to the caller instead of pretending a binary exists.
     */
    private fun installGitRuntime() {
        val marker = File(usrPrefix, GIT_RUNTIME_MARKER)
        val gitBinary = File(binDir, "git")

        if (marker.exists() && gitBinary.isFile && gitBinary.length() > 0) return

        val assetPresent = try {
            context.assets.list("git-termux")?.contains("git-termux.zip") == true
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: git-termux asset listing failed: ${e.message}")
            false
        }
        if (!assetPresent) {
            Log.w(TAG, "TermuxProvider: $ASSET_GIT_RUNTIME not bundled; git tools will report it missing")
            return
        }

        // Extract into usr/ so bin/, libexec/ and share/ land at their Termux
        // paths, next to the lib/ this runtime resolves against.
        val tempZip = File(usrPrefix, "git-termux-temp.zip")
        try {
            Log.d(TAG, "TermuxProvider: installing git runtime from $ASSET_GIT_RUNTIME")
            context.assets.open(ASSET_GIT_RUNTIME).use { input ->
                FileOutputStream(tempZip).use { output -> input.copyTo(output) }
            }

            var extracted = 0
            ZipFile(tempZip).use { zip ->
                val entries = zip.entries()
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val cleanName = entry.name.removePrefix("./")
                    if (cleanName.isEmpty()) continue

                    val entryFile = File(usrPrefix, cleanName)

                    // Defend the prefix boundary: a crafted entry name must
                    // never escape usr/.
                    val canonicalTarget = entryFile.canonicalFile
                    val canonicalPrefix = usrPrefix.canonicalFile
                    if (!canonicalTarget.path.startsWith(canonicalPrefix.path + File.separator) &&
                        canonicalTarget.path != canonicalPrefix.path
                    ) {
                        Log.w(TAG, "TermuxProvider: refusing git runtime entry outside prefix: $cleanName")
                        continue
                    }

                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                        continue
                    }

                    // git-remote-https is a symlink in the package and is stored
                    // as one in the zip. Recreate it as a symlink pointing at its
                    // sibling helper; copying the link bytes as a file would
                    // produce a 15-byte "binary" git cannot exec.
                    if (entryFile.name == "git-remote-https") {
                        if (!entryFile.exists()) {
                            entryFile.parentFile?.mkdirs()
                            try {
                                android.system.Os.symlink("git-remote-http", entryFile.absolutePath)
                            } catch (e: Exception) {
                                Log.w(TAG, "TermuxProvider: failed to create git-remote-https symlink: ${e.message}")
                            }
                        }
                        continue
                    }

                    if (entryFile.isFile && entryFile.length() > 0) continue

                    entryFile.parentFile?.mkdirs()
                    zip.getInputStream(entry).use { entryInput ->
                        FileOutputStream(entryFile).use { entryOutput ->
                            entryInput.copyTo(entryOutput)
                        }
                    }
                    extracted++
                }
            }

            // Execute bits: bin/git itself and every file under libexec/git-core.
            gitBinary.setExecutable(true, false)
            gitBinary.setReadable(true, false)
            File(usrPrefix, "libexec/git-core").walkTopDown().forEach { file ->
                if (file.isFile) {
                    file.setExecutable(true, false)
                    file.setReadable(true, false)
                }
            }

            // The package's RUNPATH is /data/data/com.termux/files/usr/lib, which
            // does not exist here. Repoint both ELFs at this prefix's lib/, using
            // the bundled patchelf through the system linker exactly like
            // patchElfBinaries() does.
            val patchelfBin = File(binDir, "patchelf")
            val remoteHttp = File(usrPrefix, "libexec/git-core/git-remote-http")
            if (!patchelfBin.exists() || !patchelfBin.canExecute()) {
                Log.w(TAG, "TermuxProvider: patchelf unavailable; git runtime RPATH left unpatched")
            } else {
                for (binary in listOf(gitBinary, remoteHttp)) {
                    if (!binary.exists() || !binary.canRead()) continue
                    try {
                        val pb = ProcessBuilder(
                            linker, patchelfBin.absolutePath,
                            "--set-rpath", libDir.absolutePath,
                            binary.absolutePath
                        )
                        pb.environment().apply {
                            put("LD_LIBRARY_PATH", libDir.absolutePath)
                        }
                        val proc = pb.start()
                        val stderr = BufferedReader(InputStreamReader(proc.errorStream)).use { it.readText() }
                        val exitCode = proc.waitFor()
                        if (exitCode == 0) {
                            Log.d(TAG, "TermuxProvider: patched git RPATH ${binary.name} -> ${libDir.absolutePath}")
                        } else {
                            Log.w(TAG, "TermuxProvider: patchelf failed on ${binary.name}: $stderr")
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "TermuxProvider: patchelf error on ${binary.name}: ${e.message}")
                    }
                }
            }

            // Verify by running it. This is the whole gate: a version string on
            // stdout is the only thing that may write the marker.
            val versionOutput = runGitVersionProbe(remoteHttp)
            if (versionOutput != null) {
                marker.writeText(
                    "tree=$ASSET_GIT_RUNTIME;extracted=$extracted;version=$versionOutput\n"
                )
                Log.d(TAG, "TermuxProvider: git runtime installed and verified ($versionOutput, $extracted files)")
            } else {
                Log.w(TAG, "TermuxProvider: git runtime present but bin/git did not run; marker withheld")
            }
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: git runtime install failed: ${e.message}")
        } finally {
            tempZip.delete()
        }
    }

    /**
     * Run the freshly installed git with --version through the system linker,
     * with the same environment GitProvider uses, and return its version line
     * when it genuinely executed. Null means it did not run.
     */
    private fun runGitVersionProbe(remoteHttp: File): String? {
        val gitBinary = File(binDir, "git")
        if (!gitBinary.exists() || !gitBinary.canRead()) return null
        if (!remoteHttp.exists()) return null

        return try {
            val pb = ProcessBuilder(linker, gitBinary.absolutePath, "--version")
            pb.environment().putAll(buildEnv())
            pb.directory(homeDir)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val output = BufferedReader(InputStreamReader(proc.inputStream)).use { it.readText() }
            val exitCode = proc.waitFor()
            val versionLine = output.lineSequence().firstOrNull { it.contains("git version") }?.trim()
            if (exitCode == 0 && versionLine != null) versionLine else null
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: git version probe failed: ${e.message}")
            null
        }
    }

    /**
     * Copy all .so files from termux/lib/ into re-tools/lib/.
     * The Android linker searches the binary's own directory for .so files
     * before looking at RPATH/LD_LIBRARY_PATH. By placing copies alongside
     * the RE binaries we ensure they're found even if RPATH patching fails.
     */
    private fun copyLibsToReToolsDir() {
        val reToolsLibDir = File(reToolsDir, "lib")
        reToolsLibDir.mkdirs()

        var count = 0
        libDir.listFiles()?.forEach { file ->
            if (!file.isFile || !file.name.endsWith(".so") && !file.name.contains(".so.")) return@forEach
            val dest = File(reToolsLibDir, file.name)
            if (dest.exists()) return@forEach
            try {
                file.copyTo(dest)
                dest.setReadable(true, false)
                count++
            } catch (_: Exception) {}
        }

        if (count > 0) {
            Log.d(TAG, "TermuxProvider: copied $count libs to ${reToolsLibDir.absolutePath}")
        }
    }

    /**
     * Write apt configuration so apt works from our custom prefix.
     * Apt is compiled with /data/data/com.termux/files/usr hardcoded.
     * We redirect everything to our actual termuxRoot via an apt.conf.d drop-in.
     *
     * The bootstrap zip already includes a valid sources.list pointing to
     * packages-cf.termux.dev (cloudflare cached). We preserve that and only
     * add our root redirect config.
     */
    private fun initAptConfig() {
        try {
            // Preserve the bootstrap's sources.list — it has the correct URLs.
            // Only write one if it doesn't exist (e.g. bootstrap was already extracted
            // but sources.list got deleted somehow).
            val sourcesList = File(etcDir, "apt/sources.list")
            if (!sourcesList.exists()) {
                sourcesList.parentFile?.mkdirs()
                sourcesList.writeText(
                    "deb https://packages-cf.termux.dev/apt/termux-main/ stable main\n"
                )
            }

            val aptConfDir = File(etcDir, "apt/apt.conf.d")
            aptConfDir.mkdirs()

            /*
             * ALWAYS REWRITTEN, never skipped when present.
             *
             * Two faults lived here. Every line was written without a
             * terminating semicolon, and apt's config parser rejects a bare
             * statement: "Syntax error ... Extra junk at end of file". So the
             * file meant to point apt at this prefix could not be parsed at
             * all, and apt fell back to its compiled-in com.termux defaults.
             *
             * And it was only written when absent, so every tree installed by
             * an earlier build kept the unparseable copy forever. Rewriting
             * unconditionally is one small file and makes the content a
             * property of this build rather than the install date.
             */
            val rootConf = File(aptConfDir, "01-root.conf")
            val prefix = usrPrefix.absolutePath
            rootConf.writeText(
                listOf(
                    "Dir \"$prefix\";",
                    "Dir::State \"$prefix/var/lib/apt\";",
                    "Dir::State::lists \"$prefix/var/lib/apt/lists\";",
                    "Dir::Cache \"$prefix/var/cache/apt\";",
                    "Dir::Log \"$prefix/var/log/apt\";",
                    "Dir::Etc \"${etcDir.absolutePath}/apt\";",
                    "Dir::Etc::sourcelist \"${etcDir.absolutePath}/apt/sources.list\";",
                    "Dir::Etc::sourceparts \"${etcDir.absolutePath}/apt/sources.list.d\";",
                    "Dir::Etc::parts \"${aptConfDir.absolutePath}\";",
                    "Dir::Etc::trustedparts \"${etcDir.absolutePath}/apt/trusted.gpg.d\";",
                    "Dir::Bin::dpkg \"$prefix/bin/dpkg\";",
                    "Dir::Bin::methods \"$prefix/lib/apt/methods\";",
                    "Dir::Bin::gpgv \"$prefix/bin/gpgv\";",
                    // apt delegates repository signature checking to apt-key and
                    // resolves it from this key. Its compiled-in default is the
                    // com.termux path, which does not exist here, so without this
                    // line every repository reads as "not signed" no matter how
                    // correct the keyring is.
                    "Dir::Bin::apt-key \"$prefix/bin/apt-key\";",
                    /*
                     * dpkg's two roots, and both are load-bearing.
                     *
                     * dpkg's compiled-in admin dir is the com.termux path, so
                     * --admindir is mandatory or it fails with "cannot create
                     * the dpkg database directory ... Permission denied".
                     *
                     * --instdir is the TERMUX ROOT, not the prefix and not /.
                     * A Termux package records its payload as
                     * `./data/data/com.termux/files/usr/bin/figlet`. Relative to
                     * instdir=/ that tries to create /data/data/com.termux and
                     * the sandbox denies it; relative to instdir=<prefix> it
                     * lands four directories too deep. Relative to
                     * instdir=<termuxRoot> it resolves through the compatibility
                     * symlink at <termuxRoot>/data/data/com.termux/files/usr
                     * straight into the real prefix. There is no other value
                     * that both satisfies the sandbox and lands the file where
                     * the app can run it.
                     */
                    "DPkg::Options:: \"--admindir=$prefix/var/lib/dpkg\";",
                    "DPkg::Options:: \"--instdir=${termuxRoot.absolutePath}\";",
                    "APT::Sandbox::User \"root\";",
                    "Acquire::https::CaInfo \"${etcDir.absolutePath}/tls/cert.pem\";",
                    "DPkg::Path \"$prefix/bin\";",
                ).joinToString("\n", postfix = "\n")
            )

            // Ensure state directories exist
            File(usrPrefix, "var/lib/apt/lists").mkdirs()
            File(usrPrefix, "var/cache/apt/archives/partial").mkdirs()
            File(usrPrefix, "var/log/apt").mkdirs()

            /*
             * The keyring directory, populated from the keys the bootstrap
             * already ships in share/termux-keyring/. Nothing links them into
             * place: the package postinst that would do it never runs, because
             * a postinst is a script and the shell it needs is the very thing
             * missing. So trusted.gpg.d arrives empty and apt has no key to
             * verify a repository against. Copy what is already on disk instead
             * of trusting an install step that cannot execute.
             */
            val trustedDir = File(etcDir, "apt/trusted.gpg.d")
            trustedDir.mkdirs()
            var keyCount = 0
            File(usrPrefix, "share/termux-keyring").listFiles()?.forEach { key ->
                if (!key.isFile || !key.name.endsWith(".gpg")) return@forEach
                val dest = File(trustedDir, key.name)
                if (dest.exists() && dest.length() == key.length()) return@forEach
                if (runCatching { key.copyTo(dest, overwrite = true) }.isSuccess) {
                    dest.setReadable(true, false)
                    keyCount++
                }
            }
            if (keyCount > 0) {
                Log.d(TAG, "TermuxProvider: installed $keyCount repo signing keys into ${trustedDir.absolutePath}")
            }

            Log.d(TAG, "TermuxProvider: apt config written to ${aptConfDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: apt config init failed: ${e.message}")
        }
    }

    /**
     * Ensure the apt transport method drivers exist at prefix/lib/apt/methods/
     * and that the https variant is present (some bootstraps only ship http).
     * Under the unified layout source and target are the same tree, so the
     * old copy pass reduced to a no-op and only this check remains.
     */
    private fun fixAptMethods() {
        val methodsDir = File(usrDir, "lib/apt/methods")
        methodsDir.mkdirs()

        val httpsMethod = File(methodsDir, "https")
        if (!httpsMethod.exists()) {
            val httpMethod = File(methodsDir, "http")
            if (httpMethod.exists()) {
                try {
                    httpMethod.copyTo(httpsMethod)
                    httpsMethod.setExecutable(true, false)
                    httpsMethod.setReadable(true, false)
                    Log.d(TAG, "TermuxProvider: created https apt method from http method")
                } catch (e: Exception) {
                    Log.w(TAG, "TermuxProvider: failed to create https method: ${e.message}")
                }
            }
        }
    }

    /**
     * Remove old wrapper scripts created by previous versions of TermuxProvider.
     * These wrappers used shebangs pointing to app-private storage which Android
     * SELinux blocks with "bad interpreter: Permission denied". The proper fix
     * is using LD_PRELOAD with libtermux-exec and correct env vars, not wrappers.
     */
    private fun removeOldAptWrappers() {
        val pairs = listOf(
            File(binDir, "apt") to File(binDir, "apt.real"),
            File(binDir, "apt-get") to File(binDir, "apt-get.real"),
            File(binDir, "dpkg") to File(binDir, "dpkg.real")
        )

        for ((wrapper, real) in pairs) {
            // If wrapper is a script (not ELF) and real exists, restore original
            if (real.exists()) {
                if (wrapper.exists()) {
                    wrapper.delete()
                }
                real.renameTo(wrapper)
                wrapper.setExecutable(true, false)
                Log.d(TAG, "TermuxProvider: removed old wrapper, restored ${wrapper.name}")
            }
        }
    }

    // --- Extraction ---

    /**
     * Build the compatibility root that lets dpkg install upstream-prefixed
     * packages into THIS prefix.
     *
     * WHY THIS IS NEEDED, and why it is structural rather than a workaround.
     * A Termux `.deb` records its payload paths absolute to its own prefix:
     *
     *   ./data/data/com.termux/files/usr/bin/figlet
     *
     * dpkg unpacks those relative to its instdir. Every choice of instdir is
     * therefore wrong on its own:
     *
     *   --instdir=/          -> tries to create /data/data/com.termux, denied
     *                           by the sandbox (that path belongs to another app)
     *   --instdir=<prefix>   -> creates <prefix>/data/data/com.termux/files/usr/
     *                           bin/figlet, four directories too deep, and apt
     *                           still reports success because dpkg exited 0
     *
     * The missing piece is a mapping between the two layouts. This creates the
     * nested directory chain `<termuxRoot>/data/data/com.termux/files` and one
     * symlink `.../files/usr -> <termuxRoot>/usr`. With that root in place,
     * dpkg's instdir is the termux root itself and a package path of
     * `data/data/com.termux/files/usr/bin/figlet` resolves through the symlink
     * to exactly `<termuxRoot>/usr/bin/figlet`, which is the real prefix.
     *
     * This is the same idea as the /lib64 symlink on a multiarch system: one
     * fixed, documented mapping so foreign-prefixed content resolves into this
     * tree. It is built once at init, is idempotent, and only ever creates
     * directories and a single symlink inside our own storage.
     */
    private fun ensureCompatibilityRoot() {
        try {
            val upstreamUsr = "$UPSTREAM_FILES_ROOT/usr"          // /data/data/com.termux/files/usr
            val upstreamFiles = UPSTREAM_FILES_ROOT                // /data/data/com.termux/files
            val rel = upstreamFiles.removePrefix("/")              // data/data/com.termux/files
            val filesDirInRoot = File(termuxRoot, rel)
            filesDirInRoot.mkdirs()

            val usrLink = File(filesDirInRoot, "usr")
            if (!usrLink.exists()) {
                try {
                    android.system.Os.symlink(
                        File(usrPrefix, "").absolutePath.trimEnd('/'),
                        usrLink.absolutePath,
                    )
                } catch (e: Exception) {
                    Log.w(TAG, "TermuxProvider: compatibility root symlink failed: ${e.message}")
                }
            }
            val ok = usrLink.exists()
            Log.d(
                TAG,
                "TermuxProvider: compatibility root ${if (ok) "ready" else "INCOMPLETE"} — " +
                    "${usrLink.absolutePath} -> ${usrPrefix.absolutePath} (upstream $upstreamUsr)",
            )
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: compatibility root failed: ${e.message}")
        }
    }

    /**
     * Rewrite every TEXT file in the prefix that names the upstream com.termux
     * root, so it names THIS prefix instead.
     *
     * WHY THIS EXISTS. The bundled payload was built for Termux's own package
     * id, and it records that path in text in four places: script shebangs
     * (`#!/data/data/com.termux/files/usr/bin/sh`), dpkg's status and its 96
     * .list rows, apt's generated helper scripts, and the keyring postinst
     * paths. None of those paths exist inside this app's sandbox.
     *
     * The cost of leaving them: apt delegates repository signature checking to
     * `apt-key`, apt-key is a shell script, the kernel resolves its shebang to
     * `/data/data/com.termux/files/usr/bin/sh`, that path does not exist, the
     * exec fails, and apt reports "Couldn't execute apt-key" followed by "not
     * signed". Every search for a keyring or GPG fault is then a search in the
     * wrong place: the keyring is fine, the signature is fine, the interpreter
     * path is dead.
     *
     * The mapping that matters:
     *
     *   upstream  /data/data/com.termux/files/usr/bin/sh
     *   ours      <filesDir>/termux/usr/bin/sh
     *
     * The upstream `files` segment corresponds to OUR `termux` directory, so
     * the replacement for the upstream root is the termux root, NOT filesDir.
     *
     * Scope, deliberately narrow: TEXT files only. ELF binaries are the
     * linker's business and rewriting bytes inside one corrupts it, so anything
     * beginning with \x7fELF is skipped, along with anything above 4 MiB.
     *
     * Idempotent and version-gated: the marker records the pass version, so a
     * change to these rules re-runs the pass on an installed tree.
     */
    private fun relocatePrefixPaths() {
        try {
            val marker = File(usrPrefix, ".prefix-relocated")
            val current = runCatching { marker.readText() }.getOrNull()
            if (current != null && current.contains("version=$PREFIX_RELOCATION_VERSION")) return

            val realRoot = termuxRoot.absolutePath
            var rewritten = 0
            var scanned = 0
            var elfSkipped = 0

            usrPrefix.walkTopDown().forEach { file ->
                if (!file.isFile) return@forEach
                if (file.name == ".prefix-relocated") return@forEach
                if (file.length() > 4L * 1024 * 1024) return@forEach

                val head = runCatching {
                    file.inputStream().use { input ->
                        val buf = ByteArray(4)
                        if (input.read(buf) == 4) String(buf, Charsets.ISO_8859_1) else ""
                    }
                }.getOrNull() ?: return@forEach

                if (head == "\u007fELF") {
                    elfSkipped++
                    return@forEach
                }

                val text = runCatching { file.readText() }.getOrNull() ?: return@forEach
                scanned++
                if (!text.contains(UPSTREAM_FILES_ROOT)) return@forEach

                val updated = text.replace(UPSTREAM_FILES_ROOT, realRoot)
                val wrote = runCatching { file.writeText(updated) }
                if (wrote.isSuccess) {
                    rewritten++
                    if (updated.startsWith("#!")) file.setExecutable(true, false)
                } else {
                    Log.w(TAG, "TermuxProvider: relocate failed for ${file.name}")
                }
            }

            marker.writeText(
                "version=$PREFIX_RELOCATION_VERSION;root=$realRoot;" +
                    "scanned=$scanned;rewritten=$rewritten;elf_skipped=$elfSkipped"
            )
            Log.d(
                TAG,
                "TermuxProvider: prefix relocation v$PREFIX_RELOCATION_VERSION — " +
                    "$rewritten of $scanned text files rewritten to $realRoot " +
                    "($elfSkipped ELF files left to the linker)"
            )
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: prefix relocation failed (non-fatal): ${e.message}")
        }
    }

    private fun extractBootstrap(): Boolean {
        val tempZip = File(termuxRoot, "bootstrap_temp.zip")
        try {
            val assetExists = context.assets.list("termux")
                ?.contains("termux-bootstrap.zip") ?: false

            if (!assetExists) {
                Log.w(TAG, "TermuxProvider: termux-bootstrap.zip not bundled in assets")
                return false
            }

            Log.d(TAG, "TermuxProvider: copying bootstrap from assets")
            context.assets.open(ASSET_BOOTSTRAP).use { input ->
                FileOutputStream(tempZip).use { output ->
                    input.copyTo(output)
                }
            }

            Log.d(TAG, "TermuxProvider: unzipping bootstrap to ${termuxRoot.absolutePath}")
            ZipFile(tempZip).use { zip ->
                val entries = zip.entries()
                var count = 0
                while (entries.hasMoreElements()) {
                    val entry = entries.nextElement()
                    val cleanName = entry.name.removePrefix("./")
                    if (cleanName.isEmpty()) continue

                    val entryFile = File(termuxRoot, cleanName)
                    if (entry.isDirectory) {
                        entryFile.mkdirs()
                    } else {
                        entryFile.parentFile?.mkdirs()
                        zip.getInputStream(entry).use { entryInput ->
                            FileOutputStream(entryFile).use { entryOutput ->
                                entryInput.copyTo(entryOutput)
                            }
                        }
                        count++
                    }
                }
                Log.d(TAG, "TermuxProvider: extracted $count files")
            }

            // Symlink application now lives in applyBootstrapSymlinks(), which runs
            // AFTER canonicalisation (called from initialize()), because the com.termux
            // translate-to-prefix branch can only succeed with the final usr/ tree on
            // disk. Extraction stops here.
            Log.d(TAG, "TermuxProvider: extracted all files; symlinks defer to post-canonicalise pass")

            return true
        } catch (e: Exception) {
            Log.e(TAG, "TermuxProvider: bootstrap extraction failed", e)
            return false
        } finally {
            tempZip.delete()
        }
    }

    /**
     * Apply SYMLINKS.txt from the extracted bootstrap, once, with the final
     * usr/ tree on disk.
     *
     * THE DEFECT THIS FIXES, and it was the root cause of apt being dead.
     * SYMLINKS.txt uses the form `target←link`, with NO spaces around the
     * arrow. This parser split on " ← " (space, arrow, space), matched zero
     * lines, and reported "0 applied this pass" on every install. So none of
     * the bootstrap's 1160 symlinks ever landed.
     *
     * What that cost: the shipped payload is multi-call. `sh`, `mktemp`,
     * `touch`, `sort`, `cut`, `awk`, `id`, `stat`, `expr` and the rest of
     * coreutils are symlinks onto the `coreutils`, `dash` and `gawk` binaries.
     * Without them the prefix has no shell and no `mktemp`. apt shells out to
     * `apt-key` for signature verification, apt-key is a `#!/.../bin/sh`
     * script, and with no `sh` that exec fails — which surfaced as
     * "Couldn't execute apt-key" and "not signed", looking like a keyring
     * fault and being nothing of the sort.
     *
     * Format handling:
     *  a) The arrow is matched with optional surrounding whitespace, so both
     *     `target←link` and the legacy `target ← link` parse.
     *  b) Absolute targets against the upstream com.termux prefix are
     *     translated to OUR prefix — a raw dead absolute symlink would point
     *     outside the app and break the keyring and helper binaries.
     *  c) A link name starting with a root-level package dir lands inside
     *     usr/, because those dirs physically live there post-canonicalise.
     *  d) Relative targets are resolved against the link's own directory and
     *     pinned to an absolute path inside our prefix. The kernel follows the
     *     link from its real location, so an absolute target behaves the same
     *     and is far easier to verify from a log.
     */
    private fun applyBootstrapSymlinks() {
        val symlinksFile = File(termuxRoot, "SYMLINKS.txt")
        if (!symlinksFile.exists()) return
        val packageDirs = setOf(
            "bin", "lib", "etc", "share", "var", "libexec", "tmp", "opt", "root", "sbin"
        )
        var linkCount = 0
        var alreadyPresent = 0
        var failed = 0

        symlinksFile.forEachLine { line ->
            val trimmed = line.trim()
            if (trimmed.isEmpty()) return@forEachLine

            // THE FIX: split on the arrow itself, whitespace optional. The old
            // split required " ← " and matched nothing in the shipped file.
            val arrow = trimmed.indexOf('←')
            if (arrow <= 0) return@forEachLine
            val rawTarget = trimmed.substring(0, arrow).trim()
            val rawLink = trimmed.substring(arrow + 1).trim().removePrefix("./")
            if (rawTarget.isEmpty() || rawLink.isEmpty()) return@forEachLine

            val linkSegments = rawLink.split("/")
            val linkPathInsidePrefix = if (linkSegments.firstOrNull() in packageDirs) {
                File(usrPrefix, rawLink).path
            } else {
                File(termuxRoot, rawLink).path
            }

            val upstreamPrefix = "$UPSTREAM_FILES_ROOT/usr/"
            val upstreamRoot = "$UPSTREAM_FILES_ROOT/"
            val resolvedTarget = when {
                rawTarget.startsWith(upstreamPrefix) ->
                    File(usrPrefix, rawTarget.removePrefix(upstreamPrefix)).absolutePath
                rawTarget.startsWith(upstreamRoot) ->
                    File(termuxRoot, rawTarget.removePrefix(upstreamRoot)).absolutePath
                // A target outside the upstream tree is kept as written, whether
                // or not it exists today. Dropping it would silently lose a link
                // the payload expects; a dangling link is visible, a missing one
                // is a mystery.
                rawTarget.startsWith("/") -> rawTarget
                else -> File(File(linkPathInsidePrefix).parentFile, rawTarget).path
            }

            val linkFile = File(linkPathInsidePrefix)
            if (linkFile.exists() || java.nio.file.Files.isSymbolicLink(linkFile.toPath())) {
                alreadyPresent++
            } else {
                try {
                    linkFile.parentFile?.mkdirs()
                    android.system.Os.symlink(resolvedTarget, linkFile.absolutePath)
                    linkCount++
                } catch (e: Exception) {
                    failed++
                    if (failed <= 5) {
                        Log.w(TAG, "TermuxProvider: symlink $rawLink -> $resolvedTarget failed: ${e.message}")
                    }
                }
            }
        }

        // Deletion is gated on this pass having produced links, never on a
        // partially-applied one. A pass that linked nothing keeps the manifest
        // so a later canonical pass can retry it.
        if (linkCount > 0 || alreadyPresent > 0) {
            symlinksFile.delete()
            Log.d(
                TAG,
                "TermuxProvider: bootstrap symlinks applied ($linkCount new, " +
                    "$alreadyPresent present, $failed failed); SYMLINKS.txt consumed"
            )
        } else {
            Log.w(TAG, "TermuxProvider: SYMLINKS.txt kept for a later pass ($failed failed this pass)")
        }
    }
}
