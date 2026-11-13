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

    /**
     * An Amazon Fire TV, identified from its own manufacturer and model fields.
     *
     * IT IS ITS OWN VENDOR BECAUSE IT IS NOT AN ANDROID TV. Issue #243: a Fire TV was
     * reported as ANDROID_TV because port 8009 was open, and 8009 is the DIAL
     * App-Launch endpoint that Fire TV, Chromecast, Android TV and Virgin boxes all
     * expose. The set's own document says `manufacturer: Amazon`, `modelName: AFTKM`,
     * which no Android TV ever does. Naming it correctly matters because the control
     * story is different: a Fire TV accepts DIAL app launches over plain HTTP now,
     * where an Android TV wants its own pairing protocol.
     */
    AMAZON_FIRE_TV,

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

    /**
     * Launch an app by its name.
     *
     * THIS IS THE FIRST OF THE THREE CAPABILITIES THAT TURN A REMOTE INTO REAL CONTROL. Pressing
     * buttons means navigating a menu by hand. Launching means saying "put Netflix on" and having
     * it happen, which is the thing a person actually wants.
     *
     * [app] is a common name a person would say, such as netflix, youtube, prime video or bbc
     * iplayer, and the adapter maps it to whatever identifier its protocol wants. An unknown name
     * is refused with the names the set does accept, because a failure that lists the alternatives
     * is a usable answer.
     */
    suspend fun launchApp(app: String): TvCommandOutcome =
        TvCommandOutcome.Failed(
            "${vendor.name} cannot launch apps over the network from this build.",
        )

    /**
     * Type text into the set, for a search box that is already focused.
     *
     * The second capability. A search box is where a person types "christmas film", and being able
     * to type it is the difference between driving the set and merely clicking around it.
     */
    suspend fun typeText(text: String): TvCommandOutcome =
        TvCommandOutcome.Failed(
            "${vendor.name} cannot accept text over the network from this build.",
        )

    /**
     * Push a stream onto the set.
     *
     * The third capability, and the one that makes "play this on the telly" real rather than a
     * sequence of menu presses. [url] is the media itself, and [title] is what the set shows while
     * it plays.
     */
    suspend fun castMedia(url: String, title: String? = null, mimeType: String? = null): TvCommandOutcome =
        TvCommandOutcome.Failed(
            "${vendor.name} cannot accept a pushed stream over the network from this build.",
        )
}

/**
 * The app names a person says, mapped per vendor.
 *
 * WHY A SHARED TABLE. "Netflix" is one word to a person and a different identifier on every
 * platform: a channel id on Roku, an app id on Samsung, a launch URI on LG, and a package name on
 * Android TV. One table here means every adapter answers to the same spoken names, so the model
 * never has to know which set it is talking to.
 */
object TvApps {

    /** The names a person uses, and what each platform calls them. */
    val spoken: List<String> = listOf(
        "netflix", "youtube", "prime video", "amazon", "bbc iplayer", "itvx", "channel 4",
        "disney plus", "spotify", "plex", "apple tv", "now", "britbox",
    )

    /** Fold a spoken name onto one token, so "Prime Video" and "prime video" are the same. */
    fun normalise(raw: String): String = raw.trim().lowercase()
        .replace("+", " plus")
        .replace("-", " ")
        .replace(Regex("\\s+"), " ")

    /** The Roku channel id for a spoken name, or null when Roku has no such channel. */
    fun rokuId(app: String): String? = when (normalise(app)) {
        "netflix" -> "12"
        "youtube" -> "837"
        "prime video", "amazon" -> "13"
        "bbc iplayer" -> "11636"
        "itvx" -> "59325"
        "channel 4" -> "15874"
        "disney plus" -> "291097"
        "spotify" -> "22297"
        "plex" -> "41417"
        "apple tv" -> "551012"
        "now" -> "51924"
        "britbox" -> "151908"
        else -> null
    }

    /** The Samsung app id for a spoken name, or null when Samsung has no such app. */
    fun samsungId(app: String): String? = when (normalise(app)) {
        "netflix" -> "11101200001"
        "youtube" -> "111299001912"
        "prime video", "amazon" -> "3201512006785"
        "bbc iplayer" -> "3201601007230"
        "itvx", "itv hub" -> "1212990000891"
        "channel 4" -> "111299002148"
        "disney plus" -> "3201901017640"
        "spotify" -> "3201606009684"
        "plex" -> "3201512006963"
        "apple tv" -> "3201807016597"
        "now" -> "3201706012478"
        else -> null
    }

    /**
     * The LG webOS app id for a spoken name.
     *
     * webOS wants a reverse-domain application id rather than a number, which is why this table
     * looks different from the others.
     */
    fun lgId(app: String): String? = when (normalise(app)) {
        "netflix" -> "netflix"
        "youtube" -> "youtube.leanback.v4"
        "prime video", "amazon" -> "amazon"
        "bbc iplayer" -> "bbc.iplayer"
        "itvx", "itv hub" -> "itvhub"
        "channel 4" -> "com.channel4.ott"
        "disney plus" -> "com.disney.disneyplus"
        "spotify" -> "spotify-app"
        "plex" -> "cdp-30"
        "apple tv" -> "com.apple.appletv"
        "now" -> "com.sky.nowtv"
        "britbox" -> "com.britbox.ott"
        else -> null
    }

    /** The Android TV package for a spoken name, used through a deep link. */
    fun androidTvPackage(app: String): String? = when (normalise(app)) {
        "netflix" -> "com.netflix.ninja"
        "youtube" -> "com.google.android.youtube.tv"
        "prime video", "amazon" -> "com.amazon.amazonvideo.livingroom"
        "bbc iplayer" -> "bbc.iplayer.android"
        "itvx", "itv hub" -> "air.ITVMobilePlayer"
        "channel 4" -> "com.channel4.ott"
        "disney plus" -> "com.disney.disneyplus"
        "spotify" -> "com.spotify.tv.android"
        "plex" -> "com.plexapp.android"
        "apple tv" -> "com.apple.atve.androidtv.appletv"
        else -> null
    }

    /** A sentence naming what a set does accept, for a refusal that is usable. */
    fun refusal(setName: String, vendor: TvVendor, spokenName: String): String =
        "$setName does not have an app called '$spokenName' that this build knows. " +
            "$vendor accepts these names: ${spoken.joinToString(", ")}."
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

    /**
     * The piece of device state this button's effect would be visible in, or null when this
     * build cannot observe it.
     *
     * WHY A BUTTON HAS TO SAY THIS (#216). `tvPress` reads the set back after a press, and it
     * used to put the POWER state in its `observed` field for every button. A volume press
     * therefore came back reporting `PowerOn`, which reads as confirmation that the volume
     * changed. It confirmed nothing: the field was answering a question the caller had not
     * asked, and a caller believes a field that is filled in.
     *
     * Only three buttons have an effect this build can actually read back. Everything else
     * returns null, and null is the honest answer: the press was sent and its outcome was not
     * observed.
     */
    fun observableState(): TvStateKey? = when (this) {
        POWER, POWER_ON, POWER_OFF -> TvStateKey.POWER
        else -> null
    }

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
