// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.smarthome

/**
 * Sonos control over its own UPnP services, as decisions rather than shell commands.
 *
 * WHY THIS EXISTS (issue 186, replacing the paths in issue 179). Every Sonos tool ran `sonos
 * <something>`, a Node CLI that Termux does not package, so all five could only ever fail. A Sonos
 * speaker is an ordinary UPnP device on port 1400: transport, volume and grouping are documented
 * SOAP services, and discovery is SSDP, which the app already does.
 *
 * WHAT IS DELIBERATELY NOT HERE. No sockets, no HTTP, no XML library. The envelopes are built as
 * strings and the tiny bits of the replies that matter are read from those strings, because the
 * decisions are what need proving and a full DOM would hide them. This is the same split that made
 * the Hue and robots rules provable on the JVM.
 *
 * THE ONE THING THAT IS EASY TO GET WRONG: every UPnP action is a POST of a SOAP envelope with the
 * action named in a `SOAPAction` header, and the action also appears inside the body. A request that
 * carries one without the other is refused with a 500 and no explanation, which is exactly the sort
 * of silent failure this batch exists to remove.
 */
object SonosApi {

    /** The port every Sonos device serves UPnP on. */
    const val PORT = 1400

    /** The UPnP service types actually used. */
    const val AV_TRANSPORT = "urn:schemas-upnp-org:service:AVTransport:1"
    const val RENDERING_CONTROL = "urn:schemas-upnp-org:service:RenderingControl:1"
    const val ZONE_GROUP_TOPOLOGY = "urn:schemas-upnp-org:service:ZoneGroupTopology:1"
    const val DEVICE_PROPERTIES = "urn:schemas-upnp-org:service:DeviceProperties:1"

    /**
     * One speaker, as the API addresses it.
     *
     * [name] is read from the device's own description rather than assumed, because grouping and
     * transport both take a name in their arguments and a guessed one changes the wrong room.
     */
    data class Speaker(val host: String, val name: String, val model: String = "") {
        val baseUrl: String get() = "http://$host:$PORT"
    }

    /** The path for one service's control endpoint. */
    fun controlPath(service: String): String = when (service) {
        AV_TRANSPORT -> "/MediaRenderer/AVTransport/Control"
        RENDERING_CONTROL -> "/MediaRenderer/RenderingControl/Control"
        ZONE_GROUP_TOPOLOGY -> "/ZoneGroupTopology/Control"
        DEVICE_PROPERTIES -> "/DeviceProperties/Control"
        else -> "/$service/Control"
    }

    /** The `SOAPAction` header value for an action on a service. */
    fun soapAction(service: String, action: String): String = "\"$service#$action\""

    /**
     * A SOAP envelope for an action.
     *
     * THE ACTION AND THE SERVICE APPEAR TWICE, once in the header and once inside the body, and
     * that duplication is the protocol rather than an accident. The header is what the server routes
     * on and the body is what it parses; a request carrying only one is refused with a bare 500.
     */
    fun envelope(service: String, action: String, arguments: Map<String, String>): String {
        val args = arguments.entries.joinToString("") { (key, value) ->
            "<$key>${escapeXml(value)}</$key>"
        }
        return """<?xml version="1.0" encoding="utf-8"?>""" +
            """<s:Envelope xmlns:s="http://schemas.xmlsoap.org/soap/envelope/"""" +
            """ s:encodingStyle="http://schemas.xmlsoap.org/soap/encoding/">""" +
            "<s:Body><u:$action xmlns:u=\"$service\">$args</u:$action></s:Body></s:Envelope>"
    }

    /** XML-escape a value, because a track title or speaker name can contain any of these. */
    fun escapeXml(value: String): String = value
        .replace("&", "&amp;")
        .replace("<", "&lt;")
        .replace(">", "&gt;")
        .replace("\"", "&quot;")
        .replace("'", "&apos;")

    // ---- transport ----

    /** The arguments every AVTransport action needs, because UPnP addresses one instance. */
    private val instanceZero = mapOf("InstanceID" to "0")

    fun play(): Pair<String, Map<String, String>> =
        AV_TRANSPORT to instanceZero + ("Speed" to "1")

    fun pause(): Pair<String, Map<String, String>> = AV_TRANSPORT to instanceZero

    fun stop(): Pair<String, Map<String, String>> = AV_TRANSPORT to instanceZero

    /** The action behind a tool's own verb, or null when the verb is not one this speaks. */
    fun transportAction(verb: String): Pair<String, String>? = when (verb.lowercase()) {
        "play" -> AV_TRANSPORT to "Play"
        "pause" -> AV_TRANSPORT to "Pause"
        "stop" -> AV_TRANSPORT to "Stop"
        else -> null
    }

    /** The arguments for one transport verb. */
    fun transportArguments(verb: String): Map<String, String> = when (verb.lowercase()) {
        "play" -> instanceZero + ("Speed" to "1")
        else -> instanceZero
    }

    fun setVolume(percent: Int): Pair<String, Map<String, String>> = RENDERING_CONTROL to
        mapOf(
            "InstanceID" to "0",
            "Channel" to "Master",
            // SONOS TAKES 0-100 HERE, unlike Hue's 0-254. Rescaling a volume request by habit would
            // make every speaker silent at what a person thought was half.
            "DesiredVolume" to percent.coerceIn(0, 100).toString(),
        )

    fun getVolume(): Pair<String, Map<String, String>> = RENDERING_CONTROL to
        mapOf("InstanceID" to "0", "Channel" to "Master")

    fun getTransportInfo(): Pair<String, Map<String, String>> = AV_TRANSPORT to
        mapOf("InstanceID" to "0")

    fun getPositionInfo(): Pair<String, Map<String, String>> = AV_TRANSPORT to
        mapOf("InstanceID" to "0")

    fun getZoneGroupState(): Pair<String, Map<String, String>> = ZONE_GROUP_TOPOLOGY to emptyMap()

    // ---- reading the replies ----

    /** The text of the first element with this name, or null. */
    fun elementText(xml: String, name: String): String? {
        val open = "<$name>"
        val start = xml.indexOf(open)
        if (start < 0) return null
        val end = xml.indexOf("</$name>", start)
        if (end < 0) return null
        return unescapeXml(xml.substring(start + open.length, end))
    }

    /** XML-unescape a value the server sent, in the order that cannot double-decode. */
    fun unescapeXml(value: String): String = value
        .replace("&lt;", "<")
        .replace("&gt;", ">")
        .replace("&quot;", "\"")
        .replace("&apos;", "'")
        // Ampersand LAST, so an escaped ampersand in the original cannot become a new entity.
        .replace("&amp;", "&")

    /**
     * The transport state, as a person would say it.
     *
     * A SONOS REPORTS `PLAYING`, `PAUSED_PLAYBACK`, `STOPPED` AND A FEW OTHERS, and the raw token is
     * an engineer's word rather than an answer. This maps the common ones and passes anything
     * unrecognised through unchanged rather than guessing, because a speaker in a state this does
     * not know about is worth reporting exactly.
     */
    fun describeTransportState(raw: String): String = when (raw.trim().uppercase()) {
        "PLAYING" -> "playing"
        "PAUSED_PLAYBACK", "PAUSED" -> "paused"
        "STOPPED" -> "stopped"
        "TRANSITIONING" -> "changing"
        "NO_MEDIA_PRESENT" -> "nothing queued"
        "" -> "unknown, because the speaker did not say"
        else -> raw.trim()
    }

    /**
     * A failure, in one sentence naming its cause.
     *
     * The distinction that matters: a speaker that did not answer is a network fact, and a speaker
     * that answered with a UPnP error is a refusal that names its own reason in the body. Reporting
     * both as "failed" is the habit this whole batch removes.
     */
    fun failureSentence(host: String, statusCode: Int?, body: String, threw: String? = null): String = when {
        threw != null && threw.contains("Connect", ignoreCase = true) ->
            "The Sonos speaker at $host could not be reached. Check that the phone is on the same " +
                "network as the speaker. ($threw)"
        threw != null ->
            "The request to the Sonos speaker at $host failed: $threw"
        statusCode == 401 || statusCode == 403 ->
            "The Sonos speaker at $host refused the request (HTTP $statusCode)."
        statusCode == 500 -> {
            val detail = elementText(body, "errorDescription")
                ?: elementText(body, "faultstring")
                ?: body.take(200)
            "The Sonos speaker at $host rejected the action. It said: $detail"
        }
        statusCode != null && statusCode !in 200..299 ->
            "The Sonos speaker at $host answered HTTP $statusCode: ${body.take(200)}"
        else ->
            "The Sonos speaker at $host answered something that could not be read: ${body.take(200)}"
    }

    /**
     * The speakers in a ZoneGroupState reply.
     *
     * THE REPLY IS ESCAPED XML INSIDE AN ELEMENT, which is the trap here: `ZoneGroupState` carries
     * `<ZoneGroupState>&lt;ZoneGroups&gt;...</ZoneGroupState>`, so the inner document has to be
     * unescaped before its `<ZoneGroupMember>` elements can be read. Reading the outer body for
     * member elements finds none, which looks like "no speakers" rather than "wrong level".
     */
    fun speakersInZoneGroupState(body: String): List<Speaker> {
        val inner = elementText(body, "ZoneGroupState") ?: return emptyList()
        val members = Regex("<ZoneGroupMember\\b([^>]*)>").findAll(inner)
        return members.mapNotNull { match ->
            val attributes = match.groupValues[1]
            val host = attributeValue(attributes, "Location")
                ?.let { Regex("""//([^:/]+)""").find(it)?.groupValues?.get(1) }
                ?: return@mapNotNull null
            val name = attributeValue(attributes, "ZoneName") ?: host
            Speaker(host = host, name = name)
        }.distinctBy { it.host }.toList()
    }

    /** One attribute out of a tag's attribute string. */
    fun attributeValue(attributes: String, name: String): String? {
        val match = Regex("""\b$name\s*=\s*"([^"]*)"""").find(attributes) ?: return null
        return unescapeXml(match.groupValues[1])
    }
}
