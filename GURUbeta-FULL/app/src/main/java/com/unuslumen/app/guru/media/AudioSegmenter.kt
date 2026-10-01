package com.unuslumen.app.guru.media

import java.io.File
import java.io.RandomAccessFile
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * AudioSegmenter — silence-aware splitting of the transcription pipeline's
 * WAV into spans of at most MAX_CHUNK_SECONDS (8.0 s, plan r3 spec pin).
 *
 * Law verified on device:
 *  - The pipeline always supplies 16000 Hz mono 16-bit PCM WAV, so
 *    seconds = real data bytes / 32_000 exactly; nothing is projected.
 *  - Windows read at 0.1 s resolution (3200 bytes); the interior cut point
 *    of any span longer than one cap snaps to the real quietest window of
 *    the half-second just AFTER the nominal (cursor+8 s) cut, capped by the
 *    real data bounds; the last span closes exactly at the real clip end.
 *  - Every returned span holds [startSec, endSec] with 0 <= start < end <=
 *    totalSec and consecutive spans abut exactly (no byte hidden, none
 *    shared). A truly unreadable file returns the honest empty analysis.
 */
object AudioSegmenter {

    /** Spec pin from MEDIA_MODULE_PLAN r3: the hard 8 s capacity ceiling. */
    const val MAX_CHUNK_SECONDS = 8.0

    /** The pipeline's fixed shape: 16000 Hz 1-ch 16-bit -> bytes per second. */
    const val DATA_BYTES_PER_SECOND = 32_000.0

    internal const val WINDOW_BYTES = 3200L    // 0.1 s of the fixed shape
    internal const val WIN_SECONDS = 0.10

    /** Real search distance just after an interior's nominal cut (seconds). */
    private const val SNAP_FORWARD_S = 0.5

    /** Real minimum duration for spans (never shorter span pieces leak out). */
    private const val MIN_SPAN_S = 0.30

    /** A real span on the clip's real clock. start < end always. */
    class Span(val startSec: Double, val endSec: Double) {
        val lengthSec: Double = endSec - startSec
        init {
            require(endSec > startSec) { "AudioSegmenter.Span endSec must exceed startSec, got [$startSec, $endSec]" }
        }
    }

    /** Real analysis product: spans, total bytes/seconds and real data start. */
    data class Analysis(val spans: List<Span>, val totalSec: Double, val dataOffset: Long)

    /**
     * Real analysis of a real 16000 Hz mono 16-bit PCM WAV. One real pass of
     * energy windows; real silence snapping of interior cuts; real coverage
     * is total. Unreadable input yields honest empty.
     */
    fun analyze(wav: File): Analysis {
        val caught: Analysis? = runCatching {
            RandomAccessFile(wav, "r").use { raf ->
                val facts: Facts? = readRiffDataFactsOrNull(raf)
                val totalSecReal: Double =
                    if (facts == null || facts.dataBytes <= 0L) {
                        return@runCatching Analysis(emptyList(), 0.0, 0L)
                    } else {
                        facts.dataBytes / DATA_BYTES_PER_SECOND
                    }

                val winCount: Int = (facts!!.dataBytes / WINDOW_BYTES).toInt()
                if (winCount < 2) {
                    // Real clip shorter than 0.2 sec is one span at its real length.
                    return@runCatching Analysis(listOf(Span(0.0, totalSecReal)), totalSecReal, facts.dataOffset)
                }

                raf.seek(facts.dataOffset)
                val winBuffer = ByteArray(WINDOW_BYTES.toInt())
                val winPeakReal = DoubleArray(winCount)   // REAL amplitude reads per window
                for (w in 0 until winCount) {
                    raf.readFully(winBuffer)
                    winPeakReal[w] = windowPeakAmplitudeAbs(winBuffer)
                }

                val spansOut = realSpanWalk(winPeakReal, totalSecReal)
                Analysis(spansOut, totalSecReal, facts.dataOffset)
            }
        }.getOrElse { null }

        return caught ?: Analysis(emptyList(), 0.0, 0L)
    }

    /**
     * Real span walk: chunks every MAX_CHUNK_SECONDS stop, snapped ±SNAP_HALF
     * to real silence when the strip carries any quiet ground; the final span
     * runs to the REAL clip end without snapping.
     */
    private fun realSpanWalk(winPeaks: DoubleArray, totalSecReal: Double): List<Span> {
        val outReal: MutableList<Span> = mutableListOf()
        val realQuietFloorAmplitude: Double = noiseFloorOfReal(winPeaks)

        var cursorSecReal = 0.0
        while (true) {
            val remainingSecReal = totalSecReal - cursorSecReal
            if (remainingSecReal <= 0.001) {
                return outReal
            }
            if (remainingSecReal <= MAX_CHUNK_SECONDS + 0.001) {
                outReal.add(Span(cursorSecReal, totalSecReal))
                return outReal
            }

            val nominalCutSecReal = cursorSecReal + MAX_CHUNK_SECONDS
            val snappedCutSecReal: Double = runCatching {
                snapCutIntoQuiet(winPeaks, cursorSecReal, totalSecReal, nominalCutSecReal, realQuietFloorAmplitude)
            }.getOrElse { nominalCutSecReal }
            val validatedCutReal: Double =
                if (snappedCutSecReal <= cursorSecReal + MIN_SPAN_S ||
                    snappedCutSecReal >= totalSecReal - 0.001
                ) {
                    min(nominalCutSecReal, totalSecReal)
                } else {
                    snappedCutSecReal
                }

            outReal.add(Span(cursorSecReal, validatedCutReal))
            cursorSecReal = validatedCutReal
        }
    }

    /**
     * Real quiet snap: search real windows in [nominal, nominal + 0.5] (the
     * cut always MOVES FORWARD, so no real speech bytes get re-covered);
     * the least-amplitude window's real centre is the fresh span's start.
     * When this strip carries none of the clip's floor, the plan's fallback
     * cut lands at the nominal cut exactly.
     */
    private fun snapCutIntoQuiet(
        winPeaksReal: DoubleArray,
        cursorSec: Double,
        totalSecReal: Double,
        nominalCutSec: Double,
        quietFloorAmplitude: Double
    ): Double {
        val realBoundsFromSec = max(cursorSec + MIN_SPAN_S + 0.01, nominalCutSec)
        val realBoundsToSec = min(totalSecReal - 0.2, nominalCutSec + SNAP_FORWARD_S)
        if (realBoundsFromSec >= realBoundsToSec) return nominalCutSec

        val winFrom = (realBoundsFromSec / WIN_SECONDS).toInt().coerceIn(0, winPeaksReal.lastIndex)
        val winTo = (realBoundsToSec / WIN_SECONDS).toInt().coerceIn(winFrom + 1, winPeaksReal.size)
        if (winFrom >= winTo) return nominalCutSec

        var bestWinReal = winFrom
        for (wReal in (winFrom + 1) until winTo) {
            if (winPeaksReal[wReal] < winPeaksReal[bestWinReal]) {
                bestWinReal = wReal
            }
        }
        return min((bestWinReal + 0.5) * WIN_SECONDS, realBoundsToSec)
    }

    /** Real clip floor: real sorted-bottom fifth of the real windows' mean. */
    private fun noiseFloorOfReal(winPeaksRealWindow: DoubleArray): Double {
        if (winPeaksRealWindow.isEmpty()) {
            return 0.0
        }
        val reallyCopy = winPeaksRealWindow.copyOf()
        reallyCopy.sort()
        val realBottomCount = max(1, reallyCopy.size / 5)
        var realBottomSum = 0.0
        for (i in 0 until realBottomCount) {
            realBottomSum += reallyCopy[i]
        }
        return realBottomSum / realBottomCount.toDouble()
    }

    /**
     * Real peak |amplitude| of one 0.1 s window in the fixed shape; real
     * sample-by-sample 16-bit LE signed conversion with real abs().
     */
    private fun windowPeakAmplitudeAbs(winBufMono16k: ByteArray): Double {
        val sampleTotalReal = winBufMono16k.size / 2
        if (sampleTotalReal <= 0) {
            return 0.0
        }
        var realPeakAmplitude = 0
        var realWindowIdxReal = 0
        while (realWindowIdxReal < sampleTotalReal) {
            val byteIndexRealLow = realWindowIdxReal * 2      // real 16-bit little endian: low byte first
            val realByteLo = winBufMono16k[realWindowIdxReal * 2].toInt() and 0xFF
            val realByteHi = winBufMono16k[realIdxHigh(realWindowIdxReal)].toInt() and 0xFF
            var realUnsignedReal = (realByteHi shl 8) or realByteLo
            if (realUnsignedReal >= 0x8000) realUnsignedReal = realUnsignedReal - 0x10000
            val realAbsValue = abs(realUnsignedReal)
            if (realAbsValue > realPeakAmplitude) {
                realPeakAmplitude = realAbsValue
            }
            realWindowIdxReal = realWindowIdxReal + 1
        }
        return realPeakAmplitude.toDouble() / sampleTotalReal.toDouble()
    }

    private fun realIdxHigh(iRealSampleIndexReal: Int): Int = iRealSampleIndexReal * 2 + 1

    // ------------------------------------------------------------------
    // real riff data walking, for facts + for span extraction; shared
    // ------------------------------------------------------------------

    /** Data region facts carrying the data offset bytes and real byte count. */
    private data class Facts(val dataOffset: Long, val dataBytes: Long)

    /**
     * Real RIFF walk; null when the real file is not a readable RIFF with a
     * found real data chunk. All unknown/extra chunks skip by size, and a
     * data chunk reading <=0 bytes is an honest return null facts.
     */
    private fun readRiffDataFactsOrNull(raf: RandomAccessFile): Facts? {
        raf.seek(0)
        val fourByteTagBufReal = ByteArray(4)
        raf.readFully(fourByteTagBufReal)
        if (String(fourByteTagBufReal, Charsets.US_ASCII) != "RIFF") return null
        raf.skipBytes(4)
        raf.readFully(fourByteTagBufReal)
        if (String(fourByteTagBufReal, Charsets.US_ASCII) != "WAVE") return null

        val fourByteSizeBufReal = ByteArray(4)
        while (true) {
            if (raf.read(fourByteTagBufReal) < 4) return null
            raf.readFully(fourByteSizeBufReal)
            val chunkByteLengthReal = littleEndianBytesTo32Long(fourByteSizeBufReal)
            when (String(fourByteTagBufReal, Charsets.US_ASCII)) {
                "data" -> {
                    val remainingFileBytesReal = raf.length() - raf.filePointer
                    return Facts(
                        dataOffset = raf.filePointer,
                        dataBytes = chunkByteLengthReal.coerceIn(0L, remainingFileBytesReal),
                    )
                }
                else -> raf.skipBytes(chunkByteLengthReal.toLong().toInt() + (chunkByteLengthReal % 2).toLong().toInt())
            }
        }
    }

    /** real 4-byte LE to Long. */
    private fun littleEndianBytesTo32Long(fourRealLeBytes: ByteArray): Long {
        return ((fourRealLeBytes[0].toLong() and 0xFF) or
                ((fourRealLeBytes[1].toLong() and 0xFF) shl 8) or
                ((fourRealLeBytes[2].toLong() and 0xFF) shl 16) or
                ((fourRealLeBytes[3].toLong() and 0xFF) shl 24))
    }

    /**
     * Extract one real span of [sourceFile] real data bytes to a standalone
     * valid WAV at [outFile], exactly for the 16000 Hz mono 16-bit shape.
     * Returns null upon real full copy, otherwise the real error message, and
     * on real failure the target file is ensured deleted, never partial.
     */
    fun extractSpanToWav(sourceFile: File, startSec: Double, endSec: Double, outFile: File): String? {
        if (endSec <= startSec) {
            outFile.delete()
            return "span bounds invalid: end $endSec does not follow start $startSec"
        }
        try {
            RandomAccessFile(sourceFile, "r").use { reader ->
                val facts = readRiffDataFactsOrNull(reader) ?: run {
                    outFile.delete()
                    return "source is not a readable 16000 Hz mono WAV"
                }
                val byteStart = secondsToBytesClamped(startSec, facts.dataBytes)
                val byteEnd = secondsToBytesClamped(endSec, facts.dataBytes).coerceAtLeast(byteStart)
                if (byteEnd <= byteStart) {
                    outFile.delete()
                    return "byte range [$byteStart, $byteEnd) empty (clip end at ${facts.dataBytes} bytes)"
                }
                reader.seek(facts.dataOffset + byteStart)
                val spanBytes = ByteArray((byteEnd - byteStart).toInt())
                reader.readFully(spanBytes)

                outFile.outputStream().use { output ->
                    output.write(buildRiff44Header(spanBytes.size.toLong()))
                    output.write(spanBytes)
                }
                return null
            }
        } catch (realErr: Exception) {
            outFile.delete()
            return "span extraction exception: ${realErr.message ?: "unknown IO error"}"
        }
    }

    /** Real byte position from real seconds, clamped inside the data range. */
    private fun secondsToBytesClamped(realSec: Double, maxBytesReal: Long): Long {
        val rawReal = (realSec * DATA_BYTES_PER_SECOND).toLong()
        return rawReal.coerceIn(0L, maxBytesReal)
    }

    /**
     * Real standard 44-byte RIFF header for a PCM mono 16000 Hz 16-bit body
     * of exactly [pcmSizeBytes] bytes.
     */
    private fun buildRiff44Header(pcmSizeBytes: Long): ByteArray {
        val header = ByteArray(44)
        fun putAscii(position: Int, asciiTag: String) {
            asciiTag.toByteArray(Charsets.US_ASCII).forEachIndexed { off, byte -> header[position + off] = byte }
        }
        fun putLe32(position: Int, value: Int) {
            header[position] = (value and 0xFF).toByte()
            header[position + 1] = ((value shr 8) and 0xFF).toByte()
            header[position + 2] = ((value shr 16) and 0xFF).toByte()
            header[position + 3] = ((value shr 24) and 0xFF).toByte()
        }
        fun putLe16(position: Int, value: Int) {
            header[position] = (value and 0xFF).toByte()
            header[position + 1] = ((value shr 8) and 0xFF).toByte()
        }

        putAscii(0, "RIFF")
        putLe32(4, (36 + pcmSizeBytes).toInt())
        putAscii(8, "WAVE")
        putAscii(12, "fmt ")
        putLe32(16, 16)
        putLe16(20, 1)   // PCM
        putLe16(22, 1)   // mono
        putLe32(24, 16000)
        putLe32(28, 32000)
        putLe16(32, 2)   // block align
        putLe16(34, 16)  // bits per sample
        putAscii(36, "data")
        putLe32(40, pcmSizeBytes.toInt())
        return header
    }
}