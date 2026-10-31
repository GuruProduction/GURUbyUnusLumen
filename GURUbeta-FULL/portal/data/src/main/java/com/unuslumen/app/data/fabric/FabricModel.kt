// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric

import kotlinx.serialization.Serializable

/**
 * The smart home fabric's device model. Issue #182.
 *
 * ONE MODEL FOR EVERY DEVICE. A Philips bulb, an Aqara sensor, a Sonos speaker
 * and a washing machine all appear here as a device with a class, a room, a
 * state and a set of capabilities. The model asks for a thing and the fabric
 * routes it, instead of the model having to know which of seven protocols to
 * reach for.
 *
 * The device classes follow Matter's device type list because that list is
 * comprehensive and vendor-neutral: every protocol in the batch maps onto it,
 * and none of them has to invent a parallel vocabulary.
 */

/**
 * The Matter device type list, plus BRIDGE for a gateway that fronts others.
 *
 * Deliberately complete rather than the shortlist a first version needs: a
 * transport that meets a device type not in this enum would otherwise have to
 * invent one, and inventing one is how two protocols end up disagreeing.
 */
@Serializable
enum class DeviceClass {
    LIGHT, PLUG, SWITCH, LOCK, DOOR, WINDOW_COVERING,
    THERMOSTAT, THERMOMETER, HUMIDITY_SENSOR, OCCUPANCY_SENSOR, CONTACT_SENSOR,
    ILLUMINANCE_SENSOR, PRESSURE_SENSOR, FLOW_SENSOR, AIR_QUALITY_SENSOR,
    FAN, AIR_PURIFIER, AIR_CONDITIONER, HEATER, WATER_HEATER,
    ROBOT_VACUUM, DISHWASHER, LAUNDRY_WASHER, LAUNDRY_DRYER, OVEN, COOKTOP,
    REFRIGERATOR, MICROWAVE, COFFEE_MAKER,
    SPEAKER, TV, CAMERA, DOORBELL,
    ALARM, SMOKE_DETECTOR, CO_DETECTOR, WATER_LEAK_DETECTOR,
    ENERGY_METER, BRIDGE,
    /** A device whose class no transport could map. Stored rather than dropped. */
    UNKNOWN;

    companion object {
        fun fromRaw(raw: String?): DeviceClass =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: UNKNOWN
    }
}

/**
 * What a device can actually do.
 *
 * THIS IS THE RULE THAT STOPS THE TOOL LYING. A capability exists in this set
 * only because the device reported it at commissioning. A washing machine that
 * reports cycle state gets CYCLE_STATE and REMAINING_TIME and nothing else, and
 * a command targeting a capability it does not have is refused with a reason
 * rather than silently doing nothing.
 */
@Serializable
enum class Capability {
    POWER, BRIGHTNESS, COLOR_TEMPERATURE, COLOR_HS, COLOR_XY,
    /** Blinds, covers, valves: a position between open and closed. */
    POSITION,
    LOCK_STATE,
    TARGET_TEMPERATURE, CURRENT_TEMPERATURE, MODE, FAN_SPEED,
    READ_TEMPERATURE, READ_HUMIDITY, READ_OCCUPANCY, READ_CONTACT,
    READ_ILLUMINANCE, READ_PRESSURE, READ_FLOW, READ_AIR_QUALITY,
    READ_ENERGY, READ_POWER,
    PLAYBACK, VOLUME, MUTE, SOURCE,
    START, STOP, PAUSE, CYCLE_STATE, REMAINING_TIME,
    ALARM_STATE;

    /** Whether a capability is something you set, as opposed to something you read. */
    val isWritable: Boolean
        get() = this !in READ_ONLY

    companion object {
        /** Readings only. A control action targeting one of these is a mistake. */
        val READ_ONLY: Set<Capability> = setOf(
            READ_TEMPERATURE, READ_HUMIDITY, READ_OCCUPANCY, READ_CONTACT,
            READ_ILLUMINANCE, READ_PRESSURE, READ_FLOW, READ_AIR_QUALITY,
            READ_ENERGY, READ_POWER, REMAINING_TIME,
        )

        fun fromRaw(raw: String?): Capability? =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) }
    }
}

/**
 * Which subsystem a device came from.
 *
 * A transport is identified by this enum, but the BEHAVIOUR lives in a
 * [DeviceTransport] implementation registered at runtime. Adding a protocol does
 * not edit this enum's meaning: it registers an adapter under one of these names.
 */
@Serializable
enum class Transport {
    MATTER, HA, MQTT, ZIGBEE, ZWAVE, IR, VENDOR, HUE, SONOS,
    /** Devices created by hand, where no adapter exists. */
    MANUAL;

    companion object {
        fun fromRaw(raw: String?): Transport =
            entries.firstOrNull { it.name.equals(raw?.trim(), ignoreCase = true) } ?: MANUAL
    }
}

/**
 * One device, exactly as the model sees it.
 *
 * [state] is keyed by capability, so a caller reads the value of a thing rather
 * than decoding a protocol payload. Values are primitives — Boolean for power
 * and lock, Int or Float for brightness and temperature, String for mode and
 * source — which keeps the store plain JSON and the tool surface honest.
 */
@Serializable
data class Device(
    val id: String,
    val name: String,
    val room: String?,
    val transport: Transport,
    val deviceClass: DeviceClass,
    val capabilities: Set<Capability>,
    val state: Map<Capability, DeviceValue>,
    val available: Boolean,
    val lastSeen: Long,
    /** Present when the transport had to store something it could not map. */
    val raw: Map<String, String> = emptyMap(),
)

/**
 * A state value.
 *
 * A sealed type rather than `Any?`, because `Any?` in a serialised store is how
 * a brightness silently becomes a String and a comparison against 0 stops
 * working. The types here are exactly the ones a smart device reports.
 */
@Serializable
sealed interface DeviceValue {
    @Serializable data class Flag(val value: Boolean) : DeviceValue
    @Serializable data class Count(val value: Int) : DeviceValue
    @Serializable data class Measure(val value: Double) : DeviceValue
    @Serializable data class Text(val value: String) : DeviceValue

    /** A comparable form, so a read-back can be compared against what was written. */
    fun asComparable(): Any = when (this) {
        is Flag -> value
        is Count -> value
        is Measure -> value
        is Text -> value
    }

    companion object {
        fun of(raw: Any?): DeviceValue? = when (raw) {
            is Boolean -> Flag(raw)
            is Int -> Count(raw)
            is Long -> Count(raw.toInt())
            is Float -> Measure(raw.toDouble())
            is Double -> Measure(raw)
            is String -> Text(raw)
            else -> null
        }
    }
}

/** A named set of device states, applied atomically. */
@Serializable
data class Scene(
    val id: String,
    val name: String,
    /** device id -> capability -> value */
    val states: Map<String, Map<Capability, DeviceValue>>,
    val createdAt: Long,
)
