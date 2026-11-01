// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The SmartGlass packet format: a header, an unprotected payload, a protected payload and a
 * signature. Requirement for Dylan's Xbox.
 *
 * EVERY NUMBER IN THIS PROTOCOL IS BIG-ENDIAN, which is why the header is written explicitly
 * rather than through a ByteBuffer with a default order. A packet assembled little-endian is
 * well-formed and simply ignored, which reads exactly like a console that is switched off.
 *
 * THE THREE MESSAGE CLASSES, which decide what a packet carries:
 *
 *  - SIMPLE messages (0xDD00 to 0xDD02) are discovery and power, and are plain text. A
 *    console answers and accepts these without any crypto at all, which is why waking one
 *    works from nothing.
 *  - PROTECTED messages carry an encrypted payload and a signature, and need the crypto
 *    context.
 *  - ACK messages are a bare header and carry no payload.
 *
 * A STRING IS NOT PLAIN TEXT. SmartGlass encodes a string as a uint16 length, the characters,
 * and a trailing null byte that the length does not count. Writing a bare UTF-8 string
 * produces a packet whose next field is read from the wrong offset, so this is written once
 * here and tested.
 */
object SmartGlassMessage {

    /** Message types, from the published protocol. */
    const val TYPE_DISCOVERY_REQUEST = 0xDD00
    const val TYPE_DISCOVERY_RESPONSE = 0xDD01
    const val TYPE_POWER_REQUEST = 0xDD02

    /** The connect exchange, which establishes the crypto context. */
    const val TYPE_CONNECT_REQUEST = 0xCC00
    const val TYPE_CONNECT_RESPONSE = 0xCC01

    /** A local join, sent once the context exists. */
    const val TYPE_LOCAL_JOIN = 0xCC03
    const val TYPE_LOCAL_JOIN_RESPONSE = 0xCC04
    const val TYPE_START_CHANNEL = 0xCC05
    const val TYPE_START_CHANNEL_RESPONSE = 0xCC06
    const val TYPE_STOP_CHANNEL = 0xCC07
    const val TYPE_STOP_CHANNEL_RESPONSE = 0xCC08

    /** The remote manager, which is how a console is actually driven. */
    const val TYPE_REMOTE_MANAGER = 0xCC0D

    /** Documented command ids inside the remote manager. */
    const val REMOTE_POWER_OFF = 0x39
    const val REMOTE_MEDIA_CONTROL = 0xF01
    const val REMOTE_BUTTON_INPUT = 0xF0A

    /** Media control sub-commands, from the protocol's own table. */
    const val MEDIA_PLAY = 0x02
    const val MEDIA_PAUSE = 0x04
    const val MEDIA_STOP = 0x06

    /** The protocol version every modern packet declares. */
    const val VERSION = 4

    /** A header, before any payload. */
    data class Header(
        val type: Int,
        val sequence: Int,
        val sourceParticipant: Long,
        val destinationParticipant: Long,
        /** Bit 0 flags a protected payload, and other bits are reserved. */
        val flags: Int,
    )

    /**
     * Build a simple packet: a header and an unprotected payload.
     *
     * Discovery and power use this, which is the whole reason a console can be woken with no
     * key exchange at all.
     */
    fun simpleHeader(type: Int, sequence: Int, payloadLength: Int): ByteArray =
        header(
            type = type,
            sequence = sequence,
            // A simple message is not routed to a channel, so both participants are zero.
            sourceParticipant = 0L,
            destinationParticipant = 0L,
            flags = 0,
            payloadLength = payloadLength,
        )

    /**
     * The header, which is 26 bytes for EVERY message.
     *
     * THE LAYOUT IS FIXED, not two variants: 4 type, 2 version, 2 sequence, 8 source
     * participant, 8 destination participant, 4 flags, 4 payload length. An earlier version of
     * this file had a separate 18-byte simple header that omitted the participants, which
     * put every later field at the wrong offset.
     *
     * A packet with a misaligned header is well-formed and ignored, which reads exactly like a
     * console that is switched off, so the field order here is the whole contract.
     */
    fun header(
        type: Int,
        sequence: Int,
        sourceParticipant: Long,
        destinationParticipant: Long,
        flags: Int,
        payloadLength: Int,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(type)
        buffer.putShort(VERSION.toShort())
        buffer.putShort(sequence.toShort())
        buffer.putLong(sourceParticipant)
        buffer.putLong(destinationParticipant)
        buffer.putInt(flags)
        buffer.putInt(payloadLength)
        return buffer.array()
    }

    /**
     * The header size, summed from its fields rather than remembered.
     *
     * 4 type + 2 version + 2 sequence + 8 source participant + 8 destination participant +
     * 4 flags + 4 payload length is 32. The earlier version of this file said 26, which was a
     * number copied from a document rather than added up, and the test that sums the fields
     * is what caught it.
     */
    const val HEADER_SIZE: Int = 4 + 2 + 2 + 8 + 8 + 4 + 4

    /**
     * An SGString: a uint16 length, the characters, then a null byte the length excludes.
     *
     * THE TRAILING NULL IS PART OF THE ENCODING and is not counted by the length. A writer
     * that omits it produces a packet where every following field is read one byte early,
     * which a console rejects without explanation.
     */
    fun string(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + bytes.size + 1)
        out[0] = ((bytes.size ushr 8) and 0xFF).toByte()
        out[1] = (bytes.size and 0xFF).toByte()
        bytes.copyInto(out, 2)
        out[out.size - 1] = 0
        return out
    }

    /**
     * The discovery request payload.
     *
     * Flags, client type, then the minimum and maximum protocol versions the client speaks.
     * A console that does not support the version range answers nothing, so the range is the
     * one every modern console accepts.
     */
    fun discoveryRequestPayload(): ByteArray {
        val buffer = ByteBuffer.allocate(10).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(0)                    // flags
        buffer.putShort(1)                  // client type: a mobile client
        buffer.putShort(2)                  // minimum version
        buffer.putShort(VERSION.toShort())  // maximum version
        return buffer.array()
    }

    /**
     * The power request payload: the console's Live ID as an SGString.
     *
     * THE CORRECTION THIS ENCODES. My earlier version of this file claimed SmartGlass has no
     * power-on message and that only Wake-on-LAN could wake a console. That was wrong: 0xDD02
     * carries the Live ID and wakes a console in standby without any crypto context. Both are
     * real, and this is the one the protocol itself provides.
     */
    fun powerRequestPayload(liveId: String): ByteArray = string(liveId)

    /**
     * Split a console's discovery reply into its parts.
     *
     * The reply carries the console name and UUID as SGStrings and then the certificate, whose
     * length is a uint16 before it. The certificate is what the crypto context needs, so a
     * reply whose certificate cannot be located is reported rather than half-used.
     */
    data class DiscoveryReply(
        val flags: Int,
        val type: Int,
        val name: String?,
        val uuid: String?,
        val lastError: Int,
        val certificate: ByteArray?,
    )

    fun readDiscoveryReply(payload: ByteArray): DiscoveryReply? {
        var index = 0
        fun readInt(): Int? {
            if (index + 4 > payload.size) return null
            val value = ((payload[index].toInt() and 0xFF) shl 24) or
                ((payload[index + 1].toInt() and 0xFF) shl 16) or
                ((payload[index + 2].toInt() and 0xFF) shl 8) or
                (payload[index + 3].toInt() and 0xFF)
            index += 4
            return value
        }
        fun readShort(): Int? {
            if (index + 2 > payload.size) return null
            val value = ((payload[index].toInt() and 0xFF) shl 8) or (payload[index + 1].toInt() and 0xFF)
            index += 2
            return value
        }
        fun readString(): String? {
            val length = readShort() ?: return null
            if (index + length > payload.size) return null
            val text = String(payload, index, length, Charsets.UTF_8)
            // Skip the string AND its trailing null.
            index += length + 1
            return text
        }

        val flags = readInt() ?: return null
        val type = readShort() ?: return null
        val name = readString()
        val uuid = readString()
        val lastError = readInt() ?: 0

        val certificateLength = readShort() ?: 0
        val certificate = if (certificateLength > 0 && index + certificateLength <= payload.size) {
            payload.copyOfRange(index, index + certificateLength)
        } else {
            null
        }

        return DiscoveryReply(flags, type, name, uuid, lastError, certificate)
    }

    /** Assemble a packet: header, unprotected payload, protected payload, signature. */
    fun packet(
        header: ByteArray,
        unprotected: ByteArray = ByteArray(0),
        protected: ByteArray = ByteArray(0),
        signature: ByteArray = ByteArray(0),
    ): ByteArray {
        val out = ByteArrayOutputStream(header.size + unprotected.size + protected.size + signature.size)
        out.write(header)
        if (unprotected.isNotEmpty()) out.write(unprotected)
        if (protected.isNotEmpty()) out.write(protected)
        if (signature.isNotEmpty()) out.write(signature)
        return out.toByteArray()
    }
}
