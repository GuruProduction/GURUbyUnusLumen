// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.tv

import com.unuslumen.app.data.fabric.Capability
import com.unuslumen.app.data.fabric.Device
import com.unuslumen.app.data.fabric.DeviceClass
import com.unuslumen.app.data.fabric.DeviceTransport
import com.unuslumen.app.data.fabric.DeviceValue
import com.unuslumen.app.data.fabric.Transport
import com.unuslumen.app.data.fabric.TransportDescriptor
import com.unuslumen.app.data.fabric.TransportWrite
import com.unuslumen.app.data.fabric.ir.InMemoryIrCodeStore

/**
 * Network television control, plugged into the fabric. Issue #187, to Steven's actual
 * requirement: the phone must drive a telly over the network the way a universal
 * remote app does, for every make rather than one.
 *
 * WHY THIS IS THE TRANSPORT AND INFRARED WAS A DETOUR. A universal remote app speaks
 * each vendor's own network remote, discovered on the local network. This transport is
 * that: [TvDiscovery] finds the set and names the make, [TvRemoteFactory] picks the
 * adapter, and the fabric's five generic tools appear to the model as an ordinary
 * television. No per-vendor tool, no code database, no hardware the phone has to carry.
 *
 * WHY IT SHARES THE FABRIC'S VENDOR ENUM. The fabric's [Transport] enum predates this
 * work and has no TELEVISION member. Adding one here would be one line in a shared file
 * that every other transport's branch also edits, which is exactly the conflict the
 * registration point exists to prevent. So this transport declares itself under
 * [Transport.VENDOR]: a television controlled by its maker's own protocol is precisely
 * a vendor protocol. That is honest rather than a workaround, and it keeps the enum's
 * meaning unchanged.
 *
 * WHAT IT READS AND WHAT IT CANNOT. Roku reports its power mode and current app, so
 * those are real readings. Samsung and LG report nothing useful without a longer
 * session, so their state is unknown rather than assumed, and a write that cannot be
 * read back is [TransportWrite.NotObserved] rather than a claimed success.
 */
class TvTransport(
    private val discovery: TvDiscovery,
    private val store: TvDeviceStore,
) : DeviceTransport {

    override val transport: Transport = Transport.VENDOR

    override fun describe(): TransportDescriptor = TransportDescriptor(
        transport = Transport.VENDOR,
        displayName = "Network television control",
        available = true,
        unavailableReason = null,
    )

    /**
     * Find every television on this network.
     *
     * A set whose vendor has no adapter is still reported, because a person needs to
     * know it was found and why it cannot be driven. It is reported as unavailable with
     * the factory's own sentence, so the fabric's refusal is specific rather than an
     * empty house.
     */
    override suspend fun discover(): List<Device> {
        val report = discovery.discover()
        val devices = mutableListOf<Device>()
        for (found in report.devices) {
            val result = TvRemoteFactory.remoteFor(found)
            store.remember(found, result.remote)
            devices.add(
                Device(
                    id = found.id,
                    name = found.name,
                    room = null,
                    transport = Transport.VENDOR,
                    deviceClass = DeviceClass.TV,
                    capabilities = capabilitiesFor(found),
                    // IR-style empty state: only a protocol that reports values gets them,
                    // and that is filled by read() rather than assumed here.
                    state = emptyMap(),
                    available = result.remote != null,
                    lastSeen = System.currentTimeMillis(),
                    raw = mapOf(
                        "vendor" to found.vendor.name,
                        "host" to found.host,
                        "port" to found.port.toString(),
                        "evidence" to found.evidence,
                        "unsupported" to (result.reason ?: ""),
                    ),
                ),
            )
        }
        return devices
    }

    /**
     * Read a set's state where its protocol allows it.
     *
     * Null means the make cannot report, which the fabric treats as unreadable and will
     * not claim a write landed against. That is the truthful answer for a set that only
     * accepts commands.
     */
    override suspend fun read(deviceId: String): Map<Capability, DeviceValue>? {
        val entry = store.get(deviceId) ?: return null
        val remote = entry.remote ?: return null
        val state = remote.readState() ?: return null
        val out = mutableMapOf<Capability, DeviceValue>()
        state.forEach { (key, value) ->
            when (key) {
                TvStateKey.POWER -> out[Capability.POWER] =
                    DeviceValue.Flag(value.equals("PowerOn", ignoreCase = true) || value == "on")
                TvStateKey.VOLUME -> value.toIntOrNull()?.let { out[Capability.VOLUME] = DeviceValue.Count(it) }
                TvStateKey.MUTED -> out[Capability.MUTE] =
                    DeviceValue.Flag(value.equals("true", ignoreCase = true))
                TvStateKey.APP -> out[Capability.SOURCE] = DeviceValue.Text(value)
                TvStateKey.INPUT -> out[Capability.SOURCE] = DeviceValue.Text(value)
            }
        }
        return out.ifEmpty { null }
    }

    /**
     * Press a button.
     *
     * The capability is turned into a named button, the adapter is asked to press it,
     * and the outcome is reported as what actually happened. A set that is not paired
     * says so and names the prompt on the screen.
     */
    override suspend fun write(
        deviceId: String,
        capability: Capability,
        value: DeviceValue,
    ): TransportWrite {
        val entry = store.get(deviceId)
            ?: return TransportWrite.Failed(
                "No network television with id '$deviceId' is known. Run a discovery pass first.",
            )

        val remote = entry.remote
            ?: return TransportWrite.Failed(
                entry.unsupportedReason
                    ?: "No adapter is installed for this television, so nothing was sent.",
            )

        val button = buttonFor(capability, value)
            ?: return TransportWrite.Failed(
                "${entry.tv.name} over the network cannot express " +
                    "'${value.asComparable()}' for ${capability.name}.",
            )

        return when (val outcome = remote.press(button)) {
            is TvCommandOutcome.Failed -> TransportWrite.Failed(outcome.reason)
            is TvCommandOutcome.Sent -> {
                // A read-back is attempted rather than assumed. Where the make reports
                // state the fabric compares it; where it does not, the honest outcome is
                // NotObserved.
                val observed = read(deviceId)?.get(capability)
                if (observed != null) {
                    TransportWrite.Confirmed(observed)
                } else {
                    TransportWrite.NotObserved(value, null)
                }
            }
        }
    }

    /** What a set of this make can do, from the buttons its adapter really implements. */
    private fun capabilitiesFor(tv: DiscoveredTv): Set<Capability> {
        val caps = mutableSetOf<Capability>()
        if (TvRemoteFactory.supportedVendors.contains(tv.vendor)) {
            caps.add(Capability.POWER)
            caps.add(Capability.VOLUME)
            caps.add(Capability.MUTE)
        }
        // Roku reports its current app, so SOURCE is a real reading there and absent
        // elsewhere rather than offered and then empty.
        if (tv.vendor == TvVendor.ROKU) caps.add(Capability.SOURCE)
        return caps
    }

    /**
     * The vendor-neutral button a capability write means.
     *
     * A number on VOLUME is a step, so a positive value is volume up and a negative one
     * is down. That is what a person means by "turn it up", and refusing a number for a
     * key that only steps would make an ordinary request fail.
     */
    private fun buttonFor(capability: Capability, value: DeviceValue): TvButton? = when (capability) {
        Capability.POWER -> if (value is DeviceValue.Flag) TvButton.POWER else TvButton.POWER
        Capability.MUTE -> TvButton.MUTE
        Capability.VOLUME -> when (value) {
            is DeviceValue.Count -> if (value.value >= 0) TvButton.VOLUME_UP else TvButton.VOLUME_DOWN
            is DeviceValue.Measure -> if (value.value >= 0) TvButton.VOLUME_UP else TvButton.VOLUME_DOWN
            else -> TvButton.VOLUME_UP
        }
        Capability.SOURCE -> TvButton.HOME
        Capability.PLAYBACK -> TvButton.PLAY
        Capability.START -> TvButton.PLAY
        Capability.STOP -> TvButton.STOP
        Capability.PAUSE -> TvButton.PAUSE
        Capability.MODE -> TvButton.MENU
        else -> null
    }
}

/**
 * The discovered sets and their adapters, keyed by fabric id.
 *
 * WHY A STORE RATHER THAN A FIELD. Discovery and control are separate tool calls, so a
 * press arrives with only the id the fabric stored. Holding the adapters here means
 * each press reuses the token a pairing produced instead of re-prompting the person.
 */
class TvDeviceStore {

    data class Entry(
        val tv: DiscoveredTv,
        val remote: TvRemote?,
        val unsupportedReason: String? = null,
    )

    private val entries = java.util.concurrent.ConcurrentHashMap<String, Entry>()

    fun remember(tv: DiscoveredTv, remote: TvRemote?, reason: String? = null) {
        entries[tv.id] = Entry(tv, remote, reason)
    }

    fun get(id: String): Entry? = entries[id]

    fun all(): List<Entry> = entries.values.toList()

    fun clear() = entries.clear()
}
