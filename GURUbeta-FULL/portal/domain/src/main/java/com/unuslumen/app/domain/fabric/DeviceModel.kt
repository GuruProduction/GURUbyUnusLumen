// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.fabric

/**
 * One smart device, whatever protocol it arrived on.
 *
 * WHY THIS EXISTS (issue 182). Its own words: "without it, every new protocol doubles the tool count
 * and the model has to know which one to reach for". A Philips bulb, an Aqara sensor, a Sonos speaker
 * and a washing machine all have to look like devices with a class, a room, a state and a set of
 * capabilities, so the model asks for a thing rather than for a protocol.
 *
 * THE OWNER'S REQUIREMENT IS CONTROL OF ANYTHING POINTED AT, and that is only achievable if the thing
 * being pointed at can be described uniformly. So this model is the foundation every other smart
 * home issue depends on, and it depends on nothing.
 *
 * EVERYTHING HERE IS ANDROID-FREE AND PURE, because the rules that stop the fabric lying are rules
 * and not plumbing: a capability a device does not report must be refused, and a control action must
 * be confirmed by reading the state back. Both are decided here so both can be proved on the JVM.
 */
data class FabricDevice(
    /** Stable across restarts, so a scene does not lose its target when the app is killed. */
    val id: String,
    /** What a person calls it, and renameable, because "the kitchen light" is not "device 0x1A2B". */
    val name: String,
    /** The room a person assigned, or null when they have not said. */
    val room: String? = null,
    val transport: Transport,
    val deviceClass: DeviceClass,
    /**
     * WHAT THIS DEVICE ACTUALLY REPORTS, not what its class usually has.
     *
     * A washing machine that only reports cycle state gets [Capability.CYCLE_STATE] and
     * [Capability.REMAINING_TIME] and nothing else. This set is the only thing that decides what may
     * be commanded, which is what stops a tool pretending.
     */
    val capabilities: Set<Capability>,
    /** Current values by capability. A capability with no entry has not been read yet. */
    val state: Map<Capability, String> = emptyMap(),
    val available: Boolean = true,
    val lastSeen: Long = 0L,
) {
    /** True when this device reports the capability, which is the gate for commanding it. */
    fun supports(capability: Capability): Boolean = capability in capabilities

    /** What a person would call this device, naming its room when one is assigned. */
    fun displayName(): String = room?.takeIf { it.isNotBlank() }?.let { "$name in the $it" } ?: name
}

/** Where a device came from. Never what it does, which is what [Capability] is for. */
enum class Transport {
    MATTER,
    HOME_ASSISTANT,
    MQTT,
    ZIGBEE,
    ZWAVE,
    IR,
    VENDOR,
}

/**
 * What a device is, from the Matter device type list.
 *
 * CHOSEN BECAUSE IT IS COMPREHENSIVE AND VENDOR-NEUTRAL. A vendor's own taxonomy would make one
 * protocol's names primary and every other protocol a translation of them, which is how a fabric
 * ends up knowing what a Philips bulb is and guessing at everything else.
 */
enum class DeviceClass(val label: String) {
    LIGHT("light"),
    PLUG("plug"),
    SWITCH("switch"),
    LOCK("lock"),
    DOOR("door"),

    WINDOW_COVERING("blind or curtain"),
    THERMOSTAT("thermostat"),
    THERMOMETER("thermometer"),
    HUMIDITY_SENSOR("humidity sensor"),
    OCCUPANCY_SENSOR("motion sensor"),
    CONTACT_SENSOR("contact sensor"),
    ILLUMINANCE_SENSOR("light sensor"),
    PRESSURE_SENSOR("pressure sensor"),
    FLOW_SENSOR("flow sensor"),
    AIR_QUALITY_SENSOR("air quality sensor"),
    FAN("fan"),
    AIR_PURIFIER("air purifier"),
    AIR_CONDITIONER("air conditioner"),
    HEATER("heater"),
    WATER_HEATER("water heater"),
    ROBOT_VACUUM("robot vacuum"),
    DISHWASHER("dishwasher"),
    LAUNDRY_WASHER("washing machine"),
    LAUNDRY_DRYER("dryer"),
    OVEN("oven"),
    COOKTOP("hob"),
    REFRIGERATOR("fridge"),
    MICROWAVE("microwave"),
    COFFEE_MAKER("coffee machine"),
    SPEAKER("speaker"),
    TV("television"),
    CAMERA("camera"),
    DOORBELL("doorbell"),
    ALARM("alarm"),
    SMOKE_DETECTOR("smoke detector"),
    CO_DETECTOR("carbon monoxide detector"),
    WATER_LEAK_DETECTOR("water leak detector"),
    ENERGY_METER("energy meter"),
    BRIDGE("bridge"),
    UNKNOWN("device"),
    ;
}

/**
 * What a device can actually do.
 *
 * THE SET THAT STOPS THE TOOL LYING. Its own words: "if a capability is not in the set, a command
 * targeting it is refused with 'this device reports cycle state but does not accept a start
 * command', rather than silently doing nothing."
 */
enum class Capability(val label: String, val isReadOnly: Boolean = false) {
    POWER("power"),
    BRIGHTNESS("brightness"),
    COLOR_TEMPERATURE("colour temperature"),
    COLOR_HS("colour"),
    COLOR_XY("colour"),

    POSITION("position"),
    LOCK_STATE("lock"),

    TARGET_TEMPERATURE("target temperature"),
    CURRENT_TEMPERATURE("current temperature", isReadOnly = true),
    MODE("mode"),
    FAN_SPEED("fan speed"),

    READ_TEMPERATURE("temperature", isReadOnly = true),
    READ_HUMIDITY("humidity", isReadOnly = true),
    READ_OCCUPANCY("motion", isReadOnly = true),
    READ_CONTACT("contact", isReadOnly = true),
    READ_ILLUMINANCE("light level", isReadOnly = true),
    READ_PRESSURE("pressure", isReadOnly = true),
    READ_FLOW("flow", isReadOnly = true),
    READ_AIR_QUALITY("air quality", isReadOnly = true),
    READ_ENERGY("energy use", isReadOnly = true),
    READ_POWER("power draw", isReadOnly = true),

    PLAYBACK("playback"),
    VOLUME("volume"),
    MUTE("mute"),
    SOURCE("source"),

    START("start"),
    STOP("stop"),
    PAUSE("pause"),
    CYCLE_STATE("cycle state", isReadOnly = true),
    REMAINING_TIME("time remaining", isReadOnly = true),

    ALARM_STATE("alarm"),
    ;
}

/**
 * What a control request ended as, with the read-back already done.
 *
 * THE READ-BACK RULE IS NON-NEGOTIABLE, and it is carried from the same standard that made
 * `recordScreen` stop reporting success on a zero-byte file: every control action reads the state
 * back and confirms it changed.
 *
 * VERIFIED means the device reports the new value. UNCHANGED means the command was accepted and the
 * device did not move, which is the single most important answer here because it is the one the old
 * pattern reported as success. REFUSED means the device never reported the capability.
 */
sealed interface ControlOutcome {
    data class Verified(val capability: Capability, val value: String) : ControlOutcome
    data class Unchanged(val capability: Capability, val askedFor: String, val actual: String?) : ControlOutcome
    data class Refused(val capability: Capability, val because: String) : ControlOutcome
    data class Failed(val capability: Capability, val because: String) : ControlOutcome

    val success: Boolean
        get() = this is Verified

    /** One sentence a model can repeat without overstating what happened. */
    fun sentence(deviceName: String): String = when (this) {
        is Verified -> "$deviceName now reports ${capability.label} at $value."
        is Unchanged ->
            "$deviceName accepted the command to set ${capability.label} to $askedFor but still " +
                "reports ${actual ?: "no value"}, so nothing changed. This is not a success."
        is Refused -> "$deviceName was not commanded: $because"
        is Failed -> "$deviceName could not be controlled: $because"
    }
}

/**
 * The rules that keep the fabric honest, as pure functions.
 *
 * WHY THEY ARE SEPARATE FROM ANY TRANSPORT. A transport knows how to talk to Matter; it must not
 * decide whether a command is allowed or whether it landed. Those two decisions are the difference
 * between a fabric that can be trusted and one that reports what it intended.
 */
object DeviceFabric {

    /**
     * Whether a capability may be commanded on this device, and why not when it may not.
     *
     * THE SENTENCE NAMES WHAT THE DEVICE DOES HAVE, because "this device does not accept a start
     * command" alone leaves a person guessing what it does accept. The ticket's own example is a
     * washing machine reporting cycle state, so the refusal has to be specific enough to act on.
     */
    fun mayCommand(device: FabricDevice, capability: Capability): ControlOutcome.Refused? {
        /*
         * A READ-ONLY CAPABILITY IS NOT A COMMAND. Asking to SET a temperature reading is a request
         * the device cannot satisfy, and accepting it would produce a value nothing observes.
         */
        if (capability.isReadOnly) {
            return ControlOutcome.Refused(
                capability,
                "this device only reports ${capability.label}, it does not accept a value for it.",
            )
        }
        if (!device.supports(capability)) {
            val whatItHas = device.capabilities
                .filterNot { it.isReadOnly }
                .joinToString(", ") { it.label }
                .ifBlank { "nothing it can be commanded to do" }
            return ControlOutcome.Refused(
                capability,
                "this device does not report ${capability.label}, so it will not be sent a command " +
                    "to set it. What it does accept: $whatItHas.",
            )
        }
        if (!device.available) {
            return ControlOutcome.Refused(
                capability,
                "this device is not reachable right now, so the command would not arrive.",
            )
        }
        return null
    }

    /**
     * Decide a control's outcome from the state read back after it.
     *
     * THE ONLY WAY TO PRODUCE A SUCCESS IS WITH A READ-BACK THAT MATCHES. There is no argument for
     * "the write returned OK", because a transport that returns OK without reading the attribute is
     * exactly the pattern this rule exists to catch.
     *
     * A DEVICE THAT REPORTS A NUMERIC VALUE IS COMPARED NUMERICALLY, so asking for brightness 50
     * against a device that reports "50.0" is a success rather than a string mismatch. That is a
     * real difference between how protocols report the same thing.
     */
    fun outcomeOf(
        device: FabricDevice,
        capability: Capability,
        askedFor: String,
        readBack: FabricDevice,
    ): ControlOutcome {
        mayCommand(device, capability)?.let { return it }
        val actual = readBack.state[capability]
            ?: return ControlOutcome.Failed(
                capability,
                "the device did not report ${capability.label} after the command, so there is " +
                    "nothing to confirm it against.",
            )
        return if (sameValue(askedFor, actual)) {
            ControlOutcome.Verified(capability, actual)
        } else {
            ControlOutcome.Unchanged(capability, askedFor, actual)
        }
    }

    /**
     * Whether two reported values mean the same thing.
     *
     * NUMBERS ARE COMPARED AS NUMBERS and booleans as booleans, because protocols differ on how they
     * spell a yes and a number. Everything else is compared case-insensitively after trimming,
     * which covers "On" against "on".
     */
    fun sameValue(askedFor: String, actual: String): Boolean {
        val left = askedFor.trim()
        val right = actual.trim()
        if (left.equals(right, ignoreCase = true)) return true

        val leftNumber = left.toDoubleOrNull()
        val rightNumber = right.toDoubleOrNull()
        if (leftNumber != null && rightNumber != null) {
            // A tiny tolerance, because a device that reports a float for an integer request is
            // answering the same question rather than disagreeing.
            return kotlin.math.abs(leftNumber - rightNumber) < 0.001
        }

        fun asBoolean(text: String): Boolean? = when (text.lowercase()) {
            "true", "on", "1", "yes" -> true
            "false", "off", "0", "no" -> false
            else -> null
        }
        val leftBool = asBoolean(left)
        val rightBool = asBoolean(right)
        if (leftBool != null && rightBool != null) return leftBool == rightBool

        return false
    }

    /**
     * Resolve what a person called a device to the device itself.
     *
     * A PERSON SAYS "the kitchen light", so this accepts a name, a room plus a name, or an id, and it
     * says which of those it matched rather than guessing. Ambiguity is reported rather than resolved
     * by picking the first, because silently controlling one of two kitchen lights is worse than
     * asking which.
     */
    sealed interface Resolution {
        data class Found(val device: FabricDevice) : Resolution
        data class Ambiguous(val candidates: List<FabricDevice>) : Resolution
        data class Missing(val query: String, /** Names the fabric does know, so a person can correct. */
                           val known: List<String>) : Resolution
    }

    fun resolve(devices: List<FabricDevice>, query: String): Resolution {
        val trimmed = query.trim()
        if (trimmed.isEmpty()) {
            return Resolution.Missing(query, devices.map { it.displayName() })
        }

        devices.firstOrNull { it.id.equals(trimmed, ignoreCase = true) }?.let {
            return Resolution.Found(it)
        }

        // "the kitchen light" and "kitchen light" both have to work, so a leading article is dropped
        // rather than being compared.
        val normalised = trimmed
            .lowercase()
            .removePrefix("the ")
            .removeSuffix(" in the kitchen")
            .trim()

        val byName = devices.filter { it.name.equals(trimmed, ignoreCase = true) }
        if (byName.size == 1) return Resolution.Found(byName.single())
        if (byName.size > 1) return Resolution.Ambiguous(byName)

        val byDisplay = devices.filter { it.displayName().equals(trimmed, ignoreCase = true) }
        if (byDisplay.size == 1) return Resolution.Found(byDisplay.single())
        if (byDisplay.size > 1) return Resolution.Ambiguous(byDisplay)

        /*
         * A PARTIAL NAME IS TRIED LAST and only when it is unambiguous. "kitchen" matching exactly
         * one light is helpful; matching three is a question, not a choice, so it is reported as
         * ambiguous rather than resolved by picking the first.
         */
        val partial = devices.filter { it.name.contains(normalised, ignoreCase = true) }
        if (partial.size == 1) return Resolution.Found(partial.single())
        if (partial.size > 1) return Resolution.Ambiguous(partial)

        return Resolution.Missing(trimmed, devices.map { it.displayName() })
    }

    /**
     * The list filtered by what a person asked for.
     *
     * Every filter is optional and an absent one means "no opinion" rather than "must be empty",
     * because a fabric that returns nothing for an unfiltered query is the absence-as-measurement
     * defect this app keeps removing.
     */
    fun filter(
        devices: List<FabricDevice>,
        room: String? = null,
        deviceClass: DeviceClass? = null,
        capability: Capability? = null,
        availableOnly: Boolean = false,
    ): List<FabricDevice> = devices.filter { device ->
        (room == null || device.room.equals(room, ignoreCase = true)) &&
            (deviceClass == null || device.deviceClass == deviceClass) &&
            (capability == null || device.supports(capability)) &&
            (!availableOnly || device.available)
    }
}
