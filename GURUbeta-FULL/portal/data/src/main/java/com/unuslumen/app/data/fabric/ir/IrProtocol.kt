// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

/**
 * The IR protocols GURU can synthesise, and the encoder that turns a named code
 * into the pulse train a transmitter has to emit. Issue #187.
 *
 * WHY THIS IS PURE KOTLIN WITH NO ANDROID IN IT. Whether a telly responds is
 * decided entirely by the numbers this file produces: the carrier frequency and
 * the alternating on/off timings in microseconds. Android's ConsumerIrManager is
 * a dumb pipe that sends whatever array it is handed, so the only thing that can
 * be wrong is the array. Keeping that decision here means a JVM test can assert
 * the exact pulses against a hand-computed reference, which is a far better proof
 * than pointing a phone at a television and hoping.
 *
 * THE ONE RULE EVERY PROTOCOL OBEYS. A pulse train is a strictly alternating
 * sequence that begins with a mark and ends with a space. Real protocols mix
 * encodings freely (biphase, pulse-distance, pulse-length), so frames are built
 * from [Atom]s that carry their own phase, and [IrFrame.build] coalesces and
 * checks them. That makes an illegal train impossible to emit rather than
 * something to notice later when a telly silently ignores it.
 *
 * WHERE THE TIMINGS COME FROM. These are the published timings every IR library
 * agrees on (IRremoteESP8266, LIRC, Flipper). They are constants rather than
 * measured captures, because a capture carries the receiver's own timing error
 * into the transmitter.
 *
 * WHAT THIS FILE CANNOT DO. It cannot invent a code for a device it has never
 * seen. A protocol plus an address plus a command is a device's identity, and the
 * code databases supply those triples. An encoder with no database is an empty
 * machine.
 */

/** Carrier frequency and bit-level shape of one named code. */
class IrSignal(
    /** Carrier in Hz, e.g. 38000. Handed to the emitter as the carrier frequency. */
    val carrierHz: Int,
    /**
     * Alternating on/off durations in microseconds, ALWAYS starting with a mark
     * and ending with a space. That is the shape ConsumerIrManager.transmit
     * expects, and a wrong starting phase makes every protocol silent.
     */
    val pulses: IntArray,
    /** Human sentence about what was emitted, for the tool result. */
    val note: String,
) {
    // IntArray is excluded from data-class equality, so equality is written out.
    // The tests compare signals, and reference equality would make them useless.
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IrSignal) return false
        return carrierHz == other.carrierHz && pulses.contentEquals(other.pulses)
    }

    override fun hashCode(): Int = 31 * carrierHz + pulses.contentHashCode()

    override fun toString(): String =
        "IrSignal(${carrierHz}Hz, ${pulses.size} pulses, ${pulses.joinToString(",")})"
}

/** The protocol names the code databases use, normalised. */
enum class IrProtocolName {
    NEC,
    NEC_EXT,
    NEC42,
    NEC42_EXT,
    SAMSUNG32,
    SIRC,
    SIRC15,
    SIRC20,
    RC5,
    RC5X,
    RC6,
    KASEIKYO,
    JVC,
    RCA,
    PIONEER,
    /** Timings supplied directly, as a learned capture or a LIRC raw code. */
    RAW,
    ;

    companion object {
        /**
         * Map a database's spelling onto this enum.
         *
         * The databases disagree on capitals and separators ("Samsung32",
         * "SAMSUNG", "NECext", "NEC Extended"), so the lookup strips separators
         * and compares case-insensitively rather than listing every spelling.
         */
        fun fromRaw(raw: String?): IrProtocolName? {
            val key = raw?.trim()?.lowercase()
                ?.replace("_", "")?.replace("-", "")?.replace(" ", "")
                ?: return null
            return when (key) {
                "nec" -> NEC
                "necext", "necextended", "necx" -> NEC_EXT
                "nec42" -> NEC42
                "nec42ext", "nec42extended" -> NEC42_EXT
                "samsung", "samsung32", "samsung36" -> SAMSUNG32
                "sirc", "sony", "sonysirc", "sirc12" -> SIRC
                "sirc15", "sony15" -> SIRC15
                "sirc20", "sony20" -> SIRC20
                "rc5" -> RC5
                "rc5x", "rc5extended" -> RC5X
                "rc6" -> RC6
                "kaseikyo", "panasonic", "panasonickaseikyo" -> KASEIKYO
                "jvc" -> JVC
                "rca" -> RCA
                "pioneer" -> PIONEER
                "raw", "parsedraw" -> RAW
                else -> null
            }
        }
    }
}

/** One run of carrier-on or carrier-off, with its own duration. */
data class Atom(val mark: Boolean, val micros: Int)

/**
 * Frame assembly, kept separate from the protocol maths so the one invariant that
 * matters is enforced in exactly one place.
 */
object IrFrame {

    /**
     * Coalesce atoms into an alternating pulse train.
     *
     * Adjacent atoms of the same phase are summed, because two consecutive marks
     * are physically one longer mark and the emitter has no way to express the
     * seam. A leading space is dropped and a trailing mark is closed with a gap,
     * so the result always starts on a mark and ends on a space.
     */
    fun build(carrierHz: Int, atoms: List<Atom>, note: String): IrSignal {
        val merged = ArrayList<Atom>(atoms.size)
        for (atom in atoms) {
            if (atom.micros <= 0) continue
            val last = merged.lastOrNull()
            if (last != null && last.mark == atom.mark) {
                merged[merged.lastIndex] = last.copy(micros = last.micros + atom.micros)
            } else {
                merged.add(atom)
            }
        }

        // Drop a leading space: the emitter drives a mark first and a leading
        // space would be indistinguishable from the gap before the frame.
        while (merged.isNotEmpty() && !merged.first().mark) merged.removeAt(0)
        if (merged.isEmpty()) {
            return IrSignal(carrierHz, IntArray(0), "no pulses")
        }
        // Close a trailing mark with a gap so the receiver sees the frame end.
        if (merged.last().mark) merged.add(Atom(mark = false, micros = FRAME_CLOSE_GAP))

        return IrSignal(
            carrierHz = carrierHz,
            pulses = IntArray(merged.size) { merged[it].micros },
            note = note,
        )
    }

    /** Every protocol's frame ends with a gap; 10 ms is inside every spec's window. */
    const val FRAME_CLOSE_GAP = 10000
}

/**
 * The encoder. One function, one switch, no Android.
 *
 * [address] and [command] are the two numbers a code database records. How they
 * are packed differs per protocol, so each branch states its own bit order rather
 * than sharing a helper that would hide the difference.
 *
 * Returns null for a protocol this build cannot synthesise, so a caller reports
 * "not supported" instead of transmitting silence.
 */
object IrEncoder {

    // NEC family. 38 kHz, complement pairs are the protocol's own checksum and a
    // receiver rejects a frame without them.
    private const val NEC_CARRIER = 38000
    private const val NEC_HDR_MARK = 9000
    private const val NEC_HDR_SPACE = 4500
    private const val NEC_BIT_MARK = 560
    private const val NEC_ONE_SPACE = 1690
    private const val NEC_ZERO_SPACE = 560
    private const val NEC_FRAME_GAP = 40000

    // Samsung: the same bit shape with a balanced header and a short gap.
    private const val SAMSUNG_CARRIER = 38000
    private const val SAMSUNG_HDR_MARK = 4500
    private const val SAMSUNG_HDR_SPACE = 4500
    private const val SAMSUNG_FRAME_GAP = 5000

    // Sony encodes the bit in the MARK, not the space, which is why it cannot
    // share the pulse-distance helper.
    private const val SONY_CARRIER = 40000
    private const val SONY_HDR_MARK = 2400
    private const val SONY_HDR_SPACE = 600
    private const val SONY_ONE_MARK = 1200
    private const val SONY_ZERO_MARK = 600
    private const val SONY_BIT_SPACE = 600
    private const val SONY_FRAME_GAP = 25000

    // Philips RC5 is biphase at 36 kHz with a 889 us half-bit.
    private const val RC5_CARRIER = 36000
    private const val RC5_HALF_BIT = 889

    // RC6 is biphase at 36 kHz with a 444 us base unit and a 6-unit leader.
    private const val RC6_CARRIER = 36000
    private const val RC6_UNIT = 444

    // Panasonic's Kaseikyo.
    private const val KASEIKYO_CARRIER = 37000
    private const val KASEIKYO_HDR_MARK = 3456
    private const val KASEIKYO_HDR_SPACE = 1728
    private const val KASEIKYO_BIT_MARK = 432
    private const val KASEIKYO_ONE_SPACE = 1296
    private const val KASEIKYO_ZERO_SPACE = 432
    private const val KASEIKYO_FRAME_GAP = 74000

    // JVC.
    private const val JVC_CARRIER = 38000
    private const val JVC_HDR_MARK = 8400
    private const val JVC_HDR_SPACE = 4200
    private const val JVC_BIT_MARK = 526
    private const val JVC_ONE_SPACE = 1725
    private const val JVC_ZERO_SPACE = 526
    private const val JVC_FRAME_GAP = 40000

    // RCA, the one common TV protocol at 58 kHz.
    private const val RCA_CARRIER = 58000
    private const val RCA_HDR_MARK = 4000
    private const val RCA_HDR_SPACE = 4000
    private const val RCA_BIT_MARK = 500
    private const val RCA_ONE_SPACE = 2000
    private const val RCA_ZERO_SPACE = 1000
    private const val RCA_FRAME_GAP = 40000

    // Pioneer: NEC-shaped at 40 kHz with a shorter header and gap.
    private const val PIONEER_CARRIER = 40000
    private const val PIONEER_HDR_MARK = 8500
    private const val PIONEER_HDR_SPACE = 4250
    private const val PIONEER_BIT_MARK = 500
    private const val PIONEER_ONE_SPACE = 1500
    private const val PIONEER_ZERO_SPACE = 500
    private const val PIONEER_FRAME_GAP = 25000

    /**
     * Encode a frame from a LIRC timing specification.
     *
     * A LIRC remote states its own header, bit timings and gap, so this needs no
     * protocol name: the file has already said what the pulses are. The code value is
     * sent as [bits] bits in the order the remote's flags declare.
     */
    fun encodeLirc(timing: LircTiming, value: Int, repeats: Int = 1): IrSignal {
        val width = timing.bits.coerceIn(1, 32)
        val order: List<Int> = if (timing.msbFirst) {
            msbFirst(value, width)
        } else {
            lsbFirst(value, width)
        }
        val atoms = ArrayList<Atom>(order.size * 2 + 4)
        repeat(repeats.coerceIn(1, 10)) {
            if (it > 0) atoms.add(Atom(mark = false, micros = timing.gap))
            atoms.add(Atom(mark = true, micros = timing.headerMark))
            atoms.add(Atom(mark = false, micros = timing.headerSpace))
            for (bit in order) {
                if (bit == 1) {
                    atoms.add(Atom(mark = true, micros = timing.oneMark))
                    atoms.add(Atom(mark = false, micros = timing.oneSpace))
                } else {
                    atoms.add(Atom(mark = true, micros = timing.zeroMark))
                    atoms.add(Atom(mark = false, micros = timing.zeroSpace))
                }
            }
            // The frame's closing mark, as every pulse-distance protocol needs.
            atoms.add(Atom(mark = true, micros = timing.oneMark.takeIf { it > 0 } ?: timing.headerMark))
        }
        return IrFrame.build(
            LIRC_DEFAULT_CARRIER,
            atoms,
            "LIRC timings, $width bits, ${LIRC_DEFAULT_CARRIER / 1000} kHz.",
        )
    }

    /**
     * The carrier a LIRC file without a `frequency` line means.
     *
     * LIRC's own default is 38 kHz, which is the most common IR carrier by a wide
     * margin, so a file that omits the line is taken at the documented default rather
     * than refused.
     */
    private const val LIRC_DEFAULT_CARRIER = 38000

    fun encode(
        protocol: IrProtocolName,
        address: Int,
        command: Int,
        repeats: Int = 1,
    ): IrSignal? {
        val times = repeats.coerceIn(1, 10)
        return when (protocol) {
            IrProtocolName.NEC -> pulseDistance(
                carrier = NEC_CARRIER,
                hdrMark = NEC_HDR_MARK,
                hdrSpace = NEC_HDR_SPACE,
                bitMark = NEC_BIT_MARK,
                oneSpace = NEC_ONE_SPACE,
                zeroSpace = NEC_ZERO_SPACE,
                gap = NEC_FRAME_GAP,
                bits = bytesWithComplements(address, command),
                repeats = times,
                note = "NEC, 32 bits, 38 kHz.",
            )

            IrProtocolName.NEC_EXT -> pulseDistance(
                carrier = NEC_CARRIER,
                hdrMark = NEC_HDR_MARK,
                hdrSpace = NEC_HDR_SPACE,
                bitMark = NEC_BIT_MARK,
                oneSpace = NEC_ONE_SPACE,
                zeroSpace = NEC_ZERO_SPACE,
                gap = NEC_FRAME_GAP,
                // Flipper's NECext carries a 16-bit address and a 16-bit command,
                // and sends each byte of both followed by its complement. That is
                // 64 bits, which is why an NECext code cannot be sent as a plain
                // NEC code: the extra pairs tell one remote from another.
                bits = bytesWithComplements(address and 0xFF, command and 0xFF) +
                    bytesWithComplements((address ushr 8) and 0xFF, (command ushr 8) and 0xFF),
                repeats = times,
                note = "NEC extended, 64 bits, 38 kHz.",
            )

            IrProtocolName.NEC42 -> pulseDistance(
                carrier = NEC_CARRIER,
                hdrMark = NEC_HDR_MARK,
                hdrSpace = NEC_HDR_SPACE,
                bitMark = NEC_BIT_MARK,
                oneSpace = NEC_ONE_SPACE,
                zeroSpace = NEC_ZERO_SPACE,
                gap = NEC_FRAME_GAP,
                bits = lsbFirst(address and 0xFF, 8) +
                    lsbFirst((address and 0xFF).inv() and 0xFF, 8) +
                    lsbFirst(command and 0x3FFF, 14) +
                    lsbFirst((command and 0x3FFF).inv() and 0x3FFF, 12),
                repeats = times,
                note = "NEC 42-bit, 38 kHz.",
            )

            IrProtocolName.NEC42_EXT -> pulseDistance(
                carrier = NEC_CARRIER,
                hdrMark = NEC_HDR_MARK,
                hdrSpace = NEC_HDR_SPACE,
                bitMark = NEC_BIT_MARK,
                oneSpace = NEC_ONE_SPACE,
                zeroSpace = NEC_ZERO_SPACE,
                gap = NEC_FRAME_GAP,
                bits = lsbFirst(address and 0x3FFF, 14) +
                    lsbFirst((address and 0x3FFF).inv() and 0x3FFF, 14) +
                    lsbFirst(command and 0x3FFF, 14),
                repeats = times,
                note = "NEC 42-bit extended, 38 kHz.",
            )

            IrProtocolName.SAMSUNG32 -> pulseDistance(
                carrier = SAMSUNG_CARRIER,
                hdrMark = SAMSUNG_HDR_MARK,
                hdrSpace = SAMSUNG_HDR_SPACE,
                bitMark = NEC_BIT_MARK,
                oneSpace = NEC_ONE_SPACE,
                zeroSpace = NEC_ZERO_SPACE,
                gap = SAMSUNG_FRAME_GAP,
                bits = bytesWithComplements(address, command),
                repeats = times,
                note = "Samsung 32-bit, 38 kHz.",
            )

            IrProtocolName.SIRC, IrProtocolName.SIRC15, IrProtocolName.SIRC20 -> {
                // Sony puts the device address in the HIGH bits and the command in
                // the low seven, and sends command first.
                val addressBits = when (protocol) {
                    IrProtocolName.SIRC -> 5
                    IrProtocolName.SIRC15 -> 8
                    else -> 13
                }
                val bits = lsbFirst(command and 0x7F, 7) +
                    lsbFirst(address and ((1 shl addressBits) - 1), addressBits)
                pulseLength(bits, times)
            }

            IrProtocolName.RC5, IrProtocolName.RC5X -> rc5(
                // 14 bits, most significant first: two start bits, a toggle that
                // stays put across a held button, five address bits, six command
                // bits. RC5X borrows the top command bit to carry a seventh, which
                // is why the same command number is a different button.
                bits = listOf(1, 1, 0) +
                    msbFirst(address and 0x1F, 5) +
                    msbFirst(command and 0x7F, 6),
                extended = protocol == IrProtocolName.RC5X,
                repeats = times,
            )

            IrProtocolName.RC6 -> rc6(address and 0xFF, command and 0xFF, times)

            IrProtocolName.KASEIKYO -> pulseDistance(
                carrier = KASEIKYO_CARRIER,
                hdrMark = KASEIKYO_HDR_MARK,
                hdrSpace = KASEIKYO_HDR_SPACE,
                bitMark = KASEIKYO_BIT_MARK,
                oneSpace = KASEIKYO_ONE_SPACE,
                zeroSpace = KASEIKYO_ZERO_SPACE,
                gap = KASEIKYO_FRAME_GAP,
                // 48 bits: a 16-bit manufacturer code in the address field, an
                // 8-bit genre, then the command with its own complement.
                bits = lsbFirst(address and 0xFFFF, 16) +
                    lsbFirst((command ushr 8) and 0xFF, 8) +
                    lsbFirst(command and 0xFF, 8) +
                    lsbFirst((command and 0xFF).inv() and 0xFF, 8),
                repeats = times,
                note = "Kaseikyo (Panasonic), 48 bits, 37 kHz.",
            )

            IrProtocolName.JVC -> pulseDistance(
                carrier = JVC_CARRIER,
                hdrMark = JVC_HDR_MARK,
                hdrSpace = JVC_HDR_SPACE,
                bitMark = JVC_BIT_MARK,
                oneSpace = JVC_ONE_SPACE,
                zeroSpace = JVC_ZERO_SPACE,
                gap = JVC_FRAME_GAP,
                bits = lsbFirst(address and 0xFF, 8) + lsbFirst(command and 0xFF, 8),
                repeats = times,
                note = "JVC, 16 bits, 38 kHz.",
            )

            IrProtocolName.RCA -> pulseDistance(
                carrier = RCA_CARRIER,
                hdrMark = RCA_HDR_MARK,
                hdrSpace = RCA_HDR_SPACE,
                bitMark = RCA_BIT_MARK,
                oneSpace = RCA_ONE_SPACE,
                zeroSpace = RCA_ZERO_SPACE,
                gap = RCA_FRAME_GAP,
                bits = lsbFirst(address and 0xFF, 8) + lsbFirst(command and 0xFF, 8),
                repeats = times,
                note = "RCA, 16 bits, 58 kHz.",
            )

            IrProtocolName.PIONEER -> pulseDistance(
                carrier = PIONEER_CARRIER,
                hdrMark = PIONEER_HDR_MARK,
                hdrSpace = PIONEER_HDR_SPACE,
                bitMark = PIONEER_BIT_MARK,
                oneSpace = PIONEER_ONE_SPACE,
                zeroSpace = PIONEER_ZERO_SPACE,
                gap = PIONEER_FRAME_GAP,
                bits = lsbFirst(address and 0xFF, 8) + lsbFirst(command and 0xFF, 8),
                repeats = times,
                note = "Pioneer, 16 bits, 40 kHz.",
            )

            // Raw timings come from a learned capture and are already a pulse
            // train, so there is nothing for the encoder to synthesise.
            IrProtocolName.RAW -> null
        }
    }

    // ------------------------------------------------------------------
    // Bit helpers
    // ------------------------------------------------------------------

    /** A bit list for one value, least significant first, as NEC and Sony send it. */
    private fun lsbFirst(value: Int, count: Int): List<Int> =
        (0 until count).map { (value ushr it) and 1 }

    /** A bit list most significant first, as RC5 sends it. */
    private fun msbFirst(value: Int, count: Int): List<Int> =
        (count - 1 downTo 0).map { (value ushr it) and 1 }

    /**
     * The four bytes NEC and Samsung send: address, its complement, command, its
     * complement, each least significant bit first.
     */
    private fun bytesWithComplements(address: Int, command: Int): List<Int> =
        listOf(
            address and 0xFF,
            (address and 0xFF).inv() and 0xFF,
            command and 0xFF,
            (command and 0xFF).inv() and 0xFF,
        ).flatMap { lsbFirst(it, 8) }

    // ------------------------------------------------------------------
    // Frame builders
    // ------------------------------------------------------------------

    /**
     * Pulse-distance framing: a header, then one mark per bit with the bit encoded
     * as the space that follows, closed by a trailing mark. Used by NEC, Samsung,
     * Kaseikyo, JVC, RCA and Pioneer.
     */
    private fun pulseDistance(
        carrier: Int,
        hdrMark: Int,
        hdrSpace: Int,
        bitMark: Int,
        oneSpace: Int,
        zeroSpace: Int,
        gap: Int,
        bits: List<Int>,
        repeats: Int,
        note: String,
    ): IrSignal {
        val atoms = ArrayList<Atom>(bits.size * 2 + 4)
        repeat(repeats) {
            // The gap sits between frames, and is a space, so it must not be
            // emitted after the closing mark of the previous frame.
            if (it > 0) atoms.add(Atom(mark = false, micros = gap))
            atoms.add(Atom(mark = true, micros = hdrMark))
            atoms.add(Atom(mark = false, micros = hdrSpace))
            for (bit in bits) {
                atoms.add(Atom(mark = true, micros = bitMark))
                atoms.add(Atom(mark = false, micros = if (bit == 1) oneSpace else zeroSpace))
            }
            // THE CLOSING MARK IS PART OF THE PROTOCOL, not decoration. NEC, Samsung
            // and their relatives end a frame with one more mark of the bit length
            // before the gap. Without it the frame stops on a space, so the last bit
            // has no terminating edge and a receiver discards the whole frame. That
            // failure is silent, which is why it is pinned by a test.
            atoms.add(Atom(mark = true, micros = bitMark))
        }
        return IrFrame.build(
            carrier,
            atoms,
            note + " " + repeatSentence(repeats, bits.size),
        )
    }

    /**
     * Pulse-length framing, which is Sony's: the bit is the mark length and every
     * bit is followed by the same short space.
     */
    private fun pulseLength(bits: List<Int>, repeats: Int): IrSignal {
        val atoms = ArrayList<Atom>(bits.size * 2 + 4)
        repeat(repeats) {
            if (it > 0) atoms.add(Atom(mark = false, micros = SONY_FRAME_GAP))
            atoms.add(Atom(mark = true, micros = SONY_HDR_MARK))
            atoms.add(Atom(mark = false, micros = SONY_HDR_SPACE))
            for (bit in bits) {
                atoms.add(Atom(mark = true, micros = if (bit == 1) SONY_ONE_MARK else SONY_ZERO_MARK))
                atoms.add(Atom(mark = false, micros = SONY_BIT_SPACE))
            }
        }
        return IrFrame.build(
            SONY_CARRIER,
            atoms,
            "Sony SIRC, " + repeatSentence(repeats, bits.size),
        )
    }

    /**
     * RC5 biphase.
     *
     * Each bit is two half-bits of equal length and the bit value is carried by
     * the phase: a one is space-then-mark, a zero is mark-then-space. Building it
     * from [Atom]s means the two halves are emitted as what they are, rather than
     * as a run of identical durations whose meaning depends on a mutable flag.
     */
    private fun rc5(bits: List<Int>, extended: Boolean, repeats: Int): IrSignal {
        val atoms = ArrayList<Atom>(bits.size * 2 + 4)
        repeat(repeats) {
            if (it > 0) atoms.add(Atom(mark = false, micros = 89000))
            for (bit in bits) {
                if (bit == 1) {
                    atoms.add(Atom(mark = false, micros = RC5_HALF_BIT))
                    atoms.add(Atom(mark = true, micros = RC5_HALF_BIT))
                } else {
                    atoms.add(Atom(mark = true, micros = RC5_HALF_BIT))
                    atoms.add(Atom(mark = false, micros = RC5_HALF_BIT))
                }
            }
        }
        return IrFrame.build(
            RC5_CARRIER,
            atoms,
            (if (extended) "Philips RC5X" else "Philips RC5") + ", " + repeatSentence(repeats, bits.size),
        )
    }

    /**
     * RC6 mode 0.
     *
     * A six-unit leader mark, a two-unit space, a one-unit start bit, a one-unit
     * toggle, then address and command in biphase where a one is mark-then-space
     * and a zero is space-then-mark.
     *
     * THE HONEST LIMIT, stated in the result rather than hidden: RC6's toggle bit
     * is stateful. A receiver that has seen a different toggle value ignores one
     * frame, so the first press after a gap can appear to do nothing and the
     * second works. That is a property of the protocol, not of this encoder.
     */
    private fun rc6(address: Int, command: Int, repeats: Int): IrSignal {
        val atoms = ArrayList<Atom>(64)
        repeat(repeats) {
            if (it > 0) atoms.add(Atom(mark = false, micros = 6 * RC6_UNIT))
            atoms.add(Atom(mark = true, micros = 6 * RC6_UNIT))
            atoms.add(Atom(mark = false, micros = 2 * RC6_UNIT))
            atoms.add(Atom(mark = true, micros = RC6_UNIT))     // start bit
            atoms.add(Atom(mark = false, micros = RC6_UNIT))
            atoms.add(Atom(mark = true, micros = RC6_UNIT))     // toggle
            atoms.add(Atom(mark = false, micros = RC6_UNIT))
            for (bit in msbFirst(address, 8) + msbFirst(command, 8)) {
                if (bit == 1) {
                    atoms.add(Atom(mark = true, micros = RC6_UNIT))
                    atoms.add(Atom(mark = false, micros = RC6_UNIT))
                } else {
                    atoms.add(Atom(mark = false, micros = RC6_UNIT))
                    atoms.add(Atom(mark = true, micros = RC6_UNIT))
                }
            }
        }
        return IrFrame.build(
            RC6_CARRIER,
            atoms,
            "Philips RC6, 36 kHz, $repeats frame(s). RC6's toggle bit is stateful, so a receiver " +
                "that saw the opposite toggle can ignore the first press.",
        )
    }

    /** The one clause every note ends with, so a result says what was actually sent. */
    private fun repeatSentence(repeats: Int, bits: Int): String =
        "$bits bits, $repeats frame(s)."
}
