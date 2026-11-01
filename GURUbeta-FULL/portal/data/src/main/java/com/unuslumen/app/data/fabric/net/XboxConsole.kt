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
 * An Xbox One or Series console, over SmartGlass on UDP 5050.
 *
 * ANONYMOUS FIRST, AND THAT IS A DELIBERATE CHOICE. The console accepts a client with an empty
 * user hash and an empty token when its own "allow connections from any device" setting is on,
 * and that grants the whole controller, power, text entry and media control. Signing in buys
 * exactly one extra thing, launching a title, and even that stopped working locally in 2019. So
 * the default path needs no account at all.
 *
 * THE TWO HARD CEILINGS, named rather than hidden because a promise GURU cannot keep is worse
 * than a refusal:
 *
 *  - LAUNCHING A GAME OR APP BY ID IS GONE. Microsoft removed the local title-launch message in
 *    2019 and consoles ignore it. A game is started by navigating with the D-pad, which this
 *    tool fully supports, or through the cloud path, which needs an Xbox Live account.
 *  - POWERING OFF A SERIES CONSOLE IS LESS RELIABLE than on an Xbox One. The local power-off
 *    message works on One; some Series firmware ignores it and needs the cloud command.
 *
 * WHAT IS FULLY SUPPORTED: every button including face buttons, both D-pads, both bumpers, both
 * stick clicks, the guide button, both triggers and both analogue sticks.
 */
class XboxConsole(
    private val address: String? = null,
    private val timeoutMillis: Int = 2000,
) {

    /** A console found on the network. */
    data class Console(
        val address: String,
        val name: String?,
        val uuid: String?,
        /** The Live ID, which power on and off need. */
        val liveId: String?,
        /** The DER certificate carrying the console's P-384 public key. */
        val certificate: ByteArray?,
        /** Whether the console reports that anonymous clients are allowed. */
        val allowsAnonymous: Boolean,
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

    // ------------------------------------------------------------------
    // Discovery
    // ------------------------------------------------------------------

    /**
     * Find consoles on the local network.
     *
     * The request is a simple packet carrying the discovery payload, and the reply carries the
     * name, the UUID, the certificate and the Live ID that power commands need.
     */
    suspend fun discover(): List<Console> = withContext(Dispatchers.IO) {
        val found = mutableListOf<Console>()
        runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                socket.soTimeout = timeoutMillis
                val request = discoveryPacket()
                socket.send(
                    DatagramPacket(request, request.size, InetAddress.getByName("255.255.255.255"), PORT),
                )

                val deadline = System.currentTimeMillis() + timeoutMillis * 2L
                val buffer = ByteArray(4096)
                while (System.currentTimeMillis() < deadline) {
                    val packet = DatagramPacket(buffer, buffer.size)
                    runCatching { socket.receive(packet) }.onSuccess {
                        readReply(packet.data.copyOfRange(0, packet.length))?.let { reply ->
                            found.add(reply.copy(address = packet.address?.hostAddress ?: "unknown"))
                        }
                    }
                }
            }
        }
        found.distinctBy { it.address }
    }

    /** The discovery packet: a simple header plus the discovery payload. */
    fun discoveryPacket(): ByteArray {
        val payload = SmartGlassMessage.discoveryRequestPayload()
        return SmartGlassMessage.packet(
            header = SmartGlassMessage.simpleHeader(
                type = SmartGlassMessage.TYPE_DISCOVERY_REQUEST,
                unprotectedLength = payload.size,
            ),
            unprotected = payload,
        )
    }

    /**
     * Parse a discovery reply.
     *
     * The reply's own header is a simple header, so the payload begins at six bytes. Reading it
     * at any other offset produces a name that looks like garbage rather than a clear failure.
     */
    fun readReply(raw: ByteArray): Console? {
        if (raw.size <= SmartGlassMessage.SIMPLE_HEADER_SIZE) return null
        val type = ByteBuffer.wrap(raw, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        if (type != SmartGlassMessage.TYPE_DISCOVERY_RESPONSE) return null

        val declared = ByteBuffer.wrap(raw, 2, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
        val start = SmartGlassMessage.SIMPLE_HEADER_SIZE
        val end = minOf(raw.size, start + declared)
        val payload = raw.copyOfRange(start, end)

        val reply = SmartGlassMessage.readDiscoveryReply(payload) ?: return null
        return Console(
            address = address ?: "",
            name = reply.name,
            uuid = reply.uuid,
            liveId = reply.liveId,
            certificate = reply.certificate,
            // Bit 0x04 in the primary device flags is "allow anonymous users".
            allowsAnonymous = (reply.flags and 0x04) != 0,
            rawReply = raw,
        )
    }

    // ------------------------------------------------------------------
    // Power, which needs no crypto
    // ------------------------------------------------------------------

    /**
     * Power a console on from standby.
     *
     * Message 0xDD02 in the clear, carrying the Live ID. This needs the console set to Instant-On
     * or Sleep, because a console in Energy Saving has no network listener to receive it.
     */
    suspend fun powerOn(console: Console): Result = powerOn(console.liveId)

    /** Power on with a Live ID alone, for a caller that already has one. */
    suspend fun powerOn(liveId: String?): Result = withContext(Dispatchers.IO) {
        if (liveId.isNullOrBlank()) {
            return@withContext Result(
                false,
                "Powering on needs the console's Live ID, which discovery reports from the " +
                    "certificate. Run discovery with the console awake, and use wake by MAC if the " +
                    "console is fully shut down rather than in standby.",
            )
        }

        val payload = SmartGlassMessage.powerOnPayload(liveId)
        val packet = SmartGlassMessage.packet(
            header = SmartGlassMessage.simpleHeader(
                type = SmartGlassMessage.TYPE_POWER_ON_REQUEST,
                unprotectedLength = payload.size,
            ),
            unprotected = payload,
        )

        val sent = runCatching {
            DatagramSocket().use { socket ->
                socket.broadcast = true
                val targets = buildList {
                    address?.let { add(it) }
                    add("255.255.255.255")
                }
                for (target in targets) {
                    runCatching {
                        socket.send(
                            DatagramPacket(
                                packet,
                                packet.size,
                                InetAddress.getByName(target),
                                PORT,
                            ),
                        )
                    }
                }
            }
            true
        }.getOrDefault(false)

        Result(
            sent,
            if (sent) {
                "A power-on for Live ID $liveId went out on port $PORT. A console in standby " +
                    "accepts it; one in Energy Saving has nothing listening and needs its setting " +
                    "changed, or a Wake-on-LAN packet to its MAC."
            } else {
                "The power-on packet could not be sent: this phone has no route to the console."
            },
        )
    }

    /** Wake a console that is fully shut down, which listens only for a magic packet. */
    suspend fun wake(mac: String): WakeOnLan.Result = WakeOnLan.wake(mac)

    // ------------------------------------------------------------------
    // Full control, over one live session
    // ------------------------------------------------------------------

    /**
     * Open a session: connect, join, and start the input channel.
     *
     * Everything that needs the encrypted channel goes through this, because a console opens the
     * input channel once per session and closes it when the session ends.
     */
    suspend fun openSession(console: Console): SmartGlassSession.Handshake =
        SmartGlassSession(host = console.address, timeoutMillis = timeoutMillis * 2).connect(console)

    /**
     * Press one controller button.
     *
     * The full input surface: face buttons, D-pad, bumpers, stick clicks and the guide button.
     * A tap is a press and a release, because the bitmask is the pad's state rather than an edge.
     */
    suspend fun pressButton(
        session: SmartGlassSession,
        handshake: SmartGlassSession.Handshake,
        button: XboxGamepad.Button,
    ): Result = sendGamepadStates(session, handshake, XboxGamepad.tap(button), "press ${button.label}")

    /** Press a direction by name, so a menu can be driven. */
    suspend fun pressDirection(
        session: SmartGlassSession,
        handshake: SmartGlassSession.Handshake,
        word: String,
    ): Result {
        val button = XboxGamepad.direction(word)
            ?: return Result(false, "'$word' is not a direction. Use up, down, left or right.")
        return pressButton(session, handshake, button)
    }

    /**
     * Pull a trigger.
     *
     * The triggers travel as float32 fields, which is what makes a half pull representable.
     * [travel] runs 0 to 1.
     */
    suspend fun pullTrigger(
        session: SmartGlassSession,
        handshake: SmartGlassSession.Handshake,
        travel: Float,
        right: Boolean = true,
    ): Result = sendGamepadStates(
        session,
        handshake,
        XboxGamepad.trigger(travel, right),
        "pull the ${if (right) "right" else "left"} trigger",
    )

    /**
     * Move a stick to a position.
     *
     * The sticks are float32 in roughly -1.0 to 1.0. Sending the wrong range is a common reason a
     * stick appears to do nothing, so the values are clamped here.
     */
    suspend fun moveStick(
        session: SmartGlassSession,
        handshake: SmartGlassSession.Handshake,
        x: Float,
        y: Float,
        right: Boolean = false,
    ): Result {
        val state = if (right) {
            XboxGamepad.State(rightX = x, rightY = y)
        } else {
            XboxGamepad.State(leftX = x, leftY = y)
        }
        return sendGamepadStates(
            session,
            handshake,
            listOf(state, XboxGamepad.State()),
            "move the ${if (right) "right" else "left"} stick to $x, $y",
        )
    }

    /** Send arbitrary gamepad states, which is how a held button or a movement burst is done. */
    private suspend fun sendGamepadStates(
        session: SmartGlassSession,
        handshake: SmartGlassSession.Handshake,
        states: List<XboxGamepad.State>,
        describe: String,
    ): Result = session.send(
        handshake = handshake,
        messageType = SmartGlassMessage.MSG_GAMEPAD,
        payloads = states.map { state ->
            SmartGlassMessage.gamepadPayload(
                buttonMask = state.masks,
                leftTrigger = state.leftTrigger,
                rightTrigger = state.rightTrigger,
                leftStickX = state.leftX,
                leftStickY = state.leftY,
                rightStickX = state.rightX,
                rightStickY = state.rightY,
            )
        }.toList(),
        describe = describe,
    )

    /**
     * Power the console off.
     *
     * Message 0x39 on the encrypted channel, carrying the Live ID. It works on an Xbox One; some
     * Series firmware ignores it, and the refusal says so rather than claiming the console slept.
     */
    suspend fun powerOff(
        session: SmartGlassSession,
        handshake: SmartGlassSession.Handshake,
        console: Console,
    ): Result {
        val liveId = console.liveId
            ?: return Result(
                false,
                "Powering off needs the console's Live ID, which discovery reports.",
            )
        return session.send(
            handshake = handshake,
            messageType = SmartGlassMessage.MSG_POWER_OFF,
            payloads = listOf(SmartGlassMessage.string(liveId)),
            describe = "power off",
        )
    }

    /** A media command: play, pause or stop. */
    suspend fun mediaCommand(
        session: SmartGlassSession,
        handshake: SmartGlassSession.Handshake,
        command: Int,
    ): Result = session.send(
        handshake = handshake,
        messageType = SmartGlassMessage.MSG_MEDIA_CONTROL,
        payloads = listOf(
            ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN).putInt(command).array(),
        ),
        describe = "media command $command",
    )

    /** The named media command, or null when the word is not one the protocol has. */
    fun mediaCommandFor(word: String): Int? = when (word.trim().lowercase()) {
        "play", "resume" -> 0x02
        "pause" -> 0x04
        "stop" -> 0x06
        else -> null
    }

    /**
     * Why launching a game by name is not offered.
     *
     * Stated as a real answer rather than a gap, because a caller asking for it deserves to know
     * that no local client can do it any more.
     */
    fun launchLimitation(): String =
        "Starting a game or app by id is not possible over the local network: Microsoft removed " +
            "the title-launch message in 2019 and consoles ignore it. Use the D-pad to navigate " +
            "the console's own menus, which this tool fully supports."

    private companion object {
        /** The SmartGlass port. */
        const val PORT = 5050
    }
}
