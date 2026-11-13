// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.smarthome

/**
 * The Hue Bridge's local REST API, as decisions rather than as shell commands.
 *
 * WHY THIS EXISTS (issue 179). Every Hue tool in this app shelled out to `openhue`, a Go binary
 * that Termux does not package and that was never in the APK. So the commands could only ever fail,
 * and they failed with a message telling a person to install something that does not exist. The
 * ticket's own summary is blunt and correct: the entire smart home capability was dead, and it had
 * always been dead.
 *
 * The bridge is an ordinary JSON HTTP server on the local network. Its API needs no binary and no
 * library, which means the whole capability reduces to URLs, request bodies and the decisions about
 * what a response means. Those decisions live here, pure, so they are provable on the JVM; the
 * transport lives in the executor.
 *
 * WHAT IS DELIBERATELY NOT HERE. No sockets, no HTTP client, no coroutines. This file decides, the
 * executor does. That split is what lets the entire request shape be tested in milliseconds, and it
 * is the same split that made the robots and pacing rules provable in the web layer.
 */
object HueApi {

    /**
     * Discovery, per the bridge's own documented endpoint.
     *
     * The cloud service answers with the bridge's LAN address, which is a convenience rather than a
     * requirement: `mdnsDiscover` already finds `_hue._tcp` bridges on the network, so a phone with
     * no internet access can still pair. Both routes reach [Bridge] and the caller picks.
     */
    const val DISCOVERY_URL = "https://discovery.meethue.com"

    /** The local API prefix, after the bridge address. */
    const val API_ROOT = "/api"

    /**
     * One bridge, as the API addresses it.
     *
     * [applicationKey] is empty until pairing. A bridge with no key can still be discovered and can
     * still be asked to pair, and that is a different state from a bridge that is not there, so the
     * two are kept apart by [hasKey].
     */
    data class Bridge(
        val host: String,
        val applicationKey: String = "",
        /** The bridge's own advertised name, when discovery returned one. */
        val name: String = "",
    ) {
        val hasKey: Boolean get() = applicationKey.isNotBlank()

        /** The path for one API resource, e.g. `lights` or `lights/3/state`. */
        fun path(resource: String): String =
            "$API_ROOT/${applicationKey.trim('/')}/${resource.trim('/')}"
    }

    /**
     * The pairing request.
     *
     * THE BRIDGE ANSWERS THIS ONE WITH AN ERROR UNTIL ITS BUTTON IS PRESSED, and that is the
     * documented handshake rather than a fault. Its response is
     * `[{"error":{"type":101,"address":"","description":"link button not pressed"}}]`.
     *
     * So "link button not pressed" is a WAITING state and must not be reported as a failure: a
     * person reading "pairing failed" would look for a fault in the app when the app is working
     * exactly as designed and the next move is theirs.
     */
    val PAIRING_BODY: String = """{"devicetype":"guru#phone"}"""

    /** The error type the bridge uses for "press the button and try again". */
    const val ERROR_LINK_BUTTON_NOT_PRESSED = 101

    /** The error type for a key the bridge does not recognise, which means re-pairing. */
    const val ERROR_UNAUTHORIZED_USER = 1

    enum class PairingState {
        /** The bridge issued a key. */
        PAIRED,
        /** The bridge is waiting for its physical button. The next move is the person's. */
        AWAITING_BUTTON,
        /** The bridge answered something this parser does not recognise. */
        UNEXPECTED,
    }

    data class PairingOutcome(
        val state: PairingState,
        /** The application key when [state] is PAIRED, else null. */
        val applicationKey: String?,
        /** One sentence a person can act on. Always populated. */
        val sentence: String,
    )

    /**
     * Read the bridge's answer to a pairing request.
     *
     * The response is a JSON ARRAY whose single element is either `success` with a `username` or
     * `error` with a `type`. Both shapes are handled, and anything else is UNEXPECTED rather than
     * being forced into one of them.
     */
    fun readPairingResponse(body: String): PairingOutcome {
        val element = firstJsonObject(body)
            ?: return PairingOutcome(
                PairingState.UNEXPECTED,
                null,
                "The bridge answered something that is not a pairing response, so pairing did not " +
                    "complete. Its reply was: ${body.take(200)}",
            )

        val success = element["success"]
        if (success != null) {
            val key = extractQuoted(success, "username")
            return if (key != null) {
                PairingOutcome(
                    PairingState.PAIRED,
                    key,
                    "The bridge issued an application key, so GURU can now control these lights.",
                )
            } else {
                PairingOutcome(
                    PairingState.UNEXPECTED,
                    null,
                    "The bridge reported success but sent no application key, so nothing can be " +
                        "controlled yet. Its reply was: ${body.take(200)}",
                )
            }
        }

        val error = element["error"]
        if (error != null) {
            val type = extractNumber(error, "type")
            return when (type) {
                ERROR_LINK_BUTTON_NOT_PRESSED -> PairingOutcome(
                    PairingState.AWAITING_BUTTON,
                    null,
                    "The bridge is waiting for its button to be pressed. Press the round button on " +
                        "the bridge, then ask again within 30 seconds.",
                )
                else -> PairingOutcome(
                    PairingState.UNEXPECTED,
                    null,
                    "The bridge refused pairing with error type ${type ?: "unknown"}: " +
                        (extractQuoted(error, "description") ?: body.take(200)),
                )
            }
        }

        return PairingOutcome(
            PairingState.UNEXPECTED,
            null,
            "The bridge's pairing reply named neither a success nor an error, so pairing did not " +
                "complete. Its reply was: ${body.take(200)}",
        )
    }

    /** The request to read every light. */
    fun lightsPath(bridge: Bridge): String = bridge.path("lights")

    /** The request to read every room, which the API calls groups. */
    fun roomsPath(bridge: Bridge): String = bridge.path("groups")

    /** The request to read every scene. */
    fun scenesPath(bridge: Bridge): String = bridge.path("scenes")

    /** The request to change one light's state. */
    fun lightStatePath(bridge: Bridge, lightId: String): String = bridge.path("lights/$lightId/state")

    /** The request to apply an action to a whole room. */
    fun roomActionPath(bridge: Bridge, groupId: String): String = bridge.path("groups/$groupId/action")

    /**
     * A light state body, from the fields the tool accepts.
     *
     * BRIGHTNESS IS RESCALED, and this is the one piece of arithmetic that is easy to get wrong.
     * Hue's `bri` runs 0-254 over the whole range, so a person asking for 50 percent means 127, not
     * 50. The tool's own parameter is 0-100 because that is what a person says.
     *
     * ONLY THE FIELDS GIVEN ARE SENT. A `PUT` that omits `on` leaves the light as it is, which is
     * what a caller asking only for a brightness change expects. Sending every field would silently
     * turn a light on when the caller only wanted to dim one that was already on.
     */
    fun lightStateBody(
        on: Boolean? = null,
        brightnessPercent: Int? = null,
        temperatureMired: Int? = null,
    ): String {
        val fields = mutableListOf<String>()
        on?.let { fields.add("\"on\":$it") }
        brightnessPercent?.let {
            fields.add("\"bri\":${percentToHueBrightness(it)}")
        }
        temperatureMired?.let { fields.add("\"ct\":${it.coerceIn(MIN_MIRED, MAX_MIRED)}") }
        return "{${fields.joinToString(",")}}"
    }

    /**
     * Percent to Hue's 0-254 scale.
     *
     * The division is integer and the rounding is deliberate: a 1 percent request must be 2 or 3
     * rather than 0, because 0 would turn the light OFF when the caller asked for the dimmest
     * setting. `coerceIn` on the way in means a caller sending 150, or -5, gets a usable value
     * rather than a body the bridge rejects.
     */
    fun percentToHueBrightness(percent: Int): Int {
        val clamped = percent.coerceIn(0, 100)
        if (clamped == 0) return 0
        // Round to nearest so 50 becomes 127 rather than 126, which is what a person expects.
        return ((clamped * 254) + 50) / 100
    }

    /** The colour temperature range the bulbs accept, in mireds. */
    const val MIN_MIRED = 153
    const val MAX_MIRED = 500

    /**
     * Whether a bridge response means the key is no longer valid.
     *
     * A key can be revoked from the Hue app, and the API then answers every request with error type
     * 1. That is not a network fault and not an empty light list: it is a re-pairing, and reporting
     * it as "no lights found" would send a person looking for their bulbs.
     */
    fun needsRepairing(body: String): Boolean =
        body.contains("\"type\":$ERROR_UNAUTHORIZED_USER") ||
            body.contains("\"type\": $ERROR_UNAUTHORIZED_USER")

    /**
     * What a failed request means, in one sentence.
     *
     * Kept apart by cause because the fixes differ: an unreachable bridge is a network problem, a
     * revoked key is a re-pairing, and an HTTP error is the bridge refusing for its own reason.
     */
    fun failureSentence(host: String, statusCode: Int?, body: String, threw: String? = null): String = when {
        threw != null && threw.contains("Connect", ignoreCase = true) ->
            "The Hue Bridge at $host could not be reached. Check that the phone is on the same " +
                "network as the bridge. ($threw)"
        threw != null ->
            "The request to the Hue Bridge at $host failed: $threw"
        statusCode == 401 || statusCode == 403 || needsRepairing(body) ->
            "The Hue Bridge at $host refused the application key, so it has to be paired again. " +
                "Press the bridge's button and ask to pair."
        statusCode != null && statusCode !in 200..299 ->
            "The Hue Bridge at $host answered HTTP $statusCode: ${body.take(200)}"
        else ->
            "The Hue Bridge at $host answered something that could not be read: ${body.take(200)}"
    }

    // ---- the smallest JSON reading that answers these questions ----
    //
    // A full parser is not needed and the app already carries one elsewhere, but this object must be
    // Android-free and dependency-free so the rules stay provable in a plain JVM test. These three
    // helpers read exactly the shapes the bridge sends and nothing more.

    /** The first `{...}` object in a body, or null when there is none. */
    fun firstJsonObject(body: String): Map<String, String>? {
        val start = body.indexOf('{')
        if (start < 0) return null
        val end = matchingBrace(body, start) ?: return null
        return splitTopLevel(body.substring(start + 1, end))
    }

    private fun matchingBrace(text: String, openIndex: Int): Int? {
        var depth = 0
        var inString = false
        var escaped = false
        for (i in openIndex until text.length) {
            val ch = text[i]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' -> depth++
                ch == '}' -> {
                    depth--
                    if (depth == 0) return i
                }
            }
        }
        return null
    }

    /** Split one object level on commas, respecting nested objects and strings. */
    private fun splitTopLevel(inner: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var depth = 0
        var inString = false
        var escaped = false
        var start = 0
        fun take(endExclusive: Int) {
            val segment = inner.substring(start, endExclusive).trim()
            start = endExclusive + 1
            val colon = segment.indexOf(':')
            if (colon <= 0) return
            val key = segment.substring(0, colon).trim().trim('"')
            result[key] = segment.substring(colon + 1).trim()
        }
        for (i in inner.indices) {
            val ch = inner[i]
            when {
                escaped -> escaped = false
                ch == '\\' && inString -> escaped = true
                ch == '"' -> inString = !inString
                inString -> Unit
                ch == '{' || ch == '[' -> depth++
                ch == '}' || ch == ']' -> depth--
                ch == ',' && depth == 0 -> take(i)
            }
        }
        if (start <= inner.length) take(inner.length)
        return result
    }

    /** The first numeric value of a field name inside a raw JSON fragment, or null. */
    fun extractNumber(fragment: String, field: String): Int? {
        val marker = "\"$field\""
        val at = fragment.indexOf(marker)
        if (at < 0) return null
        val colon = fragment.indexOf(':', at + marker.length)
        if (colon < 0) return null
        val tail = fragment.substring(colon + 1).trimStart()
        val digits = tail.takeWhile { it.isDigit() || it == '-' }
        return digits.toIntOrNull()
    }

    /** The first quoted value of a field name inside a raw JSON fragment, or null. */
    fun extractQuoted(fragment: String, field: String): String? {
        val marker = "\"$field\""
        val at = fragment.indexOf(marker)
        if (at < 0) return null
        val colon = fragment.indexOf(':', at + marker.length)
        if (colon < 0) return null
        val quote = fragment.indexOf('"', colon + 1)
        if (quote < 0) return null
        val sb = StringBuilder()
        var i = quote + 1
        while (i < fragment.length) {
            val ch = fragment[i]
            if (ch == '\\' && i + 1 < fragment.length) {
                sb.append(fragment[i + 1])
                i += 2
                continue
            }
            if (ch == '"') return sb.toString()
            sb.append(ch)
            i++
        }
        return null
    }

    /**
     * The ids and names in a `lights`-shaped response, which is `{"1":{"name":"..."}, ...}`.
     *
     * IDS COME FROM THE KEYS, not from a field, because that is how the bridge addresses a light in
     * every subsequent request. Inventing an id from an index would produce a call that changes the
     * wrong bulb.
     */
    data class NamedEntry(val id: String, val name: String)

    fun entriesIn(body: String): List<NamedEntry> {
        /*
         * ONLY TOP-LEVEL KEYS ARE ENTRIES, and that is a defect the test caught.
         *
         * The first version treated any key whose value was an object as a light, so the nested
         * `"state":{...}` inside each light came back as an extra entry named after whichever
         * `name` field it contained. Against a real bridge that is a phantom light whose id is the
         * word "state", and a later call on that id changes nothing.
         *
         * The bridge puts its light, group and scene ids at the TOP LEVEL of the response and
         * nothing else there, so this walks exactly that level: read a key, read the object it
         * points at, take that object's `name`, skip to after the object. No recursion is needed
         * and none is done, which is why a nested `name` cannot be mistaken for an entry's.
         */
        val result = mutableListOf<NamedEntry>()
        var i = skipSpace(body, 0)
        if (i >= body.length || body[i] != '{') return emptyList()
        i++
        while (i < body.length) {
            i = skipSpace(body, i)
            if (i >= body.length || body[i] == '}') break
            if (body[i] == ',') { i++; continue }

            val (key, afterKey) = readString(body, i) ?: break
            var j = skipSpace(body, afterKey)
            if (j >= body.length || body[j] != ':') break
            j = skipSpace(body, j + 1)

            if (j < body.length && body[j] == '{') {
                val end = matchingBrace(body, j) ?: break
                val value = body.substring(j, end + 1)
                extractQuoted(value, "name")?.let { result.add(NamedEntry(key, it)) }
                i = end + 1
            } else {
                // A non-object value at the top level is not an id the bridge addresses, so it is
                // skipped rather than guessed at.
                i = skipValue(body, j)
            }
        }
        // Duplicate keys cannot occur in a real response, but a malformed one must not produce a
        // list a caller acts on twice.
        return result.distinctBy { it.id }
    }

    private fun skipSpace(text: String, from: Int): Int {
        var i = from
        while (i < text.length && text[i].isWhitespace()) i++
        return i
    }

    /** Reads a JSON string starting at the opening quote, returning its text and the index after it. */
    private fun readString(text: String, at: Int): Pair<String, Int>? {
        if (at >= text.length || text[at] != '"') return null
        val sb = StringBuilder()
        var i = at + 1
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                sb.append(text[i + 1]); i += 2; continue
            }
            if (ch == '"') return sb.toString() to (i + 1)
            sb.append(ch)
            i++
        }
        return null
    }

    /** Skips one non-object value, so the top-level walk can continue past it. */
    private fun skipValue(text: String, at: Int): Int {
        var i = at
        if (i < text.length && text[i] == '"') return readString(text, i)?.second ?: text.length
        while (i < text.length && text[i] != ',' && text[i] != '}') i++
        return i
    }
}
