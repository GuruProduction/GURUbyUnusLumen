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

            // Dpkg database path bake (audit fix 6): dpkg's status and .list files record
            // absolute /data/data/com.termux/... paths baked into the upstream bootstrap.
            // On this package those files do not exist, so every dpkg/apt query would
            // describe phantom files. One pass: rewrite the marker to OUR files root.
            bakeDpkgPaths()

            // The bootstrap's layout IS the real bin path (usr/bin) — no redirect
            // shims wanted. Substructure (tmp, var, apt dirs) above; done.

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

    fun isAvailable(): Boolean = available
    fun isInitialized(): Boolean = initialized

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
     * Passes -o Dir=... overrides directly as arguments to override any
     * compiled-in defaults that LD_PRELOAD doesn't catch.
     */
    fun execAptInstall(packages: String): ShellResult {
        val bash = File(binDir, "bash")
        if (!bash.exists() || !bash.canRead()) {
            return ShellResult(exitCode = -1, stdout = "", stderr = "bash not found", success = false)
        }
        // Apt is compiled against the real Termux prefix (/data/data/com.termux/files/usr), so the
        // -o overrides stay mandatory. They now all resolve against the TRUE prefix (termux/usr):
        // methods sit at prefix/lib/apt/methods (fixAptMethods + bootstrap both put them there)
        // and dpkg sits at prefix/bin/dpkg. Only the prefix string changed with the layout fix.
        val prefix = usrPrefix.absolutePath
        val fullScript = """
            apt -o Dir="$prefix" \
                -o Dir::State="${'$'}prefix/var/lib/apt" \
                -o Dir::State::lists="${'$'}prefix/var/lib/apt/lists" \
                -o Dir::Cache="${'$'}prefix/var/cache/apt" \
                -o Dir::Log="${'$'}prefix/var/log/apt" \
                -o Dir::Etc="${'$'}prefix/etc/apt" \
                -o Dir::Etc::sourcelist="${'$'}prefix/etc/apt/sources.list" \
                -o Dir::Etc::sourceparts="${'$'}prefix/etc/apt/sources.list.d" \
                -o Dir::Etc::parts="${'$'}prefix/etc/apt/apt.conf.d" \
                -o Dir::Bin::methods="${'$'}prefix/lib/apt/methods" \
                -o Dir::Bin::dpkg="${'$'}prefix/bin/dpkg" \
                -o DPkg::Options::="--root=$prefix" \
                -o Acquire::Languages::="none" \
                update 2>&1 && \
            apt -o Dir="$prefix" \
                -o Dir::State="${'$'}prefix/var/lib/apt" \
                -o Dir::State::lists="${'$'}prefix/var/lib/apt/lists" \
                -o Dir::Cache="${'$'}prefix/var/cache/apt" \
                -o Dir::Log="${'$'}prefix/var/log/apt" \
                -o Dir::Etc="${'$'}prefix/etc/apt" \
                -o Dir::Etc::sourcelist="${'$'}prefix/etc/apt/sources.list" \
                -o Dir::Etc::sourceparts="${'$'}prefix/etc/apt/sources.list.d" \
                -o Dir::Etc::parts="${'$'}prefix/etc/apt/apt.conf.d" \
                -o Dir::Bin::methods="${'$'}prefix/lib/apt/methods" \
                -o Dir::Bin::dpkg="${'$'}prefix/bin/dpkg" \
                -o DPkg::Options::="--root=$prefix" \
                -o Acquire::Languages::="none" \
                install -y $packages 2>&1
        """.trimIndent()
        return execWithEnv(listOf(linker, bash.absolutePath, "-c", fullScript))
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
            val rootConf = File(aptConfDir, "01-root.conf")
            if (!rootConf.exists()) {
                rootConf.writeText("""
                    |Dir "${usrPrefix.absolutePath}"
                    |Dir::State "${usrPrefix.absolutePath}/var/lib/apt"
                    |Dir::State::lists "${usrPrefix.absolutePath}/var/lib/apt/lists"
                    |Dir::Cache "${usrPrefix.absolutePath}/var/cache/apt"
                    |Dir::Log "${usrPrefix.absolutePath}/var/log/apt"
                    |Dir::Etc "${etcDir.absolutePath}/apt"
                    |Dir::Etc::sourcelist "${etcDir.absolutePath}/apt/sources.list"
                    |Dir::Etc::sourceparts "${etcDir.absolutePath}/apt/sources.list.d"
                    |Dir::Etc::parts "${aptConfDir.absolutePath}"
                """.trimMargin())
            }

            // Ensure state directories exist
            File(usrPrefix, "var/lib/apt/lists").mkdirs()
            File(usrPrefix, "var/cache/apt/archives/partial").mkdirs()
            File(usrPrefix, "var/log/apt").mkdirs()

            // Also ensure the GPG keyring directory exists
            File(etcDir, "apt/trusted.gpg.d").mkdirs()

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
     * Bake the real package root into dpkg's database. Every .list and the status
     * file in var/lib/dpkg carry absolute /data/data/com.termux paths shipped with
     * the bootstrap. Replace the upstream root marker with our real files dir so
     * dpkg/libapt describe files that actually exist on this install.
     * One-shot, marker-gated: writes a done-file in var/lib/dpkg so repeated inits
     * never rescan hundreds of files every boot.
     */
    private fun bakeDpkgPaths() {
        try {
            val dpkgDir = File(File(usrPrefix, "var/lib"), "dpkg")
            if (!dpkgDir.exists()) return
            val doneMarker = File(dpkgDir, ".paths_baked")
            if (doneMarker.exists()) return

            val upstreamRoot = "/data/data/com.termux/files"
            var filesRewritten = 0
            dpkgDir.listFiles()?.forEach { entry ->
                if (entry.isFile && (entry.name == "status" || entry.name.endsWith(".list"))) {
                    val text = runCatching { entry.readText() }.getOrNull() ?: return@forEach
                    if (text.contains(upstreamRoot)) {
                        val updated = text.replace(upstreamRoot, context.filesDir.absolutePath)
                        entry.writeText(updated)
                        filesRewritten++
                    }
                }
            }
            doneMarker.writeText("baked=${filesRewritten};root=${context.filesDir.absolutePath}\n")
            Log.d(TAG, "TermuxProvider: dpkg paths baked ($filesRewritten files) to ${context.filesDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: dpkg path bake failed (non-fatal): ${e.message}")
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
     *  a) Format is `target ← link_name`, legacy lines carry reverse arrow and
     *     "./" relative names.
     *  b) Absolute targets against /data/data/com.termux/files/usr get translated
     *     to OUR prefix — a raw dead absolute symlink would break apt keyrings.
     *  c) Every link path lands inside the usr/ tree when a root-level legacy name
     *     refers to a package dir (bin/… → usr/bin/…): those dirs physically live
     *     under usr/ post-canonicalise.
     */
    private fun applyBootstrapSymlinks() {
        val symlinksFile = File(termuxRoot, "SYMLINKS.txt")
        if (!symlinksFile.exists()) return
        val packageDirs = setOf(
            "bin", "lib", "etc", "share", "var", "libexec", "tmp", "opt", "root", "sbin"
        )
        var linkCount = 0
        symlinksFile.forEachLine { line ->
            val parts = line.split(" ← ")
            if (parts.size != 2) return@forEachLine
            val rawTarget = parts[0].trim()
            val rawLink = parts[1].trim().removePrefix("./")

            val linkSegments = rawLink.split("/".toRegex())
            val linkPathInsidePrefix = if (linkSegments.firstOrNull() in packageDirs) {
                File(usrPrefix, rawLink).path
            } else {
                File(termuxRoot, rawLink).path
            }

            val comTermuxMarker = "/data/data/com.termux/files/usr/"
            val resolvedTarget = when {
                rawTarget.startsWith(comTermuxMarker) -> {
                    val ours = File(usrPrefix, rawTarget.removePrefix(comTermuxMarker))
                    if (ours.exists()) ours.absolutePath else null
                }
                rawTarget.startsWith("/") -> if (File(rawTarget).exists()) rawTarget else null
                else -> rawTarget
            }
            if (resolvedTarget != null) {
                val linkFile = File(linkPathInsidePrefix)
                if (!linkFile.exists()) {
                    try {
                        linkFile.parentFile?.mkdirs()
                        android.system.Os.symlink(resolvedTarget, linkFile.absolutePath)
                        linkCount++
                    } catch (_: Exception) {}
                }
            }
        }
        // THE AUDIT'S STAGE-1 FIX: never consume SYMLINKS.txt when this pass linked
        // nothing. A zero-apply pass means the tree was not final yet (pre-canonicalise
        // call order) or targets were absent; a later canonical pass can link them, and
        // deletion locks that impossibility in forever. Deletion only at full success.
        if (linkCount == 0) {
            Log.w(TAG, "TermuxProvider: SYMLINKS.txt kept for a later pass (0 applied this pass)")
        } else {
            symlinksFile.delete()
            Log.d(TAG, "TermuxProvider: applied $linkCount bootstrap symlinks (post-canonicalise); SYMLINKS.txt consumed")
        }
    }
}
