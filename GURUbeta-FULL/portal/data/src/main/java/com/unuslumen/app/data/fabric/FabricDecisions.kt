// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric

/**
 * The fabric's decisions, with no Android and no storage. Issue #182.
 *
 * WHY THESE ARE SEPARATE FROM [DeviceFabric]. The fabric's whole value is two
 * rules: refuse a capability the device does not report, and never claim a write
 * the device did not confirm. Those rules live in [DeviceFabric], which takes a
 * Room DAO, so proving them meant a device or a fake, and a fake proves the fake.
 *
 * Pulled out here as pure functions, the rules are provable in milliseconds
 * against the real code the fabric runs. That is the same split that turned
 * WebFetchContentQuality from an untestable private method into 14 tests, and
 * the same reason the CLI bridge's protocol lives outside its service.
 *
 * This file decides. [DeviceFabric] does the I/O and asks these.
 */
object FabricDecisions {

    /**
     * Whether a command may be sent, and why not when it may not.
     *
     * Ordered deliberately. Existence first, because a command aimed at nothing
     * is a different error from one aimed at a device that cannot do it. Then
     * capability, which is the rule the issue is about. Then the reading check,
     * because a device can report a capability it does not accept commands for.
     * Then reachability, then whether an adapter exists at all.
     */
    sealed interface Verdict {
        /** The command may proceed. */
        object Allowed : Verdict

        /** The command must not be sent. [reason] is written for a model to read. */
        data class Refused(val reason: String) : Verdict
    }

    fun canControl(
        device: Device?,
        capability: Capability,
        transportInstalled: Boolean,
    ): Verdict {
        if (device == null) {
            return Verdict.Refused("No device with that id is known to the fabric.")
        }

        if (capability !in device.capabilities) {
            val has = device.capabilities.sortedBy { it.name }.joinToString(", ") { it.name }
            return Verdict.Refused(
                "${device.name} does not report ${capability.name}. It reports: " +
                    if (has.isEmpty()) "no capabilities" else has,
            )
        }

        if (capability in Capability.READ_ONLY) {
            return Verdict.Refused(
                "${capability.name} is a reading, not a command. ${device.name} reports it " +
                    "but does not accept it.",
            )
        }

        if (!device.available) {
            return Verdict.Refused(
                "${device.name} is not reachable right now, so nothing was sent.",
            )
        }

        if (!transportInstalled) {
            return Verdict.Refused(
                "No adapter is installed for transport ${device.transport.name}, so " +
                    "${device.name} cannot be reached.",
            )
        }

        return Verdict.Allowed
    }

    /**
     * Whether a write is confirmed, given what the device reported back.
     *
     * The only path to confirmed is an observed value that equals the written
     * one. Everything else carries the reason, because "it was refused", "it was
     * sent and ignored" and "it landed" are three different answers.
     */
    sealed interface Confirmation {
        data class Confirmed(val observed: DeviceValue) : Confirmation
        data class NotConfirmed(val reason: String, val observed: DeviceValue?) : Confirmation
    }

    fun confirm(
        deviceName: String,
        capability: Capability,
        wrote: DeviceValue,
        observed: DeviceValue?,
    ): Confirmation {
        if (observed == null) {
            return Confirmation.NotConfirmed(
                "$deviceName accepted the ${capability.name} command but could not be read " +
                    "back, so the change is unconfirmed.",
                null,
            )
        }
        if (observed.asComparable() != wrote.asComparable()) {
            return Confirmation.NotConfirmed(
                "$deviceName reported ${capability.name} as ${describe(observed)} when " +
                    "${describe(wrote)} was written, so the change was not confirmed.",
                observed,
            )
        }
        return Confirmation.Confirmed(observed)
    }

    /**
     * Resolve a spoken device name against the known devices.
     *
     * Order matters and is the whole answer to "a user says the kitchen light,
     * not device 0x1A2B": exact id, exact name, then a phrase that names both a
     * room and a device, then a substring. Unambiguous only when exactly one
     * device matches, so a caller refuses to guess rather than picking first.
     */
    fun resolve(query: String, devices: List<Device>, room: String? = null): Resolution {
        val needle = query.trim().lowercase()
        if (needle.isEmpty()) return Resolution(emptyList(), false)

        val pool = if (room == null) devices else devices.filter { it.room?.equals(room, ignoreCase = true) == true }
        if (pool.isEmpty()) return Resolution(emptyList(), false)

        pool.firstOrNull { it.id.equals(query.trim(), ignoreCase = true) }
            ?.let { return Resolution(listOf(it), true) }
        pool.firstOrNull { it.name.equals(query.trim(), ignoreCase = true) }
            ?.let { return Resolution(listOf(it), true) }

        // "kitchen light" against a device named "Light" in a room called Kitchen.
        val roomQualified = pool.filter { d ->
            val r = d.room?.lowercase() ?: return@filter false
            needle.contains(r) && needle.contains(d.name.lowercase())
        }
        if (roomQualified.size == 1) return Resolution(roomQualified, true)

        val partial = pool.filter {
            it.name.lowercase().contains(needle) || (it.room?.lowercase()?.contains(needle) == true)
        }
        return Resolution(partial, partial.size == 1)
    }

    data class Resolution(val matches: List<Device>, val unambiguous: Boolean)

    /** How a value reads in a sentence. */
    fun describe(value: DeviceValue?): String = when (value) {
        null -> "nothing"
        is DeviceValue.Flag -> if (value.value) "on" else "off"
        is DeviceValue.Count -> value.value.toString()
        is DeviceValue.Measure -> value.value.toString()
        is DeviceValue.Text -> "\"${value.value}\""
    }
}
