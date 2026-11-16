// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How a vendor diagnostic command's outcome is classified.
 *
 * WHY THIS IS PURE AND TESTED. Vendor diagnostic and factory-test commands are the least
 * standardised thing on Android. On a retail phone the binary is usually missing, sometimes it
 * exists and refuses, and on a vendor build it runs. Those three look identical to a caller
 * that trusts an exit code, and two of them are not the capability existing.
 *
 * The distinction matters because the sentences are different: "this device does not have it"
 * sends nobody anywhere, and "the platform declined" says no setup will help. Reporting the
 * first as a generic failure sends a person hunting for a setting that does not exist.
 */
class DiagnosticsVerdictTest {

    private fun classify(exit: Int, stdout: String = "", stderr: String = "") =
        DiagnosticsVerdict.classify(exit, stdout, stderr)

    // ------------------------------------------------------------------
    // Ran
    // ------------------------------------------------------------------

    @Test
    fun realOutputThatCarriesNoRefusalIsReportedAsRan() {
        val verdict = classify(
            exit = 0,
            stdout = "vendor.diagnostic: state=idle\nvendor.diagnostic: last_check=ok",
        )
        assertEquals(DiagnosticsVerdict.Outcome.RAN, verdict.outcome)
        assertEquals(2, verdict.lines.size)
        assertNull("a clean run needs no deciding line", verdict.decidingLine)
    }

    // ------------------------------------------------------------------
    // Refused
    // ------------------------------------------------------------------

    /**
     * A denial in the output is a refusal even when the command exited zero.
     *
     * This is the lesson issue 44 shipped without: an exit code proves the command ran, not that
     * it was permitted.
     */
    @Test
    fun aPermissionDenialIsRefusedEvenWhenTheCommandExitedZero() {
        val verdict = classify(
            exit = 0,
            stdout = "Permission Denial: requires android.permission.DIAGNOSTIC",
        )
        assertEquals(DiagnosticsVerdict.Outcome.REFUSED, verdict.outcome)
        assertNotNull(verdict.decidingLine)
        assertTrue(verdict.decidingLine!!.contains("Permission Denial"))
    }

    @Test
    fun everyRefusalSignatureIsRecognised() {
        val samples = listOf(
            "permission denied for this user",
            "not permitted to access this service",
            "requires permission android.permission.DIAGNOSTIC",
            "no permission to read vendor state",
            "Operation not permitted",
            "java.lang.SecurityException: denied",
        )
        for (sample in samples) {
            assertEquals(
                "'$sample' must be a refusal",
                DiagnosticsVerdict.Outcome.REFUSED,
                classify(exit = 0, stdout = sample).outcome,
            )
        }
    }

    // ------------------------------------------------------------------
    // Absent
    // ------------------------------------------------------------------

    /** A shell's own words for a binary that does not exist are an absence, not a refusal. */
    @Test
    fun aMissingBinaryIsReportedAsAbsent() {
        val verdict = classify(exit = 127, stderr = "/system/bin/diag: not found")
        assertEquals(DiagnosticsVerdict.Outcome.ABSENT, verdict.outcome)
        assertNotNull(verdict.decidingLine)
    }

    /** 127 with no output at all is still the shell saying it could not find the command. */
    @Test
    fun exitOneTwentySevenWithNoOutputIsAbsent() {
        assertEquals(
            DiagnosticsVerdict.Outcome.ABSENT,
            classify(exit = 127).outcome,
        )
    }

    @Test
    fun everyAbsenceSignatureIsRecognised() {
        val samples = listOf(
            "/system/bin/thing: not found",
            "No such file or directory",
            "inaccessible or not found",
            "unknown command: diag",
            "unknown option --vendor",
            "the service does not exist",
        )
        for (sample in samples) {
            assertEquals(
                "'$sample' must be an absence",
                DiagnosticsVerdict.Outcome.ABSENT,
                classify(exit = 0, stderr = sample).outcome,
            )
        }
    }

    // ------------------------------------------------------------------
    // The order that decides an ambiguous case
    // ------------------------------------------------------------------

    /**
     * A refusal outranks an absence when both appear.
     *
     * A command that exists and is refused may also print something about a file it could not
     * open. Calling that absence would tell the person their phone lacks the capability when in
     * fact it has it and is refusing, which is the wrong fix in a different direction.
     */
    @Test
    fun aRefusalOutranksAnAbsenceWhenBothAppear() {
        val verdict = classify(
            exit = 1,
            stdout = "Permission Denial: requires permission",
            stderr = "file not found",
        )
        assertEquals(DiagnosticsVerdict.Outcome.REFUSED, verdict.outcome)
    }

    /**
     * No output at all with an unfamiliar exit code is refused, not ran.
     *
     * Reporting RAN here would claim data that is not there, which is worse than saying the
     * result is unclear.
     */
    @Test
    fun silenceWithNoRecognisableCodeIsNotReportedAsRan() {
        val verdict = classify(exit = 0)
        assertEquals(DiagnosticsVerdict.Outcome.REFUSED, verdict.outcome)
        assertTrue(verdict.lines.isEmpty())
    }

    // ------------------------------------------------------------------
    // The sentence
    // ------------------------------------------------------------------

    @Test
    fun theAbsenceSentenceBlamesTheDeviceAndNamesNoStep() {
        val sentence = DiagnosticsVerdict.absenceSentence(
            what = "a vendor diagnostic service",
            outcome = DiagnosticsVerdict.Outcome.ABSENT,
        )
        assertTrue(
            "it must be about the device: $sentence",
            sentence.contains("device does not carry"),
        )
        assertTrue(
            "it must say no setup changes it: $sentence",
            sentence.contains("no setup"),
        )
        assertFalse("it must not tell them to grant anything: $sentence", sentence.contains("Grant"))
    }

    @Test
    fun theRefusalSentenceSaysTheBuildCannotRatherThanThePersonHasNot() {
        val sentence = DiagnosticsVerdict.absenceSentence(
            what = "a vendor diagnostic service",
            outcome = DiagnosticsVerdict.Outcome.REFUSED,
        )
        assertTrue(
            "it must be about the privilege: $sentence",
            sentence.contains("declined to run it"),
        )
        assertTrue(
            "it must say no step helps: $sentence",
            sentence.contains("rather than something you can turn on"),
        )
    }

    @Test
    fun theDecidingLineIsCarriedIntoTheSentenceSoTheAnswerIsAnswerable() {
        val sentence = DiagnosticsVerdict.absenceSentence(
            what = "the vendor factory test entry",
            outcome = DiagnosticsVerdict.Outcome.REFUSED,
            detail = "Permission Denial: requires FACTORY_TEST",
        )
        assertTrue(
            "the evidence must travel with the verdict: $sentence",
            sentence.contains("requires FACTORY_TEST"),
        )
    }

    @Test
    fun theRanSentenceIsEmptyBecauseThereIsNothingToApologiseFor() {
        assertEquals(
            "",
            DiagnosticsVerdict.absenceSentence("a service", DiagnosticsVerdict.Outcome.RAN),
        )
    }
}
