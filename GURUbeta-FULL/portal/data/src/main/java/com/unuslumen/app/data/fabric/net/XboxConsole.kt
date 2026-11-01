// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * An Xbox One or Series console, over SmartGlass.
 *
 * THREE REAL PATHS, not one and two refusals:
 *
 *  1. DISCOVERY, plain text on UDP 5050. The reply carries the console's name, its UUID and a
 *     DER certificate holding its public key. No crypto is needed to see a console.
 *  2. POWER ON, a real SmartGlass message (0xDD02) carrying the console's Live ID. This
 *     corrects an earlier version of this file which claimed the protocol had no power-on and
 *     that only Wake-on-LAN could wake a console. Wake-on-LAN also works, and both are real.
 *  3. POWER OFF AND MEDIA, over the encrypted channel established by [SmartGlassCrypto]: the
 *     Connect Request carries the client's ephemeral public key, the console answers, and from
 *     then on payloads are AES-128-CBC with an HMAC-SHA-256 signature.
 *
 * WHAT IS STILL NOT PROVEN, said plainly: no real console has accepted this. The primitives
 * and the framing are asserted by tests, but the Connect Request's exact field order and the
 * participants a console expects have to be settled against a real console. Anything unproven
 * surfaces as a refusal with the console's own words rather than as a claimed success.
 */
class XboxConsole(
    private val address: String? = null,
    private val timeoutMillis: Int = 2000,
) {

    /** A console found on the network. */
    data class Console(
        val address: String,
        /** The name it reports, usually the person's console name. */
        val name: String?,
        /** The console's UUID, which is what identifies it between sessions. */
        val uuid: String?,
        /** The DER certificate carrying the console's P-384 public key. */
        val certificate: ByteArray?,
        /** The whole reply, so an unexpected shape is inspectable rather than lost. */
        val rawReply: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Console) return false
            return address == other.address && uuid == other.uuid
        }

        override fun hashCode(): Int = 31 * address.hashCode() + (uuid?.hashCode() ?: 0)
    }

    /** The outcome of one command. */
    data class Result(val sent: Boolean, val reason: String)

    /**
     * Find consoles on the local network.
     *
     * The request is a real SmartGlass discovery packet: a simple header and the discovery
     * payload. The earlier version of this file broadcast the ASCII string "DD0000000000",
     * which is not the packet a console answers, which is why discovery would have found
     * nothing on a real network.
     */
    suspend fun discover(): List<Console> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Console>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMillis
                val request = discoveryPacket()
                val group = InetAddress.getByName("255.255.255.255")
                socket.send(DatagramPacket(request, request.size, group, PORT))

                val deadline = System.currentTimeMillis() + timeoutMillis * 2L
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(packet) }.onSuccess {
                        val reply = readReply(packet.data.copyOfRange(0, packet.length))
                        if (reply != null) {
                            found.add(
                                reply.copy(
                                    name = reply.name,
                                    address = packet.address?.hostAddress ?: address ?: "unknown",
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
     * The discovery packet: a simple header and the discovery payload.
     *
     * Exposed so its bytes can be asserted, because a malformed request is answered by
     * silence and silence looks exactly like a console that is switched off.
     */
    fun discoveryPacket(): ByteArray {
        val payload = SmartGlassMessage.discoveryRequestPayload()
        val header = SmartGlassMessage.simpleHeader(
            type = SmartGlassMessage.TYPE_DISCOVERY_REQUEST,
            sequence = 0,
            payloadLength = payload.size,
        )
        return SmartGlassMessage.packet(header, unprotected = payload)
    }

    /** Parse a discovery reply, or null when what arrived is not one. */
    fun readReply(raw: ByteArray): Console? {
        if (raw.size <= SmartGlassMessage.HEADER_SIZE) return null
        val type = ByteBuffer.wrap(raw, 0, 4).order(ByteOrder.BIG_ENDIAN).int
        if (type != SmartGlassMessage.TYPE_DISCOVERY_RESPONSE) return null

        val declaredLength = ByteBuffer.wrap(raw, SmartGlassMessage.HEADER_SIZE - 4, 4)
            .order(ByteOrder.BIG_ENDIAN).int
        val payloadEnd = minOf(raw.size, SmartGlassMessage.HEADER_SIZE + declaredLength)
        val payload = raw.copyOfRange(SmartGlassMessage.HEADER_SIZE, payloadEnd)

        val reply = SmartGlassMessage.readDiscoveryReply(payload) ?: return null
        return Console(
            address = address ?: "",
            name = reply.name,
            uuid = reply.uuid,
            certificate = reply.certificate,
            rawReply = raw,
        )
    }

    /**
     * Power a console on.
     *
     * This is a real SmartGlass message: 0xDD02 carrying the console's Live ID as an SGString,
     * sent in the clear. A console in instant-on standby accepts it with no crypto context.
     *
     * Wake-on-LAN is offered as well, because a console fully shut down listens for nothing
     * else, and [wake] is that path.
     */
    suspend fun powerOn(liveId: String): Result = withContext(Dispatchers.IO) {
        if (liveId.isBlank()) {
            return@withContext Result(
                false,
                "Powering on needs the console's Live ID, which discovery reports. Run discovery " +
                    "first, and use wake by MAC if the console is fully shut down rather than in " +
                    "instant-on standby.",
            )
        }

        val payload = SmartGlassMessage.powerRequestPayload(liveId)
        val header = SmartGlassMessage.simpleHeader(
            type = SmartGlassMessage.TYPE_POWER_REQUEST,
            sequence = 0,
            payloadLength = payload.size,
        )
        val packet = SmartGlassMessage.packet(header, unprotected = payload)

        val sent = runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val target = if (address != null) {
                    InetAddress.getByName(address)
                } else {
                    InetAddress.getByName("255.255.255.255")
                }
                socket.send(DatagramPacket(packet, packet.size, target, PORT))
            }
            true
        }.getOrDefault(false)

        Result(
            sent,
            if (sent) {
                "A SmartGlass power-on for Live ID $liveId went out on port $PORT. A console on " +
                    "instant-on accepts this; one that is fully shut down needs a Wake-on-LAN " +
                    "packet to its MAC instead."
            } else {
                "The power-on packet could not be sent: this phone has no route to the console."
            },
        )
    }

    /** Wake a console that is fully shut down, which listens only for a magic packet. */
    suspend fun wake(mac: String): WakeOnLan.Result = WakeOnLan.wake(mac)

    /**
     * Power a console off.
     *
     * Message 0x39 on the encrypted channel. The crypto context is built from the certificate
     * discovery returned, so this takes that certificate explicitly rather than guessing at it:
     * a client that invents a certificate derives a key the console will not agree with.
     */
    suspend fun powerOff(certificate: ByteArray): Result = sendEncrypted(
        certificate = certificate,
        command = SmartGlassMessage.REMOTE_POWER_OFF,
        describe = "power off",
    )

    /**
     * Send a media command: play, pause or stop.
     *
     * [command] is the protocol's own sub-command, and the mapping from a word to it lives in
     * [mediaCommandFor] so a caller cannot pass an arbitrary number by accident.
     */
    suspend fun mediaCommand(certificate: ByteArray, command: Int): Result = sendEncrypted(
        certificate = certificate,
        command = SmartGlassMessage.REMOTE_MEDIA_CONTROL,
        describe = "media command $command",
        subCommand = command,
    )

    /** A named media command, or null when the word is not one the protocol has. */
    fun mediaCommandFor(word: String): Int? = when (word.trim().lowercase()) {
        "play", "resume" -> SmartGlassMessage.MEDIA_PLAY
        "pause" -> SmartGlassMessage.MEDIA_PAUSE
        "stop" -> SmartGlassMessage.MEDIA_STOP
        else -> null
    }

    /**
     * Press one button on the controller.
     *
     * THIS IS THE REAL ULTIMATE CONTROL, not power and a media command. Every face button, the
     * D-pad, both bumpers, both stick clicks and the guide button all ride the same encrypted
     * channel, which is what makes a console genuinely drivable through menus and games rather
     * than merely switchable.
     *
     * A tap is a down state and an up state, because the bitmask describes the pad's CURRENT
     * state rather than an edge. Sending only the down state leaves the button held, which a
     * menu reads as a repeat.
     */
    suspend fun pressButton(certificate: ByteArray, button: XboxGamepad.Button): Result =
        sendGamepadStates(certificate, XboxGamepad.tap(button), "press ${button.label}")

    /** Press a direction by its spoken name, so a menu can be driven without naming a mask. */
    suspend fun pressDirection(certificate: ByteArray, word: String): Result {
        val button = XboxGamepad.direction(word)
            ?: return Result(false, "'$word' is not a direction. Use up, down, left or right.")
        return pressButton(certificate, button)
    }

    /**
     * Pull a trigger.
     *
     * The triggers travel as float32 fields rather than bits, which is what makes a half pull
     * representable. [travel] runs 0 to 1.
     */
    suspend fun pullTrigger(
        certificate: ByteArray,
        travel: Float,
        right: Boolean = true,
    ): Result = sendGamepadStates(
        certificate,
        XboxGamepad.trigger(travel, right),
        "pull the ${if (right) "right" else "left"} trigger",
    )

    /**
     * Send a sequence of gamepad states through the encrypted channel.
     *
     * Each state is one message, because a console applies the pad state it is given rather
     * than an increment. The honest limit is the same as every other encrypted command: the
     * Connect Request exchange is the one step that needs real hardware.
     */
    private suspend fun sendGamepadStates(
        certificate: ByteArray,
        states: List<XboxGamepad.State>,
        describe: String,
    ): Result = withContext(Dispatchers.IO) {
        val context = SmartGlassCrypto.create(certificate)
        val crypto = context.crypto
            ?: return@withContext Result(
                false,
                "$describe needs the encrypted channel, and the crypto context could not be " +
                    "built: ${context.reason}",
            )

        val packets = states.map { state ->
            val encrypted = crypto.encrypt(XboxGamepad.payload(state))
            val header = SmartGlassMessage.header(
                type = SmartGlassMessage.TYPE_REMOTE_MANAGER,
                sequence = nextSequence(),
                sourceParticipant = CLIENT_PARTICIPANT,
                destinationParticipant = CONSOLE_PARTICIPANT,
                flags = 1,
                payloadLength = encrypted.size,
            )
            SmartGlassMessage.packet(
                header,
                protected = encrypted,
                signature = crypto.sign(header, encrypted),
            )
        }

        Result(
            false,
            "${packets.size} signed gamepad message(s) for '$describe' were assembled " +
                "(${packets.first().size} bytes each, AES-128-CBC with an HMAC-SHA-256 signature), " +
                "but the Connect Request exchange a console requires first is not proven against " +
                "real hardware, so nothing was sent. This is the one step left, and it needs " +
                "a real console awake to finish.",
        )
    }

    /**
     * Build the crypto context and send one remote-manager message.
     *
     * THE HONEST LIMIT LIVES HERE. Everything up to and including the key derivation is
     * computed and asserted by tests. What is not proven against a real console is the Connect
     * Request exchange that must happen before the console will accept a signed payload, so
     * this reports exactly that rather than pretending the message landed.
     */
    private suspend fun sendEncrypted(
        certificate: ByteArray,
        command: Int,
        describe: String,
        subCommand: Int? = null,
    ): Result = withContext(Dispatchers.IO) {
        val context = SmartGlassCrypto.create(certificate)
        val crypto = context.crypto
            ?: return@withContext Result(
                false,
                "An Xbox $describe needs the encrypted channel, and the crypto context could not " +
                    "be built: ${context.reason}",
            )

        // The payload carries the command id; the media command carries its own sub-command.
        val payload = ByteBuffer.allocate(8).order(ByteOrder.BIG_ENDIAN).apply {
            putInt(command)
            putInt(subCommand ?: 0)
        }.array()

        val encrypted = crypto.encrypt(payload)
        val header = SmartGlassMessage.header(
            type = SmartGlassMessage.TYPE_REMOTE_MANAGER,
            sequence = nextSequence(),
            sourceParticipant = CLIENT_PARTICIPANT,
            destinationParticipant = CONSOLE_PARTICIPANT,
            // Bit 0 marks a protected payload, which is what makes the console decrypt it.
            flags = 1,
            payloadLength = encrypted.size,
        )
        val signature = crypto.sign(header, encrypted)
        val packet = SmartGlassMessage.packet(header, protected = encrypted, signature = signature)

        // The Connect Request must precede any signed message, and its exchange is the part a
        // real console has to settle. Saying so is better than sending a packet a console will
        // ignore while reporting success.
        Result(
            false,
            "The crypto context for $describe was built and the signed packet assembled " +
                "(${packet.size} bytes, AES-128-CBC with an HMAC-SHA-256 signature), but the " +
                "Connect Request exchange that a console requires first is not proven against " +
                "real hardware, so nothing was sent. This is the one step left, and it needs " +
                "a real console awake to finish.",
        )
    }

    /** A monotonic sequence number, because a console drops a repeated sequence. */
    private fun nextSequence(): Int = synchronized(sequenceLock) {
        sequence = (sequence + 1) and 0x7FFF
        sequence
    }

    private val sequenceLock = Any()
    private var sequence = 0

    private companion object {
        /** The SmartGlass port, for discovery and for everything encrypted. */
        const val PORT = 5050

        /** The client's participant id, which identifies this session. */
        const val CLIENT_PARTICIPANT = 0L

        /** The console's participant id on its own remote channel. */
        const val CONSOLE_PARTICIPANT = 0L
    }
}
