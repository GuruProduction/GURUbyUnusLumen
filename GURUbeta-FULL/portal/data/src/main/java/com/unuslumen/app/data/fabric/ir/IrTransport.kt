// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

import com.unuslumen.app.data.fabric.Capability
import com.unuslumen.app.data.fabric.Device
import com.unuslumen.app.data.fabric.DeviceClass
import com.unuslumen.app.data.fabric.DeviceTransport
import com.unuslumen.app.data.fabric.DeviceValue
import com.unuslumen.app.data.fabric.Transport
import com.unuslumen.app.data.fabric.TransportDescriptor
import com.unuslumen.app.data.fabric.TransportWrite

/**
 * The IR transport: control anything with a remote. Issue #187.
 *
 * WHAT A TELEVISION ACTUALLY IS OVER IR, and why this is the layer that makes GURU
 * universal. A telly is not a Matter device and it is not a Home Assistant entity.
 * What every telly has, whatever the make, is an IR receiver that understands a
 * handful of named buttons. So GURU models an IR device as a fabric device whose
 * capabilities are the buttons it knows, and pressing a button is a write to a
 * capability. That is why this transport adds no new tool names: the five generic
 * fabric tools already cover it.
 *
 * THE HONEST LIMITS, each stated in a result rather than hidden:
 *
 *  - IR is one-way. There is no read-back path at all, so [read] returns null and a
 *    write can never be Confirmed. The fabric turns that into NotObserved, which is
 *    the true answer: the pulses left, and whether the telly obeyed is unknowable.
 *  - A phone with no emitter needs a network blaster. [IrTransmitterChoice] decides
 *    which one sends, and an absent both is a sentence naming what is missing.
 *  - A device is only controllable once a code for each button exists. A device with
 *    no codes reports that, rather than accepting a command it cannot turn into
 *    pulses.
 *
 * WHERE THE CODES COME FROM. An [IrCodeStore] supplies them, which is the seam the
 * bundled library and a user's own import both plug into. Learn mode also lands
 * here when the hardware allows a capture, which most built-in emitters do not.
 */
class IrTransport(
    private val transmitters: List<IrTransmitter>,
    private val store: IrCodeStore,
) : DeviceTransport {

    override val transport: Transport = Transport.IR

    override fun describe(): TransportDescriptor {
        val choice = IrTransmitterChoice.choose(transmitters)
        val transmitter = choice.transmitter
        if (transmitter == null) {
            return TransportDescriptor(
                transport = Transport.IR,
                displayName = "Infrared",
                available = false,
                unavailableReason = choice.reason ?: IrTransmitterChoice.absenceSentence(),
            )
        }
        val status = transmitter.status()
        // A transmitter can exist and have no codes yet. That is available, because
        // a device becomes controllable the moment a code is imported, and calling
        // it unavailable would be wrong.
        return TransportDescriptor(
            transport = Transport.IR,
            displayName = "Infrared through ${status.displayName}",
            available = true,
            unavailableReason = null,
        )
    }

    /**
     * Every IR device with at least one code.
     *
     * A device with no codes is genuinely not controllable, so it is not reported.
     * Reporting it would put a device in the list that refuses every command, which
     * reads as a broken app rather than as an empty code set.
     */
    override suspend fun discover(): List<Device> = store.devices()

    /**
     * Read a device's state.
     *
     * Always null, because IR has no return path. This is the single most important
     * line in the file: returning a cached "on" would make the fabric claim to know
     * something about a television that nothing on this phone can observe.
     */
    override suspend fun read(deviceId: String): Map<Capability, DeviceValue>? = null

    /**
     * Press one button.
     *
     * Resolves the device, resolves the capability to a named button, finds the code
     * for that button, builds the pulse train and transmits it. Every step that fails
     * returns Failed with its own reason, so "no emitter", "no code for that button"
     * and "the platform refused" are three different answers.
     */
    override suspend fun write(
        deviceId: String,
        capability: Capability,
        value: DeviceValue,
    ): TransportWrite {
        val device = store.device(deviceId)
            ?: return TransportWrite.Failed("No infrared device with id '$deviceId' is known.")

        /*
         * WHAT COUNTS AS A PRESS.
         *
         * An IR remote has buttons, not values, so every command has to become a
         * button press. Three shapes are presses and one is not:
         *
         *  - true, because "power on" and "play" are presses.
         *  - text, because "source HDMI1" is a button with a name behind it.
         *  - a number on a STEP capability, because volume, channel, fan speed and
         *    temperature are reached by pressing up or down. A person saying "turn it
         *    up" must not be refused just because they gave a number.
         *
         * false is NOT a press for POWER: a remote has one power button that toggles,
         * so "turn it off specifically" cannot be expressed as pulses, and sending a
         * toggle while claiming it turned the set off would be a fabricated fact.
         */
        val pressed = when (value) {
            is DeviceValue.Flag -> value.value
            is DeviceValue.Text -> true
            is DeviceValue.Count, is DeviceValue.Measure ->
                capability in IrButtonMapping.STEP_CAPABILITIES &&
                    (value.asComparable() as Number).toDouble() != 0.0
            else -> false
        }
        if (!pressed) {
            return TransportWrite.Failed(
                "${device.name} over infrared takes a press, not a value: " +
                    "'${value.asComparable()}' cannot be sent as pulses. " +
                    if (capability in IrButtonMapping.STEP_CAPABILITIES) {
                        "Pass a non-zero step, such as 1, to press ${IrButtonMapping.buttonFor(capability, device.deviceClass) ?: "the button"}."
                    } else {
                        "For ${capability.name}, press true."
                    },
            )
        }

        val button = IrButtonMapping.buttonFor(capability, device.deviceClass)
            ?: return TransportWrite.Failed(
                "${device.name} reports ${capability.name} but infrared has no button for it.",
            )

        // The device carries its buttons as raw protocol data rather than as codes,
        // so the code itself is read from the store by family id. That keeps the
        // fabric's Device model free of this transport's own vocabulary.
        val family = store.family(deviceId)
            ?: return TransportWrite.Failed("No infrared code set is stored for '${device.name}'.")

        val code = family.code(button)
            ?: return TransportWrite.Failed(
                "${device.name} has no code recorded for '$button'. Its recorded buttons are: " +
                    family.buttons.joinToString(", ").ifEmpty { "none" } + ".",
            )

        val plan = IrTransmitPlan.plan(code)
        if (plan.signal == null) {
            return TransportWrite.Failed(
                "The code for '$button' cannot be turned into pulses: ${plan.reason ?: "no reason given"}.",
            )
        }

        val choice = IrTransmitterChoice.choose(transmitters)
        val transmitter = choice.transmitter
            ?: return TransportWrite.Failed(choice.reason ?: IrTransmitterChoice.absenceSentence())

        return when (val outcome = transmitter.transmit(plan.signal)) {
            is TransmitOutcome.Failed -> TransportWrite.Failed(outcome.reason)
            is TransmitOutcome.Sent ->
                // NEVER Confirmed. The pulses left the phone and nothing on this
                // device can read a television back, so the honest answer is
                // "sent and unobserved" rather than a success the fabric would then
                // store as truth.
                TransportWrite.NotObserved(value, null)
        }
    }
}

/**
 * Capability to button name.
 *
 * THE TRANSLATION THIS TRANSPORT EXISTS FOR. The fabric's vocabulary is
 * capability-driven and vendor-neutral, and an IR remote's vocabulary is buttons.
 * One table decides the mapping, so a new capability is added in one place rather
 * than guessed at in a transport branch.
 */
object IrButtonMapping {

    /**
     * Capabilities reached by pressing up or down rather than by holding a value.
     *
     * A television's volume is not a number it stores; it is a pair of buttons. So a
     * numeric value on one of these is a step to press, which is why they are listed
     * rather than inferred from the value's type.
     */
    val STEP_CAPABILITIES: Set<Capability> = setOf(
        Capability.VOLUME,
        Capability.FAN_SPEED,
        Capability.TARGET_TEMPERATURE,
    )

    fun buttonFor(capability: Capability, deviceClass: DeviceClass): String? = when (capability) {
        Capability.POWER -> "power"
        Capability.VOLUME -> "vol_up"
        Capability.MUTE -> "mute"
        Capability.SOURCE -> "input"
        Capability.PLAYBACK -> "play"
        Capability.START -> "play"
        Capability.STOP -> "stop"
        Capability.PAUSE -> "pause"
        Capability.MODE -> "menu"
        Capability.FAN_SPEED -> "up"
        Capability.TARGET_TEMPERATURE -> "up"
        else -> null
    }

    /**
     * The capabilities a device of this class is given when its codes are read.
     *
     * A television with a power button and nothing else gets POWER alone, because
     * the fabric refuses a capability the device does not report and inventing one
     * here would produce a refusal that reads like a bug.
     */
    fun capabilitiesFor(deviceClass: DeviceClass, buttons: Collection<String>): Set<Capability> {
        val present = buttons.map { IrButton.normalise(it) }.toSet()
        val caps = mutableSetOf<Capability>()

        // A capability is claimed only where every button it needs exists. VOLUME is
        // the case that taught this: a set with only volume down reports VOLUME, the
        // fabric allows the command, and the transport then has to refuse because
        // there is no vol_up code. That refusal reads as a bug. Requiring both
        // directions means a half-recorded make simply does not claim volume, and the
        // fabric's own refusal says so in one sentence before anything is sent.
        if ("power" in present) caps.add(Capability.POWER)
        if ("vol_up" in present && "vol_dn" in present) caps.add(Capability.VOLUME)
        if ("mute" in present) caps.add(Capability.MUTE)
        if ("input" in present) caps.add(Capability.SOURCE)
        if ("play" in present) caps.add(Capability.PLAYBACK)
        if ("stop" in present) caps.add(Capability.STOP)
        return caps
    }
}
