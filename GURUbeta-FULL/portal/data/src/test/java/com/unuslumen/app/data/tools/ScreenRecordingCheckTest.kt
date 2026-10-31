// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the screen-recording success rule, because issue 43 was a tool reporting
 * a recording it never made.
 *
 * THE DEFECT. `recordScreen stop` answered `success: true` with a path to a
 * ZERO-BYTE file, and `error` was null. The guard was `exists()`, and an empty
 * file exists. Start also claimed success without checking the recorder had
 * launched, and stop found its file by globbing the cache, so it could return a
 * file from an earlier session.
 *
 * Every test here is a case the device report named.
 */
class ScreenRecordingCheckTest {

    // ---- start ----

    @Test
    fun aLiveRecorderIsNotAFailureEvenWithNoBytesYet() {
        // screenrecord creates the file only after the display and encoder are
        // up, so zero bytes from a live process is normal, not an error.
        assertNull(ScreenRecordingCheck.startFailure(processAlive = true, recordedBytes = 0, detail = ""))
    }

    @Test
    fun aDeadRecorderWithNoFramesIsAFailure() {
        val failure = ScreenRecordingCheck.startFailure(processAlive = false, recordedBytes = 0, detail = "")
        assertNotNull("A recorder that exited writing nothing did not record anything.", failure)
    }

    @Test
    fun theStartFailureCarriesWhatTheRecorderSaid() {
        val failure = ScreenRecordingCheck.startFailure(
            processAlive = false,
            recordedBytes = 0,
            detail = "ERROR: unable to configure codec",
        )
        assertNotNull(failure)
        assertTrue(
            "The recorder's own words are the only evidence of WHY it failed, so " +
                "the message must carry them.",
            failure!!.contains("unable to configure codec"),
        )
    }

    @Test
    fun aDeadRecorderThatWroteFramesIsNotAFailure() {
        // It recorded and finished on its own; stop collects the file.
        assertNull(ScreenRecordingCheck.startFailure(processAlive = false, recordedBytes = 4096, detail = ""))
    }

    // ---- stop: the issue 43 case ----

    @Test
    fun aZeroByteFileIsNotASuccessfulRecording() {
        val failure = ScreenRecordingCheck.stopFailure(
            path = "/cache/screenrecord_1.mp4",
            fileExists = true,
            recordedBytes = 0,
            endedCleanly = true,
            detail = "",
        )
        assertNotNull(
            "This is issue 43 exactly: the file exists, it is empty, and the old " +
                "code reported success because exists() was the only guard.",
            failure,
        )
        assertTrue("the message must say the file is empty", failure!!.contains("0 bytes"))
    }

    @Test
    fun aMissingFileIsAFailure() {
        val failure = ScreenRecordingCheck.stopFailure("/cache/x.mp4", false, 0, true, "")
        assertNotNull(failure)
        assertTrue(failure!!.contains("does not exist"))
    }

    @Test
    fun aFileWithFramesAndACleanStopIsARecording() {
        assertNull(
            ScreenRecordingCheck.stopFailure(
                path = "/cache/screenrecord_1.mp4",
                fileExists = true,
                recordedBytes = 123456,
                endedCleanly = true,
                detail = "",
            ),
        )
    }

    @Test
    fun aFileWithFramesButADirtyStopIsNotAPlayableRecording() {
        val failure = ScreenRecordingCheck.stopFailure(
            path = "/cache/screenrecord_1.mp4",
            fileExists = true,
            recordedBytes = 123456,
            endedCleanly = false,
            detail = "",
        )
        assertNotNull(
            "screenrecord finalises the MP4 from its signal handler. Killed any " +
                "other way the file has bytes and will not play, which is the same " +
                "hollow success one layer down.",
            failure,
        )
        assertFalse("a dirty stop is not reported as clean", failure!!.contains("success"))
    }

    // ---- duration ----

    @Test
    fun durationIsClampedToWhatTheRecorderAccepts() {
        assertEquals(30, ScreenRecordingCheck.durationSeconds(null))
        assertEquals(1, ScreenRecordingCheck.durationSeconds(0))
        assertEquals(1, ScreenRecordingCheck.durationSeconds(-5))
        assertEquals(180, ScreenRecordingCheck.durationSeconds(9999))
        assertEquals(45, ScreenRecordingCheck.durationSeconds(45))
    }
}
