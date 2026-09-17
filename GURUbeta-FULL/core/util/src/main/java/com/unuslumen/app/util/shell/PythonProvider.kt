package com.unuslumen.app.util.shell

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.util.zip.ZipFile

class PythonProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val PYTHON_LIB_SO = "libpython_launcher.so"
        private const val STDLIB_ASSET = "python/arm64-v8a/python-stdlib-arm64.zip"
        private const val PYTHON_DIR_NAME = "python"
        private const val STDLIB_DIR_NAME = "python-lib"
        private const val PYTHON_VERSION = "python3.14"
    }

    private val pythonDir: File
        get() = File(context.filesDir, PYTHON_DIR_NAME)

    private val stdlibDir: File
        get() = File(pythonDir, STDLIB_DIR_NAME)

    private var initialized = false
    private var available = false
    private var binaryPath: String = ""

    suspend fun initialize(): Boolean = withContext(Dispatchers.IO) {
        if (initialized) return@withContext available

        try {
            val nativeLibDir = context.applicationInfo.nativeLibraryDir

            val binaryFile = File(nativeLibDir, PYTHON_LIB_SO)
            if (!binaryFile.exists()) {
                Log.w(TAG, "PythonProvider: $PYTHON_LIB_SO not found at ${binaryFile.absolutePath}")
                initialized = true
                available = false
                return@withContext false
            }
            binaryPath = binaryFile.absolutePath
            Log.d(TAG, "PythonProvider: binary found at $binaryPath")

            pythonDir.mkdirs()
            val versionMarker = File(pythonDir, "stdlib_version")
            val currentVersion = context.packageManager
                .getPackageInfo(context.packageName, 0).longVersionCode.toString()
            val needsExtraction = !stdlibDir.exists() ||
                !versionMarker.exists() ||
                versionMarker.readText().trim() != currentVersion

            if (needsExtraction) {
                val stdlibAssetExists = try {
                    context.assets.list("python/arm64-v8a")
                        ?.contains("python-stdlib-arm64.zip") ?: false
                } catch (e: Exception) {
                    false
                }

                if (!stdlibAssetExists) {
                    Log.w(TAG, "PythonProvider: stdlib zip not found in assets")
                    initialized = true
                    available = false
                    return@withContext false
                }

                Log.d(TAG, "PythonProvider: extracting stdlib from $STDLIB_ASSET")
                val tempZip = File(pythonDir, "stdlib_temp.zip")
                try {
                    context.assets.open(STDLIB_ASSET).use { input ->
                        FileOutputStream(tempZip).use { output ->
                            input.copyTo(output)
                        }
                    }

                    stdlibDir.mkdirs()
                    ZipFile(tempZip).use { zip ->
                        val entries = zip.entries()
                        while (entries.hasMoreElements()) {
                            val entry = entries.nextElement()
                            val entryFile = File(stdlibDir, entry.name)

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

                    val pythonHomeDir = File(stdlibDir, PYTHON_VERSION)
                    val libDynloadDir = File(pythonHomeDir, "lib-dynload")

                    if (!libDynloadDir.exists() || libDynloadDir.listFiles().isNullOrEmpty()) {
                        libDynloadDir.mkdirs()
                        stdlibDir.listFiles { f -> f.name.endsWith(".so") }?.forEach { soFile ->
                            soFile.renameTo(File(libDynloadDir, soFile.name))
                        }
                    }

                    versionMarker.writeText(currentVersion)
                    Log.d(TAG, "PythonProvider: stdlib extracted to ${stdlibDir.absolutePath}")
                } finally {
                    tempZip.delete()
                }
            }

            available = true
            initialized = true
            Log.d(TAG, "PythonProvider: ready - binary=$binaryPath, stdlib=${stdlibDir.absolutePath}")
            available
        } catch (e: Exception) {
            Log.e(TAG, "PythonProvider: initialization failed", e)
            initialized = true
            available = false
            false
        }
    }

    fun isAvailable(): Boolean = available

    fun getPath(): String = binaryPath

    fun buildExecCommand(scriptPath: String): List<String> {
        return listOf(binaryPath, scriptPath)
    }

    fun buildExecCommand(code: String, isCode: Boolean = true): List<String> {
        return if (isCode) {
            listOf(binaryPath, "-c", code)
        } else {
            listOf(binaryPath, code)
        }
    }

    fun getPythonHome(): String = stdlibDir.absolutePath

    fun getPythonEnv(): Map<String, String> {
        val pythonHome = getPythonHome()
        val libDynloadDir = File(File(stdlibDir, PYTHON_VERSION), "lib-dynload")
        val nativeLibDir = context.applicationInfo.nativeLibraryDir
        return mapOf(
            "PYTHONHOME" to pythonHome,
            "PYTHONPATH" to "$pythonHome:${libDynloadDir.absolutePath}",
            "PYTHONDONTWRITEBYTECODE" to "1",
            "PYTHONUNBUFFERED" to "1",
            "HOME" to context.filesDir.absolutePath,
            "TMPDIR" to context.cacheDir.absolutePath,
            "LD_LIBRARY_PATH" to nativeLibDir
        )
    }
}
