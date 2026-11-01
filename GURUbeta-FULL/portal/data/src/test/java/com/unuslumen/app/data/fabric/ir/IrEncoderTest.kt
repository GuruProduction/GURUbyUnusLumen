// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The encoder's pulse trains, checked against the protocol specifications. #187.
 *
 * WHY THESE ASSERTIONS AND NOT A DEVICE. Whether a television responds is decided
 * entirely by the numbers this encoder produces: the carrier and the alternating
 * microsecond timings. ConsumerIrManager sends whatever array it is handed and has
 * no opinion, so the only thing that can be wrong is the array, and the array is
 * exactly what is checked here against hand-computed values from the published
 * protocol timings.
 *
 * The one thing this cannot prove is that a given television answers, because that
 * is a fact about the television.
 */
class IrEncoderTest {

    /** A signal must alternate, begin with a mark and end with a space. */
    private fun assertWellFormed(signal: IrSignal) {
        assertTrue("a signal must carry pulses", signal.pulses.isNotEmpty())
        assertTrue("a signal must carry a plausible carrier", signal.carrierHz in 30_000..60_000)
        assertTrue(
            "every pulse must be a positive duration",
            signal.pulses.all { it > 0 },
        )
        assertEquals(
            "a pulse train's length is implicitly its phase count, so an odd count cannot " +
                "alternate from a mark to a space",
            0,
            signal.pulses.size % 2,
        )
    }

    /**
     * NEC: a 9000/4500 header, then four complemented bytes, least significant bit
     * first, each bit a 560 mark plus a space that is 1690 for a one and 560 for a
     * zero.
     *
     * The worked example is address 0x08, command 0x05, which is the code the real
     * Flipper library records for a Samsung-style power button.
     */
    @Test
    fun necProducesTheDocumentedHeaderAndBitShape() {
        val signal = IrEncoder.encode(IrProtocolName.NEC, 0x08, 0x05)
        assertNotNull(signal)
        signal!!

        assertWellFormed(signal)
        assertEquals("NEC runs at 38 kHz", 38000, signal.carrierHz)
        assertEquals("an NEC header is 9000 us of mark", 9000, signal.pulses[0])
        assertEquals("an NEC header is 4500 us of space", 4500, signal.pulses[1])

        // 32 data bits = 64 pulses, plus the header's 2 and the closing mark and gap.
        assertEquals(
            "an NEC frame is a header, 32 bits and a closing gap",
            2 + 64 + 2,
            signal.pulses.size,
        )

        // Address 0x08 = 0000 1000, sent least significant bit first, so the fourth
        // bit is the one. Its space must therefore be the one-space at index 2 + 3*2.
        assertEquals("bit 3 of address 0x08 is set", 560, signal.pulses[2 + 3 * 2])
        assertEquals("so it is followed by the one-space", 1690, signal.pulses[2 + 3 * 2 + 1])
        // Bit 0 of the address is clear.
        assertEquals("bit 0 of address 0x08 is clear", 560, signal.pulses[2])
        assertEquals("so it is followed by the zero-space", 560, signal.pulses[3])
    }

    /**
     * The complement bytes are the protocol's checksum and a receiver rejects a
     * frame without them. Address 0x08 must therefore be followed by 0xF7.
     */
    @Test
    fun necSendsTheComplementOfAddressAndCommand() {
        val signal = IrEncoder.encode(IrProtocolName.NEC, 0x08, 0x05)!!
        // Second byte is the complement of the address. 0xF7 = 1111 0111, sent least
        // significant first, so bits 0,1,2 are set and bit 3 is clear.
        val byteStart = 2 + 8 * 2
        assertEquals("complement bit 0 is set", 1690, signal.pulses[byteStart + 1])
        assertEquals("complement bit 3 is clear", 560, signal.pulses[byteStart + 3 * 2 + 1])
    }

    /** Samsung shares NEC's bit shape but has a balanced header and a short gap. */
    @Test
    fun samsungUsesABalancedHeader() {
        val signal = IrEncoder.encode(IrProtocolName.SAMSUNG32, 0x07, 0x02)!!
        assertWellFormed(signal)
        assertEquals("Samsung runs at 38 kHz", 38000, signal.carrierHz)
        assertEquals("a Samsung header is 4500 us of mark", 4500, signal.pulses[0])
        assertEquals("and 4500 us of space", 4500, signal.pulses[1])
    }

    /**
     * Sony encodes the bit in the MARK and uses a 40 kHz carrier, so a one is a
     * 1200 us mark and a zero a 600 us mark, each followed by 600 us of space.
     */
    @Test
    fun sonyEncodesTheBitInTheMark() {
        // Address 1, command 0x15 is the classic Sony power code.
        val signal = IrEncoder.encode(IrProtocolName.SIRC, 0x01, 0x15)!!
        assertWellFormed(signal)
        assertEquals("Sony runs at 40 kHz", 40000, signal.carrierHz)
        assertEquals("a Sony header is 2400 us", 2400, signal.pulses[0])
        assertEquals("followed by 600 us", 600, signal.pulses[1])

        // Every data mark is either 1200 or 600, and every data space is 600.
        val data = signal.pulses.drop(2).dropLast(2)
        val marks = data.filterIndexed { index, _ -> index % 2 == 0 }
        val spaces = data.filterIndexed { index, _ -> index % 2 == 1 }
        assertTrue("a Sony mark is one of two lengths", marks.all { it == 1200 || it == 600 })
        assertTrue("a Sony data space is always 600", spaces.all { it == 600 })
    }

    /**
     * RC5 is biphase: equal halves, and the phase carries the bit. A one is a space
     * then a mark, so the very first half of a one is a space, which is why a naive
     * "append a mark and a space" builder produces an illegal train.
     */
    @Test
    fun rc5IsBiphaseAndEveryPulseIsAWholeNumberOfHalfBits() {
        val signal = IrEncoder.encode(IrProtocolName.RC5, 0x00, 0x0C)!!
        assertWellFormed(signal)
        assertEquals("RC5 runs at 36 kHz", 36000, signal.carrierHz)

        // Biphase halves merge whenever consecutive bits share a phase, so the real
        // invariant is not "every pulse is 889" but "every pulse is a whole number of
        // half-bits". Asserting the stronger, wrong claim fails on correct encoding
        // and would push somebody to break the encoder to satisfy it.
        assertTrue(
            "every RC5 pulse must be a whole number of 889 us half-bits",
            signal.pulses.all { it % 889 == 0 || it == IrFrame.FRAME_CLOSE_GAP },
        )

        /*
         * FOURTEEN BITS IS TWENTY-EIGHT HALF-BITS, and the frame carries twenty-seven
         * of them. The missing one is the leading space of the first start bit: RC5's
         * first bit is a one, a one is space-then-mark, and a pulse train must begin
         * with a mark, so [IrFrame.build] trims that leading space.
         *
         * It is stated here rather than quietly absorbed because it is a real
         * deviation from the protocol's ideal waveform: the frame starts half a bit
         * late. A receiver synchronises on the first mark edge, so this is what every
         * IR library does, but it is the kind of fact that should be written down
         * instead of discovered later by somebody comparing against a capture.
         */
        assertEquals(
            "twenty-eight half-bits are generated and one leading space is trimmed",
            27 * 889,
            signal.pulses.sum(),
        )
    }

    /** RC6 builds its frame from a 444 us unit, so every pulse is a multiple of it. */
    @Test
    fun rc6UsesItsUnitForEveryPulse() {
        val signal = IrEncoder.encode(IrProtocolName.RC6, 0x00, 0x0C)!!
        assertWellFormed(signal)
        assertEquals("RC6 runs at 36 kHz", 36000, signal.carrierHz)
        assertTrue(
            "every RC6 pulse is a whole number of 444 us units",
            signal.pulses.dropLast(1).all { it % 444 == 0 },
        )
        assertEquals("an RC6 leader is six units of mark", 6 * 444, signal.pulses[0])
        assertEquals("followed by two units of space", 2 * 444, signal.pulses[1])
    }

    /** RCA is the common television protocol at 58 kHz, so its carrier must differ. */
    @Test
    fun rcaRunsAtFiftyEightKilohertz() {
        val signal = IrEncoder.encode(IrProtocolName.RCA, 0x0F, 0x54)!!
        assertWellFormed(signal)
        assertEquals("RCA runs at 58 kHz", 58000, signal.carrierHz)
    }

    /** Every protocol this build claims must actually produce a well-formed frame. */
    @Test
    fun everySupportedProtocolProducesAWellFormedFrame() {
        val supported = IrProtocolName.entries.filter { it != IrProtocolName.RAW }
        for (protocol in supported) {
            val signal = IrEncoder.encode(protocol, 0x01, 0x02)
            assertNotNull("$protocol must encode", signal)
            assertWellFormed(signal!!)
        }
    }

    /**
     * A raw code has no synthesiser behind it, so the encoder must say so rather
     * than returning a frame of silence that looks like a transmit.
     */
    @Test
    fun rawIsNotSynthesised() {
        assertNull(
            "RAW timings come from a capture, so the encoder has nothing to build",
            IrEncoder.encode(IrProtocolName.RAW, 0, 0),
        )
    }

    /**
     * Repeats must add complete frames separated by a gap, so the pulse count grows
     * by a frame each time and the train stays well formed.
     */
    @Test
    fun repeatsAddWholeFramesNotExtraPulses() {
        val once = IrEncoder.encode(IrProtocolName.NEC, 0x08, 0x05, repeats = 1)!!
        val twice = IrEncoder.encode(IrProtocolName.NEC, 0x08, 0x05, repeats = 2)!!
        assertWellFormed(twice)
        assertTrue("two frames carry more pulses", twice.pulses.size > once.pulses.size)
        assertEquals(
            "the second frame is exactly one frame longer",
            once.pulses.size * 2,
            twice.pulses.size,
        )
    }

    /** An absurd repeat count must be clamped rather than producing a vast array. */
    @Test
    fun repeatCountIsClamped() {
        val signal = IrEncoder.encode(IrProtocolName.NEC, 0x08, 0x05, repeats = 500)!!
        assertWellFormed(signal)
        assertTrue(
            "repeats are clamped to ten frames, so the train cannot grow without limit",
            signal.pulses.size <= (2 + 64 + 2) * 10,
        )
    }
}
