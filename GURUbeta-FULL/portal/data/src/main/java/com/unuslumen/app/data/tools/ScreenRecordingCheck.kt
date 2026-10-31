// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

/**
 * Decides whether a screen recording attempt produced a recording.
 *
 * THE DEFECT THIS EXISTS FOR (issue #43). recordScreen answered `stop` with
 * `success = true` and a path to a zero-byte file. The guard was
 * `recording.exists()`, and a zero-byte file exists, so the tool reported its
 * own empty placeholder as a recording while `error` stayed null. A caller
 * checking for a problem got nothing. The same call also discovered its file
 * by globbing the cache directory for the newest `screenrecord_*` file, so it
 * could hand back a file from an earlier session.
 *
 * A capture is real when the file it points at has content, which is the rule
 * `takeScreenshot` already used. A recording that wrote no frames is a
 * failure, and the failure names the reason, including whatever screenrecord
 * printed on stderr, because that is the part that says whether the recorder
 * was denied the display, denied an encoder, or never ran at all.
 *
 * Deliberately pure and free of Android types so the decision is unit
 * testable; launching and stopping the process needs a device. This is the
 * same split as WebFetchContentQuality, which exists for issue #5.
 */
object ScreenRecordingCheck {

    /**
     * A recording is only real when its file holds at least one byte. One is
     * enough on purpose: screenrecord creates the output file only after the
     * virtual display and encoder are configured, so a byte means frames were
     * actually produced. Zero means it never got that far.
     */
    const val MIN_RECORDING_BYTES = 1L

    /** The tool's documented default when the caller does not name a duration. */
    const val DEFAULT_DURATION_SECONDS = 30

    const val MIN_DURATION_SECONDS = 1

    /**
     * screenrecord's own default ceiling for one invocation. Asking for more
     * cannot produce more, so it is clamped here rather than passed through and
     * quietly shortened by the recorder.
     */
    const val MAX_DURATION_SECONDS = 180

    /** Longest slice of the recorder's output repeated back in a message. */
    private const val DETAIL_LIMIT = 200

    /** The duration to actually record for, clamped to what screenrecord accepts. */
    fun durationSeconds(requested: Int?): Int =
        (requested ?: DEFAULT_DURATION_SECONDS).coerceIn(MIN_DURATION_SECONDS, MAX_DURATION_SECONDS)

    /**
     * Why `start` must be reported as a failure, or null when the recorder is
     * worth reporting as started.
     *
     * A recorder that is still running has not failed, even before its first
     * byte, because screenrecord only creates the file once the display and
     * encoder are up. A recorder that has already exited having written frames
     * recorded successfully; `stop` collects the file. A recorder that exited
     * having written nothing never captured anything, and that is the case the
     * device report in issue #43 hid: the tool answered `success = true` and
     * then had nothing to hand back.
     */
    fun startFailure(processAlive: Boolean, recordedBytes: Long, detail: String): String? {
        if (recordedBytes >= MIN_RECORDING_BYTES) return null
        if (processAlive) return null
        return "screenrecord exited immediately without writing a single frame, " +
            "so no recording was started.${reasonSuffix(detail)}"
    }

    /**
     * The note `start` returns when it did start. Kept separate from the
     * failure text so the caller is never told a recording is underway when the
     * recorder has already finished.
     */
    fun startNote(processAlive: Boolean, recordedBytes: Long, durationSeconds: Int): String {
        if (processAlive) {
            return "Recording started for ${durationSeconds}s. Use 'stop' to finish."
        }
        return "Recording already finished on its own after writing $recordedBytes bytes. Use 'stop' to collect the file."
    }

    /**
     * Why the finished recording is not a recording, or null when it is.
     *
     * [endedCleanly] is false when the recorder had to be killed rather than
     * asked to stop. screenrecord installs handlers for SIGINT and SIGHUP and
     * finalises the MP4 from them, so a process killed any other way leaves a
     * file whose index was never written. That file has bytes and will not
     * play, which is the same hollow success one layer down.
     */
    fun stopFailure(
        path: String,
        fileExists: Boolean,
        recordedBytes: Long,
        endedCleanly: Boolean,
        detail: String
    ): String? {
        val reason = reasonSuffix(detail)
        return when {
            !fileExists ->
                "No recording was written: $path does not exist.$reason"
            recordedBytes < MIN_RECORDING_BYTES ->
                "No frames were written: $path is 0 bytes, so the recording is empty and nothing was captured.$reason"
            !endedCleanly ->
                "$path holds $recordedBytes bytes, but screenrecord did not stop cleanly, " +
                    "so the file was never finalised and will not play.$reason"
            else -> null
        }
    }

    private fun reasonSuffix(detail: String): String {
        val trimmed = detail.trim()
        if (trimmed.isEmpty()) return ""
        val shown = if (trimmed.length > DETAIL_LIMIT) trimmed.take(DETAIL_LIMIT) + "..." else trimmed
        return " Reported: \"$shown\""
    }
}
