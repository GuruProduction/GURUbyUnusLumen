// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import androidx.test.core.app.ApplicationProvider
import com.unuslumen.app.data.tools.registry.ToolExecutionResult
import com.unuslumen.app.data.tools.registry.ToolExecutor
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Runnable
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.ServerSocket
import kotlin.coroutines.CoroutineContext

/**
 * netProbe must do its socket work off the calling thread. Issue #217.
 *
 * THE DEFECT THIS PINS. On build 3.16 netProbe threw `NetworkOnMainThreadException` on every
 * call, before a byte was read, while `netHosts` and `netDeepScan` on the same host and port
 * returned real banners. Both probe paths were plain functions doing blocking socket work,
 * and the dispatcher calls the executor from the main thread. Android refuses a socket call
 * there, so the tool was unusable on every device rather than on one network.
 *
 * WHY THE DISPATCHER IS INJECTED, AND WHY THAT IS NOT A CONCESSION. A first version of this
 * test asserted that the probe returned bytes and that no exception was mentioned. It passed
 * with the fix REMOVED, because Robolectric runs the test body on the main looper but does
 * not enforce the platform's main-thread socket ban, so the broken code happily read from a
 * loopback socket here. A test that passes on broken code is worse than no test: it signs off
 * the very defect it was written for.
 *
 * So the executor takes its dispatcher as a parameter and the test passes one that records
 * which thread each block ran on. The assertion is then about the mechanism, not the symptom:
 * the socket work must not happen on the thread that called the tool. That is exactly what
 * the bug was, and it cannot pass while the wrapper is missing.
 *
 * A REAL SERVER IS STILL REQUIRED. The probe must genuinely read bytes, or a wrapper that
 * moved nothing would satisfy a test about threads alone.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class NetProbeThreadingTest {

    private lateinit var server: ServerSocket
    private lateinit var serverThread: Thread

    /** Every thread name the probe's work actually ran on. */
    private val threads = java.util.Collections.synchronizedList(mutableListOf<String>())

    @Before
    fun startServer() {
        server = ServerSocket(0)
        serverThread = Thread {
            // Answer for the life of the test, so neither probe races a single-shot server.
            while (!server.isClosed) {
                runCatching {
                    server.accept().use { client ->
                        client.getOutputStream().apply {
                            // A DECLARED LENGTH, because a real device always sends one. The
                            // JDK reads an HTTP/1.1 response with no Content-Length as an
                            // empty body, so a fixture without one fails the probe for a
                            // reason that has nothing to do with the probe.
                            val banner = "ROKU-ISH BANNER"
                            val length = banner.toByteArray(Charsets.US_ASCII).size
                            write(
                                (
                                    "HTTP/1.1 200 OK\r\n" +
                                        "Server: Roku/9.4\r\n" +
                                        "Content-Type: text/plain\r\n" +
                                        "Content-Length: $length\r\n" +
                                        "\r\n" +
                                        banner
                                    ).toByteArray(Charsets.US_ASCII),
                            )
                            flush()
                        }
                    }
                }
            }
        }
        serverThread.isDaemon = true
        serverThread.start()
    }

    @After
    fun stopServer() {
        runCatching { server.close() }
    }

    /** The socket work must not run on the thread that called the tool. */
    @Test
    fun theSocketWorkDoesNotHappenOnTheCallingThread() {
        val result = probe("raw")
        val caller = Thread.currentThread().name

        assertTrue(
            "the probe must have run, and got: ${result.error}",
            result.success,
        )
        assertTrue(
            "the probe must have recorded the threads it ran on, and recorded none",
            threads.isNotEmpty(),
        )
        assertTrue(
            "the socket work ran on the calling thread ($caller), which is the exact fault " +
                "issue #217 filed: Android refuses a socket there and the tool fails on every " +
                "device. Threads used: $threads",
            threads.none { it == caller },
        )
    }

    /** A raw probe must return the bytes the device sent, not a refusal. */
    @Test
    fun aRawProbeReturnsTheRealBytes() {
        val result = probe("raw")
        println("raw probe: success=${result.success} error=${result.error} raw=${result.rawJson.take(300)}")

        assertFalse(
            "the probe must not fail on the main thread, and got: ${result.error}",
            result.error.orEmpty().contains("NetworkOnMainThread"),
        )
        assertTrue(
            "the raw probe must return what the server sent, and got: ${result.rawJson}",
            result.rawJson.contains("ROKU-ISH BANNER"),
        )
    }

    /** The HTTP path had the same fault and must return the body the server sent. */
    @Test
    fun anHttpProbeReturnsTheBodyTheServerSent() {
        val result = probe("http")
        println("http probe: success=${result.success} error=${result.error} raw=${result.rawJson.take(300)}")

        assertFalse(
            "the probe must not fail on the main thread, and got: ${result.error}",
            result.error.orEmpty().contains("NetworkOnMainThread"),
        )
        // ASSERTED ON THE DECODED FIELD, NOT A SUBSTRING OF THE ENVELOPE. Searching the whole
        // JSON would pass on the Server header alone while the body arrived empty.
        val decoded = Json { ignoreUnknownKeys = true }
            .decodeFromString<HostProbeResult>(result.rawJson)
        assertEquals(
            "the HTTP probe must come back with the body the server sent",
            "ROKU-ISH BANNER",
            decoded.response,
        )
    }

    /** The HTTP path must dispatch off the calling thread too, not just the raw one. */
    @Test
    fun theHttpSocketWorkDoesNotHappenOnTheCallingThread() {
        val result = probe("http")
        val caller = Thread.currentThread().name

        assertTrue("the HTTP probe must have run, and got: ${result.error}", result.success)
        assertTrue("the probe must have recorded its threads", threads.isNotEmpty())
        assertNotEquals(
            "the HTTP socket work ran on the calling thread ($caller), which is issue #217",
            listOf(caller),
            threads.distinct(),
        )
    }

    /**
     * Run the tool and record every thread the dispatcher executes on.
     *
     * The recording dispatcher delegates to a real pool, so the work genuinely happens on
     * another thread rather than being faked. That is what makes the assertion meaningful:
     * it observes where the work went, it does not pretend it went somewhere.
     */
    private fun probe(method: String): ToolExecutionResult {
        val port = server.localPort
        val recorder = RecordingDispatcher(threads)
        val executor: ToolExecutor = NetworkToolExecutor(
            NetworkScanner(),
            NetworkStore(),
            recorder,
        )
        return runBlocking {
            executor.execute(
                "netProbe",
                mapOf("address" to "127.0.0.1", "port" to port, "method" to method),
            )
        }
    }

    /**
     * A dispatcher that notes the thread it ran a block on, then really runs it elsewhere.
     *
     * Dispatches onto a fresh thread per block rather than a shared pool, so a fast test
     * cannot happen to reuse the caller's name and produce a false pass.
     */
    private class RecordingDispatcher(
        private val threads: MutableList<String>,
    ) : CoroutineDispatcher() {

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            val thread = Thread {
                threads.add(Thread.currentThread().name)
                block.run()
            }
            thread.name = "netProbe-test-io-${thread.id}"
            thread.isDaemon = true
            thread.start()
        }
    }
}
