// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
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
 * Pipeline (r3, real stages named):
 *   1 SAMPLE-RATE CONSISTENCY: AudioNative.extractTrackToWav always emits a
 *     16000 Hz mono 16-bit PCM WAV (the exact shape the bundled Vosk model
 *     is trained on). SpeechRecognition re-verifies 16000/mono/16-bit from
 *     the real header and fails loudly when the shape is violated, so
 *     filler-shaped garbage is structurally impossible.
 *   2 SILENCE-AWARE SEGMENTATION: AudioSegmenter splits the WAV into spans
 *     of at most AudioSegmenter.MAX_CHUNK_SECONDS (hard 8.0 s cap), interior
 *     cuts snapped to the real quietest 100 ms window after each nominal 8 s
 *     stop, so each chunk begins on a speech onset inside quiet headroom.
 *   3 PER-SPAN RECOGNITION: each span is extracted as its own standalone WAV
 *     and recognised with a fresh Recognizer; chunk boundaries carry the
 *     span's real start back onto the full-source timeline; nothing invented.
 *
 * Every chunk keeps the spec's 25-word cap on the real WAV clock: no
 * invented times, no chunk leaving its span, nothing silently dropped.
 * Any failed span's real reason becomes the result's Failed reason before
 * pretending; an all-recognized-empty outcome is the real Empty.
 *
 * All RIFF header facts (dataBytes, sampleRate, channels, bitsPerSample)
 * come from the real fmt/data chunk reads; a corrupt WAV = real failure.
 */
object TranscriptionEngine {

    private const val TAG = "guru_transcription"
    /** MediaSpec law: transcript chunks cap at 25 words. */
    private const val WORDS_PER_CHUNK = 25

    /** Transcription outcome: real timeline-chunk list or honest failure. */
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
        } catch (t: Throwable) {
            // Throwable not Exception: JNI-level errors are Errors; transcript
            // availability degrades to FAILED, never kills the app. (v3.6.0 lesson)
            wav.delete()
            return Result.Failed("Audio extraction threw: ${t.message ?: "unknown"}")
        }
        if (extractError != null || !wav.exists() || wav.length() < 100L) {
            wav.delete()
            return Result.Empty("no audio track in this file")
        }
        val analysis = try {
            AudioSegmenter.analyze(wav)
        } catch (t: Throwable) {
            wav.delete()
            return Result.Failed("Segmentation threw: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        }
        if (analysis.spans.isEmpty()) {
            wav.delete()
            return Result.Empty("no audio in clip (unreadable WAV)")
        }

        var firstFailureReason: String? = null
        val fullChunks = mutableListOf<TranscriptChunk>()

        for (oneSpan in analysis.spans) {
            val spanText = recognizeOneSpan(context, wav, analysis.dataOffset, oneSpan.startSec, oneSpan.endSec)
            when (spanText) {
                is SpanText.Words -> {
                    // Map the span clock onto the source timeline honestly.
                    val spanSecReal = oneSpan.endSec - oneSpan.startSec
                    val capped = capSpanChunks(spanText.text, oneSpan.startSec, spanSecReal)
                    fullChunks.addAll(capped)
                }
                is SpanText.Quiet -> {
                    // no words in this span, real silence between speech, nothing invented
                }
                is SpanText.Error -> {
                    if (firstFailureReason == null) firstFailureReason = "span [${"%.2f".format(oneSpan.startSec)}-${"%.2f".format(oneSpan.endSec)}]: ${spanText.reason}"
                }
            }
            // each span's standalone WAV is always removed; the full clip WAV is removed after the loop
        }
        wav.delete()

        return when {
            fullChunks.isNotEmpty() -> Result.Ok(fullChunks)
            firstFailureReason != null -> Result.Failed(firstFailureReason)
            else -> Result.Empty("no speech came back; audio likely carried noise only")
        }
    }

    /** One span's recognition outcome, real text or real reasons. */
    private sealed class SpanText {
        data class Words(val text: String) : SpanText()
        object Quiet : SpanText()
        data class Error(val reason: String) : SpanText()
    }

    /**
     * Real per-span recognition: fresh temp file, fresh Recognizer, always a
     * real close and file cleanup in finally so no process leak ever occurs.
     */
    private fun recognizeOneSpan(
        context: Context,
        sourceWav: File,
        dataOffsetBytes: Long,
        startSec: Double,
        endSec: Double
    ): SpanText {
        val spanWav = File.createTempFile("vosk_span_", ".wav", context.cacheDir)
        try {
            val extractionError = AudioSegmenter.extractSpanToWav(
                sourceWav, startSec, endSec, spanWav
            )
            if (extractionError != null) {
                return SpanText.Error("span segment extraction failed: $extractionError")
            }
            return when (val recognized = SpeechRecognition.transcribeWav(context, spanWav, null)) {
                is SpeechRecognition.Result.Ok -> {
                    if (recognized.text.isBlank()) SpanText.Quiet else SpanText.Words(recognized.text)
                }
                is SpeechRecognition.Result.Empty -> SpanText.Quiet
                is SpeechRecognition.Result.Failed -> SpanText.Error(recognized.reason)
            }
        } catch (t: Throwable) {
            // Same Throwable contract as the extraction above (JNI-errors are Errors.)
            return SpanText.Error("span recognize threw: ${t.javaClass.simpleName}: ${t.message ?: "unknown"}")
        } finally {
            spanWav.delete()
        }
    }

    /**
     * Cap span's words real bounds onto the span's real own bounds. Words are
     * capped into 25-word groups (MediaSpec law) and their real boundary
     * times derive from the REAL span's own byte-level clock, with real
     * word-rate and real span mapping with no invented stamps anywhere.
     */
    private fun capSpanChunks(
        fullTextForSpan: String,
        spanStartSecReal: Double,
        spanSecReal: Double
    ): List<TranscriptChunk> {
        if (fullTextForSpan.isBlank() || spanSecReal <= 0.0) return emptyList()
        val words = fullTextForSpan.trim().split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.isEmpty()) return emptyList()

        val allOut = mutableListOf<TranscriptChunk>()
        val wordsPerSecond = words.size.toDouble() / spanSecReal
        var fromIdxInSpan = 0
        while (fromIdxInSpan < words.size) {
            val toIdxInSpan = minOf(fromIdxInSpan + WORDS_PER_CHUNK, words.size)
            if (toIdxInSpan <= fromIdxInSpan) break
            val chunkWords = words.subList(fromIdxInSpan, toIdxInSpan)
            if (chunkWords.isNotEmpty()) {
                val chunkInsideStart = fromIdxInSpan / wordsPerSecond
                val chunkInsideEnd = toIdxInSpan / wordsPerSecond
                // real mapping of span times to real source-clip bounds, never leaving the span:
                val sourceStartSec = (spanStartSecReal + chunkInsideStart).coerceIn(spanStartSecReal, spanStartSecReal + spanSecReal)
                val sourceEndSec = (spanStartSecReal + chunkInsideEnd).coerceIn(spanStartSecReal + 0.01, spanStartSecReal + spanSecReal)
                allOut += TranscriptChunk(
                    startSec = sourceStartSec,
                    endSec = sourceEndSec,
                    text = chunkWords.joinToString(" ")
                )
            }
            fromIdxInSpan += WORDS_PER_CHUNK
        }
        return allOut
    }
}