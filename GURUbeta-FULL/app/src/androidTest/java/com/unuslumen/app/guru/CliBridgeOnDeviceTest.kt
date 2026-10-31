// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.unuslumen.app.data.bridge.BridgeFrame
import com.unuslumen.app.data.bridge.CliBridgeService
import com.unuslumen.app.data.bridge.ParseResult
import com.unuslumen.app.data.bridge.encodeFrame
import com.unuslumen.app.data.bridge.parseFrame
import com.unuslumen.app.data.tor.TorManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.io.PrintWriter
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Proves the CLI bridge works on a real device, with a real client.
 *
 * WHY THIS EXISTS. The protocol is unit tested, which proves the frames parse.
 * That says nothing about whether a listener is bound, whether a socket is
 * accepted, whether the token gate runs before anything else, or whether a
 * message actually travels. Those are the parts that make the feature exist, and
 * only a device with a real client can prove them.
 *
 * The client here is the same protocol the CLI will speak: connect, send hello,
 * read welcome, send a message, expect an ack.
 */
@RunWith(AndroidJUnit4::class)
class CliBridgeOnDeviceTest {

    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var bridge: CliBridgeService

    @Before
    fun setUp() {
        bridge = CliBridgeService(context, TorManager())
    }

    @After
    fun tearDown() = runBlocking {
        bridge.stop()
    }

    @Test
    fun runsInTheAppDomainNotTheShellDomain() {
        val d = runCatching { File("/proc/self/attr/current").readText().trim().trim('\u0000') }
            .getOrDefault("unknown")
        println("SELinux domain under test: $d")
        assertTrue("Must run in the app domain, ran as '$d'", d.startsWith("u:r:untrusted_app"))
    }

    /**
     * The whole feature in one test: a real socket, a real handshake, a real
     * message, and a real ack, all over the protocol the CLI will use.
     */
    @Test
    fun aRealClientConnectsAuthenticatesAndGetsAnAck() = runBlocking {
        bridge.start()
        val state = bridge.state.value
        println("bridge state: listening=${state.listening} lan=${state.lanAddress}:${state.lanPort} onion=${state.onionAddress} onionError=${state.onionError}")

        assertTrue("the bridge did not start listening on any address", state.listening)
        assertTrue("no LAN port was bound", state.lanPort > 0)

        val token = bridge.ensureToken()
        assertTrue("the pairing secret is too short to be one", token.length >= 43)

        // Connect to loopback because the test runs ON the device; a real CLI
        // would use the LAN address the room shows.
        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", state.lanPort), 5000)
        socket.soTimeout = 10_000

        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        val writer = PrintWriter(socket.outputStream, true)

        writer.print(encodeFrame(BridgeFrame.Hello(agent = "test-cli", token = token)))
        writer.flush()

        val welcomeLine = reader.readLine()
        println("welcome frame: $welcomeLine")
        val welcome = parseFrame(welcomeLine ?: "")
        assertTrue("no welcome arrived: $welcomeLine", welcome is ParseResult.Ok)
        val wf = (welcome as ParseResult.Ok).frame
        assertTrue("expected a welcome, got $wf", wf is BridgeFrame.Welcome)
        assertEquals("the room name must be the bridge room", CliBridgeService.ROOM_NAME, (wf as BridgeFrame.Welcome).room)

        // A message from the CLI must land in the bridge transcript.
        writer.print(encodeFrame(BridgeFrame.Message(text = "hello from the CLI", from = "test-cli", at = System.currentTimeMillis())))
        writer.flush()

        val ackLine = reader.readLine()
        println("ack frame: $ackLine")
        val ack = parseFrame(ackLine ?: "")
        assertTrue("the CLI got no ack: $ackLine", ack is ParseResult.Ok)
        assertTrue("expected an ack, got ${(ack as ParseResult.Ok).frame}", ack.frame is BridgeFrame.Ack)

        // And it must be visible in the service's incoming transcript.
        val incoming = bridge.incoming.value
        println("incoming count: ${incoming.size} last=${incoming.lastOrNull()?.text}")
        assertTrue("the message never reached the bridge transcript", incoming.any { it.text == "hello from the CLI" })

        // GURU replying must reach the connected client.
        val delivered = bridge.broadcast("reply from guru")
        println("broadcast delivered=$delivered")
        assertTrue("a connected client got no broadcast", delivered >= 1)

        val replyLine = reader.readLine()
        println("reply frame: $replyLine")
        val reply = parseFrame(replyLine ?: "")
        assertTrue("the client did not receive the reply: $replyLine", reply is ParseResult.Ok)
        assertTrue((reply as ParseResult.Ok).frame is BridgeFrame.Message)

        socket.close()
    }

    /**
     * The token gate must refuse a client that has the wrong secret, and must
     * refuse it with an error frame rather than by hanging or by silence.
     */
    @Test
    fun aWrongSecretIsRefusedWithAnErrorFrame() = runBlocking {
        bridge.start()
        val port = bridge.state.value.lanPort
        assertTrue("bridge not listening", port > 0)

        val socket = Socket()
        socket.connect(InetSocketAddress("127.0.0.1", port), 5000)
        socket.soTimeout = 10_000
        val reader = BufferedReader(InputStreamReader(socket.inputStream))
        val writer = PrintWriter(socket.outputStream, true)

        writer.print(encodeFrame(BridgeFrame.Hello(agent = "intruder", token = "definitely-not-the-token")))
        writer.flush()

        val line = reader.readLine()
        println("refusal frame: $line")
        val parsed = parseFrame(line ?: "")
        assertTrue("no refusal arrived", parsed is ParseResult.Ok)
        val frame = (parsed as ParseResult.Ok).frame
        assertTrue("expected an error frame, got $frame", frame is BridgeFrame.Error)
        assertEquals(BridgeFrame.CODE_BAD_TOKEN, (frame as BridgeFrame.Error).code)

        socket.close()
    }

    /**
     * The onion attempt must report either an address or a reason, never
     * silence. Tor may be down on a test device; that is a state, not a crash.
     */
    @Test
    fun theOnionAttemptAlwaysReportsAnAddressOrAReason() = runBlocking {
        bridge.start()
        val state = bridge.state.value
        println("onion=${state.onionAddress} error=${state.onionError}")
        assertTrue(
            "the onion transport must say either the address it published or why it could not. " +
                "Silence is the failure mode this test exists to catch.",
            state.onionAddress != null || state.onionError != null,
        )
        if (state.onionAddress != null) {
            assertTrue("an onion address must end in .onion", state.onionAddress!!.endsWith(".onion"))
        }
    }

    /**
     * A device with no LAN address must still start and report honestly rather
     * than claiming to be reachable.
     */
    @Test
    fun theReportedLanAddressIsNullOrReal() = runBlocking {
        bridge.start()
        val s = bridge.state.value
        println("lanAddress=${s.lanAddress} port=${s.lanPort}")
        if (s.lanAddress != null) {
            assertTrue(
                "the LAN address must be a dotted quad, got '${s.lanAddress}'",
                Regex("^\\d+\\.\\d+\\.\\d+\\.\\d+$").matches(s.lanAddress!!),
            )
            assertTrue("loopback is not reachable from another machine", !s.lanAddress!!.startsWith("127."))
        }
    }
}
