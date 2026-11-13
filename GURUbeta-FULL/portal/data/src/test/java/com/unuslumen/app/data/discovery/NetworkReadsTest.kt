// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.discovery

import com.unuslumen.app.util.shell.ShellResult
import com.unuslumen.app.util.shell.ShellTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Holds the three-state rule for a table read, because issues 221 and 224 were
 * one fault filed twice: a refused ARP table reported as an empty network.
 *
 * THE RULE BEING PINNED. A tool may only say "nothing is there" when it actually
 * looked. If the read was refused, the answer is about the tier and not about the
 * network, and the result has to say so. [NetworkReads.verdict] is the whole rule,
 * extracted so it needs no Android, no Robolectric and no device.
 */
class NetworkReadsTest {

    private fun ok(stdout: String = "", tier: ShellTier = ShellTier.ADB) =
        ShellResult(exitCode = 0, stdout = stdout, stderr = "", success = true, tier = tier)

    private fun refused(
        stderr: String,
        tier: ShellTier = ShellTier.APP_UID,
        denial: String? = null,
    ) = ShellResult(
        exitCode = 1,
        stdout = "",
        stderr = stderr,
        success = false,
        tier = tier,
        denial = denial,
    )

    @Test
    fun aRefusedReadIsNeverReportedAsAnEmptyNetwork() {
        // The exact shape issue 224 reported: Android refused /proc/net/arp.
        val read = NetworkReads.verdict(
            result = refused("/system/bin/sh: cat: /proc/net/arp: Permission denied"),
            what = "ARP table",
            count = 0,
        )

        assertEquals(
            "A denied read must be REFUSED. Collapsing it into EMPTY is the defect " +
                "this whole change exists to remove.",
            NetworkReadOutcome.REFUSED,
            read.outcome,
        )
        assertFalse("a refused read did not answer the question", read.answered)
        assertNotNull("a refusal must carry the sentence a caller repeats", read.sentence)
    }

    @Test
    fun aSuccessfulReadThatFoundNothingIsAnEmptyResultAndARealAnswer() {
        val read = NetworkReads.verdict(
            result = ok(),
            what = "ARP table",
            count = 0,
        )

        assertEquals(
            "The table was read and had no neighbours, which is a real answer about the " +
                "network. Calling it a failure would send a caller hunting a fault that is not there.",
            NetworkReadOutcome.EMPTY,
            read.outcome,
        )
        assertTrue("an empty read still answered the question", read.answered)
        assertNull("an empty read needs no apology", read.sentence)
    }

    @Test
    fun entriesFoundIsFound() {
        val read = NetworkReads.verdict(
            result = ok(),
            what = "ARP table",
            count = 7,
        )

        assertEquals(NetworkReadOutcome.FOUND, read.outcome)
        assertNull(read.sentence)
    }

    @Test
    fun aFailedReadThatStillPrintedEntriesIsRefusedRatherThanASmallSuccess() {
        // This is the trap a count-only rule falls into: a command that wrote half a
        // table and then failed would otherwise be reported as a successful scan of
        // however many lines survived.
        val read = NetworkReads.verdict(
            result = ShellResult(
                exitCode = 1,
                stdout = "192.0.2.1 0x1 0x2 aa:bb:cc:dd:ee:ff * wlan0",
                stderr = "read error",
                success = false,
                tier = ShellTier.APP_UID,
            ),
            what = "ARP table",
            count = 1,
        )

        assertEquals(
            "Partial output from a failed command is not a result. Failure is checked " +
                "before the count for exactly this reason.",
            NetworkReadOutcome.REFUSED,
            read.outcome,
        )
    }

    @Test
    fun theRefusalSentenceNamesTheTierAndTheRouteRatherThanBlamingTheNetwork() {
        val sentence = NetworkReads.refusalSentence(
            result = refused("/system/bin/sh: cat: /proc/net/arp: Permission denied"),
            what = "ARP table",
        )

        assertTrue(
            "The sentence must say the table was refused rather than the network being empty. " +
                "Got: $sentence",
            sentence.contains("refused"),
        )
        assertTrue(
            "It must not let a reader conclude the network is quiet. Got: $sentence",
            sentence.contains("network that is empty"),
        )
        assertTrue(
            "At app UID the route that unlocks it must be named. Got: $sentence",
            sentence.contains("Shizuku") || sentence.contains("ADB"),
        )
    }

    @Test
    fun aDenialDetectedByTheShellLayerIsPassedThroughRatherThanSwallowed() {
        // dumpsys-style refusals arrive with success = false and a denial already
        // written by ShellDenialSignatures. The sentence must use that wording rather
        // than inventing a second explanation of the same refusal.
        val sentence = NetworkReads.refusalSentence(
            result = refused(
                stderr = "",
                tier = ShellTier.ADB,
                denial = "The output contains \"Permission Denial:\", which means a system service refused the dump",
            ),
            what = "neighbour table",
        )

        assertTrue(
            "The detected denial is the most specific fact available. Got: $sentence",
            sentence.contains("Permission Denial:"),
        )
    }

    @Test
    fun aRefusalAtShellTierDoesNotClaimAShellTierWouldFixIt() {
        // At UID 2000 the refusal is the device's own answer, so repeating the command
        // at that tier returns the same thing. Telling a reader to install Shizuku when
        // Shizuku already ran is the same class of lie in the other direction.
        val sentence = NetworkReads.refusalSentence(
            result = refused("cat: /proc/net/arp: Permission denied", tier = ShellTier.ADB),
            what = "ARP table",
        )

        assertFalse(
            "A refusal at ADB must not suggest ADB would unlock it. Got: $sentence",
            sentence.contains("is what makes this read succeed"),
        )
    }
}
