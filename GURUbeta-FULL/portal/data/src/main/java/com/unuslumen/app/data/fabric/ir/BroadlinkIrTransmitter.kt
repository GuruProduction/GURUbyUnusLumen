// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.SocketTimeoutException

/**
 * A network IR blaster, so a phone with no IR LED of its own can still press a
 * button on a television. Issue #187.
 *
 * WHY BROADLINK FIRST. It is the blaster the issue names, it is the one Home
 * Assistant and every open client supports, and its protocol is fully documented
 * and openly implemented. Supporting it means a person with any phone can buy one
 * box and have GURU control every IR device in the house.
 *
 * WHAT THIS DOES ON EVERY CALL, and why it is not a cache. The device id and the
 * per-device key come from an authentication round trip, and a Broadlink forgets
 * a session between packets, so every transmit authenticates first. That is one
 * extra UDP round trip and it is what makes the backend work on a device that
 * rebooted between two button presses.
 *
 * WHAT IS NOT PRETENDED. A device locked to the vendor cloud will not answer
 * authentication, and this reports that as the device refusing rather than as a
 * network fault. It never reports a transmit as successful because the packet was
 * written to a socket: once the device acknowledges a command, that is the only
 * evidence there is, and it is said plainly.
 */
class BroadlinkIrTransmitter(
    private val host: String,
    private val timeoutMillis: Int = 3000,
) : IrTransmitter {

    private companion object {
        const val TAG = "guru_ir"
    }

    /** The device type and MAC learned from discovery, or null before the first probe. */
    @Volatile
    private var deviceType: Int = 0

    @Volatile
    private var mac: ByteArray = ByteArray(6)

    @Volatile
    private var lastProbe: String? = null

    override fun status(): TransmitterStatus {
        // Status is a statement about what is known, not a live probe: a suspend
        // network call cannot happen here without making every devices listing slow.
        // So availability is the last probe's answer, and a transmitter that has
        // never been probed reports itself as reachable-but-unproven rather than
        // claiming to work.
        val problem = lastProbe
        return TransmitterStatus(
            id = "broadlink",
            displayName = "the network IR blaster at $host",
            available = problem == null,
            unavailableReason = problem,
        )
    }

    override suspend fun transmit(signal: IrSignal): TransmitOutcome = withContext(Dispatchers.IO) {
        if (signal.pulses.isEmpty()) {
            return@withContext TransmitOutcome.Failed("The signal has no pulses, so there is nothing to send.")
        }

        val discovered = probe()
        if (discovered == null) {
            val reason = lastProbe ?: "the blaster at $host did not answer discovery"
            return@withContext TransmitOutcome.Failed(reason)
        }
        if (discovered.isLocked) {
            return@withContext TransmitOutcome.Failed(
                "The blaster at $host is locked to the vendor cloud, so it refuses commands from " +
                    "this app. Its lock has to be released in the vendor's own app first.",
            )
        }

        deviceType = discovered.deviceType
        mac = discovered.mac

        val auth = authenticate() ?: return@withContext TransmitOutcome.Failed(
            lastProbe ?: "the blaster at $host refused authentication",
        )

        val payload = BroadlinkProtocol.irPayload(signal)
        val packet = BroadlinkProtocol.buildCommand(
            deviceType = deviceType,
            deviceId = discovered.deviceId(),
            mac = mac,
            command = BroadlinkProtocol.CMD_IR,
            payload = payload,
            key = auth.key,
            iv = auth.iv,
        )

        val reply = exchange(packet)
            ?: return@withContext TransmitOutcome.Failed(
                "The blaster at $host accepted the connection but did not acknowledge the command, " +
                    "so the signal may not have been sent.",
            )

        Log.d(TAG, "IR sent through the blaster at $host: ${signal.pulses.size} pulses")
        TransmitOutcome.Sent(
            "Sent ${signal.pulses.size} pulses at ${signal.carrierHz / 1000} kHz through the " +
                "blaster at $host and it acknowledged the command. ${signal.note} " +
                "Acknowledged means the device received the command, not that the television " +
                "responded.",
        )
    }

    /**
     * Discovery, which is also the reachability test.
     *
     * The reply carries the device type and MAC that every later packet needs, so a
     * reachable blaster and a discovered blaster are the same thing.
     */
    private fun probe(): BroadlinkProtocol.DiscoveryReply? {
        val socket = runCatching { DatagramSocket() }.getOrNull()
        if (socket == null) {
            lastProbe = "This phone could not open a socket to reach the blaster at $host, which " +
                "is a network fault rather than a blaster fault."
            return null
        }
        return try {
            socket.soTimeout = timeoutMillis
            val address = InetAddress.getByName(host)
            val localPort = socket.localPort
            val localIp = runCatching {
                java.nio.ByteBuffer.wrap(address.address).int
            }.getOrNull()
            val packet = BroadlinkProtocol.discoveryPacket(localIp, localPort)
            socket.send(DatagramPacket(packet, packet.size, address, BroadlinkProtocol.DISCOVERY_PORT))

            val buffer = ByteArray(1024)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            val reply = BroadlinkProtocol.parseDiscoveryReply(
                response.data.copyOfRange(0, response.length),
                host,
            )
            if (reply == null) {
                lastProbe = "The device at $host answered but not in the blaster's own format, so " +
                    "it is not a Broadlink blaster."
                return null
            }
            lastProbe = null
            reply
        } catch (timeout: SocketTimeoutException) {
            lastProbe = "The blaster at $host did not answer within ${timeoutMillis / 1000} seconds, " +
                "so it is switched off or not on this network."
            null
        } catch (error: Exception) {
            lastProbe = "Reaching the blaster at $host failed: ${error.message ?: error.javaClass.simpleName}."
            null
        } finally {
            runCatching { socket.close() }
        }
    }

    /**
     * Authenticate, which yields the per-device key and IV.
     *
     * The payload is 80 bytes of the device's expected first-packet shape: the
     * first four bytes are the local time in little-endian seconds, the rest zero.
     * The reply decrypts with the INITIAL key, which is the only packet in the
     * whole protocol that does.
     */
    private fun authenticate(): BroadlinkProtocol.AuthReply? {
        val payload = ByteArray(0x50)
        val seconds = (System.currentTimeMillis() / 1000L).toInt()
        payload[0x00] = (seconds and 0xFF).toByte()
        payload[0x01] = ((seconds ushr 8) and 0xFF).toByte()
        payload[0x02] = ((seconds ushr 16) and 0xFF).toByte()
        payload[0x03] = ((seconds ushr 24) and 0xFF).toByte()

        val packet = BroadlinkProtocol.buildCommand(
            deviceType = deviceType,
            deviceId = 0,
            mac = mac,
            command = BroadlinkProtocol.CMD_AUTH,
            payload = payload,
            key = BroadlinkProtocol.initialKey(),
            iv = BroadlinkProtocol.initialIv(),
        )
        val reply = exchange(packet) ?: return null
        val auth = BroadlinkProtocol.parseAuthReply(reply)
        if (auth == null) {
            lastProbe = "The blaster at $host answered authentication with a packet that does not " +
                "decrypt, which means it is running firmware this build cannot talk to."
        }
        return auth
    }

    /** One UDP send and receive, returning the raw reply body or null. */
    private fun exchange(packet: ByteArray): ByteArray? {
        val socket = runCatching { DatagramSocket() }.getOrNull() ?: return null
        return try {
            socket.soTimeout = timeoutMillis
            val address = InetAddress.getByName(host)
            socket.send(DatagramPacket(packet, packet.size, InetSocketAddress(address, BroadlinkProtocol.DISCOVERY_PORT)))
            val buffer = ByteArray(1024)
            val response = DatagramPacket(buffer, buffer.size)
            socket.receive(response)
            response.data.copyOfRange(0, response.length)
        } catch (error: Exception) {
            Log.d(TAG, "Broadlink exchange failed: ${error.message}")
            null
        } finally {
            runCatching { socket.close() }
        }
    }
}
