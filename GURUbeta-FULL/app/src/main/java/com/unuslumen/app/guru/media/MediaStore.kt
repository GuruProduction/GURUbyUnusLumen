package com.unuslumen.app.guru.media

import android.content.Context
import java.io.File

/**
 * MediaStore — the single owner of every on-device location the media module
 * touches. One directory per media item under files/media_library, everything
 * inside internal app storage (privacy law). All paths this module writes
 * come from here; never a stray filesDir write anywhere else.
 */
object MediaStore {

    /**
     * The root directory the media library occupies inside the app's private
     * storage: /data/user/0/com.unuslumen.app.gurubeta/files/media_library/
     * (the debug applicationId carries .debug suffix in debuggable build and
     * the path resolves to the same library root at runtime).
     */
    const val LIBRARY_DIR_NAME = "media_library"

    /** Sidecar JSON written for the transcript chunks of one media item. */
    const val TRANSCRIPT_JSON_NAME = "transcript.json"

    /** Sidecar JSON written for the keyframe scene strip of one media item. */
    const val SCENES_JSON_NAME = "scenes.json"

    /** Poster thumbnail file name shown in the library grid. */
    const val POSTER_FILE_NAME = "poster.jpg"

    fun libraryRoot(context: Context): File {
        val dir = File(context.filesDir, LIBRARY_DIR_NAME)
        dir.mkdirs()
        return dir
    }

    fun mediaDir(context: Context, mediaId: String): File {
        val dir = File(libraryRoot(context), mediaId)
        dir.mkdirs()
        return dir
    }

    fun keyFrameFilePath(mediaId: String, index: Int): String {
        // The caller supplies the media dir through the media item's own subtree;
        // this name is only stamped from inside MediaIngestService where context
        // is available. Name pattern is fixed so the detail view can trust it.
        return "keyframe_%03d.jpg".format(index)
    }

    fun transcriptFile(mediaDir: File): File = File(mediaDir, TRANSCRIPT_JSON_NAME)

    fun scenesFile(mediaDir: File): File = File(mediaDir, SCENES_JSON_NAME)

    fun posterFile(mediaDir: File): File = File(mediaDir, POSTER_FILE_NAME)

    /** Full-resolution zoom frame extracted on demand. Timestamp-kept. */
    fun zoomFile(mediaDir: File, timestampSec: Double): File {
        val stamp = (timestampSec * 1000).toLong()
        return File(mediaDir, "zoom_ms%d.jpg".format(stamp))
    }

    /** Source bytes preserved under a neutral name when the file is not its own kind of move. */
    fun storedFile(mediaDir: File, originalName: String): File =
        File(mediaDir, originalName.ifBlank { "source.bin" })
}