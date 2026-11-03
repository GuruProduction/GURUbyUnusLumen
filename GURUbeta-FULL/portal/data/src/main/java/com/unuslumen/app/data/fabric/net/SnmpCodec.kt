// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import java.io.ByteArrayOutputStream

/**
 * SNMP encoding and decoding, with no socket and no Android.
 *
 * WHY THIS IS HAND-WRITTEN AND NOT A LIBRARY. The whole of what a discovery sweep needs is one
 * GET of seven varbinds and the decoding of whatever comes back. A dependency that brings a MIB
 * compiler and a transport stack for that is a build change for no gain, and it would make the
 * packet this app actually sends into something a test cannot assert byte for byte.
 *
 * THE DEFECT THIS DESIGN AVOIDS. A v1 responder fails the ENTIRE request if any single varbind
 * names an object it does not implement, so one missing OID on a printer that implements only
 * part of MIB-II returns nothing at all and the device looks absent. v2c answers per varbind
 * instead, so the same device returns the seven minus the ones it does not know. That is why
 * [Version.V2C] is the default and v1 is the fallback rather than the other way round.
 */
object SnmpCodec {

    /** The protocol version, which changes how a missing object is reported. */
    enum class Version(val wire: Int) {
        V1(0),
        V2C(1),
    }

    /** How a varbind's value was encoded, which decides how it is read. */
    enum class ValueType {
        INTEGER,
        STRING,
        OID,
        TIMETICKS,
        /** v2c told us this object does not exist on the device, per OID. */
        NO_SUCH_OBJECT,
        /** It exists but this view cannot see it. */
        NO_SUCH_INSTANCE,
        /** The agent walked past the end of the table. */
        END_OF_MIB_VIEW,
        /** Something this build does not decode, kept so it is reported rather than dropped. */
        OTHER,
    }

    /** One object identifier and the value the agent returned for it. */
    data class Varbind(val oid: String, val value: String, val type: ValueType) {
        /** Whether this varbind carries a real answer rather than a message about absence. */
        val hasValue: Boolean
            get() = type != ValueType.NO_SUCH_OBJECT &&
                type != ValueType.NO_SUCH_INSTANCE &&
                type != ValueType.END_OF_MIB_VIEW
    }

    // ------------------------------------------------------------------
    // Encoding
    // ------------------------------------------------------------------

    /**
     * One v2c or v1 GET request asking for [oids].
     *
     * [requestId] is the caller's correlation key: replies carry it back, and a reply whose id
     * does not match an outstanding request is somebody else's traffic and must be discarded.
     * That is the one field that stops an unrelated UDP packet being read as a discovery.
     */
    fun getRequest(
        community: String,
        oids: List<String>,
        requestId: Int,
        version: Version = Version.V2C,
    ): ByteArray {
        val varbinds = oids.map { oid ->
            // NULL is the requested value: a GET asks for whatever is there.
            tlv(TAG_SEQUENCE, encodeOid(oid) + tlv(TAG_NULL, ByteArray(0)))
        }.joinToBytes()

        val pdu = tlv(
            TAG_GET_REQUEST,
            tlv(TAG_INTEGER, encodeInt(requestId)) +
                tlv(TAG_INTEGER, encodeInt(0)) +   // error status
                tlv(TAG_INTEGER, encodeInt(0)) +   // error index
                tlv(TAG_SEQUENCE, varbinds),
        )

        return tlv(
            TAG_SEQUENCE,
            tlv(TAG_INTEGER, encodeInt(version.wire)) +
                tlv(TAG_OCTET_STRING, community.toByteArray(Charsets.US_ASCII)) +
                pdu,
        )
    }

    /**
     * Encode a dotted object identifier.
     *
     * The first two arcs are packed into one byte as 40*a + b, and every arc after that is
     * base-128 with the continuation bit set on all but the last byte. Getting that packing
     * wrong produces a packet an agent silently drops, which looks like an absent device.
     */
    fun encodeOid(oid: String): ByteArray {
        val arcs = oid.trim().trim('.').split('.').map { it.toIntOrNull() ?: 0 }
        if (arcs.size < 2) return tlv(TAG_OID, ByteArray(0))

        val out = ByteArrayOutputStream()
        out.write(arcs[0] * 40 + arcs[1])
        for (arc in arcs.drop(2)) {
            var value = arc
            val parts = mutableListOf<Int>()
            parts.add(value and 0x7F)
            value = value shr 7
            while (value > 0) {
                parts.add((value and 0x7F) or 0x80)
                value = value shr 7
            }
            parts.reversed().forEach { out.write(it) }
        }
        return tlv(TAG_OID, out.toByteArray())
    }

    /** A BER length, short form under 128 bytes and the two-byte long form above it. */
    private fun encodeLength(length: Int): ByteArray = when {
        length < 0x80 -> byteArrayOf(length.toByte())
        length < 0x100 -> byteArrayOf(0x81.toByte(), length.toByte())
        length < 0x10000 -> byteArrayOf(0x82.toByte(), (length shr 8).toByte(), length.toByte())
        else -> byteArrayOf(
            0x83.toByte(), (length shr 16).toByte(), (length shr 8).toByte(), length.toByte(),
        )
    }

    /** One tag-length-value triplet. */
    private fun tlv(tag: Int, value: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + encodeLength(value.size) + value

    /** A BER integer, minimal form, which is what agents expect. */
    private fun encodeInt(value: Int): ByteArray {
        val bytes = ByteArray(4) { index ->
            (value shr (8 * (3 - index))).toByte()
        }
        // Leading zero bytes are dropped, and a high bit in the first kept byte needs a
        // zero in front of it or the value reads as negative.
        var start = 0
        while (start < 3 && bytes[start].toInt() == 0) start++
        val trimmed = bytes.copyOfRange(start, 4)
        return if (trimmed[0] < 0) byteArrayOf(0) + trimmed else trimmed
    }

    private fun List<ByteArray>.joinToBytes(): ByteArray {
        val out = ByteArrayOutputStream()
        forEach { out.write(it) }
        return out.toByteArray()
    }

    // ------------------------------------------------------------------
    // Decoding
    // ------------------------------------------------------------------

    /**
     * A decoded response, or null when the bytes are not an SNMP message at all.
     *
     * Returning null rather than throwing is deliberate: a sweep is receiving arbitrary UDP from
     * every address on the subnet, and one malformed packet must not abort a scan that is
     * otherwise working.
     */
    data class Response(
        val version: Int,
        val community: String,
        val pduTag: Int,
        val requestId: Int,
        val errorStatus: Int,
        val errorIndex: Int,
        val varbinds: List<Varbind>,
    ) {
        /** Whether the agent reported a problem with the request itself, rather than answering it. */
        val failed: Boolean get() = errorStatus != 0

        /** The varbinds that carry a real value, keyed by OID. */
        fun values(): Map<String, String> = varbinds.filter { it.hasValue }.associate { it.oid to it.value }
    }

    /**
     * Decode one SNMP message.
     *
     * Every length is bounds-checked against the buffer before it is used, because the input is
     * whatever arrived on the socket. A truncated packet that indexes past the end would throw
     * inside the receive loop and take the scan with it.
     */
    fun decode(bytes: ByteArray): Response? {
        return runCatching {
            val outer = readTlv(bytes, 0) ?: return null
            if (outer.tag != TAG_SEQUENCE) return null

            var offset = outer.valueStart
            val version = readInt(bytes, offset) ?: return null
            offset = version.second

            // The community is an OCTET STRING, read as its own TLV. Reading it through the OID
            // helper would decode the bytes as arcs and produce a garbage community, which is
            // then used in the reply map and reported as the string the agent answered with.
            val communityTlv = readTlv(bytes, offset) ?: return null
            if (communityTlv.tag != TAG_OCTET_STRING) return null
            val communityText = String(
                bytes,
                communityTlv.valueStart,
                communityTlv.valueEnd - communityTlv.valueStart,
                Charsets.US_ASCII,
            ).trim()
            offset = communityTlv.valueEnd

            val pdu = readTlv(bytes, offset) ?: return null
            if (pdu.tag != TAG_GET_RESPONSE && pdu.tag != TAG_GET_RESPONSE_ALT) return null
            offset = pdu.valueStart

            val id = readInt(bytes, offset) ?: return null
            offset = id.second
            val errorStatus = readInt(bytes, offset) ?: return null
            offset = errorStatus.second
            val errorIndex = readInt(bytes, offset) ?: return null
            offset = errorIndex.second

            val listTlv = readTlv(bytes, offset) ?: return null
            if (listTlv.tag != TAG_SEQUENCE) return null

            val varbinds = mutableListOf<Varbind>()
            var cursor = listTlv.valueStart
            while (cursor < listTlv.valueEnd) {
                val varbind = readTlv(bytes, cursor) ?: break
                if (varbind.tag != TAG_SEQUENCE) break
                var inner = varbind.valueStart

                val oidTlv = readTlv(bytes, inner) ?: break
                if (oidTlv.tag != TAG_OID) break
                val oidText = decodeOidBody(bytes, oidTlv)
                inner = oidTlv.valueEnd

                val valueTlv = readTlv(bytes, inner) ?: break
                varbinds.add(decodeVarbind(oidText, bytes, valueTlv))
                cursor = varbind.valueEnd
            }

            Response(
                version = version.first,
                community = communityText,
                pduTag = pdu.tag,
                requestId = id.first,
                errorStatus = errorStatus.first,
                errorIndex = errorIndex.first,
                varbinds = varbinds,
            )
        }.getOrNull()
    }

    /** One varbind's value, decoded by its tag. */
    private fun decodeVarbind(oid: String, bytes: ByteArray, tlv: Tlv): Varbind {
        val body = bytes.copyOfRange(tlv.valueStart, tlv.valueEnd)
        return when (tlv.tag) {
            TAG_INTEGER -> Varbind(oid, decodeIntBody(body).toString(), ValueType.INTEGER)
            TAG_OCTET_STRING -> Varbind(oid, printableBody(body), ValueType.STRING)
            TAG_OID -> Varbind(oid, decodeOidBody(bytes, tlv), ValueType.OID)
            TAG_TIMETICKS -> Varbind(oid, decodeUIntBody(body).toString(), ValueType.TIMETICKS)
            TAG_NO_SUCH_OBJECT -> Varbind(oid, "", ValueType.NO_SUCH_OBJECT)
            TAG_NO_SUCH_INSTANCE -> Varbind(oid, "", ValueType.NO_SUCH_INSTANCE)
            TAG_END_OF_MIB_VIEW -> Varbind(oid, "", ValueType.END_OF_MIB_VIEW)
            else -> Varbind(oid, printableBody(body), ValueType.OTHER)
        }
    }

    /**
     * A byte body as text.
     *
     * Non-printable bytes are dropped rather than escaped, because the values that matter here are
     * system names, model strings and timestamps. A MAC address arrives as raw octets and is
     * rendered as hex when it looks like one, which is the only binary value this build uses.
     */
    private fun printableBody(body: ByteArray): String {
        if (body.isEmpty()) return ""
        val printable = body.count { it in 32..126 } >= body.size * 0.8
        return if (printable) {
            String(body, Charsets.UTF_8).trim()
        } else if (body.size == 6) {
            body.joinToString(":") { "%02x".format(it) }
        } else {
            body.joinToString("") { "%02x".format(it) }
        }
    }

    private fun decodeIntBody(body: ByteArray): Int {
        if (body.isEmpty()) return 0
        var value = if (body[0].toInt() < 0) -1 else 0
        for (byte in body) {
            value = (value shl 8) or (byte.toInt() and 0xFF)
        }
        return value
    }

    private fun decodeUIntBody(body: ByteArray): Long {
        var value = 0L
        for (byte in body) {
            value = (value shl 8) or (byte.toLong() and 0xFF)
        }
        return value
    }

    private fun decodeOidBody(bytes: ByteArray, tlv: Tlv): String {
        val body = bytes.copyOfRange(tlv.valueStart, tlv.valueEnd)
        if (body.isEmpty()) return ""
        val arcs = mutableListOf<Int>()
        val first = body[0].toInt() and 0xFF
        arcs.add(first / 40)
        arcs.add(first % 40)

        var value = 0
        for (index in 1 until body.size) {
            val byte = body[index].toInt() and 0xFF
            value = (value shl 7) or (byte and 0x7F)
            if (byte and 0x80 == 0) {
                arcs.add(value)
                value = 0
            }
        }
        return arcs.joinToString(".")
    }

    private data class Tlv(val tag: Int, val valueStart: Int, val valueEnd: Int, val length: Int)

    /** Read one tag-length-value at [offset], bounds-checked. */
    private fun readTlv(bytes: ByteArray, offset: Int): Tlv? {
        if (offset + 2 > bytes.size) return null
        val tag = bytes[offset].toInt() and 0xFF
        var cursor = offset + 1
        var length = bytes[cursor].toInt() and 0xFF
        cursor++
        if (length and 0x80 != 0) {
            val count = length and 0x7F
            if (count == 0 || count > 3 || cursor + count > bytes.size) return null
            length = 0
            for (index in 0 until count) {
                length = (length shl 8) or (bytes[cursor].toInt() and 0xFF)
                cursor++
            }
        }
        if (cursor + length > bytes.size) return null
        return Tlv(tag = tag, valueStart = cursor, valueEnd = cursor + length, length = length)
    }

    /** Read an integer at [offset] and report where it ended. */
    private fun readInt(bytes: ByteArray, offset: Int): Pair<Int, Int>? {
        val tlv = readTlv(bytes, offset) ?: return null
        if (tlv.tag != TAG_INTEGER) return null
        return decodeIntBody(bytes.copyOfRange(tlv.valueStart, tlv.valueEnd)) to tlv.valueEnd
    }

    // BER tags this build reads or writes. The constants are named rather than left as magic
    // numbers because a wrong tag byte is the fault that makes a packet silently ignored.
    private const val TAG_INTEGER = 0x02
    private const val TAG_OCTET_STRING = 0x04
    private const val TAG_NULL = 0x05
    private const val TAG_OID = 0x06
    private const val TAG_SEQUENCE = 0x30
    private const val TAG_TIMETICKS = 0x43
    private const val TAG_GET_REQUEST = 0xA0
    private const val TAG_GET_RESPONSE = 0xA2
    private const val TAG_GET_RESPONSE_ALT = 0xA1
    private const val TAG_NO_SUCH_OBJECT = 0x80
    private const val TAG_NO_SUCH_INSTANCE = 0x81
    private const val TAG_END_OF_MIB_VIEW = 0x82
}
