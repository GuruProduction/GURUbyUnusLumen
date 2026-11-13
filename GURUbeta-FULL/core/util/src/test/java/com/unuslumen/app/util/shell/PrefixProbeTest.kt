// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 334's central rule, held as tests: a health check that disagrees with the tool is wrong.
 *
 * The defect this covers is that `termuxStatus` said `prefixExecutes: true` while `termuxExec`
 * returned `Permission denied` for the same binary, because the probe launched it directly and the
 * tool forked through bash. Every test below takes one way that disagreement can return.
 */
class PrefixProbeTest {

    private val echo = PrefixProbe.probes.first { it.name == "echo" }
    private val git = PrefixProbe.probes.first { it.name == "git" }

    @Test
    fun theProbesForkRatherThanLaunchOneBinary() {
        // The single change that distinguishes the two code paths. A probe that runs one process
        // cannot see a fault that only appears in the child.
        for (probe in PrefixProbe.probes) {
            assertTrue(
                "probe '${probe.name}' must be shell text that runs a command, not a bare path: " +
                    "${probe.script}",
                probe.script.contains(" "),
            )
        }
        assertTrue(
            "the git probe must exercise the shell, which is where the fork happens",
            PrefixProbe.probes.any { it.script.startsWith("git ") },
        )
    }

    @Test
    fun echoIsProbedBecauseItHasNothingToDoWithTermux() {
        // The shim canary. echo failing can only be the shell or a preloaded library, which is
        // what makes issue 328 a one-second diagnosis instead of an afternoon.
        assertEquals("echo GURU_PREFIX_ALIVE", echo.script)
        assertTrue(
            "echo must not depend on any Termux package",
            echo.expect == "GURU_PREFIX_ALIVE",
        )
    }

    @Test
    fun anEmptySuccessIsNotARun() {
        // Issue 211: `success: true, exitCode: 0` with completely empty stdout, while
        // `echo hello` was the first command. An exit-code assertion passes that.
        val result = PrefixProbe.decide(git, exitCode = 0, output = "")
        assertFalse("exit 0 with no output is not evidence the command ran", result.ran)
        assertTrue(
            "and the sentence must name the shape rather than saying nothing: ${result.sentence}",
            result.sentence.contains("empty success") || result.sentence.contains("no output"),
        )
    }

    @Test
    fun theExpectedTextIsWhatProvesTheRun() {
        val result = PrefixProbe.decide(git, exitCode = 0, output = "git version 2.56.0")
        assertTrue("the version string proves git ran", result.ran)
        assertEquals(
            "`git --version` ran and printed \"git version\".",
            result.sentence,
        )
    }

    @Test
    fun aNonZeroExitWithTheRightTextStillCounts() {
        // git --version and apt-get --version do not promise a particular status, and treating
        // non-zero as failure is issue 175's error pointed the other way.
        val result = PrefixProbe.decide(git, exitCode = 128, output = "git version 2.56.0")
        assertTrue("the output is the evidence, not the status", result.ran)
    }

    @Test
    fun theWrongTextIsAFailureEvenOnExitZero() {
        val result = PrefixProbe.decide(git, exitCode = 0, output = "fatal: not a git repository")
        assertFalse("the command did not print what proves it ran", result.ran)
        assertTrue(
            "the sentence must quote what it did say: ${result.sentence}",
            result.sentence.contains("fatal"),
        )
    }

    @Test
    fun aLoadFailureIsNeverARun() {
        val result = PrefixProbe.decide(
            git,
            exitCode = 139,
            output = "CANNOT LINK EXECUTABLE \"git\": library \"libcurl.so\" not found",
        )
        assertFalse(result.ran)
        assertTrue(
            "the sentence must say the loader refused it, not that the command failed: " +
                "${result.sentence}",
            result.sentence.contains("loader refused"),
        )
    }

    @Test
    fun aPermissionDenialIsALoadFailureNotAnEmptyResult() {
        // This is the exact text the tool returned while the probe said the prefix was healthy.
        val result = PrefixProbe.decide(git, exitCode = 1, output = "Permission denied")
        assertFalse("a denial is not a run", result.ran)
        assertTrue(result.sentence.contains("loader refused"))
    }

    @Test
    fun aVerdictNeedsTheShellAndAtLeastOneCommand() {
        val shellOk = PrefixProbe.decide(echo, 0, "GURU_PREFIX_ALIVE")
        val gitBroken = PrefixProbe.decide(git, 1, "Permission denied")
        val aptBroken = PrefixProbe.decide(
            PrefixProbe.probes.first { it.name == "apt-get" }, 1, "Permission denied",
        )
        val verdict = PrefixProbe.verdict(listOf(shellOk, gitBroken, aptBroken))

        assertFalse(
            "a prefix where only echo works is not healthy, and this is the state that used to " +
                "be reported as healthy",
            verdict.allRan,
        )
        assertTrue(
            "and the sentence must name what did not work: ${verdict.sentence}",
            verdict.sentence.contains("git") || verdict.sentence.contains("apt-get"),
        )
    }

    @Test
    fun aBrokenShellFailsTheVerdictEvenWhenACommandRan() {
        val shellBroken = PrefixProbe.decide(echo, 139, "CANNOT LINK EXECUTABLE")
        val gitOk = PrefixProbe.decide(git, 0, "git version 2.56.0")
        val verdict = PrefixProbe.verdict(listOf(shellBroken, gitOk))
        assertFalse(
            "if echo cannot run, the preload chain is broken and nothing else is trustworthy",
            verdict.allRan,
        )
    }

    @Test
    fun aHealthyPrefixPasses() {
        val results = PrefixProbe.probes.map { probe ->
            PrefixProbe.decide(probe, 0, "output containing ${probe.expect}")
        }
        val verdict = PrefixProbe.verdict(results)
        assertTrue("every probe printed what proves it ran", verdict.allRan)
        assertTrue(
            "and the sentence must say the probe took the tool's path: ${verdict.sentence}",
            verdict.sentence.contains("same path a tool takes"),
        )
    }

    @Test
    fun aMissingAptDoesNotMakeTheExecLayerBroken() {
        // A prefix without apt installed is a different fact from a broken prefix, and
        // reporting it as broken would be the same overclaim in the other direction.
        val shellOk = PrefixProbe.decide(echo, 0, "GURU_PREFIX_ALIVE")
        val gitOk = PrefixProbe.decide(git, 0, "git version 2.56.0")
        val aptMissing = PrefixProbe.decide(
            PrefixProbe.probes.first { it.name == "apt-get" }, 127, "command not found",
        )
        val verdict = PrefixProbe.verdict(listOf(shellOk, gitOk, aptMissing))
        assertTrue("the exec layer works, so the verdict is healthy", verdict.allRan)
        assertTrue(
            "but the sentence must still name what is not working: ${verdict.sentence}",
            verdict.sentence.contains("apt-get"),
        )
    }
}
