// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric

/**
 * Home Assistant MQTT discovery messages, read into fabric devices.
 *
 * WHY THIS IS THE PRIMARY MQTT PATH (issue 185). MQTT has no device model, only topics and payloads.
 * Its own words: "many devices publish Home Assistant MQTT discovery messages ... with a JSON payload
 * describing the device. Parsing those gives automatic device creation with class, capabilities and
 * state topics, exactly like HA. This is the better path and should be primary."
 *
 * It is also the cheapest route to a real capability: every ESPHome, Tasmota and Shelly device speaks
 * MQTT, as does Zigbee2MQTT, which alone covers hundreds of Zigbee devices.
 *
 * WHY IT IS PURE. A discovery payload is a JSON document with a published shape, and reading it
 * wrongly produces a device with the wrong class or a command topic that does not exist, silently.
 * Keeping the parse out of the client means the whole grammar is provable without a broker.
 *
 * A NOTE ON THE JSON. The app carries kotlinx.serialization, but a discovery payload's fields are
 * almost all optional and its shape varies by component, so this reads through a small explicit
 * reader rather than a data class with twenty nullable fields. That keeps the rules visible.
 */
object MqttDiscovery {

    /** The topic prefix Home Assistant's own discovery uses. */
    const val DISCOVERY_PREFIX = "homeassistant"

    /**
     * Whether a topic is a discovery topic, and what it names.
     *
     * THE SHAPE IS `homeassistant/<component>/<node>/<object>/config`, and the component is the
     * entity domain — `light`, `switch`, `sensor`. A topic that does not carry exactly that shape is
     * not a discovery message, and guessing at a near miss would create a device from something
     * that was not describing one.
     */
    data class DiscoveryTopic(
        val component: String,
        val nodeId: String,
        val objectId: String,
    ) {
        /** The unique id a device keeps, derived the way HA derives it. */
        val suggestedId: String get() = "$nodeId.$objectId"
    }

    fun parseTopic(topic: String): DiscoveryTopic? {
        val parts = topic.split('/')
        if (parts.size != 5) return null
        if (!parts[0].equals(DISCOVERY_PREFIX, ignoreCase = true)) return null
        if (!parts[4].equals("config", ignoreCase = true)) return null
        if (parts[1].isBlank() || parts[2].isBlank() || parts[3].isBlank()) return null
        return DiscoveryTopic(component = parts[1], nodeId = parts[2], objectId = parts[3])
    }

    /**
     * One device described by a discovery payload.
     *
     * [stateTopic] and [commandTopic] are BOTH nullable because a payload can describe a
     * read-only sensor with no command topic, and a device with neither is not usable and is
     * reported as such rather than created empty.
     */
    data class Discovered(
        val component: String,
        val suggestedId: String,
        val name: String,
        val deviceClass: DeviceClass,
        val capabilities: Set<Capability>,
        val stateTopic: String?,
        val commandTopic: String?,
        /** The device's own grouping field, so several entities of one plug become one device. */
        val deviceIdentifier: String?,
        val availabilityTopic: String?,
    ) {
        /** A device with no command topic can only be read, which is a real and useful thing. */
        val isControllable: Boolean get() = commandTopic != null
    }

    /**
     * Read a discovery payload.
     *
     * THE `~` ABBREVIATION IS THE TRAP. A discovery payload may declare `"~": "zigbee2mqtt/kitchen"`
     * and then write `"state_topic": "~/state"`, where `~` means the base. A reader that took the
     * topic literally would subscribe to `~/state`, which no device ever publishes, and the device
     * would appear and never update — the silent failure this whole batch removes.
     */
    fun parse(topic: String, payload: String): Discovered? {
        val parsedTopic = parseTopic(topic) ?: return null
        val fields = readObject(payload)
        if (fields.isEmpty()) return null

        val base = fields["~"]?.stringValueOrNull()

        fun topicOf(field: String): String? =
            fields[field]?.stringValueOrNull()?.let { raw ->
                if (base != null && raw.startsWith("~")) base.trimEnd('/') + raw.removePrefix("~") else raw
            }

        val name = fields["name"]?.stringValueOrNull()
            ?: parsedTopic.objectId.replace('_', ' ')

        /*
         * THE DEVICE CLASS COMES FROM THE PAYLOAD, not from a guess at the name. A `sensor` with
         * `device_class: temperature` is a thermometer; without that field it is only a reading, and
         * claiming otherwise would give the fabric a device it cannot describe.
         */
        val haClass = fields["device_class"]?.stringValueOrNull()
        val deviceClass = HomeAssistantMapping.deviceClassOf(parsedTopic.component, haClass)

        /*
         * THE AVAILABILITY TOPIC IS PART OF THE DESCRIPTION, and a device that publishes one should
         * be marked unavailable from it rather than assumed present. A plug that has been unplugged
         * still has a discovery message retained on the broker forever.
         */
        val availability = topicOf("availability_topic")
            ?: (fields["availability"] as? JsonArray)?.let { readFirstObjectInArray(it.raw) }
                ?.get("topic")?.stringValueOrNull()

        return Discovered(
            component = parsedTopic.component,
            suggestedId = fields["unique_id"]?.stringValueOrNull() ?: parsedTopic.suggestedId,
            name = name,
            deviceClass = deviceClass,
            capabilities = capabilitiesOf(parsedTopic.component, fields),
            stateTopic = topicOf("state_topic"),
            commandTopic = topicOf("command_topic"),
            // A `device` value is a nested object, so its raw text is read as one level of fields
            // rather than being passed where a string is expected.
            deviceIdentifier = (fields["device"] as? JsonOther)?.let { readObject(it.raw) }
                ?.get("identifiers")?.let { identifiers ->
                    readFirstStringInArray(identifiers) ?: identifiers.stringValueOrNull()
                },
            availabilityTopic = availability,
        )
    }

    /**
     * Which capabilities a discovery message actually advertises.
     *
     * A CAPABILITY IS ONLY GRANTED WHEN THE PAYLOAD NAMES THE FIELD THAT CARRIES IT. A light whose
     * payload has no `brightness_state_topic` cannot report a brightness, so offering one produces a
     * control that writes into the void. This is the same rule as HA's bitmask, expressed for MQTT's
     * field-per-capability shape instead of a bitmask.
     */
    fun capabilitiesOf(component: String, fields: Map<String, Jsonish>): Set<Capability> {
        val caps = mutableSetOf<Capability>()
        fun names(field: String) = fields.containsKey(field)
        fun namesAny(vararg candidate: String) = candidate.any { names(it) }

        when (component.lowercase()) {
            "light" -> {
                caps += Capability.POWER
                if (namesAny("brightness_state_topic", "brightness_command_topic")) caps += Capability.BRIGHTNESS
                if (namesAny("color_temp_state_topic", "color_temp_command_topic")) caps += Capability.COLOR_TEMPERATURE
                if (namesAny("rgb_state_topic", "rgb_command_topic", "hs_state_topic", "hs_command_topic")) {
                    caps += Capability.COLOR_HS
                }
            }
            "switch", "fan" -> {
                caps += Capability.POWER
                if (component.equals("fan", ignoreCase = true) && names("percentage_state_topic")) {
                    caps += Capability.FAN_SPEED
                }
            }
            "cover" -> {
                caps += Capability.POWER
                if (namesAny("position_topic", "set_position_topic")) caps += Capability.POSITION
            }
            "lock" -> caps += Capability.LOCK_STATE
            "climate" -> {
                caps += Capability.TARGET_TEMPERATURE
                caps += Capability.MODE
                caps += Capability.CURRENT_TEMPERATURE
            }
            "sensor" -> caps += Capability.READ_TEMPERATURE
            "binary_sensor" -> caps += Capability.READ_CONTACT
            "media_player" -> {
                caps += Capability.PLAYBACK
                // MQTT media players advertise volume support through a feature list in the payload,
                // and its presence at all is the signal here.
                if (names("volume_command_topic")) {
                    caps += Capability.VOLUME
                    caps += Capability.MUTE
                }
            }
        }
        return caps
    }

    // ---------------------------------------------------------------------
    // A reader for the parts of JSON a discovery payload uses.
    //
    // Explicit rather than reflective because every field is optional and the shape varies by
    // component, so a data class would be twenty nullable fields and the rules would be invisible.
    // ---------------------------------------------------------------------

    /** One JSON value, kept as text plus the two shapes this file reads. */
    sealed interface Jsonish {
        fun stringValueOrNull(): String?
    }

    private data class JsonString(val value: String) : Jsonish {
        override fun stringValueOrNull(): String? = value
    }

    private data class JsonArray(val raw: String) : Jsonish {
        override fun stringValueOrNull(): String? = null
    }

    private data class JsonOther(val raw: String) : Jsonish {
        override fun stringValueOrNull(): String? = raw
    }

    /** The top-level fields of one object, as raw values. */
    fun readObject(json: String): Map<String, Jsonish> {
        val body = json.trim().removePrefix("{").removeSuffix("}")
        if (body.isBlank()) return emptyMap()

        val result = mutableMapOf<String, Jsonish>()
        var index = 0
        while (index < body.length) {
            val keyStart = body.indexOf('"', index)
            if (keyStart < 0) break
            val keyEnd = endOfString(body, keyStart)
            if (keyEnd < 0) break
            val key = unescape(body.substring(keyStart + 1, keyEnd))

            val colon = body.indexOf(':', keyEnd + 1)
            if (colon < 0) break

            var valueStart = colon + 1
            while (valueStart < body.length && body[valueStart].isWhitespace()) valueStart++
            if (valueStart >= body.length) break

            val valueEnd = endOfValue(body, valueStart)
            val raw = body.substring(valueStart, valueEnd).trim()
            result[key] = when {
                raw.startsWith("\"") && raw.endsWith("\"") && raw.length >= 2 ->
                    JsonString(unescape(raw.substring(1, raw.length - 1)))
                raw.startsWith("[") -> JsonArray(raw)
                raw.startsWith("{") -> JsonOther(raw)
                else -> JsonOther(raw)
            }
            index = valueEnd + 1
        }
        return result
    }

    private fun endOfString(text: String, startQuote: Int): Int {
        var i = startQuote + 1
        while (i < text.length) {
            if (text[i] == '\\') { i += 2; continue }
            if (text[i] == '"') return i
            i++
        }
        return -1
    }

    private fun endOfValue(text: String, start: Int): Int {
        if (text[start] == '"') {
            val end = endOfString(text, start)
            return if (end < 0) text.length else end + 1
        }
        if (text[start] == '{' || text[start] == '[') {
            val open = text[start]
            val close = if (open == '{') '}' else ']'
            var depth = 0
            var i = start
            var inString = false
            while (i < text.length) {
                val ch = text[i]
                when {
                    ch == '\\' && inString -> { i += 2; continue }
                    ch == '"' -> inString = !inString
                    !inString && ch == open -> depth++
                    !inString && ch == close -> {
                        depth--
                        if (depth == 0) return i + 1
                    }
                }
                i++
            }
            return text.length
        }
        var i = start
        while (i < text.length && text[i] != ',' && text[i] != '}') i++
        return i
    }

    private fun unescape(text: String): String {
        val sb = StringBuilder()
        var i = 0
        while (i < text.length) {
            val ch = text[i]
            if (ch == '\\' && i + 1 < text.length) {
                when (val next = text[i + 1]) {
                    'n' -> sb.append('\n')
                    't' -> sb.append('\t')
                    'r' -> sb.append('\r')
                    '"' -> sb.append('"')
                    '\\' -> sb.append('\\')
                    '/' -> sb.append('/')
                    else -> sb.append(next)
                }
                i += 2
                continue
            }
            sb.append(ch)
            i++
        }
        return sb.toString()
    }

    /** The first object inside an array of objects, as raw fields. */
    fun readFirstObjectInArray(raw: String): Map<String, Jsonish>? {
        val start = raw.indexOf('{')
        if (start < 0) return null
        val end = endOfValue(raw, start)
        return readObject(raw.substring(start, end))
    }

    /** The first string inside an array, which is how `identifiers` is often written. */
    fun readFirstStringInArray(value: Jsonish): String? {
        if (value !is JsonArray) return null
        val raw = value.raw
        val start = raw.indexOf('"')
        if (start < 0) return null
        val end = endOfString(raw, start)
        if (end < 0) return null
        return unescape(raw.substring(start + 1, end))
    }
}
