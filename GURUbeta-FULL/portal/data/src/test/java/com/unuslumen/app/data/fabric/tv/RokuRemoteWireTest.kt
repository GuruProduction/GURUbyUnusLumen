// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * What the Roku adapter actually puts on the wire, and what it says when the set refuses.
 * Issue #252.
 *
 * WHY THIS TEST EXISTS WHEN LocalHttpTest ALREADY PASSES. Because that test proves the
 * client can send an `Origin` header, not that the adapter asks it to. Removing the header
 * from [RokuRemote] left every test green, which means the suite was pinned to the tool and
 * not the user of the tool. This test drives the real adapter against a real listener, so
 * the assertion fails when the adapter stops sending the header, not merely when the client
 * stops being able to.
 *
 * THE TWO DEFECTS FROM THE ISSUE, both here as tests:
 *
 *  1. A POST that carries no `Origin` is refused by Roku OS 15.3.4, so the adapter must
 *     send one on every keypress.
 *  2. A 403 from a set that is awake must be reported as that set's refusal. The previous
 *     code called it "deep standby", which sent the reader to Wake-on-LAN against a
 *     television that was on and serving pages.
 */
class RokuRemoteWireTest {

    private val servers = mutableListOf<ServerSocket>()

    @After
    fun stopServers() {
        servers.forEach { runCatching { it.close() } }
        servers.clear()
    }

    /**
     * THE REGRESSION. Every keypress must carry `Origin`, and its value must be the set's
     * own origin.
     *
     * If the adapter ever stops sending this, the Roku answers 403 and control is dead.
     */
    @Test
    fun aKeypressCarriesTheSetsOwnOrigin() = runBlocking {
        val listener = oneShot(
            "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
        )
        val remote = RokuRemote(host = "127.0.0.1", port = listener.port)

        val outcome = remote.press(TvButton.HOME)

        val received = listener.received()
        assertTrue(
            "the keypress must be a POST to the key path, and got:\n$received",
            received.startsWith("POST /keypress/Home HTTP/1.1"),
        )
        assertTrue(
            "the request must carry Origin, and the set received:\n$received",
            received.contains("Origin: http://127.0.0.1:${listener.port}", ignoreCase = true),
        )
        assertTrue("an accepted press is reported as sent", outcome is TvCommandOutcome.Sent)
    }

    /**
     * A 403 on a keypress is the set's refusal, quoting the set, and never standby.
     *
     * This is the exact sentence from the issue: the Roku had just launched Netflix and
     * answered a query in the same minute, and the tool told the caller it was asleep.
     */
    @Test
    fun aFourOhThreeOnAKeypressIsTheSetsRefusalNotStandby() = runBlocking {
        val body = "Origin  is not allowed access."
        val listener = oneShot(
            "HTTP/1.1 403 Forbidden\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.length}\r\n\r\n$body",
        )
        val remote = RokuRemote(host = "127.0.0.1", port = listener.port)

        val outcome = remote.press(TvButton.HOME)

        assertTrue("a refused press must fail", outcome is TvCommandOutcome.Failed)
        val reason = (outcome as TvCommandOutcome.Failed).reason
        assertTrue("it must name the status: $reason", reason.contains("HTTP 403"))
        assertTrue(
            "it must quote the set's own explanation, which names the missing header: $reason",
            reason.contains("Origin  is not allowed access."),
        )
        assertTrue(
            "it must say the set is awake and answering: $reason",
            reason.contains("awake and answering"),
        )
        assertFalse(
            "it must not claim deep standby, because the set answered: $reason",
            reason.contains("deep standby"),
        )
        assertFalse(
            "and it must not send the reader to a wake packet: $reason",
            reason.contains("until it is woken"),
        )
    }

    /**
     * A set that genuinely does not answer is the one case that may be called asleep, and
     * the sentence must still be honest about which failure it was.
     */
    @Test
    fun aSetThatDoesNotAnswerIsTheOnlyOneCalledAsleep() = runBlocking {
        val port = ServerSocket(0).use { it.localPort }
        val remote = RokuRemote(host = "127.0.0.1", port = port, timeoutMillis = 500)

        val outcome = remote.press(TvButton.HOME)

        assertTrue(outcome is TvCommandOutcome.Failed)
        val reason = (outcome as TvCommandOutcome.Failed).reason
        assertTrue("it must say nothing answered: $reason", reason.contains("did not answer at all"))
        assertTrue("and only here may standby be mentioned", reason.contains("deep standby"))
    }

    /**
     * A refused launch is the same shape: the set's status and words, never an invented
     * cause. A channel that is not installed answers 404 and that is a real answer.
     */
    @Test
    fun aRefusedLaunchQuotesTheSetRatherThanInventingACause() = runBlocking {
        val body = "no such channel"
        val listener = oneShot(
            "HTTP/1.1 404 Not Found\r\n" +
                "Content-Length: ${body.length}\r\n\r\n$body",
        )
        val remote = RokuRemote(host = "127.0.0.1", port = listener.port)

        val outcome = remote.launchApp("netflix")

        assertTrue(outcome is TvCommandOutcome.Failed)
        val reason = (outcome as TvCommandOutcome.Failed).reason
        assertTrue("it must name the status: $reason", reason.contains("HTTP 404"))
        assertTrue("and quote the body: $reason", reason.contains("no such channel"))
        assertTrue(
            "and say a missing channel is a real answer: $reason",
            reason.contains("not installed"),
        )
    }

    /** A successful launch still reads as before, so the fix changes only the failures. */
    @Test
    fun anAcceptedLaunchIsStillReportedAsSent() = runBlocking {
        val listener = oneShot("HTTP/1.1 201 Created\r\nContent-Length: 0\r\n\r\n")
        val remote = RokuRemote(host = "127.0.0.1", port = listener.port)

        val outcome = remote.launchApp("netflix")

        assertTrue("a launch that is accepted is sent", outcome is TvCommandOutcome.Sent)
        assertTrue(
            "and DIAL treats 201 as the launch status, which this set answered",
            (outcome as TvCommandOutcome.Sent).note.contains("channel 12"),
        )
    }

    /** A probe reads the friendly name the set reports, unchanged by this fix. */
    @Test
    fun aProbeReadsTheFriendlyName() = runBlocking {
        val body = "<device-info><friendly-device-name>Living Room</friendly-device-name>" +
            "<model-name>Example Model</model-name></device-info>"
        val listener = oneShot(
            "HTTP/1.1 200 OK\r\nContent-Length: ${body.length}\r\n\r\n$body",
        )
        val remote = RokuRemote(host = "127.0.0.1", port = listener.port)

        assertEquals("Living Room (Example Model)", remote.probe())
    }

    /**
     * A discovery probe that gets a refusal must not report the set as present, because a
     * probe that cannot read the document has not identified anything.
     */
    @Test
    fun aRefusedProbeReturnsNothingRatherThanAFabricatedName() = runBlocking {
        val listener = oneShot("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\n\r\n")
        val remote = RokuRemote(host = "127.0.0.1", port = listener.port)

        assertEquals(null, remote.probe())
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /**
     * A listener that answers exactly one request and keeps what it received.
     *
     * A real socket rather than a mock, because the defect lived in what reached the device
     * and no fake can answer that question.
     */
    private inner class OneShot(val port: Int, val latch: CountDownLatch) {
        @Volatile
        var head: String = ""

        fun received(): String {
            latch.await(5, TimeUnit.SECONDS)
            return head
        }
    }

    private fun oneShot(response: String): OneShot {
        val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        servers += server
        val shot = OneShot(server.localPort, CountDownLatch(1))
        thread(isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    shot.head = readHeadOf(socket)
                    socket.getOutputStream().apply {
                        write(response.toByteArray())
                        flush()
                    }
                }
            }
            shot.latch.countDown()
        }
        return shot
    }

    /** Read up to the blank line that ends the request headers. */
    private fun readHeadOf(socket: Socket): String {
        val out = ByteArrayOutputStream()
        val input = socket.getInputStream()
        while (true) {
            val value = input.read()
            if (value == -1) break
            out.write(value)
            if (out.toString("ISO-8859-1").endsWith("\r\n\r\n")) break
        }
        return out.toString("ISO-8859-1")
    }
}
