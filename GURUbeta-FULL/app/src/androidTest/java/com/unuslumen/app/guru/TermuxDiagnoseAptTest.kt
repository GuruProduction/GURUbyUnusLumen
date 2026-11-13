// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.util.shell.TermuxProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Issue 30: `termuxDiagnose` reporting "apt-get not present in the prefix" on a prefix where
 * apt-get runs perfectly well.
 *
 * THE DEFECT, IN THE TICKET'S OWN WORDS: a boolean that means "my check failed" being reported
 * as "the thing is broken". The fix it asked for is three distinct outcomes rather than one, and
 * an `aptError` that carries what was actually observed.
 *
 * WHAT IS TRUE NOW, MEASURED RATHER THAN ASSUMED. `probeApt` returns ABSENT, WILL_NOT_LOAD or
 * RUNS with the real output attached, and the diagnose tool states which it got. The probe also
 * shares the environment with the code it reports on, which is the second half of what the
 * ticket asked: `aptRuns` and `execAptUpdate` no longer measure different things.
 *
 * THE ASSERTIONS ARE ABOUT THE DISTINCTION, not about the specific outcome, because the specific
 * outcome belongs to the device. What must never happen again is a message naming a cause the
 * probe did not observe.
 */
@RunWith(AndroidJUnit4::class)
class TermuxDiagnoseAptTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun thePrefixIsExtractedFirst() = runBlocking {
        val provider = TermuxProvider(context)
        val ready = provider.initialize()
        println("PREFIX ready=$ready")
        assertTrue("initialize() did not produce a prefix", ready)
    }

    /**
     * The probe must name the state it observed, and never a cause it did not.
     *
     * The old string was "apt-get not present in the prefix" in every case, including when
     * apt-get was present and healthy. So the assertion is that when apt-get IS on disk, the
     * probe does not claim it is absent.
     */
    @Test
    fun aPresentAptIsNeverReportedAsAbsent() = runBlocking {
        TermuxProvider(context).initialize()
        val provider = TermuxProvider(context)
        val onDisk = File(provider.getBinPath(), "apt-get")
        val probe = provider.probeApt()
        println("DIAG_APT onDisk=${onDisk.isFile} state=${probe.state} exit=${probe.exitCode} out=[${probe.output.take(200)}]")

        assertTrue("apt-get should be in the bundled prefix", onDisk.isFile)
        assertFalse(
            "apt-get is present at ${onDisk.absolutePath} but the probe says ABSENT. That is " +
                "the exact message this issue is about.",
            probe.state == TermuxProvider.AptProbe.ABSENT,
        )
    }

    @Test
    fun aptActuallyRunsThroughTheProbe() = runBlocking {
        TermuxProvider(context).initialize()
        val provider = TermuxProvider(context)
        val probe = provider.probeApt()
        println("DIAG_RUNS state=${probe.state} exit=${probe.exitCode} out=[${probe.output.take(200)}]")

        assertTrue(
            "apt-get must report RUNS through the same probe the diagnose tool uses. " +
                "state=${probe.state} exit=${probe.exitCode} out=[${probe.output.take(300)}]",
            probe.state == TermuxProvider.AptProbe.RUNS,
        )
        assertTrue(
            "and it must say what it is. out=[${probe.output.take(200)}]",
            probe.output.contains("apt"),
        )
    }

    /**
     * The three states must be genuinely distinct values, not three spellings of one boolean.
     *
     * This is a compile-time fact expressed as a test: if a future change collapses them back
     * into a boolean, this stops compiling rather than silently regressing.
     */
    @Test
    fun theProbeHasThreeDistinctStates() {
        val states = TermuxProvider.AptProbe.entries.map { it.name }
        println("DIAG_STATES $states")
        assertTrue(
            "The probe must distinguish absent from will-not-load from runs. Got: $states",
            states.containsAll(listOf("ABSENT", "WILL_NOT_LOAD", "RUNS")),
        )
        assertTrue("and they must be separate values", states.toSet().size == states.size)
    }
}
