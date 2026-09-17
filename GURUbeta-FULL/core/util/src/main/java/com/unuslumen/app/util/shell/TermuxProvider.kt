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
 * DIRECTORY LAYOUT (after extraction):
 *   files/termux/          <- PREFIX (root of the Linux environment)
 *   files/termux/bin/      <- all 250+ binaries (bash, apt, git, curl, etc.)
 *   files/termux/lib/      <- all shared libraries (.so files)
 *   files/termux/etc/      <- config files, apt sources
 *   files/termux/usr/      <- sub-structure (etc, tmp, var)
 *   files/termux/home/     <- created by us, user home directory
 *
 * EXECUTION MODEL:
 *   We do NOT pipe commands through /system/bin/sh because Android's sandbox
 *   blocks the system shell from executing app-internal ELF binaries. Instead:
 *   - For simple commands: ProcessBuilder with env vars set
 *   - First word is the binary name, looked up in termux/bin/
 *   - LD_LIBRARY_PATH includes termux/lib/ so all .so deps resolve
 */
class TermuxProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val ASSET_BOOTSTRAP = "termux/termux-bootstrap.zip"
        private const val HOME_DIR = "home"
    }

    private val termuxRoot: File
        get() = File(context.filesDir, "termux")

    private val binDir: File
        get() = File(termuxRoot, "bin")
    private val libDir: File
        get() = File(termuxRoot, "lib")
    private val etcDir: File
        get() = File(termuxRoot, "etc")
    private val usrDir: File
        get() = File(termuxRoot, "usr")
    private val homeDir: File
        get() = File(termuxRoot, HOME_DIR)
    private val tmpDir: File
        get() = File(usrDir, "tmp")
    private val varDir: File
        get() = File(usrDir, "var")

    private var initialized = false
    private var available = false
    private var symlinksCreated = false

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext available

        try {
            termuxRoot.mkdirs()

            val binHasFiles = binDir.exists() && binDir.listFiles()?.isNotEmpty() == true
            if (!binHasFiles) {
                Log.d(TAG, "TermuxProvider: extracting bootstrap from assets")
                if (!extractBootstrap()) {
                    Log.w(TAG, "TermuxProvider: bootstrap not bundled or extraction failed")
                    initialized = true
                    return@withContext false
                }
            } else {
                Log.d(TAG, "TermuxProvider: found existing bootstrap, bin has ${binDir.listFiles()?.size ?: 0} files")
            }

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

            // Copy bundled libs and tools (libexpat, libpng, zlib, patchelf, libc++_shared)
            if (!symlinksCreated) {
                copyBundledLibs()
                // Symlinks AFTER all .so files land
                createLibrarySymlinks()
                // Copy termux libs into re-tools/lib/ so linker finds them next to RE binaries
                copyLibsToReToolsDir()
                // Patch RPATH on native ELF binaries so --library-path flag isn't needed
                patchElfBinaries()
                symlinksCreated = true
            }

            // Symlink usr/bin -> ../bin for tools that expect this path
            val usrBin = File(termuxRoot, "usr/bin")
            if (!usrBin.exists()) {
                usrBin.parentFile?.mkdirs()
                try {
                    android.system.Os.symlink("../bin", usrBin.absolutePath)
                } catch (_: Exception) {}
            }

            // apt config — write at init so any termuxExec "apt ..." works
            initAptConfig()

            // Copy apt methods from lib/apt/methods/ to usr/lib/apt/methods/
            // The bootstrap zip puts methods in lib/apt/methods/ but apt expects usr/lib/apt/methods/
            // Also check if https method needs to be symlinked/copied from http
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

    // PATH search order: termux/bin, re-tools, system
    private val searchPath: List<File>
        get() = listOf(binDir, reToolsDir, File("/system/bin"), File("/system/xbin"))

    // Shell metacharacters that signal "this needs bash -c"
    private val shellMetaChars = Regex("""[;|&<>]""")

    // Shared env map reused across all ProcessBuilder calls
    private fun buildEnv(): MutableMap<String, String> = mutableMapOf(
        "PREFIX" to termuxRoot.absolutePath,
        "HOME" to homeDir.absolutePath,
        "TMPDIR" to tmpDir.absolutePath,
        "PATH" to "${binDir.absolutePath}:${reToolsDir.absolutePath}:${usrDir.absolutePath}/bin:/system/bin:/system/xbin",
        "LD_LIBRARY_PATH" to "${libDir.absolutePath}:${reToolsDir.absolutePath}/lib",
        // libtermux-exec reads these env vars to override its compiled-in defaults.
        // Without these, it falls back to /data/data/com.termux/files/usr which doesn't exist.
        // With these set, it uses our actual prefix for all path resolution.
        "TERMUX__ROOTFS" to termuxRoot.absolutePath,
        "TERMUX__PREFIX" to File(termuxRoot, "usr").absolutePath,
        "TERMUX_PREFIX" to File(termuxRoot, "usr").absolutePath,
        "TERMUX_HOME" to homeDir.absolutePath,
        "TERMUX_APP__DATA_DIR" to context.filesDir.absolutePath,
        "TERMUX_APP__LEGACY_DATA_DIR" to "/data/data/${context.packageName}",
        "TERMUX_APP_PACKAGE" to context.packageName,
        "TERMUX_ANDROID_HOME" to homeDir.absolutePath,
        // LD_PRELOAD: load libtermux-exec so it intercepts execve() and rewrites
        // hardcoded /data/data/com.termux paths to our actual prefix using the env vars above
        "LD_PRELOAD" to File(libDir, "libtermux-exec-ld-preload.so").absolutePath,
        // SSL/TLS: apt and curl need to find CA certificates
        "SSL_CERT_FILE" to File(etcDir, "tls/cert.pem").absolutePath,
        "CA_CERT_FILE" to File(etcDir, "tls/cert.pem").absolutePath,
        "REQUESTS_CA_BUNDLE" to File(etcDir, "tls/cert.pem").absolutePath,
        "GIT_SSL_CAINFO" to File(etcDir, "tls/cert.pem").absolutePath
    )

    /**
     * Execute a command inside the Termux environment.
     *
     * Binary lookup searches PATH in order: termux/bin, re-tools, system/bin, system/xbin.
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
                stderr = "command not found: $binaryName (searched termux/bin, re-tools, /system/bin)",
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
            return ShellResult(exitCode = -1, stdout = "", stderr = "bash not found in termux/bin/", success = false)
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
        val prefix = File(termuxRoot, "usr").absolutePath
        val script = "apt update 2>&1 && apt install -y $packages 2>&1"
        // Pass -o overrides via APT_CONFIG env var pointing to our apt.conf.d
        // AND also set them inline in the script as a belt-and-suspenders approach
        val fullScript = """
            export APT_PREFIX="$prefix"
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
                    |Dir "${termuxRoot.absolutePath}"
                    |Dir::State "${usrDir.absolutePath}/var/lib/apt"
                    |Dir::State::lists "${usrDir.absolutePath}/var/lib/apt/lists"
                    |Dir::Cache "${usrDir.absolutePath}/var/cache/apt"
                    |Dir::Log "${usrDir.absolutePath}/var/log/apt"
                    |Dir::Etc "${etcDir.absolutePath}/apt"
                    |Dir::Etc::sourcelist "${etcDir.absolutePath}/apt/sources.list"
                    |Dir::Etc::sourceparts "${etcDir.absolutePath}/apt/sources.list.d"
                    |Dir::Etc::parts "${aptConfDir.absolutePath}"
                """.trimMargin())
            }

            // Ensure state directories exist
            File(usrDir, "var/lib/apt/lists").mkdirs()
            File(usrDir, "var/cache/apt/archives/partial").mkdirs()
            File(usrDir, "var/log/apt").mkdirs()

            // Also ensure the GPG keyring directory exists
            File(etcDir, "apt/trusted.gpg.d").mkdirs()

            Log.d(TAG, "TermuxProvider: apt config written to ${aptConfDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "TermuxProvider: apt config init failed: ${e.message}")
        }
    }

    /**
     * Copy apt transport method drivers from lib/apt/methods/ to usr/lib/apt/methods/.
     * The bootstrap zip places them at lib/apt/methods/ (relative to termux root) but
     * apt expects them at usr/lib/apt/methods/. Also ensures the https method exists
     * (some bootstraps only include http, not https).
     */
    private fun fixAptMethods() {
        val sourceMethodsDir = File(libDir, "apt/methods")
        val targetMethodsDir = File(usrDir, "lib/apt/methods")

        if (!sourceMethodsDir.exists() && targetMethodsDir.exists()) {
            Log.d(TAG, "TermuxProvider: apt methods already at ${targetMethodsDir.absolutePath}")
            return
        }

        targetMethodsDir.mkdirs()

        if (sourceMethodsDir.exists()) {
            var count = 0
            sourceMethodsDir.listFiles()?.forEach { method ->
                val dest = File(targetMethodsDir, method.name)
                if (!dest.exists()) {
                    try {
                        method.copyTo(dest)
                        dest.setExecutable(true, false)
                        dest.setReadable(true, false)
                        count++
                    } catch (e: Exception) {
                        Log.w(TAG, "TermuxProvider: failed to copy apt method ${method.name}: ${e.message}")
                    }
                }
            }
            if (count > 0) {
                Log.d(TAG, "TermuxProvider: copied $count apt methods to ${targetMethodsDir.absolutePath}")
            }
        }

        // Check if https method exists. If not, copy from http (they share the same binary
        // in Termux builds, https is just a symlink to http or a copy)
        val httpsMethod = File(targetMethodsDir, "https")
        if (!httpsMethod.exists()) {
            val httpMethod = File(targetMethodsDir, "http")
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

            // Apply SYMLINKS.txt
            val symlinksFile = File(termuxRoot, "SYMLINKS.txt")
            if (symlinksFile.exists()) {
                var linkCount = 0
                symlinksFile.forEachLine { line ->
                    val parts = line.split(" ← ")
                    if (parts.size == 2) {
                        val target = parts[0].trim()
                        val linkName = parts[1].trim()
                        val linkFile = File(termuxRoot, linkName)
                        if (!linkFile.exists()) {
                            try {
                                linkFile.parentFile?.mkdirs()
                                android.system.Os.symlink(target, linkFile.absolutePath)
                                linkCount++
                            } catch (_: Exception) {}
                        }
                    }
                }
                Log.d(TAG, "TermuxProvider: created $linkCount symlinks from SYMLINKS.txt")
            }

            // Set executable on all binaries
            binDir.walkTopDown().filter { it.isFile }.forEach { file ->
                try { file.setExecutable(true, false) } catch (_: Exception) {}
                try { file.setReadable(true, false) } catch (_: Exception) {}
            }

            return true
        } catch (e: Exception) {
            Log.e(TAG, "TermuxProvider: bootstrap extraction failed", e)
            return false
        } finally {
            tempZip.delete()
        }
    }
}
