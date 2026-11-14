// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric

/**
 * How a Home Assistant entity becomes a fabric device.
 *
 * WHY THIS ADAPTER IS THE HIGHEST-VALUE ONE (issue 184). Its own words: a user with a smart home
 * very often already runs Home Assistant, which already speaks to thousands of integrations.
 * Writing those ourselves is years of work; connecting to the instance they already run is one
 * adapter. For a user without HA, Matter covers them.
 *
 * WHAT IS PURE HERE, AND WHY. The two things that decide whether the fabric can describe an entity
 * correctly are the domain-to-class mapping and the `supported_features` bitmask, and both are
 * arithmetic over strings and integers. Keeping them out of the HTTP client means the mapping is
 * provable without an HA instance, which is the same split that made the Hue and Sonos transports
 * testable.
 *
 * THE BITMASK IS THE PART THAT STOPS THE FABRIC LYING. Home Assistant reports what a device can do
 * as a set of bits, so a `media_player` that can only be turned on gets POWER and not VOLUME.
 * Guessing capabilities from the domain alone would give every light a colour picker whether or not
 * anything listens to it.
 */
object HomeAssistantMapping {

    /**
     * The device class for an entity, from its domain and, where it matters, its own `device_class`.
     *
     * THE DOMAIN COMES FIRST BECAUSE IT IS THE AUTHORITATIVE FIELD. `sensor` alone says almost
     * nothing — a temperature, a battery level and a power meter are all `sensor` — so the entity's
     * own `device_class` attribute refines it, and a `sensor` with none falls back to a generic
     * reading rather than being dropped.
     */
    fun deviceClassOf(domain: String, haDeviceClass: String? = null): DeviceClass = when (domain.lowercase()) {
        "light" -> DeviceClass.LIGHT
        "switch" -> DeviceClass.SWITCH
        "lock" -> DeviceClass.LOCK
        "cover" -> DeviceClass.WINDOW_COVERING
        "climate" -> DeviceClass.THERMOSTAT
        "fan" -> DeviceClass.FAN
        "vacuum" -> DeviceClass.ROBOT_VACUUM
        "water_heater" -> DeviceClass.WATER_HEATER
        "media_player" -> mediaPlayerClass(haDeviceClass)
        "camera" -> DeviceClass.CAMERA
        "alarm_control_panel" -> DeviceClass.ALARM
        "humidifier" -> DeviceClass.AIR_PURIFIER
        "lawn_mower" -> DeviceClass.UNKNOWN
        "binary_sensor" -> binarySensorClass(haDeviceClass)
        "sensor" -> sensorClass(haDeviceClass)
        else -> DeviceClass.UNKNOWN
    }

    /** A media player is a speaker or a television, and HA says which through its device class. */
    private fun mediaPlayerClass(haDeviceClass: String?): DeviceClass =
        if (haDeviceClass.equals("tv", ignoreCase = true)) DeviceClass.TV else DeviceClass.SPEAKER

    /**
     * A binary sensor is one of the contact-style classes, and the distinction is what makes a
     * motion sensor useful and a door sensor different.
     */
    private fun binarySensorClass(haDeviceClass: String?): DeviceClass = when (haDeviceClass?.lowercase()) {
        "motion", "occupancy", "presence" -> DeviceClass.OCCUPANCY_SENSOR
        "door", "window", "opening", "garage_door" -> DeviceClass.CONTACT_SENSOR
        "moisture" -> DeviceClass.WATER_LEAK_DETECTOR
        "smoke" -> DeviceClass.SMOKE_DETECTOR
        "carbon_monoxide", "gas" -> DeviceClass.CO_DETECTOR
        else -> DeviceClass.CONTACT_SENSOR
    }

    /** A sensor is whatever it measures, and an unrecognised one still reports something. */
    private fun sensorClass(haDeviceClass: String?): DeviceClass = when (haDeviceClass?.lowercase()) {
        "temperature" -> DeviceClass.THERMOMETER
        "humidity" -> DeviceClass.HUMIDITY_SENSOR
        "illuminance" -> DeviceClass.ILLUMINANCE_SENSOR
        "pressure" -> DeviceClass.PRESSURE_SENSOR
        "power", "energy", "current", "voltage" -> DeviceClass.ENERGY_METER
        "battery" -> DeviceClass.UNKNOWN
        "aqi", "pm25", "pm10", "carbon_dioxide", "volatile_organic_compounds" -> DeviceClass.AIR_QUALITY_SENSOR
        else -> DeviceClass.UNKNOWN
    }

    /**
     * The capabilities an entity reports, from its domain and its `supported_features` mask.
     *
     * THE MASK BITS ARE HA'S OWN AND THEY ARE PER DOMAIN, which is the trap. Bit 1 means brightness
     * on a light and OPEN on a cover; bit 8 means colour temperature on a light and SET_POSITION on
     * a cover. So the mask is decoded per domain rather than by one table, because one table would
     * give a blind a colour picker.
     *
     * A DOMAIN WITH NO MASK STILL GETS ITS UNIVERSAL CAPABILITIES. A light can always be turned on,
     * a lock can always be locked, whether or not the mask mentions it.
     */
    fun capabilitiesOf(domain: String, supportedFeatures: Long?): Set<Capability> {
        val base = mutableSetOf<Capability>()
        val mask = supportedFeatures ?: 0L

        when (domain.lowercase()) {
            "light" -> {
                base += Capability.POWER
                // LightEntityFeature: 1 = BRIGHTNESS, 2 = COLOR_TEMP, 4 = COLOR, 16 = TRANSITION.
                if (mask has 1L) base += Capability.BRIGHTNESS
                if (mask has 2L) base += Capability.COLOR_TEMPERATURE
                if (mask has 4L) base += Capability.COLOR_HS
            }
            "switch", "input_boolean" -> base += Capability.POWER
            "lock" -> {
                base += Capability.LOCK_STATE
            }
            "cover" -> {
                base += Capability.POSITION
                // CoverEntityFeature: 1 = OPEN, 2 = CLOSE, 4 = SET_POSITION, 8 = STOP. A cover
                // that only opens and closes has no position, so POSITION is dropped when bit 4 is
                // absent and nothing else carries a position.
                if (!(mask has 4L)) base -= Capability.POSITION
                if (mask has 1L || mask has 2L) base += Capability.POWER
            }
            "climate" -> {
                base += Capability.TARGET_TEMPERATURE
                base += Capability.MODE
                base += Capability.CURRENT_TEMPERATURE
            }
            "fan" -> {
                base += Capability.POWER
                if (mask has 1L) base += Capability.FAN_SPEED
            }
            "media_player" -> {
                base += Capability.PLAYBACK
                // MediaPlayerEntityFeature: 4 = VOLUME_SET, 8 = VOLUME_MUTE. A speaker that cannot
                // have its volume set should not be offered a volume control that does nothing.
                if (mask has 4L) base += Capability.VOLUME
                if (mask has 8L) base += Capability.MUTE
            }
            "vacuum" -> {
                base += Capability.START
                base += Capability.STOP
                base += Capability.PAUSE
            }
            "water_heater" -> {
                base += Capability.POWER
                base += Capability.TARGET_TEMPERATURE
            }
            "alarm_control_panel" -> base += Capability.ALARM_STATE
            "camera" -> base += Capability.SOURCE
            "sensor" -> base += Capability.READ_TEMPERATURE
            "binary_sensor" -> base += Capability.READ_CONTACT
        }
        return base
    }

    /** Infix for readability, so a bit test reads as a bit test rather than as arithmetic. */
    private infix fun Long.has(bit: Long): Boolean = (this and bit) != 0L

    /**
     * The fabric device for one Home Assistant entity.
     *
     * THE ENTITY ID IS THE STABLE ID, because HA keeps it across restarts and it is what every
     * service call addresses. Inventing our own id would mean the fabric could not call the entity
     * it describes.
     *
     * UNAVAILABLE IS HA'S OWN `unavailable` STATE rather than a guess, because a device that is
     * switched off at the wall reports that rather than disappearing.
     */
    fun toDevice(
        entityId: String,
        domain: String,
        friendlyName: String,
        haDeviceClass: String? = null,
        supportedFeatures: Long? = null,
        room: String? = null,
        stateValue: String? = null,
        lastSeen: Long = 0L,
    ): Device {
        val capabilities = capabilitiesOf(domain, supportedFeatures)
        return Device(
            id = entityId,
            name = friendlyName.ifBlank { entityId.substringAfter('.') },
            room = room,
            transport = Transport.HA,
            deviceClass = deviceClassOf(domain, haDeviceClass),
            capabilities = capabilities,
            /*
             * THE ENTITY'S OWN STATE IS RECORDED AGAINST THE CAPABILITY THAT CARRIES IT, so a
             * read-back has a real value to compare against rather than an empty map.
             *
             * The mapping is explicit per domain because HA's state string is not a value: "on" for a
             * light is a power flag, "on" for a lock is a lock state, and "heat" for a thermostat is a
             * mode. Inferring one from the string would put a lock's value under a lamp's capability.
             */
            state = stateValue?.let { value -> stateOf(domain, capabilities, value) } ?: emptyMap(),
            /*
             * HA REPORTS UNAVAILABLE AND UNKNOWN AS STATES, and both mean the fabric cannot say what
             * the device is doing. Neither is a reason to drop the device: somebody owns it, and
             * omitting it would report a smaller house than they have.
             */
            available = !stateValue.equals("unavailable", ignoreCase = true) &&
                !stateValue.equals("unknown", ignoreCase = true),
            lastSeen = lastSeen,
        )
    }

    /**
     * The capability an entity's own state belongs under, and the value in its own type.
     *
     * A STATE STRING IS NOT A VALUE ON ITS OWN. "on" is a power flag on a light and a lock state on a
     * lock; "heat" is a thermostat mode; "23.5" is a temperature. Giving every one of them to the
     * first capability the device happens to have would put a thermostat's mode under its target
     * temperature and make every read-back compare the wrong pair.
     */
    private fun stateOf(domain: String, capabilities: Set<Capability>, raw: String): Map<Capability, DeviceValue> {
        val trimmed = raw.trim()
        val asNumber = trimmed.toDoubleOrNull()

        fun put(capability: Capability, value: DeviceValue): Map<Capability, DeviceValue> =
            if (capability in capabilities) mapOf(capability to value) else emptyMap()

        return when (domain.lowercase()) {
            "light", "switch", "input_boolean", "fan" -> put(
                Capability.POWER,
                DeviceValue.Flag(trimmed.equals("on", ignoreCase = true)),
            )
            "lock" -> put(
                Capability.LOCK_STATE,
                DeviceValue.Text(trimmed),
            )
            "cover" -> if (asNumber != null) {
                put(Capability.POSITION, DeviceValue.Measure(asNumber))
            } else {
                put(Capability.POWER, DeviceValue.Flag(trimmed.equals("open", ignoreCase = true)))
            }
            "climate" -> put(Capability.MODE, DeviceValue.Text(trimmed))
            "sensor" -> if (asNumber != null) {
                put(Capability.READ_TEMPERATURE, DeviceValue.Measure(asNumber))
            } else {
                put(Capability.READ_TEMPERATURE, DeviceValue.Text(trimmed))
            }
            "binary_sensor" -> put(
                Capability.READ_CONTACT,
                DeviceValue.Flag(trimmed.equals("on", ignoreCase = true)),
            )
            "media_player" -> put(Capability.PLAYBACK, DeviceValue.Text(trimmed))
            "alarm_control_panel" -> put(Capability.ALARM_STATE, DeviceValue.Text(trimmed))
            else -> capabilities.firstOrNull()?.let { first ->
                mapOf(first to (asNumber?.let { DeviceValue.Measure(it) } ?: DeviceValue.Text(trimmed)))
            } ?: emptyMap()
        }
    }
}
