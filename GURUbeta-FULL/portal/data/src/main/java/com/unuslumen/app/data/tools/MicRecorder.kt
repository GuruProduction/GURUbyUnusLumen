package com.unuslumen.app.data.tools

import android.annotation.SuppressLint
import android.content.Context
import android.media.MediaMuxer
import android.media.MediaRecorder
import java.io.File

/**
 * In-process microphone recording on the platform MediaRecorder — replaces the
 * old shell out to a nonexistent `mediarecorder` binary / ffmpeg. Zero exec,
 * zero dependencies. Produces AAC in an .m4a container (Android's native
 * recorder output), which AudioNative.extractTrackToWav decodes cleanly.
 */
object MicRecorder {

    /** Record [durationSeconds] of mic audio to [output]. Returns null on success. */
    @SuppressLint("MissingPermission")
    fun record(context: Context, output: File, durationSeconds: Int): String? {
        var recorder: MediaRecorder? = null
        try {
            recorder = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                MediaRecorder(context)
            } else {
                @Suppress("DEPRECATION")
                MediaRecorder()
            }.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44_100)
                setOutputFile(output.absolutePath)
                prepare()
                start()
            }
            // Let the mic run for the requested duration
            Thread.sleep((durationSeconds.coerceIn(1, 300)) * 1000L)
            try { recorder.stop() } catch (_: Exception) {}
            return null
        } catch (e: Exception) {
            try { recorder?.release() } catch (_: Exception) {}
            return e.message ?: "Recording failed"
        } finally {
            try { recorder?.release() } catch (_: Exception) {}
        }
    }
}