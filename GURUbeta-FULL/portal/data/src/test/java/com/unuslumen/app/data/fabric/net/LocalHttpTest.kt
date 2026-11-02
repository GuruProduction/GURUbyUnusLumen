// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The local HTTP client and the answer it reports. Issue #252.
 *
 * WHAT THIS PROVES, and it is the point of the whole fix: a header the platform reserves
 * really does reach the device, and a device that answers with an error is reported as
 * having answered rather than as having disappeared.
 *
 * WHY A REAL SOCKET RATHER THAN A MOCK. The defect was not in this app's logic, it was in
 * what the JDK actually put on the wire: `Origin` was dropped silently and nothing in the
 * source could have revealed it. A mock would agree with whatever the code believed. A
 * local listener can only report what arrived, so that is what these tests use, and one of
 * them asserts the same thing about `HttpURLConnection` to keep the reason recorded.
 */
class LocalHttpTest {

    private val servers = mutableListOf<ServerSocket>()

    @After
    fun stopServers() {
        servers.forEach { runCatching { it.close() } }
        servers.clear()
    }

    // ------------------------------------------------------------------
    // The header that started this
    // ------------------------------------------------------------------

    /**
     * THE REGRESSION THAT MATTERS. A POST must carry `Origin`, and it must be the device's
     * own origin, because that is the value the set accepts.
     *
     * If this test ever fails, every Roku keypress is broken again with a 403 that reads
     * like a sleeping television.
     */
    @Test
    fun aPostCarriesTheOriginHeaderAndTheDeviceItselfIsRefusedItByTheJdk() {
        val captured = captureRequest(
            response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
            method = "POST",
            path = "/keypress/Home",
        ) { port ->
            LocalHttp.request(
                host = "127.0.0.1",
                port = port,
                method = "POST",
                path = "/keypress/Home",
                headers = mapOf("Origin" to LocalHttp.sameOrigin("127.0.0.1", port)),
            )
        }

        assertTrue(
            "the request must carry Origin, and the server received:\n${captured.head}",
            captured.head.contains("Origin: http://127.0.0.1:", ignoreCase = true),
        )

        // And the recorded reason: HttpURLConnection drops the very same header, which is
        // why this client exists at all. If a future JDK stops doing that, this assertion
        // fails and the note in LocalHttp.kt can be corrected rather than left as folklore.
        val viaJdk = captureRequest(
            response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
            method = "POST",
            path = "/keypress/Home",
        ) { port ->
            val connection = java.net.URL("http://127.0.0.1:$port/keypress/Home")
                .openConnection(java.net.Proxy.NO_PROXY) as java.net.HttpURLConnection
            connection.requestMethod = "POST"
            connection.setRequestProperty("Origin", "http://127.0.0.1:$port")
            connection.setRequestProperty("X-Probe", "present")
            connection.doOutput = true
            connection.setFixedLengthStreamingMode(0)
            connection.outputStream.close()
            connection.responseCode
            connection.disconnect()
        }

        assertTrue(
            "the custom header must still arrive, or this test proves nothing",
            viaJdk.head.contains("X-Probe: present", ignoreCase = true),
        )
        assertFalse(
            "HttpURLConnection is documented in LocalHttp.kt as dropping Origin; if this now " +
                "arrives, that note is stale. Received:\n${viaJdk.head}",
            viaJdk.head.contains("Origin:", ignoreCase = true),
        )
    }

    /** A GET needs no Origin, because Roku only refuses the methods that change the set. */
    @Test
    fun aGetIsSentWithTheHostAndConnectionClose() {
        val captured = captureRequest(
            response = "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n\r\n",
            method = "GET",
            path = "/query/device-info",
        ) { port ->
            LocalHttp.request(
                host = "127.0.0.1",
                port = port,
                method = "GET",
                path = "/query/device-info",
            )
        }

        assertTrue(captured.head.startsWith("GET /query/device-info HTTP/1.1"))
        assertTrue(captured.head.contains("Host: 127.0.0.1:", ignoreCase = true))
        assertTrue(captured.head.contains("Connection: close", ignoreCase = true))
        assertTrue(captured.head.contains("Content-Length: 0", ignoreCase = true))
    }

    // ------------------------------------------------------------------
    // An answer is an answer
    // ------------------------------------------------------------------

    /**
     * A 403 comes back as an answer carrying the device's own words.
     *
     * The old code read only `inputStream`, which is empty on an error status, so the body
     * that named the missing header was discarded and the caller got a bare code.
     */
    @Test
    fun anErrorStatusIsAnsweredWithItsBodyRatherThanThrownAway() {
        val body = "Origin  is not allowed access."
        val outcome = serveOnce(
            "HTTP/1.1 403 Forbidden\r\n" +
                "Content-Type: text/plain\r\n" +
                "Content-Length: ${body.length}\r\n\r\n$body",
        ) { port ->
            LocalHttp.request(host = "127.0.0.1", port = port, method = "POST", path = "/keypress/Home")
        }

        assertTrue("a 403 is an answer, not an absence", outcome is LocalHttp.Outcome.Answered)
        val response = (outcome as LocalHttp.Outcome.Answered).response
        assertEquals(403, response.status)
        assertFalse(response.ok)
        assertEquals("Origin  is not allowed access.", response.body)
    }

    /** A response with no body still yields a status, which is the whole answer. */
    @Test
    fun aNoContentStatusCodeIsStillAnAnswer() {
        val outcome = serveOnce("HTTP/1.1 204 No Content\r\n\r\n") { port ->
            LocalHttp.request(host = "127.0.0.1", port = port, method = "POST", path = "/keypress/Home")
        }
        assertTrue(outcome is LocalHttp.Outcome.Answered)
        assertEquals(204, (outcome as LocalHttp.Outcome.Answered).response.status)
    }

    /** A chunked body is reassembled, because some devices send one. */
    @Test
    fun aChunkedBodyIsReassembled() {
        val outcome = serveOnce(
            "HTTP/1.1 200 OK\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n" +
                "5\r\nhello\r\n" +
                "6\r\n world\r\n" +
                "0\r\n\r\n",
        ) { port ->
            LocalHttp.request(host = "127.0.0.1", port = port, method = "GET", path = "/desc")
        }
        assertEquals(
            "hello world",
            (outcome as LocalHttp.Outcome.Answered).response.body,
        )
    }

    /** Headers are matched without regard to case, because devices differ. */
    @Test
    fun aHeaderIsReadWhateverCaseItWasSentIn() {
        val outcome = serveOnce(
            "HTTP/1.1 200 OK\r\nApplication-URL: http://127.0.0.1:8009/apps\r\n\r\n",
        ) { port ->
            LocalHttp.request(host = "127.0.0.1", port = port, method = "GET", path = "/desc")
        }
        val response = (outcome as LocalHttp.Outcome.Answered).response
        assertEquals("http://127.0.0.1:8009/apps", response.header("application-url"))
        assertEquals("http://127.0.0.1:8009/apps", response.header("Application-URL"))
    }

    // ------------------------------------------------------------------
    // An absence is not an answer
    // ------------------------------------------------------------------

    /**
     * A closed port produces no answer, and that is the only case that may be called a
     * sleeping device.
     */
    @Test
    fun aClosedPortIsAnAbsenceRatherThanARefusal() {
        val port = freePort()
        val outcome = LocalHttp.request(
            host = "127.0.0.1",
            port = port,
            method = "GET",
            path = "/query/device-info",
            timeoutMillis = 500,
        )
        assertTrue("nothing was listening, so nothing answered", outcome is LocalHttp.Outcome.NoAnswer)
        assertTrue(
            "and the reason must name the refusal to connect: " +
                (outcome as LocalHttp.Outcome.NoAnswer).reason,
            outcome.reason.contains("nothing is listening"),
        )
    }

    /** A public host is refused instead of being routed around Tor. */
    @Test
    fun aPublicAddressIsRefusedRatherThanReachedDirectly() {
        val outcome = LocalHttp.request(
            host = "8.8.8.8",
            port = 80,
            method = "GET",
            path = "/",
            timeoutMillis = 500,
        )
        assertTrue(outcome is LocalHttp.Outcome.NoAnswer)
        assertTrue(
            (outcome as LocalHttp.Outcome.NoAnswer).reason.contains("not on this network"),
        )
    }

    // ------------------------------------------------------------------
    // What the answer means
    // ------------------------------------------------------------------

    /**
     * A 403 is described as the set's own refusal, and never as a sleeping device.
     *
     * This is the sentence a person reads. The old one sent them to Wake-on-LAN against a
     * television that was awake and serving pages.
     */
    @Test
    fun aFourOhThreeIsNeverDescribedAsStandby() {
        val sentence = DeviceAnswer.describe(
            "the Roku at 192.0.2.10:8060",
            LocalHttp.Response(
                status = 403,
                reason = "Forbidden",
                headers = emptyMap(),
                body = "Origin  is not allowed access.",
            ),
        )
        assertTrue("it must say the set refused", sentence.contains("HTTP 403"))
        assertTrue(
            "it must quote the device's own words: $sentence",
            sentence.contains("Origin  is not allowed access."),
        )
        assertTrue(
            "it must say the set is awake, because it answered: $sentence",
            sentence.contains("awake and answering"),
        )
        assertFalse(
            "it must NOT claim standby, which is a claim about an absent host: $sentence",
            sentence.contains("deep standby"),
        )
    }

    /** Only an absence may be attributed to standby. */
    @Test
    fun onlyAnAbsenceMentionsStandby() {
        val sentence = DeviceAnswer.describeNoAnswer(
            "the Roku at 192.0.2.10:8060",
            "nothing is listening on 192.0.2.10:8060",
        )
        assertTrue(sentence.contains("deep standby"))
        assertTrue(sentence.contains("nothing is listening"))
    }

    /** A 404 says the endpoint is wrong, not that the device is gone. */
    @Test
    fun aFourOhFourIsADeviceAnswerAboutThePath() {
        val sentence = DeviceAnswer.describe(
            "the Fire TV at 192.0.2.10:8009",
            LocalHttp.Response(404, "Not Found", emptyMap(), ""),
        )
        assertTrue(sentence.contains("does not exist on this device"))
        assertFalse(sentence.contains("standby"))
    }

    /** Every 4xx is a refusal, because the device considered the request and said no. */
    @Test
    fun everyClientErrorIsARefusalAndEverySuccessIsNot() {
        assertTrue(DeviceAnswer.isRefusal(403))
        assertTrue(DeviceAnswer.isRefusal(404))
        assertTrue(DeviceAnswer.isRefusal(400))
        assertFalse(DeviceAnswer.isRefusal(200))
        assertFalse(DeviceAnswer.isRefusal(500))
    }

    /** An empty body must not produce a dangling sentence about what the device said. */
    @Test
    fun anEmptyBodyProducesACleanSentence() {
        val sentence = DeviceAnswer.describe(
            "the Roku at 192.0.2.10:8060",
            LocalHttp.Response(403, "Forbidden", emptyMap(), ""),
        )
        assertFalse("no empty quote of nothing", sentence.contains("It said:"))
        assertTrue(sentence.contains("a required header was absent"))
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private data class Captured(val head: String)

    /**
     * Run one client call against a one-shot listener and return what arrived.
     *
     * The listener is a real socket because the entire point is what reaches the device,
     * and a fake transport cannot answer that question.
     */
    private fun captureRequest(
        response: String,
        method: String,
        path: String,
        call: (Int) -> Unit,
    ): Captured {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        servers += server
        val port = server.localPort
        val ready = CountDownLatch(1)
        val captured = arrayOfNulls<String>(1)

        thread(isDaemon = true) {
            ready.countDown()
            runCatching {
                server.accept().use { socket ->
                    captured[0] = readHeadOf(socket)
                    socket.getOutputStream().apply {
                        write(response.toByteArray())
                        flush()
                    }
                }
            }
        }

        ready.await(5, TimeUnit.SECONDS)
        call(port)
        waitFor { captured[0] != null }
        return Captured(captured[0].orEmpty())
    }

    /** Send one canned response and return the client's outcome. */
    private fun serveOnce(
        response: String,
        call: (Int) -> LocalHttp.Outcome,
    ): LocalHttp.Outcome {
        val server = ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        servers += server
        val port = server.localPort
        val ready = CountDownLatch(1)

        thread(isDaemon = true) {
            ready.countDown()
            runCatching {
                server.accept().use { socket ->
                    readHeadOf(socket)
                    socket.getOutputStream().apply {
                        write(response.toByteArray())
                        flush()
                    }
                }
            }
        }

        ready.await(5, TimeUnit.SECONDS)
        val outcome = call(port)
        // The listener thread finishes on its own; the client already has the answer.
        return outcome
    }

    /** Read up to the blank line that ends the request headers. */
    private fun readHeadOf(socket: Socket): String {
        val out = ByteArrayOutputStream()
        val input = socket.getInputStream()
        while (true) {
            val value = input.read()
            if (value == -1) break
            out.write(value)
            val text = out.toString("ISO-8859-1")
            if (text.endsWith("\r\n\r\n")) break
        }
        return out.toString("ISO-8859-1")
    }

    /** Spin until a background listener has recorded what it saw. */
    private fun waitFor(condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            Thread.sleep(10)
        }
    }

    /** A port nothing is listening on. */
    private fun freePort(): Int = ServerSocket(0).use { it.localPort }
}
