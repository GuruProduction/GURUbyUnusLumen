// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import android.content.Context
import android.util.Log
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
 * Layout law: the alphacephei archive carries ONE wrapper directory,
 * vosk-model-small-en-us-0.15/, while Model(ROOT) demands conf/, am/,
 * graph/, ivector/ exactly AT ROOT. Extraction strips the wrapper prefix on
 * the fly so the model files materialise flat at filesDir/vosk-model.
 * Validity checks the REAL files Kaldi opens (am/final.mdl, graph/HCLr.fst,
 * ivector/final.ie, conf/model.conf); the .model_ok marker is honoured only
 * on a root that verifies, so a legacy stale marker sitting above a bad
 * layout can never pin that bad layout in place; a bad root self-heals by
 * full wipe + fresh flat extraction and is re-verified before Model() runs.
 */
object SpeechRecognition {

    private const val TAG = "guru_vosk"

    // The exact wrapper prefix alphacephei's vosk-model-small-en-us-0.15.zip
    // places on every entry; this object strips it at unzip time.
    private const val VOSK_MODEL_ENTRY_PREFIX = "vosk-model-small-en-us-0.15/"
    private const val VOSK_MODEL_ASSET_NAME = "model-vosk-en-us-small.zip"

    sealed class Result {
        data class Ok(val text: String, val confidence: Float?) : Result()
        object Empty : Result()
        data class Failed(val reason: String) : Result()
    }

    @Volatile private var cachedModel: Model? = null

    private fun modelDir(context: Context): File = File(context.filesDir, "vosk-model")

    /**
     * Files Vosk's Kaldi Model(root) actually reads for this small en-US
     * model, checked as a full file set. Anything less or wrongly placed is
     * an invalid root and triggers the self-heal wipe + re-extract path.
     */
    private fun validVoskModelRoot(root: File): Boolean {
        if (!root.isDirectory) return false
        if (!File(root, "conf/model.conf").isFile) return false
        if (!File(root, "conf/mfcc.conf").isFile) return false
        if (!File(root, "am/final.mdl").isFile) return false
        if (!File(root, "graph/HCLr.fst").isFile) return false
        if (!File(root, "graph/Gr.fst").isFile) return false
        if (!File(root, "ivector/final.ie").isFile) return false
        return true
    }

    /**
     * Unpack the bundled asset zip to target with the single alphacephei
     * wrapper directory stripped on the fly, so am/, conf/, graph/, ivector/
     * land FLAT at target. Zip-slip defense stays enforced. Entries that do
     * NOT carry the wrapper prefix (and are neither empty-string roots nor
     * the wrapper's own dir entry) mean the source layout changed; extraction
     * is abandoned and nothing is trusted downstream.
     */
    private fun unzipFlat(assetName: String, target: File, context: Context) {
        File(target, VOSK_MODEL_ENTRY_PREFIX).let { // no-op, purely to document the expected root entry
        }

        context.assets.open(assetName).use { assetStream ->
            val zis = java.util.zip.ZipInputStream(assetStream.buffered())
            while (true) {
                val entry = zis.nextEntry ?: break
                val name = entry.name
                when {
                    name == VOSK_MODEL_ENTRY_PREFIX -> {
                        // The wrapper dir entry itself; nothing to write, target IS it.
                        zis.closeEntry()
                        continue
                    }
                    name.startsWith(VOSK_MODEL_ENTRY_PREFIX) -> {
                        // The normal case: strip the wrapper, write flat.
                        val relative = name.removePrefix(VOSK_MODEL_ENTRY_PREFIX)
                        if (relative.isBlank()) {
                            zis.closeEntry()
                            continue
                        }
                        val outFile = File(target, relative).canonicalFile
                        if (!outFile.path.startsWith(target.canonicalFile.path)) {
                            throw SecurityException("Blocked zip slip path: $name")
                        }
                        if (entry.isDirectory) {
                            outFile.mkdirs()
                        } else {
                            outFile.parentFile?.mkdirs()
                            FileOutputStream(outFile).use { output -> zis.copyTo(output) }
                        }
                        zis.closeEntry()
                    }
                    else -> {
                        // Any unexpected entry inside the archive: refuse and tear it all
                        // down instead of letting a foreign tree stand under Model().
                        zis.close()
                        val cause = IllegalStateException(
                            "Bundled vosk zip no longer matches the expected single-wrapper layout: unexpected top-level entry '$name'"
                        )
                        target.deleteRecursively()
                        throw cause
                    }
                }
            }
            zis.close()
        }
    }

    /**
     * Ensures, idempotent, that a Kaldi-verified FLAT model root exists under
     * filesDir/vosk-model, and then returns it. Cache: valid root + marker.
     * Valid-root-with-missing-marker is self-corrected in place (rare; the
     * marker is always written by this function only after verified passes).
     * Anything else wipes and re-extracts; verification runs before marker;
     * on verification failure the tree is destroyed so no half-broken state
     * can ever survive, and the error carries the full context.
     */
    private fun ensureModelDir(context: Context): File {
        val dir = modelDir(context)
        val tag = File(dir, ".model_ok")
        val layoutValid: Boolean = validVoskModelRoot(dir)
        Log.d(TAG, "vosk dir=${dir.absolutePath} exists=${dir.exists()} layoutValid=$layoutValid marker=${tag.exists()}")
        if (layoutValid == true && tag.exists()) return dir
        if (layoutValid == true) {
            tag.writeText("ok") // Verified, marker omitted last run; fix cheaply, cache thereafter.
            return dir
        }
        Log.d(TAG, "vosk layout invalid or missing, rebuilding from asset zip now.")

        dir.deleteRecursively()
        dir.mkdirs()

        unzipFlat(VOSK_MODEL_ASSET_NAME, dir, context)

        if (!validVoskModelRoot(dir)) {
            dir.deleteRecursively()
            throw IllegalStateException(
                "Bundled vosk model did not materialise as a valid root following flat extraction: ${dir.absolutePath}"
            )
        }

        tag.writeText("ok")
        return dir
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
     * Transcribe a 16-bit PCM WAV file at exactly 16kHz mono — the format the
     * bundled Vosk model is trained on. Feeding anything else used to arrive as
     * filler-shaped garbage ("ah ah oh oh"), so the input gate refuses loudly:
     * wrong rate/channels/depth is a Named FAILED reason, never a pretend run.
     * Thread-blocking; call from IO dispatch.
     */
    fun transcribeWav(context: Context, wav: File, language: String?): Result {
        try {
            val model = obtainModel(context)
            val header = readWavHeader(wav)
                ?: return Result.Failed("Not a valid WAV file (missing RIFF header)")
            if (header.bitsPerSample != 16) {
                return Result.Failed("Expected 16-bit PCM WAV, got ${header.bitsPerSample}-bit")
            }
            if (header.sampleRate != 16000) {
                return Result.Failed(
                    "Vosk en-US model is a 16000 Hz model; got ${header.sampleRate} Hz WAV. " +
                        "Resample before feeding the recogniser."
                )
            }
            if (header.channelCount != 1) {
                return Result.Failed(
                    "Vosk en-US model needs mono; got ${header.channelCount}-channel WAV. " +
                        "Downmix to mono before feeding the recogniser."
                )
            }
            val recognizer = Recognizer(model, 16000.0f)
            try {
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

    private data class WavHeader(val dataOffset: Long, val bitsPerSample: Int, val sampleRate: Int, val channelCount: Int)

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
            var sampleRate = 16000
            var channelCount = 1
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
                            channelCount = ((fmtBody[2].toInt() and 0xFF)) or ((fmtBody[3].toInt() and 0xFF) shl 8)
                            sampleRate = ((fmtBody[4].toInt() and 0xFF)) or
                                    ((fmtBody[5].toInt() and 0xFF) shl 8) or
                                    ((fmtBody[6].toInt() and 0xFF) shl 16) or
                                    ((fmtBody[7].toInt() and 0xFF) shl 24)
                            bitsPerSample = ((fmtBody[14].toInt() and 0xFF)) or ((fmtBody[15].toInt() and 0xFF) shl 8)
                        }
                    }
                    "data" -> {
                        val pos = raf.filePointer
                        return WavHeader(pos, bitsPerSample, sampleRate, channelCount)
                    }
                    else -> raf.skipBytes(chunkSize + (chunkSize % 2)) // chunks are word-aligned
                }
            }
        }
    }
}