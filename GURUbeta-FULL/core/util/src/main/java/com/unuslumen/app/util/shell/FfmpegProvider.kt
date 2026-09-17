package com.unuslumen.app.util.shell

import android.content.Context
import java.io.File

/**
 * Locates the BUNDLED ffmpeg binary for the current ABI.
 *
 * The binary ships in jniLibs as libguru_ffmpeg.so (ffmpeg-kit megastatic or a
 * cross-compiled static ffmpeg following the ToyboxProvider extraction pattern).
 * All consumers (ImageTurnAssembler contact sheets, CameraToolExecutor RTSP,
 * FileProcessingToolExecutor probing/frames) call ffmpegBinaryPath() so a single
 * place owns the lookup: bundled APK first, then GURU's private termux-style
 * userland, then the standalone Termux app (only if the user bootstrapped it),
 * then null when nothing exists.
 *
 * Static lookup object — no extraction needed because Android itself extracts
 * nativeLibraryDir contents at install time; we only check existence and
 * executability. Falls back gracefully so callers never crash on a missing
 * binary — they degrade to Android platform media APIs instead.
 */
object FfmpegProvider {

    private const val SO_NAME = "libguru_ffmpeg.so"

    /**
     * Best available ffmpeg binary path, or null when none exists.
     * Callers that receive null must use the platform MediaMetadataRetriever /
     * MediaCodec paths instead of shelling out.
     */
    fun ffmpegBinaryPath(context: Context): String? {
        // 1. Bundled in the APK's nativeLibraryDir (SELinux exec allowed)
        val bundled = File(context.applicationInfo.nativeLibraryDir, SO_NAME)
        if (bundled.exists()) return bundled.absolutePath

        // 2. GURU's private termux-style userland inside app storage
        val appLocalCandidates = listOf(
            File(context.filesDir, "termux/usr/bin/ffmpeg"),
            File(context.filesDir, "usr/bin/ffmpeg")
        )
        appLocalCandidates.firstOrNull { it.exists() && it.canExecute() }?.let { return it.absolutePath }

        // 3. Standalone Termux app (only when the user manually installed it)
        try {
            val termuxBin = File("/data/data/com.termux/files/usr/bin/ffmpeg")
            if (termuxBin.exists() && termuxBin.canExecute()) return termuxBin.absolutePath
        } catch (_: Exception) {
            // No permission to probe Termux storage — treat as absent
        }

        return null
    }

    /** True when some ffmpeg binary is usable on this device right now. */
    fun isAvailable(context: Context): Boolean = ffmpegBinaryPath(context) != null
}