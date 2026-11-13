// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.util.shell.TermuxProvider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Issue 289: `termuxStatus` reporting a prefix as broken while the binaries run.
 *
 * WHAT THE TICKET CLAIMED. Every binary was reported WILL_NOT_LOAD or ABSENT while almost all
 * of them executed correctly through linker64, because the probe used a different launch path
 * from the tool it was testing.
 *
 * WHAT IS TRUE NOW, AND THIS TEST IS HOW IT WAS ESTABLISHED RATHER THAN ASSUMED.
 * `probeCommand` launches through `/system/bin/linker64` with the prefix's own
 * `LD_LIBRARY_PATH`, which is the launch the ticket says works. It also shares `buildEnv()`
 * and `execWithEnv()` with `execInTermux`, so the probe and the tool it reports on cannot take
 * different paths any more. That is the cross-cutting fix #334 asks for, and it is why this
 * test measures CONSISTENCY rather than a single result.
 *
 * THE THREE STATES ARE DISTINCT. ABSENT is no file, WILL_NOT_LOAD is a file that will not run,
 * RUNS is a binary that produced output. A status field that cannot tell those apart is the
 * defect #175 described, and it is what this pins.
 */
@RunWith(AndroidJUnit4::class)
class TermuxStatusConsistencyTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext

    /**
     * The probe must agree with an actual run of the same command.
     *
     * THE WHOLE TICKET IN ONE ASSERTION. If `termuxStatus` says a binary does not load while
     * `execInTermux` runs it, one of the two is lying about the same prefix. Checking a set
     * rather than one binary matters because the original report was "every binary", and a
     * fault that affects some tools and not others would hide behind a single sample.
     */
    @Test
    fun theProbeAgreesWithTheToolItReportsOn() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()

        val disagreements = mutableListOf<String>()
        val checked = mutableListOf<String>()

        for (name in listOf("bash", "git", "curl", "sed", "grep", "ls")) {
            val probe = provider.probeCommand(name)
            if (probe.state == TermuxProvider.CommandProbe.ABSENT) continue
            checked.add(name)

            // The same binary, run the way the tool runs a command.
            val run = provider.execInTermux("$name --version 2>&1 | head -1")
            val ran = run.success && run.stdout.isNotBlank()

            println("CONSISTENCY $name probe=${probe.state} execExit=${run.exitCode} exec=[${run.stdout.take(80)}]")

            if (probe.state == TermuxProvider.CommandProbe.RUNS && !ran) {
                disagreements.add("$name: probe says RUNS but execInTermux failed (exit=${run.exitCode})")
            }
            if (probe.state == TermuxProvider.CommandProbe.WILL_NOT_LOAD && ran) {
                disagreements.add("$name: probe says WILL_NOT_LOAD but execInTermux ran it")
            }
        }

        assertTrue("No binary was found to compare, so nothing was tested", checked.size >= 4)
        assertTrue(
            "The status probe and the exec path disagree about the same prefix: $disagreements",
            disagreements.isEmpty(),
        )
    }

    /**
     * A binary the issue named by hand must never be reported ABSENT while its file is there.
     *
     * Issue 289's own table listed gcc, make, ssh and ffmpeg as ABSENT. ABSENT is only correct
     * when no file of that name exists in the search path, so this holds the definition rather
     * than the list: whatever is present must be probed, and whatever is absent must genuinely
     * be missing.
     */
    @Test
    fun absentMeansNoFileRatherThanNoIdea() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()
        val bin = java.io.File(provider.getBinPath())

        val wrong = mutableListOf<String>()
        for (name in listOf("bash", "git", "curl", "ssh", "apt", "jq", "sed", "grep")) {
            val onDisk = java.io.File(bin, name)
            val probe = provider.probeCommand(name)
            println("ABSENT_CHECK $name onDisk=${onDisk.exists()} probe=${probe.state}")
            if (onDisk.isFile && probe.state == TermuxProvider.CommandProbe.ABSENT) {
                wrong.add("$name is present on disk but reported ABSENT")
            }
            if (!onDisk.isFile && probe.state != TermuxProvider.CommandProbe.ABSENT) {
                wrong.add("$name is missing from disk but reported ${probe.state}")
            }
        }
        assertTrue("ABSENT is not being used to mean 'no such file': $wrong", wrong.isEmpty())
    }

    /**
     * The ticket's most specific complaint: a run with exit 0 and no output.
     *
     * It reported `apt: WILL_NOT_LOAD "apt produced no output and exited 0"`, and separately
     * git and curl the same way. Exit 0 with no output is ambiguous on its face, so the
     * question is what the binary actually does. `probeCommand` calls `--version`, which every
     * tool in this list answers, so a silent exit 0 means the tool ran and printed nothing.
     */
    @Test
    fun aToolThatRunsIsNotReportedAsBrokenForBeingQuiet() = runBlocking {
        val provider = TermuxProvider(context)
        provider.initialize()

        val silentButAlive = mutableListOf<String>()
        for (name in listOf("apt", "apt-get", "git", "curl")) {
            val probe = provider.probeCommand(name)
            println("QUIET_CHECK $name state=${probe.state} exit=${probe.exitCode} out=[${probe.output.take(90)}]")
            if (probe.state == TermuxProvider.CommandProbe.WILL_NOT_LOAD &&
                probe.exitCode == 0 &&
                probe.output.contains("produced no output")
            ) {
                silentButAlive.add("$name: ${probe.output}")
            }
        }

        assertTrue(
            "A binary that exited 0 is being reported WILL_NOT_LOAD purely for printing " +
                "nothing. That is the ticket's exact complaint. $silentButAlive",
            silentButAlive.isEmpty(),
        )
    }
}
