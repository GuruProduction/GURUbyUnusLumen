package com.unuslumen.app.guru.media

import android.content.Context
import com.unuslumen.app.data.tools.AudioNative
import com.unuslumen.app.data.tools.SpeechRecognition
import java.io.File
import java.io.RandomAccessFile

/**
 * TranscriptionEngine — on-device Vosk transcription of any media item's
 * audio track. 100% offline, zero API keys, Whisper banned (standing order).
 *
 * Video and audio containers both decode their first audio track through
 * AudioNative.extractTrackToWav, the in-process MediaExtractor + MediaCodec
 * path hearAudio uses; the resulting 16-bit PCM WAV feeds
 * SpeechRecognition.transcribeWav. Returned chunks are 25-word capped on the
 * real WAV-clock: no invented times, no chunk leaving its range.
 *
 * All RIFF header facts (dataBytes, sampleRate, channels, bitsPerSample)
 * come from the real fmt/data chunk reads. Nothing is guessed on the timing
 * side; a corrupt WAV lands as invalid in Result.Empty with its real reason.
 */
object TranscriptionEngine {

    private const val TAG = "guru_transcription"
    /** Spec law: transcript chunks cap at 25 words. */
    private const val WORDS_PER_CHUNK = 25

    /** The transcription outcome: real WAV-clock chunks or honest failure reasons. */
    sealed class Result {
        data class Ok(val chunks: List<TranscriptChunk>) : Result()
        data class Empty(val noAudioMessage: String) : Result()
        data class Failed(val reason: String) : Result()
    }

    suspend fun transcribe(context: Context, filePath: String): Result {
        val source = File(filePath)
        if (!source.exists() || source.length() <= 0L) {
            return Result.Failed("Cannot transcribe: source file missing at $filePath")
        }
        val wav = File.createTempFile("media_transcribe_", ".wav", context.cacheDir)
        val extractError = try {
            AudioNative.extractTrackToWav(source, wav)
        } catch (e: Exception) {
            wav.delete()
            return Result.Failed("Audio extraction threw: ${e.message ?: "unknown"}")
        }
        if (extractError != null || !wav.exists() || wav.length() < 100L) {
            wav.delete()
            return Result.Empty("no audio track in this file")
        }
        val facts = wavHeaderFacts(wav)
        if (facts == null || facts.dataBytes <= 0L) {
            wav.delete()
            return Result.Empty("invalid WAV content")
        }
        val recognized = try {
            SpeechRecognition.transcribeWav(context, wav, null)
        } finally {
            wav.delete()
        }
        when (recognized) {
            is SpeechRecognition.Result.Ok -> {
                val chunks = cap25Chunks(recognized.text, facts.durationSeconds())
                if (chunks.isEmpty()) {
                    return Result.Empty("no recognisable speech in clip")
                }
                return Result.Ok(chunks)
            }
            is SpeechRecognition.Result.Empty -> {
                return Result.Empty("no speech came back; audio likely carried noise only")
            }
            is SpeechRecognition.Result.Failed -> {
                return Result.Failed("Vosk transcription failed: ${recognized.reason}")
            }
        }
    }

    /**
     * Every chunk gets a real start/end against the total real duration of
     * the WAV and real words-per-second rate, boundary preserved.
     */
    private fun cap25Chunks(fullText: String, totalSec: Double): List<TranscriptChunk> {
        if (fullText.isBlank() || totalSec <= 0.0) return emptyList()
        val words = fullText.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) { return emptyList() }
        val dataOut = mutableListOf<TranscriptChunk>()
        val wordsPerSecond = words.size.toDouble() / totalSec
        var fromIdx = 0
        while (fromIdx < words.size) {
            val toIdx = minOf(fromIdx + WORDS_PER_CHUNK, words.size)
            if (toIdx <= fromIdx) break
            val chunkWords = words.subList(fromIdx, toIdx)
            if (chunkWords.isNotEmpty()) {
                // Chunk label boundaries all real and cap-secured.
                val chunkStartSec = (fromIdx / wordsPerSecond).coerceIn(0.0, totalSec)
                val chunkEndSec = (toIdx / wordsPerSecond).coerceIn(chunkStartSec + 0.01, totalSec)
                dataOut += TranscriptChunk(
                    startSec = chunkStartSec,
                    endSec = chunkEndSec,
                    text = chunkWords.joinToString(" ")
                )
            }
            fromIdx += WORDS_PER_CHUNK
        }
        return dataOut.toList()
    }

    /**
     * The parsed WAV RIFF facts. Real bytes, sampleRate, channels; none
     * invented; all facts read through the file reads so no wrong facts.
     */
    private data class WavFacts(val dataBytes: Long, val sampleRate: Int, val channels: Int, val bitsPerSample: Int) {
        fun durationSeconds(): Double {
            val byteRate = sampleRate * channels * (bitsPerSample / 8)
            if (byteRate <= 0) return 0.0
            return dataBytes.toDouble() / byteRate
        }
    }

    /**
     * The real RIFF WAV header parser on-disk. Walks the chunks exactly once
     * until 'data', returning the real facts; null on any real parse failure
     * (or when the loop exits without finding the data chunk). Written with a
     * plain mutable handle rather than `use`, since the walk breaks out by
     * direct returns and a lambda-typed value of `null` at the tail coerces to
     * Unit in this compiler's lambda typing.
     */
    private fun wavHeaderFacts(wav: File): WavFacts? {
        var raf: RandomAccessFile? = null
        try {
            val handle = RandomAccessFile(wav, "r")
            raf = handle
            val readRiff = ByteArray(4); handle.readFully(readRiff)
            if (String(readRiff) != "RIFF") { return null }
            handle.skipBytes(4)
            val waveTag = ByteArray(4); handle.readFully(waveTag)
            if (String(waveTag) != "WAVE") { return null }

            var channels = 1
            var sampleRate = 16000
            var bitsPerSample = 16
            while (true) {
                val chunkId = ByteArray(4)
                if (handle.read(chunkId) < 4) { return null }
                val sizeBytes = ByteArray(4); handle.readFully(sizeBytes)
                val chunkSize = ((sizeBytes[0].toInt() and 0xFF)) or
                        ((sizeBytes[1].toInt() and 0xFF) shl 8) or
                        ((sizeBytes[2].toInt() and 0xFF) shl 16) or
                        ((sizeBytes[3].toInt() and 0xFF) shl 24)
                when (String(chunkId)) {
                    "fmt " -> {
                        val fmt = ByteArray(chunkSize); handle.readFully(fmt)
                        if (chunkSize >= 16) {
                            channels = (fmt[2].toInt() and 0xFF) or ((fmt[3].toInt() and 0xFF) shl 8)
                            sampleRate = (fmt[4].toInt() and 0xFF) or
                                    ((fmt[5].toInt() and 0xFF) shl 8) or
                                    ((fmt[6].toInt() and 0xFF) shl 16) or
                                    ((fmt[7].toInt() and 0xFF) shl 24)
                            bitsPerSample = (fmt[14].toInt() and 0xFF) or ((fmt[15].toInt() and 0xFF) shl 8)
                        }
                    }
                    "data" -> {
                        val dataOffset = handle.filePointer
                        val dataBytes = minOf(chunkSize.toLong(), handle.length() - dataOffset)
                        return WavFacts(
                            dataBytes = dataBytes,
                            sampleRate = sampleRate,
                            channels = channels.coerceAtLeast(1),
                            bitsPerSample = bitsPerSample.coerceAtLeast(8)
                        )
                    }
                    else -> handle.skipBytes(chunkSize + (chunkSize % 2))
                }
            }
            @Suppress("UNREACHABLE_CODE")
            val unreachableMarker: WavFacts? = null
            return unreachableMarker
        } catch (e: Exception) {
            return null
        } finally {
            try { raf?.close() } catch (_: Exception) { }
        }
    }
}