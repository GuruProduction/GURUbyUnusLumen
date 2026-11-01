// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

/**
 * The two ways a signal physically leaves the phone. Issue #187.
 *
 * THE DISTINCTION THAT SHAPES THIS FILE. A phone either has an IR LED behind its
 * top edge or it does not, and no code makes one appear. The issue's answer is
 * two backends behind one interface: the built-in emitter where the hardware
 * exists, and a network blaster otherwise. Every caller above this interface
 * therefore asks "can anything transmit" and gets either a real answer or a
 * sentence naming what is missing, never a silent nothing.
 *
 * WHAT A TRANSMITTER IS NOT ALLOWED TO DO. It does not decide whether a command
 * is allowed, and it does not report success it did not observe. The fabric owns
 * both. A transmitter returns what the platform actually did.
 */

/** What a transmitter is and whether it can send right now. */
data class TransmitterStatus(
    /** Stable id used in results, e.g. "builtin" or "broadlink". */
    val id: String,
    /** What to call it to a person: "this phone's IR blaster". */
    val displayName: String,
    val available: Boolean,
    /** Why it is unavailable, in one sentence. Null when it is available. */
    val unavailableReason: String?,
    /** Carrier frequencies the hardware claims, when it reports them. */
    val carrierFrequencies: List<IntRange> = emptyList(),
)

/** The outcome of one transmit attempt, with the platform's own words where it refused. */
sealed interface TransmitOutcome {
    /** The platform accepted the pulses. It is not proof a device responded. */
    data class Sent(val note: String) : TransmitOutcome

    /** The attempt did not happen, with a reason worth showing a person. */
    data class Failed(val reason: String) : TransmitOutcome
}

/**
 * One way of getting a pulse train out of the phone.
 *
 * Every method is suspend because every real backend does I/O: a broadcast over
 * UDP, or a binder call into the consumer IR service.
 */
interface IrTransmitter {

    fun status(): TransmitterStatus

    /**
     * Transmit one signal.
     *
     * The caller has already decided the signal is right. This only sends it and
     * reports what happened.
     */
    suspend fun transmit(signal: IrSignal): TransmitOutcome
}

/**
 * Choosing between the backends.
 *
 * THE ORDER IS DELIBERATE. The built-in emitter is tried first because it needs
 * no network, no pairing and no second device, so it is the one that works in a
 * pub with no wifi. A network blaster is the answer only when the phone has no
 * emitter of its own.
 *
 * This object decides nothing else. It does not fall back after a failed
 * transmit, because a failed transmit on a working emitter is a wrong code and
 * blasting it again over the network would be the same wrong code twice.
 */
object IrTransmitterChoice {

    /**
     * The transmitter that should send this, or the reason there is none.
     *
     * WHY THE REASON CARRIES WHO SAID IT. With more than one backend, "not available"
     * alone does not tell a person whether their phone lacks an emitter or their
     * blaster is switched off. Prefixing each backend's own sentence with its name
     * keeps both answers, and a single backend's sentence is passed through untouched
     * so it reads as one voice.
     */
    fun choose(transmitters: List<IrTransmitter>): Result {
        val available = transmitters.filter { it.status().available }
        if (available.isNotEmpty()) return Result(available.first(), null)
        if (transmitters.isEmpty()) {
            return Result(
                null,
                "This build has no IR transmitter at all, which is a fault rather than a missing " +
                    "permission: " + absenceSentence(),
            )
        }

        val reasons = transmitters.map { transmitter ->
            val status = transmitter.status()
            val own = status.unavailableReason ?: "not available, with no reason given"
            if (transmitters.size == 1) own else "${status.displayName}: $own"
        }
        return Result(null, reasons.joinToString(" "))
    }

    data class Result(val transmitter: IrTransmitter?, val reason: String?)

    /**
     * The one sentence a person sees when nothing can transmit.
     *
     * Written here rather than at each call site so the two backends cannot drift
     * into two different explanations of the same absence.
     */
    fun absenceSentence(): String =
        "This phone has no IR blaster and no network IR blaster is configured, so nothing can " +
            "send the signal. A phone with an emitter works directly, and a network blaster such " +
            "as a Broadlink RM4 fills that role on a phone without one."
}
