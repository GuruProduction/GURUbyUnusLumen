// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.util.shell.ShellExecutor
import com.unuslumen.app.util.shell.ShellTier
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Proves the execution tier reported on device is the tier that actually ran.
 *
 * WHY THIS EXISTS. Issue 40 was a tool description claiming "NO limits" while the
 * executor ran at app UID. The fix added a tier field, and a unit test proved the
 * enum's shape. That is not the same as proving the field matches reality, and a
 * field that reports the wrong tier is the original defect wearing a new name.
 *
 * So this runs real commands in the app's own domain and checks the reported tier
 * against evidence the test gathers itself: the uid the process actually has, and
 * whether a known-privileged path is actually readable.
 */
@RunWith(AndroidJUnit4::class)
class ShellTierOnDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private val shell get() = ShellExecutor(context)

    @Test
    fun runsInTheAppDomainNotTheShellDomain() {
        val d = runCatching { File("/proc/self/attr/current").readText().trim().trim('\u0000') }
            .getOrDefault("unknown")
        println("SELinux domain under test: $d")
        assertTrue("Must run in the app domain, ran as '$d'", d.startsWith("u:r:untrusted_app"))
    }

    /**
     * The reported tier must agree with the uid the command actually ran as.
     *
     * This is the real invariant. If the executor says ROOT the command must have
     * run as uid 0; if it says ADB it must have run as shell uid 2000; if it says
     * APP_UID it must have run as this app's own uid. Anything else is the field
     * lying, which is the bug.
     */
    @Test
    fun theReportedTierMatchesTheUidThatActuallyRan() = runBlocking {
        val result = shell.execute("id -u")
        val reportedUid = result.stdout.trim().lines().lastOrNull()?.trim() ?: ""
        println("tier=${result.tier} uid='$reportedUid' exit=${result.exitCode} err=${result.stderr.take(200)}")

        assertTrue("the command did not run: exit=${result.exitCode}", result.exitCode == 0)
        assertTrue("no uid in output: '${result.stdout.take(200)}'", reportedUid.all { it.isDigit() })

        val appUid = android.os.Process.myUid().toString()
        when (result.tier) {
            ShellTier.ROOT -> assertEquals("ROOT must mean uid 0", "0", reportedUid)
            ShellTier.ADB -> assertEquals("ADB must mean the shell uid", "2000", reportedUid)
            ShellTier.APP_UID -> assertEquals(
                "APP_UID must mean this app's own uid. The field agreed with itself " +
                    "but not with reality, which is issue 40 again.",
                appUid,
                reportedUid,
            )
            ShellTier.NONE -> throw AssertionError("command ran but tier says NONE")
        }
    }

    /**
     * The capability text must be true of the tier it describes.
     *
     * On APP_UID the claim is that /proc/net/arp cannot be read. If it CAN be
     * read, the text overstates the limit and a caller avoids something it could
     * have done. Either direction of wrongness fails here.
     */
    @Test
    fun theAppUidLimitIsMeasuredNotAssumed() = runBlocking {
        val result = shell.execute("head -1 /proc/net/arp")
        println("tier=${result.tier} exit=${result.exitCode} out='${result.stdout.take(120)}' err='${result.stderr.take(120)}'")

        if (result.tier == ShellTier.APP_UID) {
            val denied = result.stderr.contains("Permission denied", ignoreCase = true) || result.exitCode != 0
            assertTrue(
                "The capability text says /proc/net is unavailable at APP_UID. This run " +
                    "shows exit=${result.exitCode} err='${result.stderr.take(200)}'. If it " +
                    "actually worked, the text is wrong and must change.",
                denied,
            )
        } else {
            // At a stronger tier the read may well succeed; assert nothing about
            // limits there, just record what happened.
            println("skipping the APP_UID limit assertion because tier is ${result.tier}")
        }
    }

    /**
     * describeCapabilityTier() must agree with what execute() then reports.
     *
     * A prediction that disagrees with the outcome is worse than no prediction.
     */
    @Test
    fun thePredictionMatchesTheOutcome() = runBlocking {
        val predicted = shell.describeCapabilityTier()
        val actual = shell.execute("true").tier
        println("predicted=$predicted actual=$actual")
        assertEquals(
            "describeCapabilityTier disagrees with the tier the next command actually got",
            predicted,
            actual,
        )
    }

    /**
     * The tier must be non-null and one of the declared values on every path,
     * including a command that fails.
     */
    @Test
    fun aFailingCommandStillReportsItsTier() = runBlocking {
        val result = shell.execute("this_binary_does_not_exist_xyz 2>/dev/null")
        println("failing command tier=${result.tier} exit=${result.exitCode}")
        assertFalse("a nonexistent binary must not report success", result.success)
        assertTrue("tier must be set even for a failure", result.tier in ShellTier.values())
    }
}
