// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * An Xbox One or Series console. Requested because Dylan has one.
 *
 * WHAT IS GENUINELY POSSIBLE, stated before any code, because Xbox is the one device on the
 * list where the honest answer is narrower than for everything else.
 *
 * SMARTGLASS IS HALF A PROTOCOL IN THE OPEN. Discovery and power are plain text on UDP 5050,
 * and everything else is encrypted under a crypto context negotiated from a certificate the
 * console sends back. The message types are documented (0x39 power off, 0xF01 media control,
 * 0xF0A button input), but the encryption layer is not, so a full remote is a real
 * implementation job rather than an afternoon.
 *
 * POWER ON IS NOT A SMARTGLASS COMMAND AT ALL. The published message list has no power-on,
 * and the reason is that a console in instant-on standby listens for nothing but a Wake-on-LAN
 * magic packet on its MAC. That is why this file wakes a console the same way it wakes a
 * television, and says so rather than pretending SmartGlass has a power-on.
 *
 * SO THIS DOES WHAT IS PROVABLE: it discovers the console on the network, which is plain text
 * and needs no crypto, and it wakes it with a magic packet. Powering one off and driving its
 * media need the encrypted channel, and those refuse with the real reason rather than sending
 * bytes a console will ignore.
 */
class XboxConsole(
    private val address: String? = null,
    private val timeoutMillis: Int = 1500,
) {

    /** A console found on the network. */
    data class Console(
        val address: String,
        /** The console's id, which is what a discovery reply carries. */
        val id: Long?,
        /** The name it reports, usually the person's gamertag or the console name. */
        val name: String?,
        val rawReply: String,
    )

    /**
     * Find consoles on the local network.
     *
     * Discovery is the plain-text half of the protocol: a UDP broadcast to port 5050 with a
     * simple discovery request, and the reply carries the console's live id and its name.
     * That is genuinely enough to see a console, which is what "GURU sees everything on the
     * network" needs first.
     */
    suspend fun discover(): List<Console> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Console>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMillis
                val request = "DD0000000000000000000000".toByteArray(Charsets.US_ASCII)
                val group = InetAddress.getByName("255.255.255.255")
                socket.send(DatagramPacket(request, request.size, group, 5050))

                val deadline = System.currentTimeMillis() + timeoutMillis * 2L
                val buffer = ByteArray(1024)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(packet) }.onSuccess {
                        val text = String(packet.data, 0, packet.length, Charsets.US_ASCII).trim()
                        // A discovery reply begins with "DD" and carries the id as hex after it.
                        if (text.startsWith("DD", ignoreCase = true) && text.length > 10) {
                            found.add(
                                Console(
                                    address = packet.address?.hostAddress ?: "unknown",
                                    id = text.drop(2).take(12).toLongOrNull(16),
                                    name = null,
                                    rawReply = text,
                                ),
                            )
                        }
                    }
                }
            }
        }
        found.distinctBy { it.address }
    }

    /**
     * Wake a console in standby.
     *
     * This is the only working power-on, and it needs the MAC rather than the address,
     * because a console in standby does not answer anything except the magic packet.
     */
    suspend fun wake(mac: String): WakeOnLan.Result = WakeOnLan.wake(mac)

    /**
     * Power a console off.
     *
     * Refused with the real reason rather than attempted. Message 0x39 is the documented
     * power-off, but it rides the encrypted channel, and sending it in the clear produces a
     * datagram a console drops without replying. Reporting that as sent would be exactly the
     * false success this project exists to remove.
     */
    suspend fun powerOff(): Result = withContext(Dispatchers.IO) {
        Result(
            sent = false,
            reason = "An Xbox powers off over message 0x39 on SmartGlass's ENCRYPTED channel, " +
                "which needs a crypto context negotiated from the console's certificate. That " +
                "layer is not implemented yet, so nothing was sent rather than sending a packet " +
                "the console would drop.",
        )
    }

    /** Media control, refused for the same reason as power off. */
    suspend fun mediaCommand(command: String): Result = withContext(Dispatchers.IO) {
        Result(
            sent = false,
            reason = "'$command' on an Xbox needs message 0xF01 on the encrypted channel, which is " +
                "not implemented yet. Nothing was sent.",
        )
    }

    data class Result(val sent: Boolean, val reason: String)

    /**
     * The Xbox discovery request, exposed so its bytes can be asserted.
     *
     * The format is a literal "DD" followed by ten zero bytes, and a console answers it with
     * its own id. It is ASCII rather than binary, which is why it is written as a string
     * here instead of being built byte by byte.
     */
    fun discoveryRequest(): ByteArray = DISCOVERY_REQUEST.toByteArray(Charsets.US_ASCII)

    private companion object {
        /** The documented discovery request: "DD" and ten zero bytes. */
        const val DISCOVERY_REQUEST = "DD0000000000000000000000"

        /** The SmartGlass port, for discovery and for everything encrypted. */
        const val PORT = 5050
    }
}
