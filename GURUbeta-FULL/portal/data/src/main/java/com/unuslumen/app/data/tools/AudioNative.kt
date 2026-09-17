package com.unuslumen.app.data.tools

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Native audio operations on Android's framework APIs. Zero external binaries —
 * the bionic linker rejects every externally-shipped ELF (dynamic ffmpeg = linker
 * namespace conflict, static ET_EXEC = PIE rule, musl static = "Could not find a
 * PHDR"), so ffmpeg/ffprobe cannot be shipped. These replace the old shell-outs.
 *
 * trim():    MediaExtractor -> MediaMuxer sample-copy. Lossless, fast, output keeps
 *            the source codec (typically AAC-in-MP4). Only muxable containers work.
 * convert(): decode source to 16-bit PCM via MediaCodec, then either write a WAV
 *            header around it (wav) or re-encode with the platform AAC encoder into
 *            .m4a. Android has no built-in mp3/ogg/flac encoders; the calling tool
 *            rejects those targets with an honest message before reaching here.
 *
 * Every public function returns null on success or a specific error string.
 */
object AudioNative {

    // ------------------------------------------------------------------
    // trim
    // ------------------------------------------------------------------

    /**
     * Trim [input] to the [startSeconds, endSeconds] range by copying compressed
     * samples through MediaExtractor into a MediaMuxer MP4 output. The output keeps
     * the source codec (typically AAC), so this is lossless and fast.
     * Returns null on success, or a specific error string on failure.
     */
    fun trim(input: File, output: File, startSeconds: Float, endSeconds: Float): String? {
        val extractor = MediaExtractor()
        var muxer: MediaMuxer? = null
        try {
            extractor.setDataSource(input.absolutePath)

            // Locate the first audio track and remember its format
            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = f
                    extractor.selectTrack(i)
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) {
                return "No audio track in ${input.name}"
            }

            val startUs = (startSeconds * 1_000_000f).toLong().coerceAtLeast(0L)
            val endUs = (endSeconds * 1_000_000f).toLong()

            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux
            val muxerTrack = muxer.addTrack(trackFormat)
            extractor.seekTo(startUs, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
            muxer.start()

            val info = MediaCodec.BufferInfo()
            val buffer = java.nio.ByteBuffer.allocate(1024 * 1024)
            var wroteAny = false

            while (true) {
                val sampleSize = extractor.readSampleData(buffer, 0)
                if (sampleSize < 0) break
                val sampleTimeUs = extractor.sampleTime
                if (sampleTimeUs > endUs) break
                if (sampleTimeUs >= startUs) {
                    info.offset = 0
                    info.size = sampleSize
                    info.presentationTimeUs = sampleTimeUs - startUs
                    info.flags = extractor.sampleFlags
                    muxer.writeSampleData(muxerTrack, buffer, info)
                    wroteAny = true
                }
                if (!extractor.advance()) break
            }

            if (!wroteAny) {
                output.delete()
                return "No audio samples in range ${startSeconds}s to ${endSeconds}s — the range may fall outside the file's duration"
            }
            return null
        } catch (e: Exception) {
            output.delete()
            return "Trim failed: ${e.message}. Supported input containers are MP4/M4A/3GP audio."
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------
    // convert
    // ------------------------------------------------------------------

    /**
     * Convert any decodable audio input into [targetFormat], either "wav" (raw PCM
     * dump with a proper header) or "m4a"/"aac" (platform AAC encoder + MP4 muxer).
     * Returns null on success, or a specific error string on failure.
     */
    fun convert(input: File, output: File, targetFormat: String): String? {
        return try {
            val decoded = decodeToPcm(input)
            if (decoded == null) {
                return "Could not decode audio — format unsupported, or the file contains no audio track"
            }
            if (decoded.pcm.isEmpty()) {
                return "Decoding produced no audio data — file may be silent-only or corrupt"
            }
            if (targetFormat == "wav") {
                writeWavFile(decoded.pcm, decoded.sampleRate, decoded.channelCount, output)
            } else {
                encodePcmToAac(decoded.pcm, decoded.sampleRate, decoded.channelCount, output)
            }
        } catch (e: Exception) {
            output.delete()
            "Conversion failed: ${e.message}"
        }
    }

    /** Decoded 16-bit PCM plus the source stream's format facts. */
    private data class PcmData(val pcm: ByteArray, val sampleRate: Int, val channelCount: Int)

    /**
     * Decode the first audio track of [input] entirely to 16-bit PCM.
     * Returns null if no audio track exists or decoding fails.
     */
    private fun decodeToPcm(input: File): PcmData? {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(input.absolutePath)

            var trackIndex = -1
            var trackFormat: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    trackIndex = i
                    trackFormat = f
                    extractor.selectTrack(i)
                    break
                }
            }
            if (trackIndex < 0 || trackFormat == null) return null

            val sampleRate = trackFormat.getInteger(MediaFormat.KEY_SAMPLE_RATE)
            val channels = trackFormat.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
            val srcMime = trackFormat.getString(MediaFormat.KEY_MIME) ?: return null

            val codec = MediaCodec.createDecoderByType(srcMime)
            codec.configure(trackFormat, null, null, 0)
            codec.start()

            val pcmOut = ByteArrayOutputStream()
            val info = MediaCodec.BufferInfo()
            var inputDone = false
            var outputDone = false

            while (!outputDone) {
                // Feed the decoder
                if (!inputDone) {
                    val inIndex = codec.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = codec.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val sampleSize = extractor.readSampleData(inBuffer, 0)
                            if (sampleSize < 0) {
                                codec.queueInputBuffer(inIndex, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(inIndex, 0, sampleSize, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                }

                // Drain output
                val outIndex = codec.dequeueOutputBuffer(info, 10_000)
                if (outIndex >= 0) {
                    val outBuffer = codec.getOutputBuffer(outIndex)
                    if (outBuffer != null && info.size > 0) {
                        val chunk = ByteArray(info.size)
                        outBuffer.get(chunk)
                        pcmOut.write(chunk)
                    }
                    codec.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
                // INFO_TRY_AGAIN_LATER and INFO_OUTPUT_FORMAT_CHANGED: just loop again
            }

            codec.stop()
            codec.release()
            return PcmData(pcmOut.toByteArray(), sampleRate, channels)
        } catch (e: Exception) {
            return null
        } finally {
            try { extractor.release() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------
    // extractTrackToWav — public helper for the hearAudio tool
    // ------------------------------------------------------------------

    /**
     * Extract the audio track of [input] (audio OR video container) entirely to a
     * 16kHz mono PCM WAV suitable for speech recognition. Any resampling to 16k
     * is NOT attempted — the source rate is preserved; callers feed the WAV to
     * whatever engine accepts it.
     * Returns null on success (output written), or a specific error string.
     */
    fun extractTrackToWav(input: File, output: File): String? {
        return try {
            val decoded = decodeToPcm(input)
            if (decoded == null) {
                "Could not decode audio — format unsupported, or the file contains no audio track"
            } else if (decoded.pcm.isEmpty()) {
                "Audio track decoded to zero bytes — file may be silent-only"
            } else {
                writeWavFile(decoded.pcm, decoded.sampleRate, decoded.channelCount, output)
            }
        } catch (e: Exception) {
            output.delete()
            "Audio extraction failed: ${e.message}"
        }
    }

    // ------------------------------------------------------------------
    // wav writer
    // ------------------------------------------------------------------

    /**
     * Wrap raw 16-bit little-endian PCM in a WAV (RIFF) container.
     * Returns null on success, or an error string on failure.
     */
    private fun writeWavFile(pcmBytes: ByteArray, sampleRate: Int, channelCount: Int, output: File): String? {
        return try {
            val channels = if (channelCount > 0) channelCount else 1
            val bitsPerSample = 16
            val blockAlign = channels * 2 // 16-bit = 2 bytes per sample per channel
            val byteRate = sampleRate * blockAlign
            val dataLen = pcmBytes.size

            java.io.RandomAccessFile(output, "rw").use { raf ->
                raf.setLength(0)
                // RIFF chunk descriptor
                raf.writeBytes("RIFF")
                raf.writeIntLe(36 + dataLen)
                raf.writeBytes("WAVE")
                // fmt subchunk
                raf.writeBytes("fmt ")
                raf.writeIntLe(16)                      // PCM chunk size
                raf.writeShortLe(1)                     // audio format: PCM
                raf.writeShortLe(channels)
                raf.writeIntLe(sampleRate)
                raf.writeIntLe(byteRate)
                raf.writeShortLe(blockAlign)
                raf.writeShortLe(bitsPerSample)
                // data subchunk
                raf.writeBytes("data")
                raf.writeIntLe(dataLen)
                raf.write(pcmBytes)
            }
            if (output.exists() && output.length() > 0) null
            else "WAV writer produced an empty file"
        } catch (e: Exception) {
            output.delete()
            "Failed to write WAV: ${e.message}"
        }
    }

    /**
     * Encode 16-bit PCM into AAC inside an .m4a container using the platform encoder.
     * Returns null on success, or a specific error string on failure.
     */
    private fun encodePcmToAac(pcmBytes: ByteArray, sampleRate: Int, channelCount: Int, output: File): String? {
        var encoder: MediaCodec? = null
        var muxer: MediaMuxer? = null
        try {
            val encodeFormat = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channelCount).apply {
                setInteger(MediaFormat.KEY_AAC_PROFILE, android.media.MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                setInteger(MediaFormat.KEY_BIT_RATE, 128_000)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 65536)
            }
            val enc = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC)
            enc.configure(encodeFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            enc.start()
            encoder = enc

            val mux = MediaMuxer(output.absolutePath, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
            muxer = mux

            val info = MediaCodec.BufferInfo()
            var muxerTrack = -1
            var pcmOffset = 0
            var presentationUs = 0L
            var outputDone = false
            var wroteAny = false
            var eosQueued = false

            while (!outputDone) {
                // Feed the encoder with PCM chunks
                if (pcmOffset < pcmBytes.size) {
                    val inIndex = enc.dequeueInputBuffer(10_000)
                    if (inIndex >= 0) {
                        val inBuffer = enc.getInputBuffer(inIndex)
                        if (inBuffer != null) {
                            val chunkSize = minOf(inBuffer.capacity(), pcmBytes.size - pcmOffset)
                            inBuffer.clear()
                            inBuffer.put(pcmBytes, pcmOffset, chunkSize)
                            val bytesPerSecond = sampleRate * channelCount * 2 // 16-bit = 2 bytes
                            val chunkUs = (chunkSize.toLong() * 1_000_000L) / bytesPerSecond
                            enc.queueInputBuffer(inIndex, 0, chunkSize, presentationUs, 0)
                            presentationUs += chunkUs
                            pcmOffset += chunkSize
                        }
                    }
                } else if (!eosQueued) {
                    // All PCM fed — signal end of stream (retried until the encoder accepts it,
                    // otherwise a full input queue would leave this loop draining forever)
                    val eosIndex = enc.dequeueInputBuffer(10_000)
                    if (eosIndex >= 0) {
                        enc.queueInputBuffer(eosIndex, 0, 0, presentationUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                        eosQueued = true
                    }
                }

                // Drain encoder output
                val outIndex = enc.dequeueOutputBuffer(info, 10_000)
                if (outIndex == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    muxerTrack = muxer.addTrack(enc.outputFormat)
                    muxer.start()
                } else if (outIndex >= 0) {
                    val encoded = enc.getOutputBuffer(outIndex)
                    if (encoded != null && info.size > 0 && muxerTrack >= 0) {
                        muxer.writeSampleData(muxerTrack, encoded, info)
                        wroteAny = true
                    }
                    enc.releaseOutputBuffer(outIndex, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) {
                        outputDone = true
                    }
                }
                // INFO_TRY_AGAIN_LATER: just loop again
            }

            if (!wroteAny) {
                output.delete()
                return "Encoding produced no audio samples"
            }
            if (!output.exists() || output.length() == 0L) {
                return "Encoding produced an empty file"
            }
            return null
        } catch (e: Exception) {
            output.delete()
            return "Encoding failed: ${e.message}"
        } finally {
            try { muxer?.stop() } catch (_: Exception) {}
            try { muxer?.release() } catch (_: Exception) {}
            try { encoder?.stop() } catch (_: Exception) {}
            try { encoder?.release() } catch (_: Exception) {}
        }
    }

    // ------------------------------------------------------------------
    // little-endian helpers for the WAV header
    // ------------------------------------------------------------------

    private fun java.io.RandomAccessFile.writeIntLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
        write((value shr 16) and 0xFF)
        write((value shr 24) and 0xFF)
    }

    private fun java.io.RandomAccessFile.writeShortLe(value: Int) {
        write(value and 0xFF)
        write((value shr 8) and 0xFF)
    }
}