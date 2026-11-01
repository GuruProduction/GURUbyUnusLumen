// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

import com.unuslumen.app.data.fabric.Capability
import com.unuslumen.app.data.fabric.Device
import com.unuslumen.app.data.fabric.DeviceClass
import com.unuslumen.app.data.fabric.Transport
import com.unuslumen.app.data.fabric.DeviceValue

/**
 * Where IR codes live. Issue #187.
 *
 * WHY THIS IS AN INTERFACE AND NOT A TABLE. Codes arrive from three places at
 * different times: a bundled library on first boot, a file a person imports, and
 * a capture from learn mode. All three produce the same thing, an [IrDeviceFamily]
 * with codes, and the transport must not care which one filled it. So the store is
 * a seam with one implementation on disk and one in memory for tests.
 *
 * WHAT A STORE MUST GUARANTEE. [devices] must be cheap, because the fabric reads
 * it on every tool call. It must never invent a device from a family it could not
 * parse, because a device that refuses every command reads to a model as a broken
 * app rather than as an empty code set.
 */
interface IrCodeStore {

    /** Every controllable IR device, rebuilt from what is stored. */
    suspend fun devices(): List<Device>

    /** One device by fabric id, or null when no codes exist for it. */
    suspend fun device(id: String): Device?

    /** Every button recorded for a device, so a refusal can say what does exist. */
    suspend fun buttons(id: String): List<String>

    /**
     * The stored family itself, which is where the codes live.
     *
     * The fabric's [Device] model carries capabilities and no protocol detail, so a
     * transport that needs the code behind a button asks for the family rather than
     * widening the shared model with one protocol's vocabulary.
     */
    suspend fun family(id: String): IrDeviceFamily?
}

/**
 * The in-memory store, which is also the real one today.
 *
 * WHY IT IS NOT PERSISTED YET, stated plainly rather than hidden: persisting needs
 * a Room migration for the code tables, and a migration this batch cannot prove on
 * a device would be a schema change shipped blind. So the store holds what has been
 * imported and a caller re-imports on boot. That is a real limit and it is named in
 * the PR body rather than discovered by a person whose remote stopped working after
 * a restart.
 */
class InMemoryIrCodeStore(
    initial: List<IrDeviceFamily> = emptyList(),
) : IrCodeStore {

    private val families = java.util.concurrent.CopyOnWriteArrayList<IrDeviceFamily>()

    init {
        families.addAll(initial)
    }

    /** Replace everything with a fresh parse, which is what an import does. */
    fun replaceAll(newFamilies: List<IrDeviceFamily>) {
        families.clear()
        families.addAll(newFamilies)
    }

    /** Add one family, such as a learned capture. */
    fun add(family: IrDeviceFamily) {
        val existing = families.firstOrNull { it.fabricId() == family.fabricId() }
        if (existing == null) {
            families.add(family)
            return
        }
        // A learned capture for a device that already exists merges rather than
        // duplicating it, because the point of learning is to add a button the
        // library lacked.
        val merged = existing.copy(codes = existing.codes + family.codes)
        families[families.indexOfFirst { it.fabricId() == family.fabricId() }] = merged
    }

    override suspend fun devices(): List<Device> =
        families.filter { it.codes.isNotEmpty() }.map { it.toDevice() }

    override suspend fun device(id: String): Device? =
        families.firstOrNull { it.fabricId() == id }?.takeIf { it.codes.isNotEmpty() }?.toDevice()

    override suspend fun buttons(id: String): List<String> =
        families.firstOrNull { it.fabricId() == id }?.buttons ?: emptyList()

    override suspend fun family(id: String): IrDeviceFamily? =
        families.firstOrNull { it.fabricId() == id && it.codes.isNotEmpty() }
}

/**
 * The fabric id of a family.
 *
 * A stable id derived from the source and the name, because a device's identity
 * has to survive a re-import: a person's room assignment hangs off this id and a
 * random one would move their television back to the kitchen every boot.
 */
fun IrDeviceFamily.fabricId(): String =
    "ir:" + (source.substringAfterLast('/').substringBeforeLast('.') + ":" + name)
        .lowercase()
        .replace(Regex("[^a-z0-9]+"), "-")
        .trim('-')

/** The family as a fabric device, with capabilities derived from its real buttons. */
fun IrDeviceFamily.toDevice(): Device {
    val buttons = this.buttons
    val capabilities = IrButtonMapping.capabilitiesFor(DeviceClass.TV, buttons).toMutableSet()
    // A family that is clearly something other than a television still needs a
    // class, and the honest one is whatever its buttons suggest. POWER plus digit
    // buttons is a TV or a set-top box; either way TV is the closest Matter class
    // and inventing a new one would put a class in the store no protocol maps.
    val deviceClass = classify(buttons)
    if (deviceClass == DeviceClass.TV) {
        // A television has VOLUME and MUTE the moment it has codes for them, which
        // capabilitiesFor already decided. A set-top box often lacks both, so the
        // set stays exactly what the codes support.
    }
    return Device(
        id = fabricId(),
        name = name,
        room = null,
        transport = Transport.IR,
        deviceClass = deviceClass,
        capabilities = capabilities,
        // IR has no readable state, so the map is empty rather than carrying an
        // assumed "off". An empty map is the truthful representation of a device
        // nothing can observe.
        state = emptyMap(),
        // Reachable in the sense that matters: a code exists for it. Whether the
        // pulses will be obeyed is unknowable and is not claimed.
        available = true,
        lastSeen = System.currentTimeMillis(),
        raw = mapOf("source" to source, "buttons" to buttons.joinToString(",")),
    )
}

/**
 * What kind of device a code set describes.
 *
 * A family with digit buttons and a channel button is a television or a set-top
 * box. A family with MODE and a temperature button is air conditioning. Anything
 * else is unknown rather than guessed, because a wrong class changes which
 * capabilities the fabric offers and a wrong capability is a refusal a person
 * cannot understand.
 */
private fun classify(buttons: Collection<String>): DeviceClass {
    val normalised = buttons.map { IrButton.normalise(it) }.toSet()
    val hasDigits = normalised.any { it.startsWith("digit_") }
    val hasChannel = "ch_next" in normalised || "ch_prev" in normalised
    val hasTemperature = normalised.any { it.contains("temp") }
    val hasMode = "mode" in normalised
    return when {
        hasTemperature || (hasMode && "fan" in normalised) -> DeviceClass.AIR_CONDITIONER
        hasDigits || hasChannel -> DeviceClass.TV
        "power" in normalised -> DeviceClass.TV
        else -> DeviceClass.UNKNOWN
    }
}
