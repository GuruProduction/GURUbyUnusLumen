// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.shell

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.ConnectException
import java.net.SocketTimeoutException

/**
 * Issue 389's three defects, held as rules.
 *
 * The pairing path had one number where Android has three, and the one it hardcoded was not a
 * wireless-debugging port at all.
 */
class AdbTargetTest {

    @Test
    fun theAdvertisedAddressIsTriedBeforeLoopback() {
        // AOSP pairs against the address the dialog shows. Loopback is a fallback, not an assumption.
        assertEquals(
            listOf("192.0.2.10", "127.0.0.1"),
            AdbTarget.pairingCandidates("192.0.2.10"),
        )
    }

    @Test
    fun withNoAdvertisedAddressOnlyLoopbackIsTried() {
        assertEquals(listOf("127.0.0.1"), AdbTarget.pairingCandidates(null))
        assertEquals(listOf("127.0.0.1"), AdbTarget.pairingCandidates("   "))
    }

    @Test
    fun anAdvertisedLoopbackIsNotTriedTwice() {
        assertEquals(listOf("127.0.0.1"), AdbTarget.pairingCandidates("127.0.0.1"))
    }

    @Test
    fun portFiveFiveFiveFiveIsNoLongerTheFallbackConnectPort() {
        // THE DEFECT. Falling back to 5555 produced a connect against a port nothing listens on, and
        // then a sentence blaming wireless debugging. No port is better than a wrong one.
        assertNull("an unknown connect port must stay unknown", AdbTarget.connectPort(null))
    }

    @Test
    fun aDialogPortIsAcceptedWhenItIsUsable() {
        assertEquals(39123, AdbTarget.connectPort(39123))
        assertEquals(39123, AdbTarget.pairingPort(39123))
    }

    @Test
    fun anImpossiblePortIsRejectedRatherThanDialled() {
        assertNull(AdbTarget.connectPort(0))
        assertNull(AdbTarget.connectPort(-1))
        assertNull(AdbTarget.connectPort(70000))
        assertTrue(AdbTarget.isPlausiblePort(39271))
        assertFalse(AdbTarget.isPlausiblePort(null))
    }

    @Test
    fun thePairingAndConnectPortsAreSeparateNumbers() {
        // They come from the same dialog and are different random numbers. Collapsing them is the
        // defect this file removes, so the two functions exist apart.
        val pairing = AdbTarget.pairingPort(40001)
        val connect = AdbTarget.connectPort(40002)
        assertEquals(40001, pairing)
        assertEquals(40002, connect)
    }

    @Test
    fun aBrokenPipeIsTransientSoItIsRetried() {
        // The ticket's own symptom aborted the whole pairing because only ConnectException was retried.
        assertTrue(
            AdbTarget.isTransient(RuntimeException("Broken pipe")),
        )
        assertTrue(AdbTarget.isTransient(java.net.SocketException("Connection reset")))
        assertTrue(AdbTarget.isTransient(ConnectException("Connection refused")))
        assertTrue(AdbTarget.isTransient(SocketTimeoutException("timeout")))
    }

    @Test
    fun aCryptographicFailureIsNotRetried() {
        // A wrong pairing code gives the same answer every time, so retrying only delays telling
        // the person.
        assertFalse(AdbTarget.isTransient(javax.crypto.AEADBadTagException("tag mismatch")))
        assertFalse(AdbTarget.isTransient(IllegalStateException("Unsupported header version: 9")))
        assertFalse(AdbTarget.isTransient(null))
    }

    @Test
    fun theFailureSentenceNamesHostPortAndStage() {
        // Issue 389's third defect: the old string named none of the three, which is why the report
        // went looking at the adb binary for a cause that was never there.
        val sentence = AdbTarget.failureSentence(
            host = "192.0.2.10",
            port = 39123,
            stage = "the TLS handshake",
            cause = "Broken pipe",
        )
        assertTrue("it must name the host: $sentence", sentence.contains("192.0.2.10"))
        assertTrue("and the port: $sentence", sentence.contains("39123"))
        assertTrue("and the stage: $sentence", sentence.contains("the TLS handshake"))
        assertTrue("and the cause: $sentence", sentence.contains("Broken pipe"))
    }

    @Test
    fun aFailureWithNoDetailStillReadsAsASentence() {
        val sentence = AdbTarget.failureSentence("192.0.2.10", 39123, "opening the socket", null)
        assertTrue(sentence.contains("no detail"))
        assertFalse("it must not trail off", sentence.endsWith(":"))
    }
}
