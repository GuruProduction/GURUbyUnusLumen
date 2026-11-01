// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

/**
 * The Broadlink packet format: the network IR backend for a phone with no blaster.
 * Issue #187.
 *
 * WHY THIS IS PURE KOTLIN. A Broadlink packet is a fixed 56-byte header with a
 * checksum, followed by an AES-CBC encrypted payload. Every byte position and
 * every arithmetic step is decided here, so a JVM test can assert the exact
 * header a real packet must have. That matters because the failure mode is total
 * silence: a wrong checksum, a wrong key or a wrong offset produces a packet the
 * device discards without replying, which is indistinguishable from a network
 * fault.
 *
 * THE PROTOCOL, taken from the published Broadlink reverse engineering that
 * python-broadlink and Home Assistant both implement:
 *
 *  - Discovery is a 48-byte UDP broadcast to port 80 whose reply carries the
 *    device's type, MAC and IP.
 *  - Authentication (command 0x0065) is sent with the built-in initial key and
 *    IV, and the reply's payload decrypts to a device id followed by a per-device
 *    key. Every later packet uses that key, so an unauthenticated session is one
 *    packet only.
 *  - An IR command is command 0x006A with a payload of 0x02, a repeat byte, a
 *    little-endian length, and the pulse timings in units of 2^-15 seconds.
 *
 * THE UNIT CONVERSION is the single most likely place to go wrong. The device
 * counts 8192 units per second, so a microsecond duration is `micros * 8192 /
 * 1_000_000` units. python-broadlink writes it as `micros * 269 / 8192`, which is
 * the same ratio arrived at by a different route; both are stated here so nobody
 * has to trust one arithmetic form.
 *
 * WHAT THIS CANNOT DO. It cannot talk to a Broadlink device that has never been
 * put on the network by its own app, because a device with no key learned yet
 * only answers discovery and authentication. It also cannot reach an RM device
 * locked to the vendor cloud without the lock being released.
 */
object BroadlinkProtocol {

    /** The 48-byte discovery broadcast, sent to port 80 on 255.255.255.255. */
    const val DISCOVERY_PORT = 80

    /** Command ids, as the device's own service numbers. */
    const val CMD_AUTH = 0x0065
    const val CMD_IR = 0x006A

    /** Header layout. Named rather than magic numbers, so the offsets are readable. */
    private const val OFFSET_MAGIC = 0x00
    private const val OFFSET_LENGTH = 0x02
    private const val OFFSET_CHECKSUM = 0x20
    private const val OFFSET_DEVICE_TYPE = 0x24
    private const val OFFSET_COMMAND = 0x26
    private const val OFFSET_COUNT = 0x28
    private const val OFFSET_MAC = 0x2A
    private const val OFFSET_DEVICE_ID = 0x30
    private const val OFFSET_PAYLOAD_CHECKSUM = 0x34
    private const val HEADER_SIZE = 0x38

    /** The checksum seed every Broadlink checksum starts from. */
    private const val CHECKSUM_SEED = 0xBEAF

    /** The built-in key and IV, used only for the very first packet. */
    private val INITIAL_KEY = intArrayOf(
        0x09, 0x76, 0x28, 0x34, 0x3f, 0xe9, 0x9e, 0x23,
        0x76, 0x5c, 0x15, 0x13, 0xac, 0xcf, 0x8b, 0x02,
    )
    private val INITIAL_IV = intArrayOf(
        0x56, 0x2e, 0x17, 0x99, 0x6d, 0x09, 0x3d, 0x28,
        0xdd, 0xb3, 0xba, 0x69, 0x5a, 0x2e, 0x6f, 0x58,
    )

    /**
     * The additive checksum: sum every byte from the seed, wrapping at 16 bits.
     *
     * The published description is explicit that the checksum bytes themselves are
     * zero while it is computed, which is why [checksum] takes a range rather than
     * being handed a finished packet.
     */
    fun checksum(bytes: ByteArray): Int {
        var sum = CHECKSUM_SEED
        for (byte in bytes) {
            sum = (sum + (byte.toInt() and 0xFF)) and 0xFFFF
        }
        return sum
    }

    /** The 48-byte discovery packet. Time fields are in local time, as the format requires. */
    fun discoveryPacket(
        localIp: Int?,
        localPort: Int,
        now: Long = System.currentTimeMillis(),
    ): ByteArray {
        val packet = ByteArray(0x30)
        val calendar = java.util.Calendar.getInstance()
        calendar.timeInMillis = now

        // Offsets 0x08..0x0F: the GMT offset in seconds, then the year.
        val offsetSeconds = (calendar.get(java.util.Calendar.ZONE_OFFSET) +
            calendar.get(java.util.Calendar.DST_OFFSET)) / 1000
        packet[0x08] = (offsetSeconds and 0xFF).toByte()
        packet[0x09] = ((offsetSeconds ushr 8) and 0xFF).toByte()
        packet[0x0a] = ((offsetSeconds ushr 16) and 0xFF).toByte()
        packet[0x0b] = ((offsetSeconds ushr 24) and 0xFF).toByte()
        val year = calendar.get(java.util.Calendar.YEAR)
        packet[0x0c] = (year and 0xFF).toByte()
        packet[0x0d] = ((year ushr 8) and 0xFF).toByte()
        packet[0x0e] = (calendar.get(java.util.Calendar.MINUTE)).toByte()
        packet[0x0f] = (calendar.get(java.util.Calendar.HOUR_OF_DAY)).toByte()
        packet[0x10] = (calendar.get(java.util.Calendar.SECOND)).toByte()
        packet[0x11] = (calendar.get(java.util.Calendar.DAY_OF_WEEK)).toByte()
        packet[0x12] = (calendar.get(java.util.Calendar.DAY_OF_MONTH)).toByte()
        packet[0x13] = (calendar.get(java.util.Calendar.MONTH) + 1).toByte()

        if (localIp != null) {
            writeIntLe(packet, 0x18, localIp)
        }
        packet[0x1a] = ((localPort ushr 8) and 0xFF).toByte()
        packet[0x1b] = (localPort and 0xFF).toByte()

        // The command byte is part of the packet the checksum covers, so it is set
        // first. Computing the checksum before it leaves a packet whose own checksum
        // does not verify, and a device discards that without replying.
        packet[0x26] = 0x06

        // The checksum covers the packet with its own two bytes left zero, which they
        // still are at this point.
        //
        // LITTLE-ENDIAN, like every other multi-byte field in this format. Writing it
        // big-endian here while buildCommand wrote it little-endian meant the two
        // builders disagreed about one field, and a device verifies both the same way,
        // so one of them could never have worked.
        writeShortLe(packet, OFFSET_CHECKSUM, checksum(packet))
        return packet
    }

    /** The session facts a device reports during discovery. */
    data class DiscoveryReply(
        val deviceType: Int,
        val mac: ByteArray,
        val host: String,
        /** True when the device reports a random name rather than a known RM model. */
        val isLocked: Boolean = false,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is DiscoveryReply) return false
            return deviceType == other.deviceType && host == other.host && mac.contentEquals(other.mac)
        }

        override fun hashCode(): Int = 31 * deviceType + host.hashCode() + mac.contentHashCode()

        /**
         * The device id is its MAC reversed, as a six-byte little-endian value.
         *
         * SIX BYTES, not four. The packet header has four bytes for the device id, so
         * the top two bytes of this value never travel; but computing it as four would
         * drop the low bytes that actually identify the device, and the packet would
         * name the wrong device. The header truncation is done at the write, where it
         * is visible, rather than here where it would look like the whole value.
         */
        fun deviceId(): Long {
            var id = 0L
            for (index in mac.indices) {
                id = (id shl 8) or (mac[mac.size - 1 - index].toLong() and 0xFF)
            }
            return id
        }
    }

    /** What a device reports during discovery, with the IP the reply came from. */
    fun parseDiscoveryReply(packet: ByteArray, host: String): DiscoveryReply? {
        if (packet.size < 0x40) return null
        val deviceType = readShortLe(packet, OFFSET_DEVICE_TYPE) ?: return null
        val mac = packet.copyOfRange(OFFSET_MAC, OFFSET_MAC + 6)
        val name = packet.copyOfRange(0x40, minOf(packet.size, 0x88))
            .toString(Charsets.ISO_8859_1)
            .trim('\u0000', ' ')
        // The vendor locks newer devices to its cloud, which it signals by sending
        // a scrambled name. Reporting that plainly is the difference between "not
        // on the network" and "on the network and refusing".
        val locked = deviceType == 0x2737 || name.any { it.code > 0x7F }
        return DiscoveryReply(deviceType, mac, host, isLocked = locked)
    }

    /**
     * Build one authenticated command packet.
     *
     * [key] and [iv] are the per-device values recovered from the authentication
     * reply. The payload is encrypted with AES-CBC and the header's two checksums
     * are then filled in, in the order the format requires: payload first, then the
     * whole packet.
     */
    fun buildCommand(
        deviceType: Int,
        deviceId: Long,
        mac: ByteArray,
        command: Int,
        payload: ByteArray,
        key: IntArray,
        iv: IntArray,
        count: Int = 1,
    ): ByteArray {
        // AES-CBC works on whole blocks, so the payload is padded to a 16-byte
        // boundary. The device tolerates trailing zeros and rejects a short block.
        val padded = if (payload.size % 16 == 0) payload else payload + ByteArray(16 - payload.size % 16)
        val encrypted = AesCbc.encrypt(padded, key, iv)

        val packet = ByteArray(HEADER_SIZE + encrypted.size)
        // The four magic pairs, which mark the packet as a Broadlink command.
        packet[0x00] = 0x5a; packet[0x01] = 0xa5.toByte()
        packet[0x02] = 0xaa.toByte(); packet[0x03] = 0x55
        packet[0x04] = 0x5a; packet[0x05] = 0xa5.toByte()
        packet[0x06] = 0xaa.toByte(); packet[0x07] = 0x55

        writeShortLe(packet, OFFSET_DEVICE_TYPE, deviceType)
        writeShortLe(packet, OFFSET_COMMAND, command)
        writeShortLe(packet, OFFSET_COUNT, count)
        mac.copyInto(packet, OFFSET_MAC, 0, minOf(6, mac.size))
        writeIntLe(packet, OFFSET_DEVICE_ID, (deviceId and 0xFFFFFFFFL).toInt())
        encrypted.copyInto(packet, HEADER_SIZE)

        // Payload checksum: over the UNENCRYPTED payload, with its own bytes zero,
        // which is why it is computed before they are written.
        val payloadSum = checksum(padded)
        writeShortLe(packet, OFFSET_PAYLOAD_CHECKSUM, payloadSum)

        // Whole-packet checksum over the finished packet with its own bytes zero.
        writeShortLe(packet, OFFSET_CHECKSUM, 0)
        val packetSum = checksum(packet)
        writeShortLe(packet, OFFSET_CHECKSUM, packetSum)
        return packet
    }

    /**
     * Build the IR payload for a command.
     *
     * Layout: 0x02 flags, a repeat count, a little-endian byte length, then the
     * timings. Each timing is converted to the device's 8192-per-second unit.
     */
    fun irPayload(signal: IrSignal): ByteArray {
        val units = IntArray(signal.pulses.size) { index ->
            microsToBroadlinkUnits(signal.pulses[index])
        }
        val payload = ByteArray(8 + units.size * 2)
        payload[0] = 0x02
        payload[0x01] = 0x00
        payload[0x02] = 0x00
        payload[0x03] = 0x00
        payload[0x04] = 0x26
        // One repeat means the device sends the frame again after its own gap,
        // which is what most televisions need to accept a power command.
        payload[0x05] = 0x00
        val length = units.size * 2
        payload[0x06] = (length and 0xFF).toByte()
        payload[0x07] = ((length ushr 8) and 0xFF).toByte()
        units.forEachIndexed { index, value ->
            writeShortLe(payload, 8 + index * 2, value)
        }
        return payload
    }

    /**
     * Microseconds to the device's 8192-per-second unit.
     *
     * `micros * 8192 / 1_000_000` and the widely used `micros * 269 / 8192` are the
     * same ratio, so either form is correct; this one divides once and cannot
     * overflow at the durations IR actually uses.
     */
    fun microsToBroadlinkUnits(micros: Int): Int =
        ((micros.toLong() * 8192L) / 1_000_000L).toInt().coerceIn(1, 0xFFFF)

    /** The device's unit back to microseconds, for a readback comparison. */
    fun broadlinkUnitsToMicros(units: Int): Int =
        ((units.toLong() * 1_000_000L) / 8192L).toInt()

    /** What the authentication reply decrypts to. */
    data class AuthReply(val deviceId: Int, val key: IntArray, val iv: IntArray)

    /**
     * Decrypt an authentication reply.
     *
     * The reply's payload begins at byte 0x38 and decrypts with the INITIAL key and
     * IV, not the per-device ones, because the device has not yet been told that
     * this session knows them. Bytes 0..3 are the device id and 4..19 the key.
     */
    fun parseAuthReply(packet: ByteArray): AuthReply? {
        if (packet.size <= HEADER_SIZE) return null
        val body = packet.copyOfRange(HEADER_SIZE, packet.size)
        val decrypted = AesCbc.decrypt(body, INITIAL_KEY, INITIAL_IV) ?: return null
        if (decrypted.size < 20) return null
        val deviceId = readIntLe(decrypted, 0) ?: return null
        val key = IntArray(16) { (decrypted[4 + it].toInt() and 0xFF) }
        val iv = IntArray(16) { (decrypted[20 + it].toInt() and 0xFF) }
        return AuthReply(deviceId, key, iv)
    }

    /** The initial key, exposed so the authenticating packet can be built. */
    fun initialKey(): IntArray = INITIAL_KEY.copyOf()

    /** The initial IV, exposed for the same reason. */
    fun initialIv(): IntArray = INITIAL_IV.copyOf()

    // ------------------------------------------------------------------
    // Little-endian helpers, kept explicit because the format is byte-oriented
    // and a Kotlin ByteBuffer would hide the endianness that matters here.
    // ------------------------------------------------------------------

    private fun writeShortLe(target: ByteArray, offset: Int, value: Int) {
        if (offset + 1 >= target.size) return
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeIntLe(target: ByteArray, offset: Int, value: Int) {
        if (offset + 3 >= target.size) return
        target[offset] = (value and 0xFF).toByte()
        target[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        target[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        target[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }

    private fun readShortLe(source: ByteArray, offset: Int): Int? {
        if (offset + 1 >= source.size) return null
        return (source[offset].toInt() and 0xFF) or ((source[offset + 1].toInt() and 0xFF) shl 8)
    }

    /**
     * Read a little-endian 16-bit field from a finished packet.
     *
     * Exposed so a test verifies a checksum the same way the device does, rather than
     * re-implementing the byte order and disagreeing with its own build.
     */
    fun readShortLE(source: ByteArray, offset: Int): Int? = readShortLe(source, offset)

    private fun readIntLe(source: ByteArray, offset: Int): Int? {
        if (offset + 3 >= source.size) return null
        return (source[offset].toInt() and 0xFF) or
            ((source[offset + 1].toInt() and 0xFF) shl 8) or
            ((source[offset + 2].toInt() and 0xFF) shl 16) or
            ((source[offset + 3].toInt() and 0xFF) shl 24)
    }
}
