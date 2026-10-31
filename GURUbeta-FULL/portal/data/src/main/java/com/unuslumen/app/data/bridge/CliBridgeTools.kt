// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.bridge

import kotlinx.serialization.Serializable

/**
 * The tools GURU uses to talk on the bridge.
 *
 * Kept next to the service rather than in the giant tool definitions file,
 * because the whole capability is one feature and splitting its definition from
 * its behaviour is how a description and a tool drift apart — the fault issues 40
 * and 44 were filed about.
 */
object CliBridgeTools {
    const val BRIDGE_STATUS = "cliBridgeStatus"
    const val BRIDGE_SEND = "cliBridgeSend"
    const val BRIDGE_TRANSCRIPT = "cliBridgeTranscript"

    val names = setOf(BRIDGE_STATUS, BRIDGE_SEND, BRIDGE_TRANSCRIPT)
}

@Serializable
data class BridgeStatusResult(
    val listening: Boolean,
    val lanAddress: String?,
    val lanPort: Int,
    val onionAddress: String?,
    /** Why the onion was refused, when it was. Null means it published. */
    val onionError: String?,
    val connectedAgents: List<String>,
    val paired: Boolean,
    /** What to tell a person, in one line. */
    val summary: String,
)

@Serializable
data class BridgeSendResult(
    val success: Boolean,
    /** How many connected agents received it. Zero is a real answer, not an error. */
    val delivered: Int,
    val error: String?,
)

@Serializable
data class BridgeTranscriptResult(
    val messages: List<BridgeMessageView>,
    val count: Int,
)

@Serializable
data class BridgeMessageView(val from: String, val text: String, val at: Long)

/**
 * Build the one-line summary a model should read instead of inferring state from
 * booleans.
 *
 * Issue 176's acceptance asks that the room distinguish what is reachable, and
 * the same rule applies to a tool: a caller must not have to work out from three
 * fields whether it can send.
 */
fun summariseBridge(state: CliBridgeService.BridgeState): String {
    if (!state.listening) {
        return if (state.paired) {
            "Bridge is paired but not listening. Start it to accept a CLI."
        } else {
            "Bridge has not been paired yet. Start it to generate a secret."
        }
    }
    val reach = mutableListOf<String>()
    if (state.lanAddress != null) reach.add("LAN ${state.lanAddress}:${state.lanPort}")
    state.onionAddress?.let { reach.add("onion ${it}") }
    val where = if (reach.isEmpty()) "no reachable address" else reach.joinToString(" and ")
    val who = when (state.connectedAgents.size) {
        0 -> "no agents connected"
        1 -> "1 agent connected (${state.connectedAgents.first()})"
        else -> "${state.connectedAgents.size} agents connected"
    }
    val onionNote = state.onionError?.let { ". Onion unavailable: $it" } ?: ""
    return "Bridge listening on $where, $who$onionNote"
}
