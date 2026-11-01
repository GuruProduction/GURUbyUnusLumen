// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The SmartGlass wire format, taken from the reference implementation's own struct
 * definitions rather than from prose.
 *
 * TWO PACKET SHAPES, and they genuinely differ:
 *
 *  - SIMPLE packets (discovery, power on, connect) carry a header of a 16-bit packet type, a
 *    16-bit unprotected payload length, and a 16-bit version. That is SIX bytes, and only a
 *    connect packet adds a fourth field, the 16-bit protected payload length.
 *  - MESSAGE packets, which is everything sent on a channel, carry a 26-byte header in the
 *    field order below.
 *
 * THE VERSION IS 2. Version 2 is what the reference implementation writes, and a packet
 * declaring a version the console does not speak is dropped with no reply at all.
 *
 * EVERY FIELD IS BIG-ENDIAN.
 *
 * A STRING IS A 16-BIT LENGTH, THE CHARACTERS, AND A TRAILING NULL THE LENGTH EXCLUDES.
 */
object SmartGlassMessage {

    /** Packet types, which are 16-bit fields in a simple header. */
    const val TYPE_POWER_ON_REQUEST = 0xDD02
    const val TYPE_DISCOVERY_REQUEST = 0xDD00
    const val TYPE_DISCOVERY_RESPONSE = 0xDD01
    const val TYPE_CONNECT_REQUEST = 0xCC00
    const val TYPE_CONNECT_RESPONSE = 0xCC01

    /**
     * A channel message, and the channel management types.
     *
     * THESE ARE MESSAGE TYPES INSIDE THE FLAGS FIELD, not packet types. A message packet always
     * carries pkt_type 0xD00D and the flags field names what kind of message it is. The earlier
     * version of this file used the CC0x numbers as packet types, which a console ignores.
     */
    const val TYPE_MESSAGE = 0xD00D
    const val MSG_LOCAL_JOIN = 0x03
    const val MSG_TITLE_LAUNCH = 0x23
    const val MSG_START_CHANNEL = 0x26
    const val MSG_START_CHANNEL_RESPONSE = 0x27
    const val MSG_POWER_OFF = 0x39
    const val MSG_MEDIA_CONTROL = 0xF01
    const val MSG_GAMEPAD = 0xF0A
    const val MSG_CONSOLE_STATUS = 0x50

    /** The key type value for a P-384 public key. */
    const val KEY_TYPE_P384 = 0x01

    /** The protocol version every header declares. */
    const val VERSION = 2

    /** A simple header: type, unprotected length, version. */
    const val SIMPLE_HEADER_SIZE = 2 + 2 + 2

    /** A connect header adds the protected payload length, so it is two bytes longer. */
    const val CONNECT_HEADER_SIZE = 2 + 2 + 2 + 2

    /**
     * The message header, from the reference implementation:
     *
     *   pkt_type                  Int16
     *   protected_payload_length  Int16
     *   sequence_number           Int32
     *   target_participant_id     Int32
     *   source_participant_id     Int32
     *   flags                     Int16 bitfield
     *   channel_id                Int64
     *
     * which is 2 + 2 + 4 + 4 + 4 + 2 + 8 = 26 bytes.
     */
    const val MESSAGE_HEADER_SIZE = 2 + 2 + 4 + 4 + 4 + 2 + 8

    /** A P-384 public key is 96 raw bytes: X then Y, with NO 0x04 prefix. */
    const val P384_PUBLIC_KEY_SIZE = 0x60

    /**
     * The flags bitfield: version in the top two bits, then need_ack, then is_fragment, then
     * the twelve-bit message type.
     */
    data class Flags(
        val version: Int = VERSION,
        val needAck: Boolean = false,
        val isFragment: Boolean = false,
        val messageType: Int = 0,
    ) {
        fun toShort(): Int {
            var value = (version and 0x3) shl 14
            if (needAck) value = value or (1 shl 13)
            if (isFragment) value = value or (1 shl 12)
            value = value or (messageType and 0x0FFF)
            return value
        }
    }

    /**
     * A simple header.
     *
     * The protected length is only written for a connect packet, because the reference
     * implementation's struct declares that field conditionally. Writing it unconditionally
     * makes every other simple packet two bytes too long and shifts the payload.
     */
    fun simpleHeader(
        type: Int,
        unprotectedLength: Int,
        protectedLength: Int = 0,
    ): ByteArray {
        val isConnect = type == TYPE_CONNECT_REQUEST || type == TYPE_CONNECT_RESPONSE
        val buffer = ByteBuffer.allocate(if (isConnect) CONNECT_HEADER_SIZE else SIMPLE_HEADER_SIZE)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(type.toShort())
        buffer.putShort(unprotectedLength.toShort())
        if (isConnect) buffer.putShort(protectedLength.toShort())
        buffer.putShort(VERSION.toShort())
        return buffer.array()
    }

    /** A message header, in the reference implementation's field order. */
    fun messageHeader(
        type: Int,
        protectedLength: Int,
        sequence: Int,
        targetParticipant: Int,
        sourceParticipant: Int,
        flags: Flags,
        channelId: Long,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(MESSAGE_HEADER_SIZE).order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(type.toShort())
        buffer.putShort(protectedLength.toShort())
        buffer.putInt(sequence)
        buffer.putInt(targetParticipant)
        buffer.putInt(sourceParticipant)
        buffer.putShort(flags.toShort().toShort())
        buffer.putLong(channelId)
        return buffer.array()
    }

    /** An SGString: a 16-bit length, the characters, then a null the length excludes. */
    fun string(value: String): ByteArray {
        val bytes = value.toByteArray(Charsets.UTF_8)
        val out = ByteArray(2 + bytes.size + 1)
        out[0] = ((bytes.size ushr 8) and 0xFF).toByte()
        out[1] = (bytes.size and 0xFF).toByte()
        bytes.copyInto(out, 2)
        out[out.size - 1] = 0
        return out
    }

    /** The discovery payload: flags, client type, minimum version, maximum version. */
    fun discoveryRequestPayload(): ByteArray {
        val buffer = ByteBuffer.allocate(4 + 2 + 2 + 2).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(0)
        buffer.putShort(1)                    // client type: a mobile client
        buffer.putShort(2)                    // minimum version
        buffer.putShort(VERSION.toShort())    // maximum version
        return buffer.array()
    }

    /**
     * The power-on payload: the console's Live ID as an SGString.
     *
     * Message 0xDD02 is the protocol's own power-on and needs no crypto at all. Wake-on-LAN is
     * the other route, for a console that is fully shut down rather than in instant-on standby.
     */
    fun powerOnPayload(liveId: String): ByteArray = string(liveId)

    /**
     * The Connect Request's unprotected payload.
     *
     *   sg_uuid          16 raw bytes
     *   public_key_type  Int16, where 2 is P-384
     *   public_key       96 bytes for P-384: X and Y with NO 0x04 prefix
     *   iv               16 raw bytes
     *
     * THE KEY HAS NO PREFIX HERE. It is the raw coordinates, so writing the uncompressed point
     * form with its 0x04 in front makes the console read the IV a byte early and reject the
     * whole request with no explanation.
     */
    fun connectRequestUnprotected(
        clientUuid: ByteArray,
        publicKeyType: Int,
        publicKey: ByteArray,
        iv: ByteArray,
    ): ByteArray {
        require(clientUuid.size == 16) { "a client UUID is 16 bytes, and this one is ${clientUuid.size}" }
        require(iv.size == 16) { "an initialisation vector is 16 bytes, and this one is ${iv.size}" }
        // Grok's correction, confirmed against the reference: the type values are 0x00, 0x01 and
        // 0x02 for P-256, P-384 and P-521. Writing 2 for P-384 is off by one and the console
        // reads the key as a different curve.
        val expected = when (publicKeyType) {
            0x00 -> 0x40      // P-256
            0x01 -> P384_PUBLIC_KEY_SIZE
            0x02 -> 0x84      // P-521
            else -> publicKey.size
        }
        require(publicKey.size == expected) {
            "a P-384 public key is 96 raw bytes, and this one is ${publicKey.size}. " +
                "The uncompressed point form with a 0x04 prefix is 97 and is not what this " +
                "field carries."
        }

        val out = ByteArrayOutputStream(16 + 2 + publicKey.size + 16)
        out.write(clientUuid)
        out.write(byteArrayOf(((publicKeyType ushr 8) and 0xFF).toByte(), (publicKeyType and 0xFF).toByte()))
        out.write(publicKey)
        out.write(iv)
        return out.toByteArray()
    }

    /**
     * The Connect Request's protected payload.
     *
     * The user hash and the JWT travel here, and an unauthenticated client sends both empty.
     * The three request numbers are for fragmentation; a single un-fragmented request uses the
     * same number for all three, which is what the reference implementation does.
     */
    fun connectRequestProtected(
        userHash: String,
        jwt: String,
        requestNumber: Int,
        groupStart: Int,
        groupEnd: Int,
    ): ByteArray {
        val hash = string(userHash)
        val token = string(jwt)
        val buffer = ByteBuffer.allocate(hash.size + token.size + 12).order(ByteOrder.BIG_ENDIAN)
        buffer.put(hash)
        buffer.put(token)
        buffer.putInt(requestNumber)
        buffer.putInt(groupStart)
        buffer.putInt(groupEnd)
        return buffer.array()
    }

    /** A connect reply: the connection result, the pairing state and the participant id. */
    data class ConnectReply(val result: Int, val pairingState: Int, val participantId: Int) {
        /** Result 0 is success. Anything else is the console's own refusal code. */
        val succeeded: Boolean get() = result == 0
    }

    /** Parse a connect reply's protected payload, which is two shorts and an int. */
    fun readConnectReply(payload: ByteArray): ConnectReply? {
        if (payload.size < 8) return null
        val buffer = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN)
        return ConnectReply(
            result = buffer.short.toInt() and 0xFFFF,
            pairingState = buffer.short.toInt() and 0xFFFF,
            participantId = buffer.int,
        )
    }

    /** The Local Join payload, which announces this client to the console. */
    fun localJoinPayload(
        deviceType: Int = 0x08,   // Android, per the protocol's client type list
        nativeWidth: Int = 1920,
        nativeHeight: Int = 1080,
        dpiX: Int = 160,
        dpiY: Int = 160,
        capabilities: Long = 0,
        clientVersion: Int = 1,
        osMajor: Int = 10,
        osMinor: Int = 0,
        displayName: String = "GURU",
    ): ByteArray {
        val name = string(displayName)
        val buffer = ByteBuffer.allocate(2 + 2 + 2 + 2 + 2 + 8 + 4 + 4 + 4 + name.size)
            .order(ByteOrder.BIG_ENDIAN)
        buffer.putShort(deviceType.toShort())
        buffer.putShort(nativeWidth.toShort())
        buffer.putShort(nativeHeight.toShort())
        buffer.putShort(dpiX.toShort())
        buffer.putShort(dpiY.toShort())
        buffer.putLong(capabilities)
        buffer.putInt(clientVersion)
        buffer.putInt(osMajor)
        buffer.putInt(osMinor)
        buffer.put(name)
        return buffer.array()
    }

    /**
     * The gamepad payload for one state.
     *
     * GROK'S CORRECTION, which changes the shape completely. The message type 0xF0A lives in the
     * HEADER FLAGS, not in the payload, so there is no message id field and no remote-manager
     * wrapper. The payload is:
     *
     *   uint64  timestamp
     *   uint16  button bitmask
     *   float32 left trigger
     *   float32 right trigger
     *   float32 left thumb X
     *   float32 left thumb Y
     *   float32 right thumb X
     *   float32 right thumb Y
     *
     * The sticks are float32 in roughly -1.0 to 1.0. The earlier version of this file had a
     * 14-byte payload with a message id and no sticks, which the console would have read as a
     * malformed message and dropped.
     */
    fun gamepadPayload(
        buttonMask: Int,
        leftTrigger: Float = 0f,
        rightTrigger: Float = 0f,
        leftStickX: Float = 0f,
        leftStickY: Float = 0f,
        rightStickX: Float = 0f,
        rightStickY: Float = 0f,
        timestamp: Long = System.currentTimeMillis() * 1000L,
    ): ByteArray {
        val buffer = ByteBuffer.allocate(8 + 2 + 4 * 6).order(ByteOrder.BIG_ENDIAN)
        buffer.putLong(timestamp)
        buffer.putShort(buttonMask.toShort())
        buffer.putFloat(leftTrigger.coerceIn(0f, 1f))
        buffer.putFloat(rightTrigger.coerceIn(0f, 1f))
        buffer.putFloat(leftStickX.coerceIn(-1f, 1f))
        buffer.putFloat(leftStickY.coerceIn(-1f, 1f))
        buffer.putFloat(rightStickX.coerceIn(-1f, 1f))
        buffer.putFloat(rightStickY.coerceIn(-1f, 1f))
        return buffer.array()
    }

    /**
     * Non-standard PKCS#7 padding: pad only when the length is not already a multiple of 16, and
     * repeat the pad length as the pad bytes. A block that is already aligned gets nothing.
     *
     * Adding a full block of padding to an aligned payload is the mistake that makes a console
     * decrypt to the wrong length and drop the packet.
     */
    fun pad(payload: ByteArray): ByteArray {
        val remainder = payload.size % 16
        if (remainder == 0) return payload
        val padLength = 16 - remainder
        return payload + ByteArray(padLength) { padLength.toByte() }
    }

    /** The Start Channel payload: a request id, a title id, the service UUID, an activity id. */
    fun startChannelPayload(
        channelRequestId: Int,
        titleId: Int,
        serviceUuid: ByteArray,
        activityId: Int = 0,
    ): ByteArray {
        require(serviceUuid.size == 16) { "a service UUID is 16 raw bytes" }
        val buffer = ByteBuffer.allocate(4 + 4 + 16 + 4).order(ByteOrder.BIG_ENDIAN)
        buffer.putInt(channelRequestId)
        buffer.putInt(titleId)
        buffer.put(serviceUuid)
        buffer.putInt(activityId)
        return buffer.array()
    }

    /** A discovery reply's parts. */
    data class DiscoveryReply(
        val flags: Int,
        val type: Int,
        val name: String?,
        val uuid: String?,
        val lastError: Int,
        val certificate: ByteArray?,
        /** The Live ID, which power on and off both need. */
        val liveId: String?,
    )

    fun readDiscoveryReply(payload: ByteArray): DiscoveryReply? {
        var index = 0

        fun readInt(): Int? {
            if (index + 4 > payload.size) return null
            val value = ByteBuffer.wrap(payload, index, 4).order(ByteOrder.BIG_ENDIAN).int
            index += 4
            return value
        }
        fun readShort(): Int? {
            if (index + 2 > payload.size) return null
            val value = ByteBuffer.wrap(payload, index, 2).order(ByteOrder.BIG_ENDIAN)
                .short.toInt() and 0xFFFF
            index += 2
            return value
        }
        fun readString(): String? {
            val length = readShort() ?: return null
            // The trailing null is part of the data and is not counted by the length.
            if (index + length + 1 > payload.size) return null
            val text = String(payload, index, length, Charsets.UTF_8)
            index += length + 1
            return text
        }

        val flags = readInt() ?: return null
        val type = readShort() ?: return null
        val name = readString()
        // The UUID travels as an SGString in its text form, not as raw bytes.
        val uuid = readString()
        val lastError = readInt() ?: 0
        val certLength = readShort() ?: 0
        val certificate = if (certLength > 0 && index + certLength <= payload.size) {
            payload.copyOfRange(index, index + certLength)
        } else {
            null
        }
        // The certificate carries the Live ID in its subject, which is where the reference
        // implementation reads it from. It is parsed here so a caller never has to know that.
        return DiscoveryReply(
            flags = flags,
            type = type,
            name = name,
            uuid = uuid,
            lastError = lastError,
            certificate = certificate,
            liveId = certificate?.let { liveIdFromCertificate(it) },
        )
    }

    /**
     * The Live ID from a console certificate.
     *
     * The certificate's subject carries the console's Live ID in one of its fields. It is
     * extracted from the DER text rather than by parsing the subject structure, because the value
     * appears as a printable string and this reads it without a full ASN.1 parser.
     */
    fun liveIdFromCertificate(certificate: ByteArray): String? = runCatching {
        val text = String(certificate, Charsets.ISO_8859_1)
        // A Live ID is sixteen hex or decimal digits; the subject carries it as text.
        Regex("([0-9A-Fa-f]{16})").findAll(text)
            .map { it.groupValues[1] }
            .firstOrNull { it.length == 16 }
    }.getOrNull()

    /** Assemble a packet from its parts. */
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
