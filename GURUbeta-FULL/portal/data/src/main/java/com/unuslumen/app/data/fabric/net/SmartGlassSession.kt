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
 * A live SmartGlass session: connect, join a channel, then send.
 *
 * THIS IS THE PIECE THAT MAKES THE CONSOLE CONTROLLABLE. Before it, every command was assembled
 * and refused because a console will not accept a signed message until it has answered a
 * Connect Request and the client has joined a channel. This file does that handshake in order:
 *
 *  1. CONNECT. Send a Connect Request with the client's ephemeral public key. The console
 *     answers a Connect Response carrying a participant id, and every later message is
 *     addressed from that participant.
 *  2. LOCAL JOIN. Announce the client, which the console needs before it opens a channel.
 *  3. START CHANNEL. Ask for the system input channel, which is where a gamepad is read.
 *  4. SEND. From here a signed message on that channel is accepted.
 *
 * WHAT IS STILL UNPROVEN AND WHY IT IS HONEST: no real console has run this. The handshake has
 * five observable steps and every one of them can fail differently, so each stage reports its
 * own outcome rather than a single "it did not work". When a real console refuses, the sentence
 * names the stage that refused it, which is the difference between ten minutes and ten hours.
 */
class SmartGlassSession(
    private val host: String,
    private val timeoutMillis: Int = 3000,
) {

    /** The outcome of one stage of the handshake. */
    data class Stage(val name: String, val ok: Boolean, val detail: String)

    /** The whole handshake's outcome. */
    data class Handshake(
        val stages: List<Stage>,
        val crypto: SmartGlassCrypto?,
        val participantId: Int?,
        val channelId: Long?,
    ) {
        val connected: Boolean
            get() = stages.all { it.ok } && crypto != null && participantId != null

        /** One sentence naming the first stage that failed, which is what a person needs. */
        fun sentence(): String {
            val failed = stages.firstOrNull { !it.ok }
            return if (failed == null) {
                "Connected to the console and joined the input channel."
            } else {
                "The console handshake failed at '${failed.name}': ${failed.detail}"
            }
        }
    }

    /**
     * Run the whole handshake against a discovered console.
     *
     * [console] must carry the certificate from a discovery reply, because that certificate
     * holds the public key the ECDH exchange needs. Without it the first stage cannot start,
     * and saying so is better than deriving a key the console will never agree with.
     */
    suspend fun connect(console: XboxConsole.Console): Handshake = withContext(Dispatchers.IO) {
        val stages = mutableListOf<Stage>()

        val certificate = console.certificate
        if (certificate == null) {
            stages.add(
                Stage(
                    "read the certificate",
                    false,
                    "The discovery reply carried no certificate, so there is no console public key " +
                        "to exchange keys against. Run discovery again with the console awake.",
                ),
            )
            return@withContext Handshake(stages, null, null, null)
        }

        val context = SmartGlassCrypto.create(certificate)
        val crypto = context.crypto
        if (crypto == null) {
            stages.add(
                Stage("derive the keys", false, context.reason ?: "the crypto context could not be built"),
            )
            return@withContext Handshake(stages, null, null, null)
        }
        stages.add(Stage("derive the keys", true, "An ECDH shared secret and three derived keys exist."))

        val socket = runCatching { DatagramSocket() }.getOrNull()
        if (socket == null) {
            stages.add(Stage("open a socket", false, "This phone could not open a UDP socket."))
            return@withContext Handshake(stages, null, null, null)
        }

        try {
            socket.soTimeout = timeoutMillis
            val target = InetAddress.getByName(host)

            // ---- Stage 1: the Connect Request ----
            val clientUuid = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val iv = ByteArray(16).also { java.security.SecureRandom().nextBytes(it) }
            val publicKey = SmartGlassCrypto.encodeKeyForRequest(
                crypto.keyPair.public as java.security.interfaces.ECPublicKey,
            )

            val unprotected = SmartGlassMessage.connectRequestUnprotected(
                clientUuid = clientUuid,
                publicKeyType = SmartGlassMessage.KEY_TYPE_P384,
                publicKey = publicKey,
                iv = iv,
            )
            val protectedPayload = SmartGlassMessage.connectRequestProtected(
                userHash = "",
                jwt = "",
                requestNumber = 1,
                groupStart = 1,
                groupEnd = 1,
            )
            // The connect request's protected payload is encrypted with the UNPROTECTED IV the
            // client just sent, not with the derived one, because the console has no context yet.
            val encryptedProtected = crypto.encryptWith(iv, protectedPayload)

            val header = SmartGlassMessage.simpleHeader(
                type = SmartGlassMessage.TYPE_CONNECT_REQUEST,
                unprotectedLength = unprotected.size,
                protectedLength = encryptedProtected.size,
            )
            val request = SmartGlassMessage.packet(header, unprotected, encryptedProtected)
            socket.send(DatagramPacket(request, request.size, target, SmartGlassProtocolPort))

            val reply = receive(socket)
            if (reply == null) {
                stages.add(
                    Stage(
                        "connect",
                        false,
                        "The console did not answer the Connect Request within " +
                            "${timeoutMillis / 1000} seconds. A console that is off, asleep or on " +
                            "another network answers nothing.",
                    ),
                )
                return@withContext Handshake(stages, crypto, null, null)
            }

            val replyType = ByteBuffer.wrap(reply, 0, 2).order(ByteOrder.BIG_ENDIAN).short.toInt() and 0xFFFF
            if (replyType != SmartGlassMessage.TYPE_CONNECT_RESPONSE) {
                stages.add(
                    Stage(
                        "connect",
                        false,
                        "The console answered packet type 0x%04X rather than a Connect Response, so " +
                            "it is not speaking SmartGlass to this client.".format(replyType),
                    ),
                )
                return@withContext Handshake(stages, crypto, null, null)
            }

            // The reply's unprotected payload holds the IV, and its protected payload is
            // encrypted under that IV. The header is six bytes for a simple packet.
            val unprotectedStart = SmartGlassMessage.CONNECT_HEADER_SIZE
            if (reply.size < unprotectedStart + 16) {
                stages.add(Stage("connect", false, "The Connect Response was too short to carry an IV."))
                return@withContext Handshake(stages, crypto, null, null)
            }
            val replyIv = reply.copyOfRange(unprotectedStart, unprotectedStart + 16)
            val protectedStart = unprotectedStart + 16
            if (reply.size <= protectedStart) {
                stages.add(
                    Stage("connect", false, "The Connect Response carried no protected payload."),
                )
                return@withContext Handshake(stages, crypto, null, null)
            }
            val replyProtected = reply.copyOfRange(protectedStart, reply.size)
            val decrypted = crypto.decryptWith(replyIv, replyProtected)
            val connectReply = decrypted?.let { SmartGlassMessage.readConnectReply(it) }

            if (connectReply == null) {
                stages.add(
                    Stage(
                        "connect",
                        false,
                        "The Connect Response's protected payload did not decrypt, which means the " +
                            "console derived a different key from the same exchange. That points at " +
                            "the salt or the key encoding rather than at the network.",
                    ),
                )
                return@withContext Handshake(stages, crypto, null, null)
            }
            if (!connectReply.succeeded) {
                stages.add(
                    Stage(
                        "connect",
                        false,
                        "The console refused the connection with result ${connectReply.result} and " +
                            "pairing state ${connectReply.pairingState}.",
                    ),
                )
                return@withContext Handshake(stages, crypto, null, null)
            }
            stages.add(
                Stage(
                    "connect",
                    true,
                    "The console accepted the connection and issued participant id " +
                        "${connectReply.participantId}.",
                ),
            )

            val participant = connectReply.participantId

            // ---- Stage 2: Local Join ----
            //
            // The message type lives in the flags field, and the packet type is always 0xD00D.
            // A Local Join asks for an acknowledgement, and the console will not open a channel
            // until it has been acknowledged.
            val joinPayload = SmartGlassMessage.localJoinPayload()
            val joinHeader = SmartGlassMessage.messageHeader(
                type = SmartGlassMessage.TYPE_MESSAGE,
                protectedLength = 0,
                sequence = 1,
                targetParticipant = participant,
                sourceParticipant = participant,
                flags = SmartGlassMessage.Flags(
                    needAck = true,
                    messageType = SmartGlassMessage.MSG_LOCAL_JOIN,
                ),
                channelId = 0,
            )
            val encryptedJoin = crypto.encryptWith(
                ByteArray(16).also { java.security.SecureRandom().nextBytes(it) },
                joinPayload,
            )
            val joinIv = crypto.messageIv(joinHeader)
            val joinEncrypted = crypto.encryptWith(joinIv, SmartGlassMessage.pad(joinPayload))
            val join = SmartGlassMessage.packet(
                header = joinHeader,
                protected = joinEncrypted,
                signature = crypto.sign(joinEncrypted),
            )
            socket.send(DatagramPacket(join, join.size, target, SmartGlassProtocolPort))

            val joinReply = receive(socket)
            stages.add(
                Stage(
                    "join",
                    joinReply != null,
                    if (joinReply != null) {
                        "The console acknowledged the Local Join."
                    } else {
                        "The console did not acknowledge the Local Join, so it will not open a " +
                            "channel. A missing acknowledgement almost always means the connect " +
                            "request's protection was rejected."
                    },
                ),
            )
            if (joinReply == null) {
                return@withContext Handshake(stages, crypto, participant, null)
            }

            // ---- Stage 3: Start the system input channel ----
            val channelRequestId = 1
            val startPayload = SmartGlassMessage.startChannelPayload(
                channelRequestId = channelRequestId,
                titleId = 0,
                serviceUuid = SERVICE_SYSTEM_INPUT,
            )
            val startHeader = SmartGlassMessage.messageHeader(
                type = SmartGlassMessage.TYPE_MESSAGE,
                protectedLength = SmartGlassMessage.pad(startPayload).size,
                sequence = 2,
                targetParticipant = participant,
                sourceParticipant = participant,
                flags = SmartGlassMessage.Flags(
                    needAck = true,
                    messageType = SmartGlassMessage.MSG_START_CHANNEL,
                ),
                channelId = 0,
            )
            val startIv = crypto.messageIv(startHeader)
            val startEncrypted = crypto.encryptWith(startIv, SmartGlassMessage.pad(startPayload))
            val start = SmartGlassMessage.packet(
                header = startHeader,
                protected = startEncrypted,
                signature = crypto.sign(startEncrypted),
            )
            socket.send(DatagramPacket(start, start.size, target, SmartGlassProtocolPort))

            val channelReply = receive(socket)
            if (channelReply == null) {
                stages.add(
                    Stage(
                        "start the input channel",
                        false,
                        "The console did not answer the channel request, so a gamepad press would " +
                            "have nowhere to go.",
                    ),
                )
                return@withContext Handshake(stages, crypto, participant, null)
            }

            // The response's protected payload is a request id, the channel id and a result, so
            // the channel id sits after the first four bytes of that payload.
            val channelId = readChannelId(channelReply, crypto)
            stages.add(
                Stage(
                    "start the input channel",
                    channelId != null,
                    if (channelId != null) {
                        "The console opened the input channel with id $channelId."
                    } else {
                        "The channel response could not be read, so no channel id is known."
                    },
                ),
            )

            Handshake(stages, crypto, participant, channelId)
            Handshake(stages, crypto, participant, channelId)
        } catch (error: Exception) {
            stages.add(
                Stage(
                    "the session",
                    false,
                    "The session failed: ${error.message ?: error.javaClass.simpleName}.",
                ),
            )
            Handshake(stages, null, null, null)
        } finally {
            runCatching { socket.close() }
        }
    }


    /**
     * Send one message on the established channel.
     *
     * THIS IS WHAT MAKES THE CONSOLE CONTROLLABLE. Everything before it is handshake; this is the
     * path every button, trigger, stick and power command travels. Each payload is padded to a
     * block, encrypted with an IV derived from its own header, signed with the session key, and
     * sent as one packet.
     *
     * A sequence number that does not advance makes a console drop a session, so the counter is
     * shared across every message this session sends rather than reset per call.
     */
    suspend fun send(
        handshake: Handshake,
        messageType: Int,
        payloads: List<ByteArray>,
        describe: String,
    ): XboxConsole.Result = withContext(Dispatchers.IO) {
        val crypto = handshake.crypto
            ?: return@withContext XboxConsole.Result(
                false,
                "$describe needs the encrypted channel, and the handshake produced no keys.",
            )
        val participant = handshake.participantId
            ?: return@withContext XboxConsole.Result(
                false,
                "$describe needs a participant id, and the console issued none.",
            )
        val channel = handshake.channelId

        val socket = runCatching { DatagramSocket() }.getOrNull()
            ?: return@withContext XboxConsole.Result(false, "This phone could not open a UDP socket.")

        try {
            socket.soTimeout = timeoutMillis
            val target = InetAddress.getByName(host)

            for (payload in payloads) {
                val padded = SmartGlassMessage.pad(payload)
                val header = SmartGlassMessage.messageHeader(
                    type = SmartGlassMessage.TYPE_MESSAGE,
                    protectedLength = padded.size,
                    sequence = nextSequence(),
                    targetParticipant = participant,
                    sourceParticipant = participant,
                    flags = SmartGlassMessage.Flags(messageType = messageType),
                    channelId = channel ?: 0L,
                )
                val iv = crypto.messageIv(header)
                val encrypted = crypto.encryptWith(iv, padded)
                val packet = SmartGlassMessage.packet(
                    header = header,
                    protected = encrypted,
                    signature = crypto.sign(encrypted),
                )
                socket.send(DatagramPacket(packet, packet.size, target, SmartGlassProtocolPort))

                // A console applies the pad state it is given, so messages in a burst must not
                // arrive out of order. A short gap is cheaper than the packet loss.
                if (payloads.size > 1) Thread.sleep(30)
            }

            XboxConsole.Result(
                true,
                "$describe was sent to the console on channel ${channel ?: 0}: " +
                    "${payloads.size} message(s), AES-128-CBC with an HMAC-SHA-256 signature.",
            )
        } catch (error: Exception) {
            XboxConsole.Result(
                false,
                "$describe failed while sending: ${error.message ?: error.javaClass.simpleName}.",
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    /** Read the channel id out of a start-channel response. */
    private fun readChannelId(reply: ByteArray, crypto: SmartGlassCrypto): Long? = runCatching {
        if (reply.size <= SmartGlassMessage.MESSAGE_HEADER_SIZE) return null
        // The IV for this reply is derived from its own header, and its protected payload follows
        // the header with the signature after it.
        val iv = crypto.messageIv(reply.copyOfRange(0, 16))
        val protectedLength = ByteBuffer.wrap(reply, 2, 2).order(ByteOrder.BIG_ENDIAN)
            .short.toInt() and 0xFFFF
        val start = SmartGlassMessage.MESSAGE_HEADER_SIZE
        if (protectedLength <= 0 || start + protectedLength > reply.size) return null
        val protectedPayload = reply.copyOfRange(start, start + protectedLength)
        val plain = crypto.decryptWith(iv, protectedPayload) ?: return null
        // uint32 request id, then uint64 channel id, then uint32 result.
        if (plain.size < 16) return null
        ByteBuffer.wrap(plain, 4, 8).order(ByteOrder.BIG_ENDIAN).long
    }.getOrNull()

    /** A monotonic sequence number, shared across every message this session sends. */
    private fun nextSequence(): Int = synchronized(sequenceLock) {
        sequence += 1
        sequence
    }

    private val sequenceLock = Any()
    private var sequence = 2

    /** Read one datagram, or null on a timeout. */
    private fun receive(socket: DatagramSocket): ByteArray? = runCatching {
        val buffer = ByteArray(4096)
        val packet = DatagramPacket(buffer, buffer.size)
        socket.receive(packet)
        packet.data.copyOfRange(0, packet.length)
    }.getOrNull()

    private companion object {
        /** The SmartGlass port. */
        const val SmartGlassProtocolPort = 5050

        /**
         * The system input service UUID.
         *
         * The reference implementation's MessageTarget constant, as 16 raw bytes. A console only
         * opens the channels it is asked for, and the pad is read on this one.
         */
        /**
         * The SystemInput service GUID.
         *
         * Taken from the reference implementation after a wrong guess at a different UUID, which
         * is why it is written as its canonical text form and converted rather than as hex bytes
         * somebody typed.
         */
        val SERVICE_SYSTEM_INPUT: ByteArray = uuid("fa20b8ca-66fb-46e0-adb6-0b978a59d35f")

        /** A UUID's sixteen raw bytes, from its canonical text form. */
        fun uuid(text: String): ByteArray {
            val hex = text.replace("-", "")
            require(hex.length == 32) { "'$text' is not a UUID" }
            return ByteArray(16) { index ->
                hex.substring(index * 2, index * 2 + 2).toInt(16).toByte()
            }
        }
    }
}
