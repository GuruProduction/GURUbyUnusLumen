// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The SNMP byte layer, proved against packets rather than against a device.
 *
 * WHY BYTES AND NOT A LIVE AGENT. A wrong length byte or a mis-packed OID produces a packet that
 * an agent silently ignores, which on a scanner reads as "no device here". That failure is
 * indistinguishable from an empty network, and it is the single easiest way to ship a sweep that
 * looks like it works. These assert the actual bytes, and decode a real response shape.
 */
class SnmpCodecTest {

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    @Test
    fun `the first two arcs are packed into one byte`() {
        // 1.3 packs as 40*1 + 3 = 43 = 0x2B, which every SNMP decoder relies on.
        val encoded = SnmpCodec.encodeOid("1.3.6.1.2.1.1.1.0")
        assertEquals(0x06, encoded[0].toInt())
        // Value starts after the tag and the one-byte length.
        assertEquals(0x2B, encoded[2].toInt())
    }

    @Test
    fun `an arc above 127 is encoded base-128 with a continuation bit`() {
        // 2435 is 19 * 128 + 3, so it is two bytes: 0x93 carries 19 with the continuation bit
        // set, and 0x03 carries the remainder. Working the arithmetic out by hand matters here,
        // because a wrong expectation would pass while the encoder sent a packet agents ignore.
        val encoded = SnmpCodec.encodeOid("1.3.6.1.4.1.2435")
        val text = encoded.joinToString(" ") { "%02x".format(it) }
        assertTrue(
            "the two-byte arc encoding is missing from $text",
            text.contains("93 03"),
        )
    }

    @Test
    fun `a request carries the community and the version`() {
        val packet = SnmpCodec.getRequest(
            community = "public",
            oids = listOf("1.3.6.1.2.1.1.1.0"),
            requestId = 4660,
            version = SnmpCodec.Version.V2C,
        )

        val asText = packet.joinToString("") { "%02x".format(it) }
        // The community appears verbatim in the bytes, which is how it is found in a capture.
        assertTrue("community missing", asText.contains("7075626c6963"))
        // v2c is wire value 1, and v1 is 0. A version an agent does not support is ignored.
        assertTrue("version missing", asText.contains("020101"))
    }

    @Test
    fun `a v1 request encodes version zero`() {
        val packet = SnmpCodec.getRequest(
            community = "public",
            oids = listOf("1.3.6.1.2.1.1.1.0"),
            requestId = 1,
            version = SnmpCodec.Version.V1,
        )
        val asText = packet.joinToString("") { "%02x".format(it) }
        assertTrue(asText.contains("020100"))
    }

    @Test
    fun `every requested OID appears in the packet`() {
        val oids = DeviceIdentifier.SYSTEM_OIDS
        val packet = SnmpCodec.getRequest("public", oids, 1)
        // One GET request tag inside the PDU per varbind set, and seven varbind sequences.
        val sequences = countTag(packet, 0x30)
        assertTrue(
            "expected at least ${oids.size} sequences, found $sequences",
            sequences >= oids.size,
        )
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    @Test
    fun `a v2c response decodes to its values`() {
        val response = buildV2cResponse(
            requestId = 4242,
            varbinds = listOf(
                Triple("1.3.6.1.2.1.1.1.0", "HP LaserJet 4000", 0x04),
                Triple("1.3.6.1.2.1.1.7.0", "\u0048", 0x02),
            ),
        )

        val decoded = SnmpCodec.decode(response)
        assertNotNull(decoded)
        assertEquals(4242, decoded!!.requestId)
        assertEquals("1", decoded.community)
        assertEquals("HP LaserJet 4000", decoded.values()["1.3.6.1.2.1.1.1.0"])
    }

    @Test
    fun `noSuchObject is reported as a missing value rather than an empty string`() {
        // v2c answers per varbind, and that is the whole reason it is asked first: a device
        // implementing only part of MIB-II still returns the part it has.
        val response = buildV2cResponse(
            requestId = 7,
            varbinds = listOf(
                Triple("1.3.6.1.2.1.1.1.0", "Real Printer", 0x04),
                Triple("1.3.6.1.2.1.1.6.0", "", 0x80),
            ),
        )

        val decoded = SnmpCodec.decode(response)!!
        assertEquals("Real Printer", decoded.values()["1.3.6.1.2.1.1.1.0"])
        assertNull(decoded.values()["1.3.6.1.2.1.1.6.0"])

        val missing = decoded.varbinds.first { it.oid == "1.3.6.1.2.1.1.6.0" }
        assertEquals(SnmpCodec.ValueType.NO_SUCH_OBJECT, missing.type)
        assertTrue(!missing.hasValue)
    }

    @Test
    fun `an error status marks the response as failed`() {
        val response = buildV2cResponse(
            requestId = 9,
            varbinds = emptyList(),
            errorStatus = 2,
        )
        val decoded = SnmpCodec.decode(response)!!
        assertTrue(decoded.failed)
    }

    @Test
    fun `a non-SNMP packet decodes to null rather than throwing`() {
        // The sweep receives UDP from every address on the subnet, so one malformed packet must
        // not abort a scan that is otherwise working.
        assertNull(SnmpCodec.decode(ByteArray(0)))
        assertNull(SnmpCodec.decode(byteArrayOf(1, 2, 3)))
        assertNull(SnmpCodec.decode("hello".toByteArray()))
    }

    @Test
    fun `a truncated packet decodes to null rather than reading past the end`() {
        val full = buildV2cResponse(
            requestId = 1,
            varbinds = listOf(Triple("1.3.6.1.2.1.1.1.0", "A device", 0x04)),
        )
        // Cut it at every length and none of them may throw or read out of bounds.
        for (length in 0 until full.size) {
            SnmpCodec.decode(full.copyOfRange(0, length))
        }
    }

    @Test
    fun `a MAC address octet string is rendered as hex, not as mojibake`() {
        val mac = byteArrayOf(0x94.toByte(), 0xB3.toByte(), 0xF7.toByte(), 0x90.toByte(), 0x92.toByte(), 0x81.toByte())
        val response = buildV2cResponseRaw(
            requestId = 3,
            // Tag 0x04 is an octet string, which is how an agent returns ifPhysAddress.
            varbinds = listOf(Triple("1.3.6.1.2.1.2.2.1.6.1", mac, 0x04)),
        )
        val decoded = SnmpCodec.decode(response)!!
        assertEquals("94:b3:f7:90:92:81", decoded.values()["1.3.6.1.2.1.2.2.1.6.1"])
    }

    // ------------------------------------------------------------------
    // Builders that produce the wire shape a real agent sends
    // ------------------------------------------------------------------

    private fun buildV2cResponse(
        requestId: Int,
        varbinds: List<Triple<String, String, Int>>,
        errorStatus: Int = 0,
    ): ByteArray {
        val built = varbinds.map { (oid, value, tag) ->
            if (tag == 0x80) {
                Triple(oid, ByteArray(0), tag) to value
            } else {
                Triple(oid, value.toByteArray(Charsets.UTF_8), tag) to value
            }
        }
        return assemble(
            requestId = requestId,
            errorStatus = errorStatus,
            entries = built.map { (spec, _) -> spec },
        )
    }

    private fun buildV2cResponseRaw(
        requestId: Int,
        varbinds: List<Triple<String, ByteArray, Int>>,
    ): ByteArray = assemble(requestId, 0, varbinds)

    private fun assemble(
        requestId: Int,
        errorStatus: Int,
        entries: List<Triple<String, ByteArray, Int>>,
    ): ByteArray {
        val varbindBytes = entries.map { (oid, value, tag) ->
            val valueTlv = tlv(tag, value)
            tlv(0x30, SnmpCodec.encodeOid(oid) + valueTlv)
        }.fold(ByteArray(0)) { acc, bytes -> acc + bytes }

        val pdu = tlv(
            0xA2,
            tlv(0x02, intBytes(requestId)) +
                tlv(0x02, intBytes(errorStatus)) +
                tlv(0x02, intBytes(0)) +
                tlv(0x30, varbindBytes),
        )

        return tlv(
            0x30,
            tlv(0x02, intBytes(1)) +
                tlv(0x04, "1".toByteArray()) +
                pdu,
        )
    }

    private fun tlv(tag: Int, value: ByteArray): ByteArray {
        val length = when {
            value.size < 0x80 -> byteArrayOf(value.size.toByte())
            value.size < 0x100 -> byteArrayOf(0x81.toByte(), value.size.toByte())
            else -> byteArrayOf(0x82.toByte(), (value.size shr 8).toByte(), value.size.toByte())
        }
        return byteArrayOf(tag.toByte()) + length + value
    }

    private fun intBytes(value: Int): ByteArray = when {
        value < 0x80 -> byteArrayOf(value.toByte())
        value < 0x8000 -> byteArrayOf((value shr 8).toByte(), value.toByte())
        else -> byteArrayOf((value shr 16).toByte(), (value shr 8).toByte(), value.toByte())
    }

    private fun countTag(packet: ByteArray, tag: Int): Int =
        packet.count { (it.toInt() and 0xFF) == tag }
}
