// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Broadlink packet format, byte by byte. #187.
 *
 * WHY THE BYTES ARE PINNED. The failure mode of this protocol is total silence: a
 * wrong checksum, a wrong offset or a wrong key produces a packet the device
 * discards without replying. That is indistinguishable from a network fault, so
 * every position that matters is asserted here rather than being discovered by
 * watching a socket time out.
 */
class BroadlinkProtocolTest {

    /**
     * The additive checksum: sum from 0xBEAF and wrap at 16 bits.
     *
     * A hand-computed reference: an array of 1, 2, 3 sums to 0xBEB5.
     */
    @Test
    fun checksumAddsFromItsSeedAndWrapsAtSixteenBits() {
        assertEquals(0xBEB5, BroadlinkProtocol.checksum(byteArrayOf(1, 2, 3)))
        assertEquals(0xBEAF, BroadlinkProtocol.checksum(ByteArray(0)))
        // Max byte value repeated must wrap rather than overflow.
        val wrapped = BroadlinkProtocol.checksum(ByteArray(300) { 0xFF.toByte() })
        assertTrue("the sum must stay inside 16 bits", wrapped in 0..0xFFFF)
    }

    /** The discovery packet is 48 bytes with its command byte and checksum set. */
    @Test
    fun discoveryPacketHasItsDocumentedShape() {
        val packet = BroadlinkProtocol.discoveryPacket(localIp = null, localPort = 12345)
        assertEquals("a discovery packet is 48 bytes", 0x30, packet.size)
        assertEquals("the command byte marks a discovery request", 0x06, packet[0x26].toInt())
        // The port is written big-endian at 0x1a, which is 12345 = 0x3039.
        assertEquals(0x30, packet[0x1a].toInt() and 0xFF)
        assertEquals(0x39, packet[0x1b].toInt() and 0xFF)
        // The checksum is a little-endian 16-bit field, like every other multi-byte
        // field in this format, so it is read the same way the device reads it.
        val stored = BroadlinkProtocol.readShortLE(packet, 0x20)
        val recomputed = BroadlinkProtocol.checksum(packet.copyOf().also { it[0x20] = 0; it[0x21] = 0 })
        assertEquals(
            "a discovery packet's checksum must verify against the packet it describes",
            recomputed,
            stored,
        )
    }

    /** A discovery reply carries the device type and MAC every later packet needs. */
    @Test
    fun discoveryReplyCarriesTheDeviceIdentity() {
        val reply = ByteArray(0x80)
        // Device type 0x2737 at 0x24 little-endian.
        reply[0x24] = 0x37
        reply[0x25] = 0x27
        val mac = byteArrayOf(0x78.toByte(), 0x9F.toByte(), 0x70, 0x11, 0x22, 0x33)
        mac.copyInto(reply, 0x2A)
        reply[0x40] = 'R'.code.toByte()
        reply[0x41] = 'M'.code.toByte()

        val parsed = BroadlinkProtocol.parseDiscoveryReply(reply, "192.168.1.50")
        assertNotNull(parsed)
        assertEquals(0x2737, parsed!!.deviceType)
        assertArrayEquals(mac, parsed.mac)
        assertEquals("192.168.1.50", parsed.host)
    }

    /**
     * The device id is the MAC reversed, which is why a wrong byte order produces a
     * packet the device silently ignores.
     */
    @Test
    fun deviceIdIsTheMacReversed() {
        val reply = BroadlinkProtocol.DiscoveryReply(
            deviceType = 0x2737,
            mac = byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05, 0x06),
            host = "10.0.0.1",
        )
        // Six bytes reversed: 06 05 04 03 02 01.
        assertEquals(0x060504030201L, reply.deviceId())
    }

    /** A short discovery reply is refused rather than read past its end. */
    @Test
    fun aShortReplyIsRefused() {
        assertNull(
            "a reply under the header size cannot be a blaster's",
            BroadlinkProtocol.parseDiscoveryReply(ByteArray(0x10), "10.0.0.1"),
        )
    }

    /**
     * A command packet has the four magic pairs, the command id, the MAC, the device
     * id and both checksums in their documented positions.
     */
    @Test
    fun commandPacketHasItsDocumentedLayout() {
        val mac = byteArrayOf(0x78.toByte(), 0x9F.toByte(), 0x70, 0x11, 0x22, 0x33)
        val key = BroadlinkProtocol.initialKey()
        val iv = BroadlinkProtocol.initialIv()

        val packet = BroadlinkProtocol.buildCommand(
            deviceType = 0x2737,
            deviceId = 0x060504030201L,
            mac = mac,
            command = BroadlinkProtocol.CMD_IR,
            payload = ByteArray(16) { it.toByte() },
            key = key,
            iv = iv,
        )

        assertEquals("magic pair 1", 0x5A, packet[0x00].toInt())
        assertEquals("magic pair 1b", 0xA5, packet[0x01].toInt() and 0xFF)
        assertEquals("magic pair 2a", 0xAA, packet[0x02].toInt() and 0xFF)
        assertEquals("magic pair 2b", 0x55, packet[0x03].toInt())
        assertEquals("magic pair 3", 0x5A, packet[0x04].toInt())
        assertEquals("magic pair 4a", 0xAA, packet[0x06].toInt() and 0xFF)

        assertEquals("device type low", 0x37, packet[0x24].toInt() and 0xFF)
        assertEquals("device type high", 0x27, packet[0x25].toInt() and 0xFF)
        assertEquals("command low", 0x6A, packet[0x26].toInt() and 0xFF)
        assertEquals("command high", 0x00, packet[0x27].toInt() and 0xFF)
        assertArrayEquals("the MAC sits at 0x2a", mac, packet.copyOfRange(0x2A, 0x30))

        // The device id is six bytes wide but the header carries four, so the low
        // four bytes travel: 0x060504030201's low four are 030201's bytes reversed.
        // Byte 0x30 is therefore 0x01 and 0x33 is 0x04, which is what identifies the
        // device. Asserting a six-byte value here would be asserting an offset the
        // header does not have.
        assertEquals(0x01, packet[0x30].toInt() and 0xFF)
        assertEquals(0x04, packet[0x33].toInt() and 0xFF)

        // Both checksums must verify against a recomputation, read the same way the
        // device reads them: little-endian, like every other field in this format.
        val recomputed = BroadlinkProtocol.checksum(packet.copyOf().also { it[0x20] = 0; it[0x21] = 0 })
        assertEquals(
            "the whole-packet checksum verifies",
            recomputed,
            BroadlinkProtocol.readShortLE(packet, 0x20),
        )
        assertEquals(
            "the payload checksum verifies over the padded plaintext",
            BroadlinkProtocol.checksum(ByteArray(16) { it.toByte() }),
            BroadlinkProtocol.readShortLE(packet, 0x34),
        )
    }

    /**
     * The payload is encrypted, so the plaintext must not appear in the packet. This
     * catches the mistake of building a packet with the payload written directly.
     */
    @Test
    fun thePayloadIsEncryptedNotPlaintext() {
        val payload = ByteArray(16) { 0x41 }   // sixteen 'A' bytes
        val packet = BroadlinkProtocol.buildCommand(
            deviceType = 0x2737,
            deviceId = 1L,
            mac = byteArrayOf(1, 2, 3, 4, 5, 6),
            command = BroadlinkProtocol.CMD_IR,
            payload = payload,
            key = BroadlinkProtocol.initialKey(),
            iv = BroadlinkProtocol.initialIv(),
        )
        val body = packet.copyOfRange(0x38, packet.size)
        assertTrue(
            "an encrypted body must not contain the plaintext it was given",
            !body.contentEquals(payload),
        )
        assertEquals("the body is padded to a whole AES block", 0, body.size % 16)
    }

    /**
     * The IR payload's layout: flags 0x02, a zero repeat, the length in
     * little-endian bytes, then the timings in the device's own unit.
     */
    @Test
    fun irPayloadUsesTheDocumentedOffsets() {
        val signal = IrSignal(
            carrierHz = 38000,
            pulses = intArrayOf(9000, 4500, 560, 1690),
            note = "test",
        )
        val payload = BroadlinkProtocol.irPayload(signal)
        assertEquals("flags is 0x02 for an IR command", 0x02, payload[0].toInt())
        assertEquals("the repeat byte is a repeat count", 0x00, payload[0x05].toInt())
        assertEquals("a fixed id byte", 0x26, payload[0x04].toInt())

        val length = (payload[0x06].toInt() and 0xFF) or ((payload[0x07].toInt() and 0xFF) shl 8)
        assertEquals("the length counts bytes, two per timing", signal.pulses.size * 2, length)
        assertEquals("four timings become eight bytes plus the header", 8 + 8, payload.size)

        val first = (payload[8].toInt() and 0xFF) or ((payload[9].toInt() and 0xFF) shl 8)
        assertEquals(
            "9000 us is 73 units at 8192 per second",
            BroadlinkProtocol.microsToBroadlinkUnits(9000),
            first,
        )
    }

    /**
     * The unit conversion, which is the most likely place to go wrong.
     *
     * 8192 units per second means one microsecond is 0.008192 units, so 9000 us is
     * 73 units and 122 us is exactly one.
     */
    @Test
    fun microsecondsConvertToTheDeviceUnit() {
        assertEquals(73, BroadlinkProtocol.microsToBroadlinkUnits(9000))
        assertEquals(36, BroadlinkProtocol.microsToBroadlinkUnits(4500))
        assertEquals(4, BroadlinkProtocol.microsToBroadlinkUnits(560))
        assertEquals(13, BroadlinkProtocol.microsToBroadlinkUnits(1690))
        assertEquals("a duration under one unit is clamped to one", 1, BroadlinkProtocol.microsToBroadlinkUnits(50))
    }

    /** The conversion must round trip within a unit, or a readback comparison lies. */
    @Test
    fun theUnitConversionRoundTripsWithinOneUnit() {
        for (micros in listOf(560, 900, 1690, 4500, 9000, 40000)) {
            val units = BroadlinkProtocol.microsToBroadlinkUnits(micros)
            val back = BroadlinkProtocol.broadlinkUnitsToMicros(units)
            assertTrue(
                "$micros us round tripped to $back us, which is more than one unit away",
                kotlin.math.abs(back - micros) <= 122,
            )
        }
    }

    /**
     * A packet that is not a whole number of AES blocks is padded, because the cipher
     * has no padding mode of its own here and a short block is rejected outright.
     */
    @Test
    fun aShortPayloadIsPaddedToABlock() {
        val packet = BroadlinkProtocol.buildCommand(
            deviceType = 1,
            deviceId = 1L,
            mac = byteArrayOf(1, 2, 3, 4, 5, 6),
            command = BroadlinkProtocol.CMD_AUTH,
            payload = ByteArray(5),
            key = BroadlinkProtocol.initialKey(),
            iv = BroadlinkProtocol.initialIv(),
        )
        assertEquals(
            "the body is exactly one AES block",
            0x38 + 16,
            packet.size,
        )
    }

    /** AES must actually work on this JVM, or every packet above would be empty. */
    @Test
    fun aesRoundTrips() {
        val key = BroadlinkProtocol.initialKey()
        val iv = BroadlinkProtocol.initialIv()
        val plaintext = ByteArray(32) { it.toByte() }
        val encrypted = AesCbc.encrypt(plaintext, key, iv)
        assertEquals("a whole block encrypts", 32, encrypted.size)
        assertTrue("the ciphertext differs from the plaintext", !encrypted.contentEquals(plaintext))
        val decrypted = AesCbc.decrypt(encrypted, key, iv)
        assertNotNull(decrypted)
        assertArrayEquals("decryption restores the plaintext", plaintext, decrypted)
    }
}
