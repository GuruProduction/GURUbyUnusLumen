package com.unuslumen.app.util.shell

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Manages the bundled Tesseract OCR engine.
 *
 * The tesseract 5.5.0 CLI is cross-compiled against leptonica 1.84.1 and
 * shipped per-ABI as libguru_tesseract.so in jniLibs (the same extraction
 * pattern as toybox/curl/git — Android only extracts and executes files
 * matching lib*.so from nativeLibraryDir). The eng.traineddata language model
 * ships in assets/tesseract/tessdata and is copied to app storage on first
 * use.
 *
 * Tesseract finds language data via the TESSDATA_PREFIX env var, so runs
 * execute through env -TESSDATA_PREFIX. The binary needs no root or Termux:
 * nativeLibraryDir has the nativedir_file SELinux label which allows exec on
 * all supported Android versions.
 */
class TesseractProvider(private val context: Context) {

    companion object {
        private const val TAG = "guru"
        private const val SO_NAME = "libguru_tesseract.so"

        /** Models bundled under assets/tesseract/tessdata/. */
        private val BUNDLED_LANGUAGES = listOf("eng")
    }

    private var initialized = false
    private var available = false

    /** Directory holding the extracted traineddata, passed as TESSDATA_PREFIX. */
    private val tessdataDir: File
        get() = File(context.filesDir, "tesseract/tessdata")

    /** Full path to the extracted binary (lives in nativeLibraryDir). */
    val binaryPath: String
        get() = File(context.applicationInfo.nativeLibraryDir, SO_NAME).absolutePath

    /**
     * Tesseract 4/5 resolves TESSDATA_PREFIX as the DIRECTORY CONTAINING the
     * .traineddata files (unlike 3.x which wanted the parent). This must be the
     * tessdata dir itself or the engine fails with "Error opening data file"
     * and emits empty stdout — the exact symptom of the first on-device run.
     */
    val tessdataPrefix: String
        get() = tessdataDir.absolutePath

    /**
     * Check the binary exists and extract language models on first use.
     * Idempotent and cheap after the first success. Never blocks the send path:
     * the caller decides where it is allowed to run.
     */
    suspend fun initialize(): Boolean = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
        if (initialized) return@withContext available
        try {
            val binary = File(binaryPath)
            if (!binary.exists()) {
                Log.w(TAG, "TesseractProvider: $SO_NAME not present for this ABI")
                initialized = true
                return@withContext false
            }
            if (!binary.canExecute()) binary.setExecutable(true, false)

            // Extract traineddata on first use / after version upgrades
            tessdataDir.mkdirs()
            for (lang in BUNDLED_LANGUAGES) {
                val target = File(tessdataDir, "$lang.traineddata")
                val assetPath = "tesseract/tessdata/$lang.traineddata"
                val already = target.exists() && target.length() > 1_000_000L
                if (!already) {
                    context.assets.open(assetPath).use { input ->
                        target.outputStream().use { output -> input.copyTo(output) }
                    }
                    Log.d(TAG, "TesseractProvider: extracted $lang.traineddata (${target.length()} bytes)")
                }
            }

            available = true
            initialized = true
            Log.d(TAG, "TesseractProvider: ready — binary=$binaryPath tessdata=$tessdataDir")
            true
        } catch (e: Exception) {
            Log.e(TAG, "TesseractProvider: init failed", e)
            initialized = true
            false
        }
    }

    fun isAvailable(): Boolean = available && File(binaryPath).exists()

    /**
     * OCR command components for AppRuntimeExec (pure app-runtime exec —
     * no root, no ADB, no shell). The binary lives in nativeLibraryDir which
     * carries the nativedir_file SELinux label permitting exec from the app
     * UID. TESSDATA_PREFIX points at the tessdata directory (tesseract 4/5
     * resolves $TESSDATA_PREFIX/<lang>.traineddata) and the model was extracted
     * there on first run.
     */
    fun ocrArgs(imagePath: String, psm: Int = 3): List<String> =
        listOf(imagePath, "stdout", "--psm", psm.toString())

    fun ocrEnv(): Map<String, String> = mapOf("TESSDATA_PREFIX" to tessdataPrefix)
}