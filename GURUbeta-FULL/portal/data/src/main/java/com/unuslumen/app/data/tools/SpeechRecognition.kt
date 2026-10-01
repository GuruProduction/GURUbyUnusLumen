package com.unuslumen.app.data.tools

import android.content.Context
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import java.io.File
import java.io.FileOutputStream

/**
 * Fully on-device speech recognition via the bundled Vosk model.
 * Zero network, zero API keys, zero cloud. The model ships inside the APK at
 * assets/model-vosk-en-us-small.zip and is unpacked to filesDir on first use.
 *
 * Input: a 16-bit PCM WAV file (AudioNative.extractTrackToWav output). The
 * recogniser decodes it in chunks and returns the final recognised text with a
 * mean confidence over accepted hypotheses.
 */
object SpeechRecognition {

    sealed class Result {
        data class Ok(val text: String, val confidence: Float?) : Result()
        object Empty : Result()
        data class Failed(val reason: String) : Result()
    }

    @Volatile private var cachedModel: Model? = null

    private fun modelDir(context: Context): File = File(context.filesDir, "vosk-model")

    /**
     * Unzip the bundled asset model to filesDir exactly once. Vosk's Model class
     * needs a real directory path; Android assets can't be read as one, so the
     * first call materialises the archive to storage and every later call reuses it.
     *
     * Model-layout law (root-cause of the v3.6.x "Failed to create a model")
     * the archive carries a single wrapper folder, vosk-model-small-en-us-0.15/,
     * while Model(ROOT) demands conf/model.conf + am/ exactly AT the ROOT it is
     * given. ensureModelDir flattens: the one top-level dir the zip holds
     * becomes the extraction target itself, so am/, conf/, graph/, ivector/
     * land directly under filesDir/vosk-model. The .model_ok marker is only
     * ever written after the root verifies with the real file set.
     */
    private fun ensureModelDir(context: Context): File {
        val dir = modelDir(context)
        val tag = File(dir, ".model_ok")
        if (dir.exists() && tag.exists()) return dir

        dir.deleteRecursively()
        dir.mkdirs()
        val zipName = "model-vosk-en-us-small.zip"
        context.assets.open(zipName).use { input ->
            val tmp = File(context.cacheDir, zipName)
            FileOutputStream(tmp).use { output -> input.copyTo(output) }
            unzip(tmp, dir)
            tmp.delete()
        }
        flattenSingleTopLevelDir(dir)

        if (!validVoskModelRoot(dir)) {
            throw IllegalStateException(
                "Bundled vosk model did not materialise as a valid root (missing conf/model.conf or am/)."
            )
        }
        tag.writeText("ok")
        return dir
    }

    /**
     * When the extraction produced exactly one nested top folder and NOTHING
     * beside it (the alphacephei-zip layout), hoist every entry of that one
     * child into dir itself and drop the now-empty wrapper. Multi-entry
     * archives stay untouched, no invented merges.
     */
    private fun flattenSingleTopLevelDir(dir: File) {
        val children = dir.list().orEmpty().map { child -> File(dir, child) }
        val onlyTop = children.singleOrNull()?.takeIf { it.isDirectory }
        if (onlyTop == null) return
        val hasModelSignature = File(onlyTop, "conf/model.conf").isFile &&
            File(onlyTop, "am").isDirectory
        if (!hasModelSignature) return  // Wrapper with unexpected shape; layout untouched, verify step below fails loudly.

        val markerSuffix = "__hoist_tmp"
        val hoistTarget = File(dir, markerSuffix)
        hoistTarget.deleteRecursively()
        if (!onlyTop.renameTo(hoistTarget)) {
            return  // Rename failed on this FS; verification step below keeps everything honest instead of pretending.
        }
        val hoisted = hoistTarget.listFiles().orEmpty()
        var allMoved = true
        for (entry in hoisted) {
            val moved = entry.renameTo(File(dir, entry.name))
            if (!moved) { allMoved = false; break }
        }
        if (allMoved) {
            hoistTarget.deleteRecursively()
        } else {
            // Roll back to the exact pre-hoist state so nothing half-broken persists on disk.
            hoistTarget.deleteRecursively()
            dir.deleteRecursively()
        }
    }

    /**
     * Validate the layout Vosk's Kaldi base code requires at the model ROOT:
     * conf/model.conf reading is the canonical first op when creating, then
     * am/final.mdl for acoustic weights existence conf.
     */
    private fun validVoskModelRoot(root: File): Boolean {
        if (!root.isDirectory) return false
        if (!File(root, "conf/model.conf").isFile) return false
        if (!File(root, "am").isDirectory) return false
        if (!File(root, "graph").isDirectory) return false
        return true
    }

    private fun unzip(zip: File, target: File) {
        val zis = java.util.zip.ZipInputStream(zip.inputStream().buffered())
        while (true) {
            val entry = zis.nextEntry ?: break
            val outFile = File(target, entry.name).canonicalFile
            if (!outFile.path.startsWith(target.canonicalFile.path)) {
                throw SecurityException("Blocked zip slip path: ${entry.name}")
            }
            if (entry.isDirectory) {
                outFile.mkdirs()
            } else {
                outFile.parentFile?.mkdirs()
                FileOutputStream(outFile).use { output -> zis.copyTo(output) }
            }
            zis.closeEntry()
        }
        zis.close()
    }

    private fun obtainModel(context: Context): Model {
        cachedModel?.let { return it }
        synchronized(this) {
            cachedModel?.let { return it }
            val dir = ensureModelDir(context)
            val model = Model(dir.absolutePath)
            cachedModel = model
            return model
        }
    }

    /**
     * Transcribe a 16-bit PCM WAV file. Thread-blocking; call from IO dispatch.
     */
    fun transcribeWav(context: Context, wav: File, language: String?): Result {
        try {
            val model = obtainModel(context)
            val recognizer = Recognizer(model, 16000.0f)
            try {
                val header = readWavHeader(wav)
                    ?: return Result.Failed("Not a valid WAV file (missing RIFF header)")
                if (header.bitsPerSample != 16) {
                    return Result.Failed("Expected 16-bit PCM WAV, got ${header.bitsPerSample}-bit")
                }

                val fullText = StringBuilder()
                val confidences = mutableListOf<Float>()

                val buffer = ByteArray(8192)
                java.io.RandomAccessFile(wav, "r").use { raf ->
                    raf.seek(header.dataOffset)
                    while (true) {
                        val n = raf.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        if (recognizer.acceptWaveForm(buffer, n)) {
                            val result = JSONObject(recognizer.result)
                            val piece = result.optString("text", "")
                            if (piece.isNotBlank()) {
                                fullText.append(piece)
                                fullText.append(' ')
                            }
                            val conf = result.optDouble("confidence", -1.0)
                            if (conf >= 0) confidences.add(conf.toFloat())
                        }
                    }
                    val final = JSONObject(recognizer.finalResult)
                    val tail = final.optString("text", "")
                    if (tail.isNotBlank()) fullText.append(tail)
                    val finalConf = final.optDouble("confidence", -1.0)
                    if (finalConf >= 0) confidences.add(finalConf.toFloat())
                }

                val text = fullText.toString().trim().replace(Regex("\\s+"), " ")
                if (text.isBlank()) return Result.Empty
                val mean = if (confidences.isEmpty()) null
                    else confidences.reduce { a, b -> a + b } / confidences.size
                return Result.Ok(text, mean)
            } finally {
                recognizer.close()
            }
        } catch (e: Exception) {
            return Result.Failed(e.message ?: "Unknown on-device recognition error")
        }
    }

    private data class WavHeader(val dataOffset: Long, val bitsPerSample: Int)

    /**
     * Parse the RIFF/WAVE header to find the start of actual PCM data. Handles
     * arbitrary extra chunks (LIST, fact, etc) rather than assuming a fixed 44-byte header.
     */
    private fun readWavHeader(file: File): WavHeader? {
        java.io.RandomAccessFile(file, "r").use { raf ->
            val riff = ByteArray(4); raf.readFully(riff)
            if (String(riff) != "RIFF") return null
            raf.skipBytes(4) // riff chunk size
            val wave = ByteArray(4); raf.readFully(wave)
            if (String(wave) != "WAVE") return null

            var bitsPerSample = 16
            while (true) {
                val chunkId = ByteArray(4)
                if (raf.read(chunkId) < 4) return null
                val sizeBytes = ByteArray(4); raf.readFully(sizeBytes)
                val chunkSize = ((sizeBytes[0].toInt() and 0xFF)) or
                        ((sizeBytes[1].toInt() and 0xFF) shl 8) or
                        ((sizeBytes[2].toInt() and 0xFF) shl 16) or
                        ((sizeBytes[3].toInt() and 0xFF) shl 24)
                when (String(chunkId)) {
                    "fmt " -> {
                        val fmtBody = ByteArray(chunkSize); raf.readFully(fmtBody)
                        // fmt layout: audioFormat(2) channels(2) sampleRate(4) byteRate(4) blockAlign(2) bitsPerSample(2)
                        if (chunkSize >= 16) {
                            bitsPerSample = ((fmtBody[14].toInt() and 0xFF)) or ((fmtBody[15].toInt() and 0xFF) shl 8)
                        }
                    }
                    "data" -> {
                        val pos = raf.filePointer
                        return WavHeader(pos, bitsPerSample)
                    }
                    else -> raf.skipBytes(chunkSize + (chunkSize % 2)) // chunks are word-aligned
                }
            }
        }
    }
}