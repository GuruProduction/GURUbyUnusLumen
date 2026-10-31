// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.util.shell.GuruAccessibilityService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Holds the accessibility status to one honest answer.
 *
 * WHY THIS EXISTS. Issue 36: `checkAdbStatus` reported `accessibilityRunning:
 * false` while UI automation worked. The cause was two signals for one question:
 * the status read one flag, every automation path read `GuruAccessibilityService
 * .instance`, so a status field could report a failure the automation path did
 * not see.
 *
 * The rule these tests enforce: the status and the automation gate are the same
 * source of truth, and that source distinguishes three states rather than
 * collapsing them into a boolean. "Not enabled in Settings" and "enabled but not
 * bound" need opposite responses, so a status that cannot tell them apart is not
 * a status.
 */
@RunWith(AndroidJUnit4::class)
class AccessibilityStatusTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun runsInTheAppDomainNotTheShellDomain() {
        val d = runCatching { File("/proc/self/attr/current").readText().trim().trim('\u0000') }
            .getOrDefault("unknown")
        println("SELinux domain under test: $d")
        assertTrue("Must run in the app domain, ran as '$d'", d.startsWith("u:r:untrusted_app"))
    }

    /**
     * The invariant that fixes the defect: the derived boolean and the instance
     * can never disagree.
     *
     * This is the whole fix in one assertion. Before, `isRunning` was a parallel
     * flag with its own lifecycle; now it is derived, so any drift is impossible
     * by construction and any reintroduction of a parallel flag fails here.
     */
    @Test
    fun theRunningFlagAgreesWithTheInstance() {
        val instancePresent = GuruAccessibilityService.instance != null
        val flag = GuruAccessibilityService.isRunning.value
        assertEquals(
            "isRunning and instance must agree. A status that disagrees with the " +
                "gate every automation path checks is exactly issue 36.",
            instancePresent,
            flag,
        )
    }

    /** The three states are exhaustive and the reported one matches the evidence. */
    @Test
    fun stateMatchesTheEvidence() {
        val state = GuruAccessibilityService.state()
        val instancePresent = GuruAccessibilityService.instance != null
        val enabled = GuruAccessibilityService.isEnabledInSettings()
        println("state=$state instance=$instancePresent enabledInSettings=$enabled")

        when (state) {
            GuruAccessibilityService.State.RUNNING ->
                assertTrue("RUNNING requires a bound instance", instancePresent)
            GuruAccessibilityService.State.ENABLED_NOT_BOUND -> {
                assertTrue("ENABLED_NOT_BOUND means no instance", !instancePresent)
                assertTrue("ENABLED_NOT_BOUND requires the settings flag", enabled)
            }
            GuruAccessibilityService.State.DISABLED -> {
                assertTrue("DISABLED means no instance", !instancePresent)
                assertTrue("DISABLED means not enabled in settings", !enabled)
            }
        }
    }

    /**
     * The state is one of the three declared values, never a null or a stray
     * string, so a caller parsing it cannot be surprised.
     */
    @Test
    fun theStateNameIsOneOfTheThree() {
        val name = GuruAccessibilityService.state().name
        assertTrue(
            "unexpected state name '$name'",
            name in setOf("DISABLED", "ENABLED_NOT_BOUND", "RUNNING"),
        )
    }
}
