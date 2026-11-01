// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The three code-library parsers, checked against real files and hand-computed
 * arithmetic. #187.
 *
 * WHY REAL FILES. These parsers exist because the databases are the only source of
 * a television's codes, so a parser proved against invented input proves nothing
 * about the files that actually ship. Each sample below is in the exact format the
 * real database uses, and the Flipper one is copied from the tv asset verbatim.
 */
class IrCodeLibraryTest {

    // ------------------------------------------------------------------
    // Flipper
    // ------------------------------------------------------------------

    /** The verbatim shape of a real Flipper block, including its "# Model:" marker. */
    private val flipperSample = """
        Filetype: IR library file
        Version: 1
        #
        # Model: VIZIO
        #
        name: Power
        type: parsed
        protocol: NEC
        address: 04 00 00 00
        command: 09 00 00 00
        #
        name: Vol_up
        type: parsed
        protocol: NEC
        address: 04 00 00 00
        command: 02 00 00 00
        #
    """.trimIndent()

    @Test
    fun flipperParsesTheRealBlockShape() {
        val parsed = IrFlipperParser.parse(flipperSample, "flipper_tv.ir")
        assertEquals("one model", 1, parsed.families.size)
        val family = parsed.families.first()
        assertEquals("VIZIO is carried onto the family", "VIZIO", family.name)
        assertEquals("both codes are read", 2, family.codes.size)

        val power = family.code("power")
        assertNotNull("a power button must be found", power)
        assertEquals(IrProtocolName.NEC, power!!.protocol)
        assertEquals("address 04 00 00 00 is 0x04", 0x04, power.address)
        assertEquals("command 09 00 00 00 is 0x09", 0x09, power.command)
    }

    /**
     * The four hex bytes are LEAST significant first, which is the order they are
     * transmitted in, so `EA C7 00 00` is 0xC7EA and `04 00 00 00` is 0x04.
     *
     * Getting this backwards is the subtlest bug in the library: the code still
     * parses, encodes and transmits without complaint and simply does nothing,
     * because a NEC encoder takes the low byte and would send 0x00 rather than 0x04.
     */
    @Test
    fun flipperNumbersAreLeastSignificantByteFirst() {
        assertEquals(0xC7EA, IrFlipperParser.parseFlipperNumber("EA C7 00 00"))
        assertEquals(0x08, IrFlipperParser.parseFlipperNumber("08 00 00 00"))
        assertEquals(0xE084, IrFlipperParser.parseFlipperNumber("84 E0 00 00"))
        assertEquals("a single byte is itself", 0x1C, IrFlipperParser.parseFlipperNumber("1C"))
        assertEquals(
            "the low byte is the one a NEC encoder sends, which is why the order matters",
            0x04,
            IrFlipperParser.parseFlipperNumber("04 00 00 00")!! and 0xFF,
        )
        assertNull("a five-byte value is not a valid field", IrFlipperParser.parseFlipperNumber("00 00 00 00 00"))
        assertNull("a non-hex byte is refused", IrFlipperParser.parseFlipperNumber("ZZ 00 00 00"))
    }

    /** A raw capture becomes a raw code with its timings and carrier preserved. */
    @Test
    fun flipperParsesARawCapture() {
        val raw = """
            name: Power
            type: raw
            frequency: 38000
            duty_cycle: 0.330000
            data: 634 2571 505 519 479 519
        """.trimIndent()
        val parsed = IrFlipperParser.parse(raw, "capture.ir")
        assertEquals(1, parsed.codeCount)
        val code = parsed.families.first().codes.first()
        assertEquals(IrProtocolName.RAW, code.protocol)
        assertEquals(38000, code.carrierHz)
        assertNotNull(code.rawPulses)
        assertEquals(6, code.rawPulses!!.size)
        assertEquals(634, code.rawPulses!![0])
    }

    /**
     * A protocol this build cannot synthesise must be reported, not dropped. A
     * silently shortened library looks like a database that does not know the
     * device, which is a different and much worse answer.
     */
    @Test
    fun flipperReportsAnUnknownProtocolRatherThanDroppingIt() {
        val unknown = """
            name: Power
            type: parsed
            protocol: ZX81
            address: 01 00 00 00
            command: 02 00 00 00
        """.trimIndent()
        val parsed = IrFlipperParser.parse(unknown, "odd.ir")
        assertEquals("nothing is invented", 0, parsed.codeCount)
        assertEquals("and the reason is carried", 1, parsed.problems.size)
        assertTrue(parsed.problems.first().contains("ZX81"))
    }

    /** A block missing its address must be reported rather than becoming address 0. */
    @Test
    fun flipperReportsAMissingAddress() {
        val broken = """
            name: Power
            type: parsed
            protocol: NEC
            command: 02 00 00 00
        """.trimIndent()
        val parsed = IrFlipperParser.parse(broken, "broken.ir")
        assertEquals(0, parsed.codeCount)
        assertTrue(parsed.problems.first().contains("unreadable"))
    }

    // ------------------------------------------------------------------
    // LIRC
    // ------------------------------------------------------------------

    /**
     * A real lircd.conf remote. The point of this test is that `pre_data` is folded
     * into the code, because dropping it is how an LIRC import produces codes that
     * are plausible and wrong.
     */
    @Test
    fun lircFoldsPreDataIntoTheCode() {
        val lirc = """
            begin remote
              name  SAMSUNG
              bits           16
              flags SPACE_ENC
              eps            30
              aeps          100
              header       4500 4500
              one           560 1690
              zero          560  560
              pre_data_bits   16
              pre_data       0xE0E0
              gap          5000
              begin codes
                  KEY_POWER   0x40BF
              end codes
            end remote
        """.trimIndent()
        val parsed = IrLircParser.parse(lirc, "lircd.conf")
        assertEquals("the remote is read", 1, parsed.families.size)
        assertEquals("SAMSUNG", parsed.families.first().name)
        val code = parsed.families.first().codes.first()
        assertEquals("KEY_ is stripped so a spoken name matches", "POWER", code.button)
        assertEquals(
            "pre_data E0E0 shifted above the 16 code bits plus 40BF is E0E040BF",
            0xE0E040BF.toInt(),
            code.command,
        )
        assertNull(
            "the file states timings but no protocol name, so no protocol is invented",
            code.protocol,
        )
    }

    /**
     * A remote that carries its own header/one/zero IS transmittable, because those
     * five fields are the whole pulse specification. Refusing it would throw away the
     * LIRC database, which is the largest source of real remote codes there is.
     */
    @Test
    fun lircTransmitsFromItsOwnTimings() {
        val lirc = """
            begin remote
              name  OLDHIFI
              bits           12
              header       4000 4000
              one           500 2000
              zero          500 1000
              gap          40000
              begin codes
                  KEY_POWER   0x123
              end codes
            end remote
        """.trimIndent()
        val parsed = IrLircParser.parse(lirc, "oldhifi.conf")
        assertEquals("the code is read", 1, parsed.codeCount)
        val code = parsed.families.first().codes.first()

        val timing = code.lircTiming
        assertNotNull("its timings travel with the code", timing)
        assertEquals("a 12-bit code", 12, timing!!.bits)
        assertEquals(4000, timing.headerMark)
        assertEquals(2000, timing.oneSpace)

        val plan = IrTransmitPlan.plan(code)
        assertNull("and it plans into a signal: ${plan.reason}", plan.reason)
        assertNotNull(plan.signal)
        assertEquals("the plan uses the LIRC header first", 4000, plan.signal!!.pulses[0])
        assertEquals("then its header space", 4000, plan.signal!!.pulses[1])
    }

    /** A remote with no timings and no protocol name must refuse rather than guess. */
    @Test
    fun lircRefusesARemoteWithNoTimingsAndNoProtocol() {
        val lirc = """
            begin remote
              name  NOTHING
              bits           8
              begin codes
                  KEY_POWER   0x12
              end codes
            end remote
        """.trimIndent()
        val parsed = IrLircParser.parse(lirc, "nothing.conf")
        assertEquals("nothing usable is claimed", 0, parsed.codeCount)
        assertTrue(
            "and the reason says why",
            parsed.problems.any { it.contains("no complete header") },
        )
    }

    /** A raw_codes remote is a capture, so its numbers are taken as given. */
    @Test
    fun lircParsesRawCodes() {
        val lirc = """
            begin remote
              name  CAPTURED
              flags RAW_CODES
              eps            30
              aeps          100
              frequency    38000
              begin raw_codes
                  KEY_POWER
                      9000 4500 560 560 560 1690
              end raw_codes
            end remote
        """.trimIndent()
        val parsed = IrLircParser.parse(lirc, "captured.conf")
        assertEquals(1, parsed.codeCount)
        val code = parsed.families.first().codes.first()
        assertEquals(IrProtocolName.RAW, code.protocol)
        assertEquals(38000, code.carrierHz)
        assertEquals(6, code.rawPulses!!.size)
    }

    // ------------------------------------------------------------------
    // Pronto
    // ------------------------------------------------------------------

    /**
     * The Pronto arithmetic, which is the single most likely place to go wrong.
     *
     * A frequency word of 0x006D is 109 decimal, and the published conversion is
     * 1_000_000 / (109 * 0.241246), which is 38,020 Hz. One carrier cycle is
     * therefore about 26.3 us, so a burst of 343 cycles is about 9023 us.
     */
    @Test
    fun prontoFrequencyWordConvertsToItsCarrier() {
        val carrier = IrProntoParser.carrierFor(0x006D)
        assertTrue(
            "0x006D is the documented 38 kHz class of carrier, and got $carrier Hz",
            carrier in 37_900..38_100,
        )
    }

    /**
     * A complete learned Pronto code. The once-only burst count is two pairs, so the
     * pulse train must be four values, and each value must be the burst count times
     * the carrier period rather than the burst count itself.
     */
    @Test
    fun prontoConvertsCyclesToMicroseconds() {
        // 0000 006D 0002 0000 0157 00AC 0015 0015
        val pronto = "0000 006D 0002 0000 0157 00AC 0015 0015"
        val parsed = IrProntoParser.parse(pronto, "pronto")
        assertEquals("one code", 1, parsed.codeCount)
        val code = parsed.families.first().codes.first()
        assertEquals(IrProtocolName.RAW, code.protocol)

        val carrier = IrProntoParser.carrierFor(0x006D)
        val cycleMicros = 1_000_000.0 / carrier

        val pulses = code.rawPulses!!
        assertEquals("two burst pairs", 4, pulses.size)
        assertEquals(
            "0x0157 is 343 cycles, which is a mark of about 9023 us",
            (343 * cycleMicros).toInt(),
            pulses[0],
        )
        assertEquals(
            "0x00AC is 172 cycles, which is a space of about 4526 us",
            (172 * cycleMicros).toInt(),
            pulses[1],
        )
    }

    /** A burst count read as microseconds would be a thousandth of the right length. */
    @Test
    fun prontoDoesNotReadCyclesAsMicroseconds() {
        val parsed = IrProntoParser.parse("0000 006D 0001 0000 0157 00AC", "pronto")
        val pulses = parsed.families.first().codes.first().rawPulses!!
        assertTrue(
            "343 is a cycle count, so the mark must be milliseconds-scale in microseconds, " +
                "not 343 us",
            pulses[0] > 1000,
        )
    }

    /** A truncated Pronto string is refused with the arithmetic that shows why. */
    @Test
    fun prontoRefusesATruncatedCode() {
        val parsed = IrProntoParser.parse("0000 006D 0004 0000 0157", "pronto")
        assertEquals(0, parsed.codeCount)
        assertTrue(
            "the reason states how many words were expected",
            parsed.problems.first().contains("burst pairs"),
        )
    }

    /** The 0100 raw form has no carrier, so it is refused by name rather than mis-sent. */
    @Test
    fun prontoRefusesTheUnmodulatedRawForm() {
        val parsed = IrProntoParser.parse("0100 006D 0001 0000 0157 00AC", "pronto")
        assertEquals(0, parsed.codeCount)
        assertTrue(
            "the reason says why",
            parsed.problems.first().contains("no carrier modulation"),
        )
    }

    // ------------------------------------------------------------------
    // Button folding
    // ------------------------------------------------------------------

    /**
     * Every database spells the same button differently, and a person says a fourth
     * thing. All of them must land on one token or a spoken request finds nothing.
     */
    @Test
    fun buttonNamesFoldOntoOneToken() {
        val power = setOf("power", "Power", "PWR", "standby", "on off")
        for (name in power) {
            assertEquals("'$name' is the power button", "power", IrButton.normalise(name))
        }
        val up = setOf("vol_up", "Vol_up", "VOL+", "volume up", "turn it up", "louder")
        for (name in up) {
            assertEquals("'$name' is volume up", "vol_up", IrButton.normalise(name))
        }
        val next = setOf("ch_next", "CH+", "channel up", "next channel")
        for (name in next) {
            assertEquals("'$name' is channel up", "ch_next", IrButton.normalise(name))
        }
    }

    /** A button a library does not have must not be invented from a similar one. */
    @Test
    fun anUnknownButtonStaysDistinct() {
        assertEquals("netflix", IrButton.normalise("Netflix"))
        assertFalse(
            "Netflix is not the power button",
            IrButton.normalise("Netflix") == IrButton.normalise("power"),
        )
        assertEquals(
            "a multi-word button keeps its words joined by one underscore",
            "prime_video",
            IrButton.normalise("Prime Video"),
        )
    }

    // ------------------------------------------------------------------
    // Transmit planning
    // ------------------------------------------------------------------

    /** A parsed code with a supported protocol plans into a real signal. */
    @Test
    fun aSupportedCodePlansIntoASignal() {
        val code = IrCode(
            button = "power",
            protocol = IrProtocolName.NEC,
            address = 0x04,
            command = 0x09,
            source = "test",
        )
        val plan = IrTransmitPlan.plan(code)
        assertNotNull("a NEC code plans", plan.signal)
        assertNull(plan.reason)
        assertEquals(38000, plan.signal!!.carrierHz)
    }

    /**
     * A code whose protocol is unknown must refuse. An LIRC file with no protocol
     * name is exactly this case, and transmitting silence is not an option.
     */
    @Test
    fun aCodeWithNoProtocolAndNoTimingsRefuses() {
        val code = IrCode(button = "power", protocol = null, command = 0x123, source = "test")
        val plan = IrTransmitPlan.plan(code)
        assertNull("nothing to transmit", plan.signal)
        assertNotNull("and the reason is stated", plan.reason)
        assertTrue(plan.reason!!.contains("no protocol"))
    }

    /** A raw capture is transmitted exactly as recorded, with no re-synthesis. */
    @Test
    fun aRawCaptureIsTransmittedAsRecorded() {
        val code = IrCode(
            button = "power",
            protocol = IrProtocolName.RAW,
            rawPulses = intArrayOf(9000, 4500, 560, 560),
            carrierHz = 38000,
            source = "capture",
        )
        val plan = IrTransmitPlan.plan(code)
        assertNotNull(plan.signal)
        assertEquals(38000, plan.signal!!.carrierHz)
        assertEquals(4, plan.signal!!.pulses.size)
        assertEquals(9000, plan.signal!!.pulses[0])
    }

    /** An odd-length capture ends on a mark, so a gap must close it. */
    @Test
    fun anOddCaptureIsClosedWithAGap() {
        val code = IrCode(
            button = "power",
            protocol = IrProtocolName.RAW,
            rawPulses = intArrayOf(9000, 4500, 560),
            carrierHz = 38000,
            source = "capture",
        )
        val plan = IrTransmitPlan.plan(code)
        assertEquals("a closing space is appended", 4, plan.signal!!.pulses.size)
        assertEquals(IrFrame.FRAME_CLOSE_GAP, plan.signal!!.pulses.last())
    }

    /** An empty capture is refused rather than sent as nothing. */
    @Test
    fun anEmptyCaptureRefuses() {
        val code = IrCode(
            button = "power",
            protocol = IrProtocolName.RAW,
            rawPulses = intArrayOf(),
            source = "capture",
        )
        val plan = IrTransmitPlan.plan(code)
        assertNull(plan.signal)
        assertNotNull(plan.reason)
    }
}
