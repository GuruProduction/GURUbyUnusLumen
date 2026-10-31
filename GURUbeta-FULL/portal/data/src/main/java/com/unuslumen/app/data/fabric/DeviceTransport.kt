// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric

/**
 * The registration point every protocol plugs into. Issue #182.
 *
 * THIS IS THE MOST IMPORTANT CONTRACT IN THE SMART HOME WORK.
 *
 * Five transports follow this one: Matter, Home Assistant, MQTT, Hue/Sonos,
 * IR, Zigbee and Z-Wave. If each of them had to edit a shared file to exist,
 * every branch would conflict with every other and the batch would serialise
 * into a queue of rebases. So a transport is a CLASS, it declares its own name,
 * and it is discovered. Nothing upstream of it changes when one is added.
 *
 * HOW DISCOVERY WORKS, and why it is not reflection. A transport registers with
 * [DeviceTransportRegistry] by being passed in at startup from a generated list,
 * exactly like the tool registry's GeneratedToolRegistrations. That keeps the
 * mechanism the same shape the codebase already uses, keeps it R8-safe, and
 * makes the set of installed transports readable in one place.
 *
 * WHAT A TRANSPORT MUST AND MUST NOT DO
 *
 *  - It owns its own credentials, sealing them in the credential vault.
 *  - It maps its own vocabulary onto [DeviceClass] and [Capability].
 *  - It NEVER decides whether a command is allowed. The fabric owns capability
 *    enforcement, so refusal is identical across every protocol.
 *  - It reports state it has actually read. A transport that returns a value it
 *    assumed instead of read makes the whole fabric lie, which is the defect
 *    this issue exists to prevent.
 */

/** What a transport reports about itself, so the fabric can describe it. */
data class TransportDescriptor(
    val transport: Transport,
    val displayName: String,
    /** Whether this transport is reachable right now. */
    val available: Boolean,
    /** Why it is not available, in one sentence. Null when it is. */
    val unavailableReason: String?,
)

/** The result of asking a transport to change something. */
sealed interface TransportWrite {
    /**
     * The transport accepted the write and the device's reported state changed.
     *
     * [observed] is what the transport READ BACK, not what it wrote. The fabric
     * compares them again itself, so a transport cannot certify its own claim.
     */
    data class Confirmed(val observed: DeviceValue) : TransportWrite

    /**
     * The transport accepted the write but the device does not report the change.
     *
     * This is a real outcome, not an error path: a device can accept a command
     * and ignore it, and reporting success there is exactly the class of lie
     * issues 25, 30, 43 and 44 were filed about.
     */
    data class NotObserved(val wrote: DeviceValue, val stillReports: DeviceValue?) : TransportWrite

    /** The transport refused or could not reach the device, with a reason. */
    data class Failed(val reason: String) : TransportWrite
}

/**
 * One protocol's adapter.
 *
 * Every method is suspend because every transport does I/O, and every one of
 * them is allowed to fail with a reason rather than throw.
 */
interface DeviceTransport {

    /** Which transport this adapter serves. */
    val transport: Transport

    /** Human-readable state of the adapter right now. */
    fun describe(): TransportDescriptor

    /**
     * Discover every device this transport can see.
     *
     * Returns what it found. An empty list is a legitimate answer and the caller
     * distinguishes it from failure by checking [describe].available, so a
     * transport never has to choose between lying and throwing.
     */
    suspend fun discover(): List<Device>

    /**
     * Read the current state of one device.
     *
     * Returns null when the device cannot be reached. The fabric treats null as
     * unreadable and will not claim a write landed.
     */
    suspend fun read(deviceId: String): Map<Capability, DeviceValue>?

    /**
     * Write one capability.
     *
     * The transport performs the write and then reads the value back, so the
     * fabric gets [TransportWrite.Confirmed] with an observed value, or
     * [TransportWrite.NotObserved] when the device ignored it. A transport that
     * cannot read back returns [TransportWrite.Failed] rather than guessing.
     */
    suspend fun write(deviceId: String, capability: Capability, value: DeviceValue): TransportWrite
}

/**
 * The set of installed transports.
 *
 * Populated once at startup. Read by the fabric for discovery and by the tools
 * for describing what is reachable. Thread-safe by copy-on-write because writes
 * happen once and reads happen on every tool call.
 */
class DeviceTransportRegistry {

    private val transports = java.util.concurrent.CopyOnWriteArrayList<DeviceTransport>()

    fun register(transport: DeviceTransport) {
        if (transports.none { it.transport == transport.transport }) {
            transports.add(transport)
        }
    }

    fun all(): List<DeviceTransport> = transports.toList()

    fun forTransport(transport: Transport): DeviceTransport? =
        transports.firstOrNull { it.transport == transport }

    /** What every installed transport reports about itself. */
    fun descriptors(): List<TransportDescriptor> = transports.map { it.describe() }

    /** Transports that are reachable right now. */
    fun available(): List<DeviceTransport> = transports.filter { it.describe().available }
}
