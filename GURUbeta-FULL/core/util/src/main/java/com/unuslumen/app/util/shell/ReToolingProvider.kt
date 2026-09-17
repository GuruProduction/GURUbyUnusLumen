package com.unuslumen.app.util.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

/**
 * Manages bundled reverse engineering tool binaries.
 *
 * Follows the same pattern as BusyboxProvider and PythonProvider:
 * architecture-specific binaries stored in assets, extracted to app storage
 * on first use, set executable, cached for subsequent calls.
 *
 * Tools bundled:
 * - aapt  — Android Asset Packaging Tool (native ARM64 binary)
 * - aapt2 — Android Asset Packaging Tool v2 (native ARM64 binary)
 * - apktool — APK decompiler/recompiler (Java JAR)
 * - jadx — DEX to Java decompiler (Java JAR via shell wrapper)
 * - dex2jar — DEX to JAR converter (Java tool suite, zipped)
 * - zipalign — APK alignment tool (native ARM64 binary)
 */
class ReToolingProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"

        // Asset paths
        private const val ASSET_AAPT_ARM64 = "re-tools/aapt-arm64"
        private const val ASSET_AAPT2_ARM64 = "re-tools/aapt2-arm64"
        private const val ASSET_AAPT2_X86_64 = "re-tools/aapt2-x86_64"
        private const val ASSET_APKTOOL = "re-tools/apktool.jar"
        private const val ASSET_JADX_ARM64 = "re-tools/jadx-arm64"
        private const val ASSET_JADX_X86_64 = "re-tools/jadx-x86_64"
        private const val ASSET_JADX_JAR = "re-tools/jadx.jar"
        private const val ASSET_DEX2JAR = "re-tools/dex2jar.zip"
        private const val ASSET_ANDROID_JAR = "re-tools/android.jar"
        private const val ASSET_ZIPALIGN_ARM64 = "re-tools/zipalign-arm64"

        // ADB binary and its shared libraries
        private const val ASSET_ADB_ARM64 = "re-tools/adb-arm64"
        private const val ADB_FILENAME = "adb"
        private const val ADB_LIBS_DIR_ARM64 = "re-tools/adb-libs-arm64"
        private const val ADB_LIBS_DIR_X86_64 = "re-tools/adb-libs-x86_64"

        // Shared library assets (needed by aapt)
        private val AAPT_LIBS = listOf(
            "libandroid-ziparchive.so",
            "libandroid-utils.so",
            "libandroid-fw.so",
            "libandroid-cutils.so",
            "libandroid-base.so"
        )

        // Binary filenames
        private const val AAPT_FILENAME = "aapt"
        private const val AAPT2_FILENAME = "aapt2"
        private const val ZIPALIGN_FILENAME = "zipalign"
        private const val APKTOOL_FILENAME = "apktool.jar"
        private const val JADX_FILENAME = "jadx"
        private const val ANDROID_JAR_FILENAME = "android.jar"
        private const val DEX2JAR_DIRNAME = "dex2jar"
        private const val D2J_DEX2JAR_SCRIPT = "d2j-dex2jar.sh"
        private const val D2J_DEX2JAR_BAT = "d2j-dex2jar.bat"
    }

    private val toolsDir: File
        get() = File(context.filesDir, "re-tools")

    private val libsDir: File
        get() = File(toolsDir, "lib")

    private val aaptPath: File
        get() = File(toolsDir, AAPT_FILENAME)

    private val aapt2Path: File
        get() = File(toolsDir, AAPT2_FILENAME)

    private val zipalignPath: File
        get() = File(toolsDir, ZIPALIGN_FILENAME)

    private val androidJarPath: File
        get() = File(toolsDir, ANDROID_JAR_FILENAME)

    private val apktoolPath: File
        get() = File(toolsDir, APKTOOL_FILENAME)

    private val jadxPath: File
        get() = File(toolsDir, JADX_FILENAME)

    private val adbPath: File
        get() = File(toolsDir, ADB_FILENAME)

    private val adbLibsDir: File
        get() = File(toolsDir, "adb-libs")

    private val dex2jarDir: File
        get() = File(toolsDir, DEX2JAR_DIRNAME)

    private var initialized = false
    private var aaptAvailable = false
    private var aapt2Available = false
    private var zipalignAvailable = false
    private var apktoolAvailable = false
    private var jadxAvailable = false
    private var dex2jarAvailable = false
    private var adbAvailable = false

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext anyAvailable()

        try {
            toolsDir.mkdirs()
            libsDir.mkdirs()

            // 1. Extract aapt and its shared library deps
            aaptAvailable = extractAaptWithLibs()

            // 2. Extract aapt2
            aapt2Available = extractArchBinary(
                arm64Asset = ASSET_AAPT2_ARM64,
                x8664Asset = ASSET_AAPT2_X86_64,
                destFile = aapt2Path
            )

            // 3. Extract zipalign
            zipalignAvailable = extractArchBinary(
                arm64Asset = ASSET_ZIPALIGN_ARM64,
                x8664Asset = "re-tools/zipalign-x86_64",
                destFile = zipalignPath
            )

            // 4. Extract android.jar (needed by aapt for resource resolution)
            extractAssetFile(ASSET_ANDROID_JAR, androidJarPath)

            // 5. Extract apktool.jar
            apktoolAvailable = extractAssetFile(ASSET_APKTOOL, apktoolPath)

            // 6. Extract jadx shell wrapper and jar
            jadxAvailable = extractArchBinary(
                arm64Asset = ASSET_JADX_ARM64,
                x8664Asset = ASSET_JADX_X86_64,
                destFile = jadxPath
            )
            val jadxJarPath = File(toolsDir, "jadx.jar")
            if (!jadxJarPath.exists()) {
                extractAssetFile(ASSET_JADX_JAR, jadxJarPath)
            }
            if (!jadxAvailable && jadxJarPath.exists()) {
                jadxAvailable = true
            }

            // 7. Extract dex2jar (zipped tool suite)
            dex2jarAvailable = extractDex2jar()

            // 8. Extract adb binary and its shared libraries
            adbAvailable = extractAdbWithLibs()

            initialized = true
            Log.d(TAG, "ReToolingProvider: initialized — aapt=$aaptAvailable aapt2=$aapt2Available zipalign=$zipalignAvailable apktool=$apktoolAvailable jadx=$jadxAvailable dex2jar=$dex2jarAvailable adb=$adbAvailable")
        } catch (e: Exception) {
            Log.e(TAG, "ReToolingProvider: initialization failed", e)
            initialized = true
        }

        anyAvailable()
    }

    fun isAaptAvailable(): Boolean = aaptAvailable
    fun isAapt2Available(): Boolean = aapt2Available
    fun isZipalignAvailable(): Boolean = zipalignAvailable
    fun isApktoolAvailable(): Boolean = apktoolAvailable
    fun isJadxAvailable(): Boolean = jadxAvailable
    fun isDex2jarAvailable(): Boolean = dex2jarAvailable
    fun isAdbAvailable(): Boolean = adbAvailable

    fun anyAvailable(): Boolean = aaptAvailable || aapt2Available || apktoolAvailable || jadxAvailable || dex2jarAvailable || adbAvailable

    val isInitialized: Boolean get() = initialized

    fun getAaptPath(): String = aaptPath.absolutePath
    fun getAapt2Path(): String = aapt2Path.absolutePath
    fun getZipalignPath(): String = zipalignPath.absolutePath
    fun getAndroidJarPath(): String = androidJarPath.absolutePath
    fun getApktoolPath(): String = apktoolPath.absolutePath
    fun getJadxPath(): String = jadxPath.absolutePath
    fun getDex2jarDir(): String = dex2jarDir.absolutePath
    fun getLibsPath(): String = libsDir.absolutePath
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
     * Wrap aapt dump badging — the most common RE operation.
     * Sets up ANDROID_DATA and LD_LIBRARY_PATH so aapt can find its deps.
     */
    fun wrapAapt(args: String): String {
        return "ANDROID_DATA=${toolsDir.absolutePath} LD_LIBRARY_PATH=${libsDir.absolutePath} ${aaptPath.absolutePath} $args"
    }

    /**
     * Wrap aapt2 for binary XML dump — aapt2 dump badging works the same as aapt.
     */
    fun wrapAapt2(args: String): String {
        return "${aapt2Path.absolutePath} $args"
    }

    fun wrapZipalign(args: String): String {
        return "${zipalignPath.absolutePath} $args"
    }

    fun wrapApktool(args: String): String {
        return "java -jar ${apktoolPath.absolutePath} $args"
    }

    fun wrapJadx(args: String): String {
        val jarPath = File(toolsDir, "jadx.jar")
        if (jarPath.exists()) {
            return "java -jar ${jarPath.absolutePath} $args"
        }
        return "${jadxPath.absolutePath} $args"
    }

    fun wrapDex2jar(apkPath: String, outputJar: String): String {
        val scriptPath = File(dex2jarDir, D2J_DEX2JAR_SCRIPT)
        val batPath = File(dex2jarDir, D2J_DEX2JAR_BAT)
        return if (scriptPath.exists()) {
            "bash ${scriptPath.absolutePath} -f -o \"$outputJar\" \"$apkPath\""
        } else if (batPath.exists()) {
            "java -cp \"${dex2jarDir.absolutePath}/lib/*\" com.googlecode.dex2jar.tools.Dex2jarCmd -f -o \"$outputJar\" \"$apkPath\""
        } else {
            "java -cp \"${dex2jarDir.absolutePath}/lib/*\" com.googlecode.dex2jar.tools.Dex2jarCmd -f -o \"$outputJar\" \"$apkPath\""
        }
    }

    // --- Private extraction helpers ---

    /**
     * Extract aapt binary and all its shared library dependencies.
     * aapt needs android.jar and several .so files to function.
     */
    private fun extractAaptWithLibs(): Boolean {
        val aaptOk = extractArchBinary(
            arm64Asset = ASSET_AAPT_ARM64,
            x8664Asset = "re-tools/aapt-x86_64",
            destFile = aaptPath
        )
        if (!aaptOk) return false

        // Extract all shared libraries aapt needs
        var allLibsOk = true
        for (libName in AAPT_LIBS) {
            val libFile = File(libsDir, libName)
            if (!libFile.exists()) {
                val ok = extractAssetFile("re-tools/$libName", libFile)
                if (!ok) {
                    // Lib files are nice to have but aapt may still work on some devices without them
                    Log.w(TAG, "ReToolingProvider: aapt library $libName not bundled, aapt may fail on some devices")
                }
                allLibsOk = allLibsOk && ok
            }
        }

        // Android.jar is essential — aapt won't resolve resource IDs without it
        if (!androidJarPath.exists()) {
            val jarOk = extractAssetFile(ASSET_ANDROID_JAR, androidJarPath)
            if (!jarOk) {
                Log.w(TAG, "ReToolingProvider: android.jar not bundled — aapt will be limited")
                // Still mark as available, just warn
            }
        }

        return true
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
        // Extract the adb binary
        val adbOk = extractArchBinary(
            arm64Asset = ASSET_ADB_ARM64,
            x8664Asset = "re-tools/adb-x86_64",
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
        // We list the directory contents directly and extract each file,
        // bypassing the broken extractAssetFile() existence check that
        // only works for one level of nesting.
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

    private fun extractDex2jar(): Boolean {
        if (dex2jarDir.exists() && dex2jarDir.listFiles()?.isNotEmpty() == true) return true

        return try {
            val assetExists = context.assets.list("re-tools")
                ?.contains("dex2jar.zip") ?: false

            if (!assetExists) {
                Log.w(TAG, "ReToolingProvider: dex2jar.zip not bundled")
                return false
            }

            Log.d(TAG, "ReToolingProvider: extracting dex2jar.zip to ${dex2jarDir.absolutePath}")
            dex2jarDir.mkdirs()

            val tempZip = File(toolsDir, "dex2jar_temp.zip")
            try {
                context.assets.open(ASSET_DEX2JAR).use { input ->
                    FileOutputStream(tempZip).use { output ->
                        input.copyTo(output)
                    }
                }

                ZipFile(tempZip).use { zip ->
                    val entries = zip.entries()
                    while (entries.hasMoreElements()) {
                        val entry = entries.nextElement()
                        val entryFile = File(dex2jarDir, entry.name)
                        if (entry.isDirectory) {
                            entryFile.mkdirs()
                        } else {
                            entryFile.parentFile?.mkdirs()
                            zip.getInputStream(entry).use { entryInput ->
                                FileOutputStream(entryFile).use { entryOutput ->
                                    entryInput.copyTo(entryOutput)
                                }
                            }
                        }
                    }
                }

                dex2jarDir.walkTopDown().forEach { file ->
                    if (file.name.endsWith(".sh")) {
                        file.setExecutable(true, false)
                    }
                }

                Log.d(TAG, "ReToolingProvider: dex2jar extracted to ${dex2jarDir.absolutePath}")
                true
            } finally {
                tempZip.delete()
            }
        } catch (e: Exception) {
            Log.w(TAG, "ReToolingProvider: failed to extract dex2jar: ${e.message}")
            false
        }
    }
}


 
 
// force re-read
