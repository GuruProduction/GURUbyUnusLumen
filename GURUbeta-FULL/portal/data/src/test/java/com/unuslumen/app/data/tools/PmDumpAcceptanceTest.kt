// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.util.shell.ShellDenialSignatures
import com.unuslumen.app.util.shell.ShellResult
import com.unuslumen.app.util.shell.ShellTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The two halves of issue 44, held as tests so the defect cannot return.
 *
 * WHAT THE TICKET REPORTED. `pmDump` returned `exitCode: 0` and `success: true` while every
 * line of its output was a permission denial. `dumpsys` and `pm dump` print refusals to
 * STDOUT and still exit 0, so a total denial looked exactly like a clean run, and the
 * `DUMP OF SERVICE ...` headers made the denial body read as a structure of real services.
 *
 * WHAT THE TICKET ASKED FOR, and what is now true:
 *
 *  1. Detect denial signatures in stdout as well as stderr, at the executor boundary, so
 *     every tool with that shape is fixed in one place rather than tool by tool.
 *  2. For `pmDump` specifically, use `PackageManager.getPackageInfo` with the GET_* flags,
 *     which needs no permission and returns the same information, so the tool actually works
 *     rather than merely failing honestly.
 *
 * The in-process half cannot be tested here because it needs a real PackageManager; it is
 * covered by the fact that the shell path is now a fallback rather than the only route. The
 * detection half is pure and is pinned below against the ticket's own output blob.
 */
class PmDumpAcceptanceTest {

    /**
     * The exact stdout from the ticket. The six `DUMP OF SERVICE` headers are the reason this
     * was believable: the body is structurally a dump and every line of it is a refusal.
     */
    private val denialBlob = """
        DUMP OF SERVICE package:
        Permission Denial: can't dump PackageManager from from pid=19342, uid=10332 due to missing android.permission.DUMP permission

        DUMP OF SERVICE activity:
        Permission Denial: can't dump ActivityManager from from pid=19342, uid=10332 due to missing android.permission.DUMP permission

        DUMP OF SERVICE meminfo:
        Permission Denial: can't dump meminfo from from pid=19342, uid=10332 due to missing android.permission.DUMP permission

        DUMP OF SERVICE procstats:
        Permission Denial: can't dump ProcessStatsService from from pid=19342, uid=10332 due to missing android.permission.DUMP permission

        DUMP OF SERVICE usagestats:
        Permission Denial: can't dump UsageStatsService from from pid=19342, uid=10332 due to missing android.permission.DUMP permission

        DUMP OF SERVICE batterystats:
        Permission Denial: can't dump BatteryStatsService from from pid=19342, uid=10332 due to missing android.permission.DUMP permission
    """.trimIndent()

    @Test
    fun theTicketOutputIsDetectedAsADenialEvenWithExitZero() {
        // The whole defect: exit 0, empty stderr, and a page of refusals on stdout.
        val result = ShellDenialSignatures.apply(
            ShellResult(exitCode = 0, stdout = denialBlob, stderr = "", success = true, tier = ShellTier.APP_UID),
            ShellTier.APP_UID,
        )

        assertFalse(
            "A denial printed to stdout with exit 0 must not be reported as success.",
            result.success,
        )
        assertNotNull("and it must carry the reason", result.denial)
    }

    @Test
    fun theDumpPermissionIsRecognisedSoTheToolCanExplainTheWallInsteadOfThePage() {
        assertTrue(
            "DUMP is signature-level, so the caller's answer is different from an ordinary " +
                "failure: this tool can never work here and must say so.",
            ShellDenialSignatures.isDumpPermissionDenial(denialBlob, ""),
        )
    }

    @Test
    fun theExplanationNamesThePermanentWallAtAppUidAndDoesNotAskForARetry() {
        val sentence = ShellDenialSignatures.explain("Permission Denial:", ShellTier.APP_UID)
        assertTrue(
            "At app UID the denial is a property of the UID, so retrying is pointless and " +
                "the sentence has to say that. Got: $sentence",
            sentence.contains("permanent"),
        )
        assertTrue(
            "and it must name the route that does work. Got: $sentence",
            sentence.contains("Shizuku") || sentence.contains("ADB"),
        )
    }

    @Test
    fun theObservationIsKeptRatherThanDiscarded() {
        // Some tools legitimately search another program's output for the word "denial", so
        // the honest failure must not lose the text it read.
        val result = ShellDenialSignatures.apply(
            ShellResult(exitCode = 0, stdout = denialBlob, stderr = "", success = true, tier = ShellTier.APP_UID),
            ShellTier.APP_UID,
        )
        assertEquals("the exit code is evidence", 0, result.exitCode)
        assertTrue("stdout is evidence", result.stdout.contains("DUMP OF SERVICE"))
    }

    @Test
    fun aLogQuotingSomeoneElsesDenialIsNotOurFailure() {
        // The opposite lie, which the fix must not create: a successful `logcat -d` contains
        // other processes' refusals by design, and calling that run failed would be the same
        // dishonesty aimed the other way.
        val logcat = "10-31 06:31:12.345 1234 1234 E ActivityManager: Permission Denial: " +
            "not allowed to start service"
        val result = ShellDenialSignatures.apply(
            ShellResult(exitCode = 0, stdout = logcat, stderr = "", success = true, tier = ShellTier.APP_UID),
            ShellTier.APP_UID,
        )

        assertTrue(
            "A timestamped line is another process's record, not an answer to this command, " +
                "so the run succeeded and must stay a success.",
            result.success,
        )
        assertNull("and it is certainly not called our own denial", result.denial)
        // The incidental class also excludes a line that BEGINS with a log timestamp, because
        // such a line describes the logger rather than our caller. So the advisory is null
        // here as well, and that is the documented rule rather than a gap: a refusal quoted
        // inside a timestamped record belongs to whoever logged it.
    }

    @Test
    fun aCleanDumpAtShellTierIsNotInventedAsAFailure() {
        // The control: a real dump must still read as a real dump.
        val real = "DUMP OF SERVICE package:\n  Packages:\n    Package [com.example] (abc):"
        val result = ShellDenialSignatures.apply(
            ShellResult(exitCode = 0, stdout = real, stderr = "", success = true, tier = ShellTier.ADB),
            ShellTier.ADB,
        )
        assertTrue(result.success)
        assertNull(result.denial)
    }
}
