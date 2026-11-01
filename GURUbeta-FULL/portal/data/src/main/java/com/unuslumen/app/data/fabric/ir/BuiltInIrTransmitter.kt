// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

import android.content.Context
import android.hardware.ConsumerIrManager
import android.util.Log

/**
 * The phone's own IR blaster. Issue #187.
 *
 * THE HARDWARE REALITY, SAID ONCE. Most phones have no IR LED, and Android exposes
 * the ones that do through [ConsumerIrManager]. `hasIrEmitter()` is the only
 * honest test of whether the LED exists, because it asks the consumer IR service
 * rather than reading a feature flag that many manufactures get wrong.
 *
 * THE PERMISSION IS A NORMAL ONE. `android.permission.TRANSMIT_IR` is
 * protectionLevel normal, granted at install with no dialog. So an absent
 * transmitter is never "you have not granted this": it is either the hardware or
 * the manifest, and both are reported as what they are.
 *
 * WHAT THIS CANNOT DO. `ConsumerIrManager` transmits only. It cannot receive, so
 * learn mode is impossible on a built-in emitter and the result says so rather
 * than offering a capture that cannot happen.
 */
class BuiltInIrTransmitter(private val context: Context) : IrTransmitter {

    private companion object {
        const val TAG = "guru_ir"
    }

    /**
     * The consumer IR service, or null when the platform has none.
     *
     * Read through getSystemService rather than by a feature flag: the service is
     * what actually transmits, and a device can declare the feature without
     * exposing a working service.
     */
    private val manager: ConsumerIrManager?
        get() = runCatching {
            context.getSystemService(Context.CONSUMER_IR_SERVICE) as? ConsumerIrManager
        }.getOrNull()

    /**
     * Whether an LED genuinely exists.
     *
     * A null manager and a false hasIrEmitter are different faults: the first is a
     * platform with no consumer IR service at all, the second is a platform that
     * has the service and reports no emitter. They produce different sentences.
     */
    fun hasEmitter(): Boolean = runCatching { manager?.hasIrEmitter() == true }.getOrDefault(false)

    override fun status(): TransmitterStatus {
        val service = manager
        if (service == null) {
            return TransmitterStatus(
                id = "builtin",
                displayName = "this phone's IR blaster",
                available = false,
                unavailableReason = "This phone's platform has no consumer IR service, so there is " +
                    "nothing to transmit through even if the handset carries an LED.",
            )
        }
        val emitter = runCatching { service.hasIrEmitter() }.getOrDefault(false)
        if (!emitter) {
            return TransmitterStatus(
                id = "builtin",
                displayName = "this phone's IR blaster",
                available = false,
                unavailableReason = "This phone reports no infrared emitter, so it cannot send a " +
                    "signal however the app is set up. A network IR blaster covers this case.",
            )
        }
        val ranges = runCatching {
            service.carrierFrequencies?.map { it.minFrequency..it.maxFrequency } ?: emptyList()
        }.getOrDefault(emptyList())
        return TransmitterStatus(
            id = "builtin",
            displayName = "this phone's IR blaster",
            available = true,
            unavailableReason = null,
            carrierFrequencies = ranges,
        )
    }

    /**
     * Transmit a signal.
     *
     * The pulse array is passed straight through: it is already alternating
     * microseconds beginning with a mark, which is the contract
     * [ConsumerIrManager.transmit] documents. Reordering or normalising it here
     * would break the code the encoder just proved.
     */
    override suspend fun transmit(signal: IrSignal): TransmitOutcome {
        val service = manager
            ?: return TransmitOutcome.Failed(
                "This phone's platform has no consumer IR service, so nothing was transmitted.",
            )
        if (runCatching { service.hasIrEmitter() }.getOrDefault(false) != true) {
            return TransmitOutcome.Failed(
                "This phone reports no infrared emitter, so nothing was transmitted.",
            )
        }
        if (signal.pulses.isEmpty()) {
            return TransmitOutcome.Failed("The signal has no pulses, so there is nothing to send.")
        }

        // A carrier the hardware does not claim is refused before transmitting,
        // because the platform silently does nothing with an unsupported carrier
        // on some devices and that reads exactly like a wrong code.
        val ranges = runCatching { service.carrierFrequencies }.getOrNull()
        if (!ranges.isNullOrEmpty()) {
            val supported = ranges.any { signal.carrierHz in it.minFrequency..it.maxFrequency }
            if (!supported) {
                val listed = ranges.joinToString(", ") { "${it.minFrequency}-${it.maxFrequency} Hz" }
                return TransmitOutcome.Failed(
                    "The emitter reports carrier support for $listed, so it cannot send " +
                        "${signal.carrierHz} Hz and nothing was transmitted.",
                )
            }
        }

        return runCatching {
            service.transmit(signal.carrierHz, signal.pulses)
            Log.d(TAG, "IR transmitted: ${signal.pulses.size} pulses at ${signal.carrierHz} Hz")
            TransmitOutcome.Sent(
                "Sent ${signal.pulses.size} pulses at ${signal.carrierHz / 1000} kHz through the " +
                    "phone's own emitter. ${signal.note}",
            )
        }.getOrElse { error ->
            // The platform's own exception message is the reason, because it names
            // the real fault: a carrier it cannot do, or an LED it lost.
            TransmitOutcome.Failed(
                "The platform refused the transmission: ${error.message ?: error.javaClass.simpleName}.",
            )
        }
    }
}
