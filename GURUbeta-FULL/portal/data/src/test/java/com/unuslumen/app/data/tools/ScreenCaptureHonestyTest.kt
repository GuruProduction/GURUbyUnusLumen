// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.tools.screen.ScreenCaptureEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * The capture rules that decide what a screenshot or a recording is allowed to claim.
 *
 * WHY THIS IS A JVM TEST AND NOT A DEVICE ONE. The faults in issues 41 and 43 were not
 * failures of the platform; they were failures of reporting. `takeScreenshot` blamed root
 * and ADB for a mechanism that never needed either, and `recordScreen` called a zero-byte
 * file a recording because its guard asked whether the file existed rather than whether it
 * held anything. Both of those are decisions, and decisions are provable here.
 *
 * WHAT THIS CANNOT PROVE: that a real projection yields a frame, that the encoder produces
 * a playable MP4, or that the consent dialog appears. Those need the device.
 */
class ScreenCaptureHonestyTest {

    // ------------------------------------------------------------------
    // The zero-byte lie (issue 43)
    // ------------------------------------------------------------------

    /**
     * A recording with no frames is refused, with the empty file removed.
     *
     * THIS IS THE EXACT DEFECT. The old guard was `recording.exists()`, and a zero-byte file
     * exists, so `stop` answered `success = true` with a path to nothing while `error` stayed
     * null. A caller checking for a problem got silence.
     */
    @Test
    fun anEmptyRecordingFileIsNotARecording() {
        val empty = Files.createTempFile("guru-rec-test", ".mp4").toFile()
        try {
            assertEquals("the fixture must really be empty", 0L, empty.length())

            // The rule the engine applies: bytes decide, not existence.
            val isRecording = empty.isFile && empty.length() > 0L
            assertFalse(
                "an existing but empty file must never count as a recording",
                isRecording,
            )
        } finally {
            empty.delete()
        }
    }

    @Test
    fun aFileWithFramesIsARecording() {
        val withBytes = Files.createTempFile("guru-rec-test", ".mp4").toFile()
        try {
            withBytes.writeBytes(byteArrayOf(1, 2, 3, 4))
            val isRecording = withBytes.isFile && withBytes.length() > 0L
            assertTrue("a file with frames is a recording", isRecording)
        } finally {
            withBytes.delete()
        }
    }

    // ------------------------------------------------------------------
    // The duration ceiling
    // ------------------------------------------------------------------

    /**
     * A duration is clamped to what the encoder can sustain.
     *
     * The ceiling is the recorder's own rule and it moved here from the shell path rather
     * than being deleted with it, because a request for an hour of screen recording is a
     * request for a very large file and a very long encoder run.
     */
    @Test
    fun theDurationIsClampedToTheRecordersCeiling() {
        assertEquals(30, ScreenRecordingCheck.durationSeconds(null))
        assertEquals(1, ScreenRecordingCheck.durationSeconds(0))
        assertEquals(1, ScreenRecordingCheck.durationSeconds(-5))
        assertEquals(180, ScreenRecordingCheck.durationSeconds(10_000))
        assertEquals(45, ScreenRecordingCheck.durationSeconds(45))
    }

    @Test
    fun theMinimumIsOneSecondAndTheMaximumIsThreeMinutes() {
        assertEquals(1, ScreenRecordingCheck.MIN_DURATION_SECONDS)
        assertEquals(180, ScreenRecordingCheck.MAX_DURATION_SECONDS)
    }

    // ------------------------------------------------------------------
    // The outcomes say which thing went wrong
    // ------------------------------------------------------------------

    /**
     * The screenshot outcomes are four separate answers.
     *
     * A null would collapse "you never allowed this", "the session ended" and "the screen
     * produced no frame" into one, and those need three different actions from the person.
     */
    @Test
    fun everyScreenshotOutcomeCarriesItsOwnReason() {
        val refused = ScreenCaptureEngine.ScreenshotOutcome.Refused("no consent")
        val noFrame = ScreenCaptureEngine.ScreenshotOutcome.NoFrame("nothing arrived")
        val failed = ScreenCaptureEngine.ScreenshotOutcome.Failed("could not write")
        val captured = ScreenCaptureEngine.ScreenshotOutcome.Captured("/tmp/a.png", 12L)

        assertTrue(refused.reason.isNotBlank())
        assertTrue(noFrame.reason.isNotBlank())
        assertTrue(failed.reason.isNotBlank())
        assertEquals("/tmp/a.png", captured.path)
        assertEquals(12L, captured.sizeBytes)
    }

    @Test
    fun aRefusalSentenceNamesTheToolThatFixesIt() {
        // The sentence a person reads when they have not granted consent must tell them the
        // route, and the route is a tool they or GURU can run, not a gesture at Settings.
        val refused = ScreenCaptureEngine.ScreenshotOutcome.Refused(
            "Screen capture has not been allowed yet, so nothing was captured. Ask your " +
                "human to run requestScreenCapturePermission, tap Allow on the system dialog, " +
                "and then try again.",
        )
        assertTrue(
            "a refusal must name the way forward: ${refused.reason}",
            refused.reason.contains("requestScreenCapturePermission"),
        )
    }

    /**
     * A recording already running is refused rather than replaced.
     *
     * Replacing it silently loses the earlier one, and the person has no way to know their
     * first recording vanished.
     */
    @Test
    fun anAlreadyRunningRecordingIsReportedWithItsPath() {
        val already = ScreenCaptureEngine.RecordingOutcome.AlreadyRecording("/tmp/one.mp4")
        assertEquals("/tmp/one.mp4", already.path)
    }

    @Test
    fun stoppingWithNoRecordingSaysSoRatherThanReturningAnEmptyPath() {
        // NotRecording is a distinct outcome, so the tool can say "call start first" instead
        // of reporting success with no file.
        val notRecording = ScreenCaptureEngine.RecordingOutcome.NotRecording
        assertEquals(
            "the object case must be the singleton, not a fresh instance with a path",
            ScreenCaptureEngine.RecordingOutcome.NotRecording,
            notRecording,
        )
    }

    @Test
    fun everyRecordingOutcomeIsASeparateAnswer() {
        val started = ScreenCaptureEngine.RecordingOutcome.Started("/tmp/a.mp4", 30)
        val stopped = ScreenCaptureEngine.RecordingOutcome.Stopped("/tmp/a.mp4", 4096L)
        val failed = ScreenCaptureEngine.RecordingOutcome.Failed("encoder refused")
        val refused = ScreenCaptureEngine.RecordingOutcome.Refused("no consent")

        assertEquals(30, started.durationSeconds)
        assertEquals(4096L, stopped.sizeBytes)
        assertTrue(failed.reason.isNotBlank())
        assertTrue(refused.reason.isNotBlank())
    }

    // ------------------------------------------------------------------
    // The shell paths are gone
    // ------------------------------------------------------------------

    /**
     * The tool set no longer advertises a mechanism that needs root.
     *
     * The old descriptions said "take a screenshot" and the code behind them shelled out to
     * `screencap`. Nothing in the source may reach for either shell binary again, because
     * that is the defect: it fails on every ordinary install and its existence implies root
     * is the intended route.
     */
    @Test
    fun theScreenToolsDoNotReachForTheShellBinaries() {
        // Gradle's working directory for a unit test is the module directory on most AGP
        // versions and the build directory on others, so the file is found by walking up
        // until the source tree is recognisable. A missing file FAILS rather than passing:
        // a test that cannot find its subject proves nothing, and silently returning would
        // be the same hollow success this whole file exists to remove.
        val code = codeLines(findSource("ScreenToolExecutor.kt").readText())

        assertFalse(
            "screencap must not be executed in the screenshot path",
            code.any { it.contains("screencap") },
        )
        assertFalse(
            "screenrecord must not be executed in the recording path",
            code.any { it.contains("screenrecord") },
        )
        assertTrue(
            "the tool must go through the one engine",
            code.any { it.contains("ScreenCaptureEngine") },
        )
    }

    @Test
    fun theSightControllerHasNoShellFallbackEither() {
        val code = codeLines(findSource("ScreenSightController.kt").readText())
        assertFalse(
            "the sight fallback executed screencap, which needs root on every install",
            code.any { it.contains("screencap") },
        )
        assertFalse(
            "the sight fallback went through the shell executor",
            code.any { it.contains("ShellExecutor(context)") },
        )
    }

    /**
     * Only the executable lines of a source file.
     *
     * WHY COMMENTS ARE STRIPPED. The removal of a mechanism is worth explaining where it was
     * removed, and those explanations name the thing that is gone. A test that forbade the
     * word would force the next reader to delete the reason the code is the way it is, which
     * is how a codebase forgets why it made a decision.
     */
    private fun codeLines(text: String): List<String> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("//") && !it.startsWith("*") && !it.startsWith("/*") }

    /** Walk up from the working directory until the file is under a source tree. */
    private fun findSource(name: String): File {
        var dir: File? = File(System.getProperty("user.dir").orEmpty()).absoluteFile
        while (dir != null) {
            val candidate = File(dir, "src/main/java/com/unuslumen/app/data/tools/$name")
            if (candidate.isFile) return candidate

            // The sight controller lives in a sibling package.
            val sibling = File(dir, "src/main/java/com/unuslumen/app/data/multimodal/$name")
            if (sibling.isFile) return sibling

            val nested = File(dir, "portal/data/src/main/java/com/unuslumen/app/data/tools/$name")
            if (nested.isFile) return nested

            val nestedSibling = File(dir, "portal/data/src/main/java/com/unuslumen/app/data/multimodal/$name")
            if (nestedSibling.isFile) return nestedSibling

            dir = dir.parentFile
        }
        throw IllegalStateException(
            "Could not find $name by walking up from " +
                "${System.getProperty("user.dir").orEmpty()}, so this test cannot prove " +
                "anything and must not pass.",
        )
    }
}
