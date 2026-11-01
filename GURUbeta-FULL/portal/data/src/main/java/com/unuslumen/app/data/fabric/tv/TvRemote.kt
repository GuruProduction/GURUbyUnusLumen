// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

/**
 * The network television contract: one remote, many vendor protocols.
 * Issue #187's real requirement, stated by Steven on 2026-11-01: the phone must
 * control a television "like a universal remote app", over the network, for every
 * make rather than one.
 *
 * WHY THIS IS THE RIGHT SHAPE AND INFRARED WAS NOT. A universal remote app does not
 * speak one protocol; it speaks the vendor's own network remote, per make. Roku
 * answers plain HTTP on 8060. Samsung runs a WebSocket on 8001 that asks for a token.
 * LG runs a WebSocket on 3000 that asks the person to accept. Vizio wants a paired
 * auth token. Android TV and Google TV use their own pairing protocol on 6466. Philips
 * joins a local API through a pairing request. There is no universal protocol, so
 * universality has to live in the adapter layer: one [TvRemote] contract, one
 * implementation per vendor, and a discovery step that works out which vendor a set is
 * so a person never has to know.
 *
 * WHAT EVERY ADAPTER MUST AND MUST NOT DO, carried from the fabric's own rules:
 *
 *  - It reports the truth about what it did. A command it could not send is a failure
 *    with a reason, never a success.
 *  - It never claims a state it has not read. A television with no query API is one
 *    whose state is unknown, not one that is "probably on".
 *  - It says what a person has to do when pairing is needed, naming the prompt they
 *    will see on the screen rather than a gesture at one.
 */

/** Which vendor's protocol a set speaks, discovered rather than asked for. */
enum class TvVendor {
    ROKU,
    SAMSUNG,
    LG,
    VIZIO,
    ANDROID_TV,
    PHILIPS,
    /** Reachable on the network and identifiable as something, but no adapter exists. */
    UNKNOWN,
}

/** A television found on the local network. */
data class DiscoveredTv(
    val vendor: TvVendor,
    /** The set's own name, when it reports one. */
    val name: String,
    val host: String,
    /** The port the remote protocol answers on. */
    val port: Int,
    /** What answered, in one line, so a person can check it is their telly. */
    val evidence: String,
    /** Whether the set needs a pairing step before it accepts commands. */
    val needsPairing: Boolean = false,
) {
    /** Stable id for the fabric store, so a room assignment survives a re-scan. */
    val id: String get() = "tv:${vendor.name.lowercase()}:$host"
}

/** The result of one command attempt. */
sealed interface TvCommandOutcome {
    /** The set accepted the command. */
    data class Sent(val note: String) : TvCommandOutcome

    /**
     * The set refused, or could not be reached, with a reason a person can act on.
     *
     * A pairing prompt is the common case and is stated as what will appear on the
     * television, because that is what the person has to look at.
     */
    data class Failed(val reason: String, val needsPairing: Boolean = false) : TvCommandOutcome
}

/**
 * One vendor's remote protocol.
 *
 * Every implementation is constructed with the address it discovered, so an adapter
 * never holds state about which set it is talking to beyond its own connection.
 */
interface TvRemote {

    val vendor: TvVendor

    /** Say whether this set is still reachable and what it is. */
    suspend fun probe(): String?

    /**
     * Send one named button.
     *
     * [button] is a vendor-neutral token from [TvButton], so the fabric's capability
     * vocabulary decides what is pressed and the adapter only decides how to say it.
     */
    suspend fun press(button: TvButton): TvCommandOutcome

    /**
     * Read the set's current state where the protocol allows it.
     *
     * Null means the protocol cannot report state, which is a real answer and not a
     * failure: a set that only accepts commands has an unknown state, and saying so is
     * the honest report.
     */
    suspend fun readState(): Map<TvStateKey, String>? = null

    /** Set an absolute value where the protocol supports it, such as volume 0..100. */
    suspend fun setLevel(key: TvStateKey, value: Int): TvCommandOutcome =
        TvCommandOutcome.Failed(
            "${vendor.name} sets ${key.name.lowercase()} by stepping only, so it cannot be set " +
                "to an exact value.",
        )
}

/** The buttons the fabric can ask for, vendor-neutral. */
enum class TvButton {
    POWER,
    POWER_ON,
    POWER_OFF,
    VOLUME_UP,
    VOLUME_DOWN,
    MUTE,
    CHANNEL_UP,
    CHANNEL_DOWN,
    UP,
    DOWN,
    LEFT,
    RIGHT,
    OK,
    BACK,
    HOME,
    MENU,
    PLAY,
    PAUSE,
    STOP,
    REWIND,
    FAST_FORWARD,
    INPUT,
    ;

    companion object {
        /** Read a spoken or written button name. */
        fun fromRaw(raw: String?): TvButton? {
            val key = raw?.trim()?.lowercase()
                ?.replace("_", " ")?.replace("-", " ")?.replace(".", " ") ?: return null
            val normalised = key.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
            return when (normalised) {
                "power", "toggle power", "standby" -> POWER
                "power on", "on", "turn on" -> POWER_ON
                "power off", "off", "turn off" -> POWER_OFF
                "volume up", "vol up", "vol+", "louder", "turn it up", "turn up" -> VOLUME_UP
                "volume down", "vol down", "vol-", "quieter", "turn it down", "turn down" -> VOLUME_DOWN
                "mute", "unmute", "silence" -> MUTE
                "channel up", "ch up", "ch+", "next channel" -> CHANNEL_UP
                "channel down", "ch down", "ch-", "previous channel" -> CHANNEL_DOWN
                "up" -> UP
                "down" -> DOWN
                "left" -> LEFT
                "right" -> RIGHT
                "ok", "select", "enter", "confirm" -> OK
                "back", "return", "exit" -> BACK
                "home" -> HOME
                "menu", "settings" -> MENU
                "play" -> PLAY
                "pause" -> PAUSE
                "stop" -> STOP
                "rewind" -> REWIND
                "fast forward", "forward", "ff" -> FAST_FORWARD
                "input", "source", "hdmi" -> INPUT
                else -> null
            }
        }
    }
}

/** A readable value a set can report. */
enum class TvStateKey {
    POWER,
    VOLUME,
    MUTED,
    APP,
    INPUT,
}
