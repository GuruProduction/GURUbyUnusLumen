// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Reports the reverse-engineering toolchain's real state, and nothing else.
 *
 * WHAT THIS CLASS USED TO BE, and why that was a lie (issue 200). It built a
 * parallel toolset out of `assets/re-tools/`, naming aapt-arm64, aapt2-arm64,
 * apktool.jar, jadx.jar, dex2jar.zip, android.jar and android.jar's support
 * libraries. NONE of those files were ever in the APK. Extraction logged "asset
 * not bundled" for each and moved on, and every availability flag was then set
 * from a boolean that had never reflected a working binary.
 *
 * The visible result on a handset running build 3.11 was:
 *
 *   RE tools: aapt=false aapt2=false apktool=false jadx=true
 *
 * with `jadx=true` true because a 5751-byte Gradle start script named
 * `jadx-arm64` existed, and that script's first act is to look for `java`, which
 * was absent from the entire APK. The flag reported a filename, not a tool.
 *
 * WHAT IT IS NOW. The tools live in the Termux prefix, installed from the pinned
 * archive by TermuxProvider.installReToolchain(), and every question about them
 * is answered by RUNNING them through the same three-state probe the rest of the
 * prefix uses. ABSENT, WILL_NOT_LOAD and RUNS are the only answers, each with the
 * real output attached, so a caller can tell "not installed" from "installed and
 * broken" without guessing.
 *
 * adb is the one thing that stays in `re-tools`, because it is a power-user
 * route rather than part of the toolchain: it is copied to /data/local/tmp and
 * run there, which is why its handling does not resemble the prefix tools.
 */
class ReToolingProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"

        // adb is the only binary this provider extracts for itself. Everything
        // else comes from the prefix.
        private const val ASSET_ADB_ARM64 = "re-tools/adb-arm64"
        private const val ASSET_ADB_X86_64 = "re-tools/adb-x86_64"
        private const val ADB_FILENAME = "adb"
        private const val ADB_LIBS_DIR_ARM64 = "re-tools/adb-libs-arm64"
        private const val ADB_LIBS_DIR_X86_64 = "re-tools/adb-libs-x86_64"

        /**
         * The tool names as the rest of the app refers to them, each with the
         * argument it genuinely accepts.
         *
         * A single blanket `--version` is wrong here: aapt, aapt2 and zipalign
         * are native tools with their own subcommand vocabulary, apktool prints
         * and exits 1 on an unknown flag, and only the JVM tools use `-version`.
         * Probing them all with the same argument is how a working tool gets
         * reported broken.
         */
        private val TOOL_PROBES: List<Pair<String, List<String>>> = listOf(
            "java" to listOf("-version"),
            "aapt" to listOf("version"),
            "aapt2" to listOf("version"),
            "zipalign" to emptyList(),
            "apktool" to listOf("--version"),
        )

        /** Tools that decompile DEX to Java. All three are JVM applications. */
        private val DECOMPILER_PROBES: List<Pair<String, List<String>>> = listOf(
            "jadx" to listOf("--version"),
            "dex2jar" to listOf("--version"),
        )
    }

    private val toolsDir: File
        get() = File(context.filesDir, "re-tools")

    private val adbPath: File
        get() = File(toolsDir, ADB_FILENAME)

    private val adbLibsDir: File
        get() = File(toolsDir, "adb-libs")

    /** The Termux prefix this provider asks about. Constructed lazily, not held. */
    private val termux by lazy { TermuxProvider(context) }

    private var initialized = false
    private var adbAvailable = false

    /**
     * Read the toolchain's state. Installs the prefix if it is not there.
     *
     * Deliberately does NOT invent an availability flag for the prefix tools:
     * those are answered on demand by [toolState], because a cached boolean is
     * exactly the mechanism that let `jadx=true` survive a handset with no JVM.
     * The only latch held here is adb, whose extraction this class owns.
     */
    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext anyAvailable()

        try {
            toolsDir.mkdirs()
            adbAvailable = extractAdbWithLibs()
        } catch (e: Exception) {
            Log.e(TAG, "ReToolingProvider: adb preparation failed", e)
            adbAvailable = false
        }

        // Bring the prefix up so the toolchain exists to be asked about. This is
        // the install path, not a status read: the prefix carries the JVM and the
        // tools, and a status question must not be able to claim the app cannot
        // do something it can install itself.
        runCatching { termux.initialize() }
            .onFailure { Log.w(TAG, "ReToolingProvider: prefix initialise failed: ${it.message}") }

        initialized = true
        Log.d(TAG, "ReToolingProvider: prepared — ${describeToolchain()}")
        anyAvailable()
    }

    /**
     * The three-state answer for one tool, by running it.
     *
     * This is the only honest way to answer the question, and it is what issue
     * 200 asks for when it says the status must state the real state of each
     * tool rather than a subset.
     */
    fun toolState(name: String): TermuxProvider.CommandProbeResult {
        val args = TOOL_PROBES.firstOrNull { it.first == name }?.second
            ?: DECOMPILER_PROBES.firstOrNull { it.first == name }?.second
            ?: listOf("--version")
        return termux.probeTool(name, args)
    }

    /** True only when the tool actually ran. */
    fun isToolAvailable(name: String): Boolean =
        toolState(name).state == TermuxProvider.CommandProbe.RUNS

    fun isAaptAvailable(): Boolean = isToolAvailable("aapt")
    fun isAapt2Available(): Boolean = isToolAvailable("aapt2")
    fun isZipalignAvailable(): Boolean = isToolAvailable("zipalign")
    fun isApktoolAvailable(): Boolean = isToolAvailable("apktool")
    fun isJadxAvailable(): Boolean = isToolAvailable("jadx")
    fun isDex2jarAvailable(): Boolean = isToolAvailable("dex2jar")

    /**
     * Whether the JVM is present and runs. Everything that is a `java -jar`
     * depends on this, so it is asked separately rather than inferred from a
     * decompiler's presence.
     */
    fun isJavaAvailable(): Boolean = isToolAvailable("java")

    fun isAdbAvailable(): Boolean = adbAvailable

    fun anyAvailable(): Boolean =
        adbAvailable || TOOL_PROBES.any { isToolAvailable(it.first) }

    val isInitialized: Boolean get() = initialized

    /**
     * One line stating the real state of every tool, for termuxInit's report.
     *
     * Issue 200's complaint was a status line that said `jadx=true` while jadx
     * could not run. This builds the line from the probes, so the text and the
     * behaviour cannot disagree.
     */
    fun describeToolchain(): String {
        val names = TOOL_PROBES.map { it.first } + DECOMPILER_PROBES.map { it.first }
        return names.joinToString(" ") { name ->
            val probe = toolState(name)
            "${name}=${probe.state == TermuxProvider.CommandProbe.RUNS}"
        }
    }

    /**
     * Detailed state for every tool, for a caller that wants the reason rather
     * than a boolean. Each entry carries ABSENT / WILL_NOT_LOAD / RUNS and the
     * real output, so "not installed" is never confused with "installed and
     * broken".
     */
    fun toolchainReport(): Map<String, TermuxProvider.CommandProbeResult> {
        val names = TOOL_PROBES.map { it.first } + DECOMPILER_PROBES.map { it.first }
        return names.associateWith { toolState(it) }
    }

    fun getAdbPath(): String = adbPath.absolutePath
    fun getAdbLibsPath(): String = adbLibsDir.absolutePath

    /**
     * Wrap an adb command for execution.
     *
     * The adb binary is copied to /data/local/tmp/ (which is exec-allowed)
     * because app storage (/data/user, /data/data) is mounted with noexec
     * on modern Android. The adb server daemon needs to fork and execve
     * itself, which requires a real exec-allowed filesystem. The linker
     * trick only works for the initial load, not for the daemon fork.
     *
     * Shared libraries stay in app storage since they're loaded via mmap,
     * not execve. LD_LIBRARY_PATH points to both the extracted adb-libs
     * directory and system library paths so the linker finds everything.
     *
     * ADB_SERVER_SOCKET is set so the binary uses the default socket
     * without needing the -L flag (unsupported in this version 35.0.2).
     */
    fun wrapAdb(args: String): String {
        val tmpAdbPath = "/data/local/tmp/guru_adb"
        val tmpAdbFile = File(tmpAdbPath)
        // Copy the binary to /data/local/tmp/ in Kotlin code, not shell.
        // /data/local/tmp/ is world-writable (drwxrwxrwt) so the app process
        // can write there directly. A shell cp might fail under Runtime.exec
        // because the shell process runs as a different UID.
        try {
            if (!tmpAdbFile.exists() || tmpAdbFile.length() != adbPath.length()) {
                adbPath.copyTo(tmpAdbFile, overwrite = true)
                tmpAdbFile.setExecutable(true, false)
                Log.d(TAG, "ReToolingProvider: copied adb binary to $tmpAdbPath")
            }
        } catch (e: Exception) {
            Log.w(TAG, "ReToolingProvider: failed to copy adb to $tmpAdbPath: ${e.message}")
        }
        return "ADB_SERVER_SOCKET=tcp:127.0.0.1:5037 LD_LIBRARY_PATH=${adbLibsDir.absolutePath}:/system/lib64:/vendor/lib64 $tmpAdbPath $args"
    }

    /**
     * Extract the adb binary and all its shared libraries from assets.
     * adb needs abseil-cpp, protobuf, zstd, zlib, and utf8 libraries.
     * The libs are in re-tools/adb-libs-arm64/ or re-tools/adb-libs-x86_64/
     * depending on the device architecture.
     *
     * After extraction, creates a libz.so.1 symlink to libz.so.1.3.2
     * because the adb binary links against libz.so.1 but the package
     * ships libz.so.1.3.2.
     */
    private fun extractAdbWithLibs(): Boolean {
        val adbOk = extractArchBinary(
            arm64Asset = ASSET_ADB_ARM64,
            x8664Asset = ASSET_ADB_X86_64,
            destFile = adbPath
        )
        if (!adbOk) return false

        // Select the correct libs subdirectory based on architecture
        val arch = System.getProperty("os.arch") ?: return false
        val libsAssetDir = when {
            arch.contains("aarch64", ignoreCase = true) ||
            arch.contains("arm64", ignoreCase = true) -> ADB_LIBS_DIR_ARM64
            arch.contains("x86_64", ignoreCase = true) ||
            arch.contains("amd64", ignoreCase = true) -> ADB_LIBS_DIR_X86_64
            else -> {
                Log.w(TAG, "ReToolingProvider: unknown architecture for adb libs: $arch")
                return false
            }
        }

        // Extract all shared libraries from the arch-specific directory.
        adbLibsDir.mkdirs()
        try {
            val libAssets = context.assets.list(libsAssetDir) ?: emptyArray()
            for (libName in libAssets) {
                val libFile = File(adbLibsDir, libName)
                if (!libFile.exists()) {
                    try {
                        context.assets.open("$libsAssetDir/$libName").use { input ->
                            FileOutputStream(libFile).use { output ->
                                input.copyTo(output)
                            }
                        }
                    } catch (e: Exception) {
                        Log.w(TAG, "ReToolingProvider: failed to extract adb lib $libName: ${e.message}")
                    }
                }
            }
            Log.d(TAG, "ReToolingProvider: extracted ${libAssets.size} adb libs to ${adbLibsDir.absolutePath}")
        } catch (e: Exception) {
            Log.w(TAG, "ReToolingProvider: failed to list/extract adb libs: ${e.message}")
        }

        // Create libz.so.1 symlink to libz.so.1.3.2
        val zLibFile = File(adbLibsDir, "libz.so.1")
        val zLibReal = File(adbLibsDir, "libz.so.1.3.2")
        if (!zLibFile.exists() && zLibReal.exists()) {
            try {
                android.system.Os.symlink("libz.so.1.3.2", zLibFile.absolutePath)
                Log.d(TAG, "ReToolingProvider: created libz.so.1 -> libz.so.1.3.2 symlink")
            } catch (e: Exception) {
                Log.w(TAG, "ReToolingProvider: failed to create libz.so.1 symlink: ${e.message}")
                // Fallback: copy the file
                try {
                    zLibReal.copyTo(zLibFile)
                } catch (e2: Exception) {
                    Log.w(TAG, "ReToolingProvider: failed to copy libz.so.1: ${e2.message}")
                }
            }
        }

        return true
    }

    private fun extractArchBinary(arm64Asset: String, x8664Asset: String, destFile: File): Boolean {
        if (destFile.exists() && destFile.canExecute()) return true

        val arch = System.getProperty("os.arch") ?: return false
        val assetName = when {
            arch.contains("aarch64", ignoreCase = true) ||
            arch.contains("arm64", ignoreCase = true) -> arm64Asset
            arch.contains("x86_64", ignoreCase = true) ||
            arch.contains("amd64", ignoreCase = true) -> x8664Asset
            else -> {
                Log.w(TAG, "ReToolingProvider: unknown architecture: $arch")
                return false
            }
        }

        return extractAssetFile(assetName, destFile) && destFile.setExecutable(true, false)
    }

    private fun extractAssetFile(assetName: String, destFile: File): Boolean {
        if (destFile.exists()) return true

        return try {
            val assetExists = context.assets.list(assetName.substringBefore("/"))
                ?.contains(assetName.substringAfter("/")) ?: false

            if (!assetExists) {
                Log.w(TAG, "ReToolingProvider: asset '$assetName' not bundled")
                return false
            }

            Log.d(TAG, "ReToolingProvider: extracting $assetName to ${destFile.absolutePath}")
            destFile.parentFile?.mkdirs()
            context.assets.open(assetName).use { input ->
                FileOutputStream(destFile).use { output ->
                    input.copyTo(output)
                }
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "ReToolingProvider: failed to extract $assetName: ${e.message}")
            false
        }
    }
}
