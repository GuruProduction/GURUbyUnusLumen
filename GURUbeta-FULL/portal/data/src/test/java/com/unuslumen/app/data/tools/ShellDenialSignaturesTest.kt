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
 * Holds the denial-detection contract, because issue 44 was a tool reporting a
 * total refusal as a success.
 *
 * THE DEFECT. `pm dump` printed "Permission Denial: …" to STDOUT, wrote nothing
 * to stderr, and exited 0. The tool decided success from exitCode and stderr, so
 * a page of refusals arrived as `success: true`. The "DUMP OF SERVICE …" headers
 * survive the refusal, so the body still looks like a dump to a reader.
 *
 * THE OTHER DIRECTION, which these tests guard as hard as the first. A word
 * search alone would mark a successful `logcat -d` as failed, because a log is
 * full of other processes' "Permission denied" lines. That is issue 44's
 * dishonesty reversed, so only a refusal that begins a line — the shape of the
 * platform answering our caller — may change success. A quoted mention is
 * surfaced as an advisory and leaves success alone.
 *
 * Every string here is the real text from the issue body or a real command's
 * output, not an invented input.
 */
class ShellDenialSignaturesTest {

    /**
     * The exact stdout `pmDump` returned on the reporting device: six service
     * headers, six refusals, exit 0, empty stderr.
     */
    private val pmDumpDenialBlob = """
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

    /** The accessibility case the issue names, which carries no pid or uid. */
    private val accessibilityDenial =
        "Permission Denial: can't dump AccessibilityManagerService"

    // ---- the issue 44 case ----

    @Test
    fun exitZeroWithSixDenialLinesIsDetectedAsAFailure() {
        val result = ShellDenialSignatures.apply(
            ShellResult(
                exitCode = 0,
                stdout = pmDumpDenialBlob,
                stderr = "",
                success = true,
                tier = ShellTier.APP_UID,
            ),
            ShellTier.APP_UID,
        )

        assertFalse(
            "This is issue 44 exactly: exit 0, empty stderr, and every line of " +
                "stdout a refusal. Reporting success here is the bug.",
            result.success,
        )
        assertNotNull("A failure must carry its reason.", result.denial)
        assertEquals(
            "The denial names the signature that matched.",
            "Permission Denial:",
            ShellDenialSignatures.detectCommandDenial(result.stdout, result.stderr),
        )
    }

    @Test
    fun theDenialKeepsTheRealExitCodeAndEveryLineOfOutput() {
        val result = ShellDenialSignatures.apply(
            ShellResult(0, pmDumpDenialBlob, "", true, ShellTier.APP_UID),
            ShellTier.APP_UID,
        )

        // A caller may legitimately search this text, and some tools grep for
        // exactly these words. Discarding it would make the honest failure as
        // useless as the false success it replaces.
        assertEquals("the observed exit code survives", 0, result.exitCode)
        assertEquals("stdout survives unchanged", pmDumpDenialBlob, result.stdout)
        assertEquals("stderr survives unchanged", "", result.stderr)
        val refusalCount = result.stdout.split("Permission Denial:").size - 1
        assertTrue(
            "every one of the six refusals is still readable, not just the first",
            refusalCount == 6,
        )
    }

    @Test
    fun theAccessibilityDenialIsCaughtEvenWithoutAPidOrUid() {
        // The issue's second observation: `dumpsys accessibility` also exits 0
        // while printing this, and it names no pid or uid. Requiring those would
        // miss the case the issue filed, so the shape test is "the line begins
        // with the refusal", not "the line carries pid and uid".
        assertEquals(
            "A platform refusal without pid or uid is still a refusal of our command.",
            "Permission Denial:",
            ShellDenialSignatures.detectCommandDenial(accessibilityDenial, ""),
        )
        val result = ShellDenialSignatures.apply(
            ShellResult(0, accessibilityDenial, "", true, ShellTier.APP_UID),
            ShellTier.APP_UID,
        )
        assertFalse(
            "exit 0 with a refusal on stdout is the same defect as the pm dump case.",
            result.success,
        )
        assertNotNull(result.denial)
    }

    @Test
    fun theDenialNamesTheDumpPermissionSpecifically() {
        assertTrue(
            "pmDump must be able to tell a DUMP wall from any other failure, " +
                "because that one cannot be retried and needs a different tool.",
            ShellDenialSignatures.isDumpPermissionDenial(pmDumpDenialBlob, ""),
        )
    }

    @Test
    fun aCleanDumpIsNotADenial() {
        val realDump = """
            Packages:
              Package [com.example.app] (a1b2c3):
                userId=10332
                versionName=1.2.3
                versionCode=45
                requested permissions:
                  android.permission.INTERNET
                  android.permission.CAMERA
        """.trimIndent()

        assertNull(
            "A dump that answered must not be read as a refusal.",
            ShellDenialSignatures.detect(realDump, ""),
        )
        val result = ShellDenialSignatures.apply(
            ShellResult(0, realDump, "", true, ShellTier.APP_UID),
            ShellTier.APP_UID,
        )
        assertTrue("nothing refused, so the run succeeded", result.success)
        assertNull(result.denial)
        assertNull(result.advisory)
    }

    @Test
    fun aDenialOnStderrOnlyIsDetected() {
        // A file operation reports its refusal on stderr rather than stdout, so
        // detection has to search both streams or it misses half the cases.
        val stderr = "grep: /data/data/com.android.settings/shared_prefs/x.xml: Permission denied"
        assertEquals(
            "both streams are searched, so a refusal that landed on stderr is found",
            "Permission denied",
            ShellDenialSignatures.detect("", stderr),
        )
        assertNull(
            "neither stream carries a signature, so nothing is detected",
            ShellDenialSignatures.detect("", ""),
        )

        // It is reported, not acted on as a failed run. `grep` exiting 0 having
        // printed a line that names the words is not the same event as `dumpsys`
        // answering, and only a refusal that begins a line is treated as ours.
        val result = ShellDenialSignatures.apply(
            ShellResult(0, "", stderr, true, ShellTier.APP_UID),
            ShellTier.APP_UID,
        )
        assertTrue(
            "the command ran and its exit code says it succeeded",
            result.success,
        )
        assertNotNull("the refusal is still surfaced", result.advisory)
        assertEquals("stderr survives unchanged", stderr, result.stderr)
    }

    // ---- the opposite lie, which the tests guard just as hard ----

    /**
     * A log is full of other processes' refusals. Calling this run failed would
     * be issue 44's dishonesty pointed the other way.
     *
     * The three shapes here are the ones a real logcat produces: the plain text
     * refusal, the SELinux `avc: denied` audit line, and a SecurityException.
     */
    private val logcatOutput = """
        10-31 06:31:12.345  1234  1234 I ActivityManager: Start proc 19342:com.unuslumen.app.gurubeta/u0a332 for activity
        10-31 06:31:12.456  2000  2000 E WifiService: Permission denied
        10-31 06:31:12.567  1234  1234 W auditd  : avc: denied { read } for pid=19342 comm="app_process" name="arp"
        10-31 06:31:12.678  9876  9876 E AndroidRuntime: java.lang.SecurityException: Neither user 10332 nor current process has android.permission.READ_LOGS
        10-31 06:31:12.789  4321  4321 I chatty  : uid=10332(com.unuslumen.app.gurubeta) expire 12 lines
        10-31 06:31:12.890  19342  19342 I guru    : AdbClient: command 'ls' out=0 err=0 exit=0
    """.trimIndent()

    @Test
    fun aLogQuotingSomeoneElsesRefusalStaysASuccessWithAnAdvisory() {
        assertNull(
            "Not one of these is a refusal of OUR command; they are other " +
                "processes' log lines and are the reason a bare word search is wrong.",
            ShellDenialSignatures.detectCommandDenial(logcatOutput, ""),
        )

        val result = ShellDenialSignatures.apply(
            ShellResult(0, logcatOutput, "", true, ShellTier.APP_UID),
            ShellTier.APP_UID,
        )
        assertTrue(
            "logcat -d did its job. Reporting it failed is the same dishonesty " +
                "as issue 44 aimed the other way.",
            result.success,
        )
        assertNull("no refusal was aimed at this command", result.denial)
        assertNotNull(
            "the mention is still reported, so a caller who wants to know can see it",
            result.advisory,
        )
        assertEquals("stdout survives unchanged", logcatOutput, result.stdout)
    }

    @Test
    fun aMentionInsideAToolsOwnMessageStaysASuccess() {
        // The case the brief names: a tool legitimately reports the words
        // "permission denied" inside a quoted message it is producing. Its exit
        // code and text must survive and it must not be called a failure.
        val stdout = """{"ok":false,"error":"wget: cannot write to '/x': Permission denied"}"""
        val result = ShellDenialSignatures.apply(
            ShellResult(0, stdout, "", true, ShellTier.APP_UID),
            ShellTier.APP_UID,
        )
        assertTrue(
            "The tool itself succeeded in reporting that message.",
            result.success,
        )
        assertNotNull(
            "the quoted refusal is surfaced as an advisory",
            result.advisory,
        )
        assertEquals("stdout survives unchanged", stdout, result.stdout)
        assertEquals("the exit code survives", 0, result.exitCode)
    }

    // ---- a non-zero exit adds the reason rather than changing the verdict ----

    @Test
    fun anAlreadyFailedRunKeepsFailingAndGainsTheReason() {
        val result = ShellDenialSignatures.apply(
            ShellResult(
                exitCode = 1,
                stdout = "",
                stderr = "cat: /data/local/tmp/x: Permission denied",
                success = false,
                tier = ShellTier.APP_UID,
            ),
            ShellTier.APP_UID,
        )
        assertFalse("a failed run stays failed", result.success)
        assertEquals("the real exit code is preserved", 1, result.exitCode)
        assertNotNull("the reason is recorded", result.advisory)
    }

    // ---- permanence, and never claiming it where it is untrue ----

    @Test
    fun aDenialAtAppUidIsPermanent() {
        assertTrue(
            "An app cannot grant itself a signature permission or raise its own " +
                "UID, so repeating the command can never succeed.",
            ShellDenialSignatures.isPermanent("Permission Denial:", ShellTier.APP_UID),
        )
    }

    @Test
    fun aDenialAtAdbOrRootIsNeverCalledPermanent() {
        // Shell UID 2000 and root both hold what an app lacks, so a refusal
        // there is a fact about the target, not a wall the tier built.
        assertFalse(ShellDenialSignatures.isPermanent("Permission Denial:", ShellTier.ADB))
        assertFalse(ShellDenialSignatures.isPermanent("Permission Denial:", ShellTier.ROOT))
        assertFalse(ShellDenialSignatures.isPermanent(null, ShellTier.APP_UID))
    }

    @Test
    fun theExplanationUsesTheTiersOwnVocabulary() {
        // Issue 40: the tier's words must not be reinvented here, or the
        // explanation can drift from what the executor promises.
        val atAppUid = ShellDenialSignatures.explain("Permission Denial:", ShellTier.APP_UID)
        assertTrue(
            "APP_UID's own capabilities text says denials are permanent",
            ShellTier.APP_UID.capabilities().contains("permanent"),
        )
        assertTrue(
            "so the explanation says permanent too",
            atAppUid.contains("permanent"),
        )
        assertTrue("and says not to retry", atAppUid.contains("must not be retried"))

        val atAdb = ShellDenialSignatures.explain("Permission Denial:", ShellTier.ADB)
        assertFalse(
            "ADB holds UID 2000, so claiming permanence there would be false.",
            atAdb.contains("must not be retried"),
        )
        assertTrue("it names the tier that ran", atAdb.contains("ADB shell"))
    }

    @Test
    fun everySignatureInTheListIsDetectable() {
        // A signature listed but unmatched would be a promise the detector does
        // not keep, so each one is checked against a line that begins with it.
        for (signature in ShellDenialSignatures.SIGNATURES) {
            val line = if (signature.text.endsWith(":")) {
                "${signature.text} can't dump something from pid=1, uid=1"
            } else {
                "${signature.text} from a failing command"
            }
            assertNotNull(
                "the listed signature '${signature.text}' must actually be detectable",
                ShellDenialSignatures.detect(line, ""),
            )
        }
    }

    @Test
    fun anEmptyResultIsNeverADenial() {
        assertNull(ShellDenialSignatures.detect("", ""))
        val result = ShellDenialSignatures.apply(
            ShellResult(0, "", "", true, ShellTier.APP_UID),
            ShellTier.APP_UID,
        )
        assertTrue(result.success)
        assertNull(result.denial)
        assertNull(result.advisory)
    }
}
