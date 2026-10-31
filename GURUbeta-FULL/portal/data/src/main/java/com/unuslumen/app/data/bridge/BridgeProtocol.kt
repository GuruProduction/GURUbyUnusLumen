// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.bridge

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * The CLI bridge wire protocol — issue #176.
 *
 * Deliberately tiny: one JSON object per line over a single socket. Every frame
 * carries a `type`, and there are four of them, because a chat channel between
 * two agents needs hello, a message, a status question and a command.
 *
 * WHY LINE-DELIMITED JSON. Both ends have to be written once, in different
 * languages, and stay in step for years. A length-prefixed binary framing is
 * faster and needs a shared struct definition to change together; a JSON line is
 * readable with `nc`, debuggable by eye, and a new optional field is backwards
 * compatible without either side being redeployed.
 *
 * THIS OBJECT IS PURE. No sockets, no Android, no clock. Parsing and
 * serialising are the parts that must not be wrong, so they are unit tested
 * without a device, and the transport layers above simply move strings.
 */

const val PROTOCOL_VERSION = 1

/** The most a single frame may be, to stop one peer exhausting the other's heap. */
const val MAX_FRAME_CHARS = 64 * 1024

@Serializable
sealed interface BridgeFrame {
    @Serializable @SerialName("hello")
    data class Hello(
        /** Who is connecting: "lux", "guru-cli", any operator-chosen name. */
        val agent: String,
        val version: Int = PROTOCOL_VERSION,
        /** The shared secret from pairing. Never logged. */
        val token: String,
    ) : BridgeFrame

    @Serializable @SerialName("welcome")
    data class Welcome(
        /** The GURU instance name, so the CLI can show what it reached. */
        val guru: String,
        val room: String,
    ) : BridgeFrame

    @Serializable @SerialName("message")
    data class Message(
        val text: String,
        /** Who wrote it. "guru" or the agent name from hello. */
        val from: String,
        /** Epoch millis, set by the sender. */
        val at: Long,
    ) : BridgeFrame

    @Serializable @SerialName("status")
    data class Status(
        /** Present on a request (asking); absent on the reply. */
        val request: Boolean = false,
        val inflight: List<String> = emptyList(),
        val queued: List<String> = emptyList(),
        val blocked: List<String> = emptyList(),
    ) : BridgeFrame

    @Serializable @SerialName("ack")
    data class Ack(val id: String) : BridgeFrame

    /**
     * A refusal or a failure, carrying why.
     *
     * Exists so a peer never has to infer failure from silence. `code` is stable
     * enough for a client to branch on; `reason` is for a human.
     */
    @Serializable @SerialName("error")
    data class Error(val code: String, val reason: String) : BridgeFrame

    companion object {
        const val CODE_BAD_TOKEN = "bad_token"
        const val CODE_BAD_FRAME = "bad_frame"
        const val CODE_VERSION = "version_mismatch"
        const val CODE_TOO_LONG = "frame_too_long"
    }
}

/**
 * Encode a frame to one line, with the trailing newline the protocol expects.
 *
 * `encodeDefaults` is on because a Hello without an explicit version would
 * silently fail the version check on the other side.
 */
fun encodeFrame(frame: BridgeFrame): String {
    val json = Json {
        encodeDefaults = true
        ignoreUnknownKeys = true
        classDiscriminator = "type"
    }
    return json.encodeToString(BridgeFrame.serializer(), frame) + "\n"
}

/**
 * The result of reading one line from a peer.
 *
 * Deliberately a sealed result rather than a nullable frame: "the peer sent
 * something I cannot read" is a case that has to be reported to the peer, and a
 * null would make it indistinguishable from "nothing arrived yet".
 */
sealed interface ParseResult {
    data class Ok(val frame: BridgeFrame) : ParseResult
    data class Bad(val code: String, val reason: String) : ParseResult
}

/** Decode one line. Trailing whitespace and a stray newline are tolerated. */
fun parseFrame(line: String): ParseResult {
    val trimmed = line.trim()
    if (trimmed.isEmpty()) return ParseResult.Bad(BridgeFrame.CODE_BAD_FRAME, "empty frame")
    if (trimmed.length > MAX_FRAME_CHARS) {
        return ParseResult.Bad(BridgeFrame.CODE_TOO_LONG, "frame exceeds $MAX_FRAME_CHARS characters")
    }
    return try {
        val json = Json {
            ignoreUnknownKeys = true
            classDiscriminator = "type"
            isLenient = false
        }
        ParseResult.Ok(json.decodeFromString(BridgeFrame.serializer(), trimmed))
    } catch (e: Exception) {
        ParseResult.Bad(BridgeFrame.CODE_BAD_FRAME, "could not parse frame: ${e.message?.take(120)}")
    }
}

/**
 * Whether a Hello may proceed.
 *
 * Three checks in the order that matters: the frame must be a hello at all, the
 * version must match this build, and then the token. Version before token so a
 * mismatched client is told to upgrade rather than told its secret is wrong,
 * which would send a person hunting for the wrong problem.
 *
 * The comparison is constant-time per character, because a shared secret checked
 * with `==` leaks its prefix length through timing, and this socket is reachable
 * by anyone holding the onion address.
 */
fun authoriseHello(frame: BridgeFrame, expectedToken: String): BridgeFrame.Error? {
    if (frame !is BridgeFrame.Hello) {
        return BridgeFrame.Error(BridgeFrame.CODE_BAD_FRAME, "first frame must be hello")
    }
    if (frame.version != PROTOCOL_VERSION) {
        return BridgeFrame.Error(
            BridgeFrame.CODE_VERSION,
            "peer speaks version ${frame.version}, this build speaks $PROTOCOL_VERSION",
        )
    }
    if (!constantTimeEquals(frame.token, expectedToken)) {
        return BridgeFrame.Error(BridgeFrame.CODE_BAD_TOKEN, "token rejected")
    }
    return null
}

/**
 * Length-safe, prefix-safe token comparison.
 *
 * Iterates the whole expected string regardless of where a mismatch is found, so
 * the time taken does not reveal how many leading characters were correct.
 */
fun constantTimeEquals(a: String, b: String): Boolean {
    if (a.length != b.length) return false
    var diff = 0
    for (i in a.indices) {
        diff = diff or (a[i].code xor b[i].code)
    }
    return diff == 0
}

/**
 * Mask a token for any log line.
 *
 * Nothing about this channel may appear in a log that leaves the device, and the
 * simplest way to guarantee that is to never format a whole token anywhere. If a
 * caller needs to distinguish two tokens, four characters are enough.
 */
fun maskToken(token: String): String =
    if (token.length <= 8) "***" else "${token.take(4)}...${token.takeLast(2)}"
