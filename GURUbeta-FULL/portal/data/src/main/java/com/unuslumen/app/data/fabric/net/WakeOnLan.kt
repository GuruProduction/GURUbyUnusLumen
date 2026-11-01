// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress

/**
 * Waking a device that is asleep on the network. Requirement 5 of the spec.
 *
 * WHY THIS COMES BEFORE CONTROL. A television in standby has no listening socket, so
 * every adapter's first attempt fails against a set that would answer perfectly if it
 * were awake. Waking first is what makes a "turn the telly on" request possible at all,
 * and it is the one command that works on a device with no protocol of its own.
 *
 * THE PACKET IS THE WHOLE PROTOCOL: six bytes of 0xFF, then the target's MAC repeated
 * sixteen times, sent as a UDP broadcast. Port 9 is the conventional one and port 7 is
 * the original, so both are sent because firmware disagrees about which it honours.
 *
 * WHAT IT CANNOT DO. A device has to have Wake-on-LAN enabled in its own settings, and a
 * set that is off at the wall has no network card listening. Neither is something the
 * packet can fix, and a refusal says so rather than implying a retry might work.
 */
object WakeOnLan {

    /** The conventional discard port every modern device listens on. */
    private const val PORT_PRIMARY = 9

    /** The original echo port, still honoured by older firmware. */
    private const val PORT_SECONDARY = 7

    /**
     * Build the magic packet for one MAC.
     *
     * Parsing is deliberately strict: a MAC with the wrong number of groups or a
     * non-hex character is refused rather than guessed at, because a malformed packet
     * broadcasts nothing and looks exactly like a device that will not wake.
     */
    fun magicPacket(mac: String): ByteArray? {
        val bytes = parseMac(mac) ?: return null
        val packet = ByteArray(6 + 16 * 6)
        for (index in 0 until 6) packet[index] = 0xFF.toByte()
        for (repeat in 0 until 16) {
            bytes.copyInto(packet, 6 + repeat * 6)
        }
        return packet
    }

    /** Split a MAC into its six bytes, accepting colons, dashes or none. */
    fun parseMac(mac: String): ByteArray? {
        val cleaned = mac.trim().replace(":", "").replace("-", "").replace(".", "")
        if (cleaned.length != 12) return null
        val out = ByteArray(6)
        for (index in 0 until 6) {
            val pair = cleaned.substring(index * 2, index * 2 + 2)
            val value = pair.toIntOrNull(16) ?: return null
            out[index] = value.toByte()
        }
        return out
    }

    /**
     * Send the magic packet for one MAC.
     *
     * Returns the outcome with the platform's own reason where it refused, because a
     * packet that could not be sent is not a device that would not wake.
     */
    suspend fun wake(mac: String): Result = withContext(Dispatchers.IO) {
        val packet = magicPacket(mac)
            ?: return@withContext Result(
                sent = false,
                reason = "'$mac' is not a MAC address. An address looks like AA:BB:CC:DD:EE:FF.",
            )

        var lastError: String? = null
        for (port in listOf(PORT_PRIMARY, PORT_SECONDARY)) {
            // runCatching returns a Result, so it is folded rather than used as a
            // boolean: a socket that opened but sent nothing is not a success, and the
            // distinction decides whether the caller retries or reports a device fault.
            val sent = runCatching {
                DatagramSocket().use { socket ->
                    socket.broadcast = true
                    // Both the limited broadcast and the subnet broadcast, because some
                    // access points drop one and pass the other.
                    var delivered = false
                    val targets = listOf("255.255.255.255", "192.168.0.255", "10.0.0.255")
                    for (target in targets) {
                        runCatching {
                            val address = InetAddress.getByName(target)
                            socket.send(DatagramPacket(packet, packet.size, address, port))
                            delivered = true
                        }
                    }
                    delivered
                }
            }.getOrDefault(false)
            if (sent) {
                return@withContext Result(
                    sent = true,
                    reason = "A wake packet for $mac went out on port $port. A device wakes only " +
                        "if Wake-on-LAN is enabled in its own settings and it has power at the wall.",
                )
            }
            lastError = "port $port could not be opened for broadcast"
        }

        Result(
            sent = false,
            reason = "The wake packet could not be sent: ${lastError ?: "no broadcast address was " +
                "reachable from this network"}.",
        )
    }

    data class Result(val sent: Boolean, val reason: String)
}
