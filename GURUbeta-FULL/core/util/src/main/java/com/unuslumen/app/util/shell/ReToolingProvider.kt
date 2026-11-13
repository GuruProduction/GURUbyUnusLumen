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
 * ADB IS PACKAGED AS A NATIVE LIBRARY, AND THAT IS THE ONLY ROUTE THAT WORKS (issue 209).
 *
 * The old handling extracted the binary into `files/re-tools/adb` and then copied it again to
 * `/data/local/tmp/guru_adb` to run it. Both halves of that failed on a real handset, and the
 * ticket measured the reason:
 *
 *  - `/data/local/tmp` is not writable at app UID. `touch /data/local/tmp/gurutest` answers
 *    `Permission denied`, so the second copy never landed and every call exited 127.
 *  - App data under `u:r:untrusted_app` is W^X, so no loose binary in `files/` or `cache/` can
 *    execute however it is chmod'd. The ticket tested four locations and all four refused.
 *
 * The one executable location an unprivileged app has is `nativeLibraryDir`, which is populated
 * by the package installer from `jniLibs` and carries the `nativedir_file` label that permits
 * execution. Five binaries in this app already work that way, and `libguru_toybox.so` has been
 * proven on his own phone. So adb is packaged as `libguru_adb.so` and run from there.
 *
 * WHY THE LIBRARIES STILL COME FROM APP STORAGE. A shared library is mapped rather than executed,
 * so `mmap` on a noexec mount is fine: only `execve` is refused. adb's dependency set is large
 * (abseil, protobuf, zstd, zlib, utf8) and it is far simpler to point `LD_LIBRARY_PATH` at an
 * extracted directory than to package twenty more `.so` files as jniLibs and get each one's name
 * exactly right.
 *
 * THE ONE CASE THIS DOES NOT SOLVE, named so nobody has to rediscover it: `adb start-server`
 * daemonises itself with a second `execve`. That child is a fork of a process already running
 * from an executable path, so it works, but it is worth knowing that the binary is being executed
 * twice rather than once.
 */
class ReToolingProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"

        /**
         * The packaged adb binary, as a native library (issue 209).
         *
         * `libguru_adb.so` lives in `jniLibs/` beside every other `libguru_*.so`, and the package
         * installer extracts it to the native library directory at install time. The `lib` prefix
         * and `.so` suffix are not cosmetic: they are the two things that make Android treat the
         * file as a native library and extract it to an executable location. The same binary under
         * an asset name cannot be executed from anywhere the app can write.
         *
         * The old `assets/re-tools/adb-arm64` copy is deleted. It was the same 2.8 MB binary, and
         * shipping both meant carrying it twice in every APK for no reader.
         */
        private const val ADB_LIB_SO = "libguru_adb.so"

        /** The shared libraries adb needs, extracted to app storage and mapped, never executed. */
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

    /**
     * The adb binary, in the one place an app can execute anything from (issue 209).
     *
     * `applicationInfo.nativeLibraryDir` is resolved fresh on every read rather than cached,
     * because a reinstall gives the directory a new hashed path and a cached value would point at
     * a tree the package installer has already removed. That is the same trap the toybox symlinks
     * hit, and it is why those are rebuilt on every launch.
     */
    private val adbPath: File
        get() = File(context.applicationInfo.nativeLibraryDir, ADB_LIB_SO)

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
     * Wrap an adb command for execution, from the native library directory.
     *
     * THIS FUNCTION USED TO COPY THE BINARY TO /data/local/tmp AND RUN IT THERE, which could never
     * work (issue 209). Two measurements from the ticket settle it: `/data/local/tmp` is not
     * writable at app UID, so the copy failed with `Permission denied` and every call exited 127;
     * and app storage under `u:r:untrusted_app` is W^X, so no loose binary in `files/` or `cache/`
     * executes however it is chmod'd.
     *
     * The binary now runs from [adbPath], which is `nativeLibraryDir`: the package installer puts
     * it there and the `nativedir_file` label permits execution. Nothing is copied at call time, so
     * there is no silent failure to swallow.
     *
     * SHARED LIBRARIES STAY IN APP STORAGE, and that is not an inconsistency. A library is
     * `mmap`ed rather than executed, and a noexec mount refuses only `execve`, so mapping from
     * `files/` works. `LD_LIBRARY_PATH` points at the extracted directory first and the system
     * directories after it, so adb's own copies win over a platform version of the same name.
     *
     * `ADB_SERVER_SOCKET` is set because version 35.0.2 of this binary does not accept `-L`.
     */
    fun wrapAdb(args: String): String =
        "ADB_SERVER_SOCKET=tcp:127.0.0.1:5037 " +
            "LD_LIBRARY_PATH=${adbLibsDir.absolutePath}:/system/lib64:/vendor/lib64 " +
            "${adbPath.absolutePath} $args"

    /**
     * Why adb cannot run, when it cannot, in words a caller can act on.
     *
     * A missing `libguru_adb.so` means the APK was built without the jniLibs copy, which is a
     * packaging fault rather than a device fault, and the two look identical from an exit code of
     * 127. This distinguishes them so the report says which one it is.
     */
    fun adbUnavailableReason(): String? = when {
        !adbPath.exists() ->
            "The adb binary is not in the native library directory at ${adbPath.absolutePath}. " +
                "That means it was not packaged into jniLibs, so this build cannot run adb at all."
        !adbPath.canExecute() ->
            "The adb binary is present at ${adbPath.absolutePath} but is not executable, which " +
                "means the package installer did not extract it as a native library."
        else -> null
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
        /*
         * THE BINARY IS NOT EXTRACTED ANY MORE (issue 209). It is packaged as `libguru_adb.so`
         * and the package installer puts it in the native library directory, which is the only
         * place the app can execute anything from. So the only availability question worth asking
         * is whether it arrived, and [adbUnavailableReason] is what answers it with a reason.
         */
        adbUnavailableReason()?.let { reason ->
            Log.w(TAG, "ReToolingProvider: $reason")
            return false
        }

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

}
