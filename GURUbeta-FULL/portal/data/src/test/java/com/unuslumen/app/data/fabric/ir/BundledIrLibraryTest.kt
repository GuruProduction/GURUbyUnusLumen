// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The library that actually ships, parsed by the parser that actually runs. #187.
 *
 * WHY THIS EXISTS SEPARATELY FROM THE PARSER TESTS. Those prove the parser against
 * small samples. This proves the shipped asset: that every entry in it parses, that
 * no code was silently dropped, and that a model's power button becomes a real
 * transmittable signal. A bundled library that fails to parse is a feature that
 * looks installed and controls nothing, which is exactly the defect that made a
 * television do nothing.
 *
 * The asset is read from the source tree rather than through Android's AssetManager,
 * because the point is to check the file itself, not the platform's ability to open
 * it.
 */
class BundledIrLibraryTest {

    private val assetPath = "src/main/assets/ir/flipper_tv.ir"

    private fun assetText(): String {
        // Gradle runs unit tests with the module directory as the working directory,
        // so this resolves to portal/data/src/main/assets/ir/flipper_tv.ir.
        val direct = File(assetPath)
        val file = if (direct.exists()) direct else File("GURUbeta-FULL/portal/data/$assetPath")
        assertTrue(
            "the bundled IR asset must exist at $assetPath; a missing asset is a build fault " +
                "because the library would ship empty",
            file.exists(),
        )
        return file.readText()
    }

    @Test
    fun theBundledAssetParsesWithoutProblems() {
        val parsed = IrFlipperParser.parse(assetText(), "flipper_tv.ir")
        assertTrue(
            "the bundled library must parse into families, and got: ${parsed.problems}",
            parsed.families.isNotEmpty(),
        )
        assertEquals(
            "every bundled entry must be readable, but these were not: ${parsed.problems}",
            0,
            parsed.problems.size,
        )
    }

    @Test
    fun theBundledAssetCoversManyTelevisionMakes() {
        val parsed = IrFlipperParser.parse(assetText(), "flipper_tv.ir")
        // 72 makes, because a make is only listed when the source records a power code
        // for it. The source names about 110, and the rest are volume-only or a single
        // channel button, which is not a controllable television.
        assertTrue(
            "the library must cover a real spread of makes rather than one model, and got " +
                "${parsed.families.size}",
            parsed.families.size >= 70,
        )
        assertTrue(
            "and it must carry a useful number of codes in total, and got ${parsed.codeCount}",
            parsed.codeCount >= 330,
        )
        assertTrue(
            "every make must have a power button, because a device with none cannot be turned on",
            parsed.families.all { it.code("power") != null },
        )
    }

    /**
     * Codes must be filed under the make they belong to.
     *
     * THE BUG THIS PINS. The Flipper file names most devices with a "# Model:" comment
     * but several with a plain "# Name" line. A generator that only honoured
     * "# Model:" merged the next make's codes into the previous one, so VIZIO ended up
     * carrying an RCA remote's power button. A code filed under the wrong model is a
     * wrong code, and a wrong code presses nothing.
     */
    @Test
    fun codesAreFiledUnderTheMakeTheyBelongTo() {
        val parsed = IrFlipperParser.parse(assetText(), "flipper_tv.ir")

        // VIZIO's own codes are NEC with address 0x04, and its power button is a raw
        // capture. The RCA remote that follows in the source file must not appear.
        val vizio = parsed.families.first { it.name.contains("VIZIO", ignoreCase = true) }
        val muteCode = vizio.code("mute")
        assertNotNull("VIZIO has a mute button", muteCode)
        assertEquals("VIZIO mute is NEC", IrProtocolName.NEC, muteCode!!.protocol)
        assertEquals("VIZIO's address is 0x04", 0x04, muteCode.address)
        assertTrue(
            "VIZIO must carry no RCA code, because the RCA remote in the source file belongs " +
                "to a different make and a borrowed code presses nothing",
            vizio.codes.none { it.protocol == IrProtocolName.RCA },
        )

        // The RCA remote is its own family, with its own codes intact.
        val rcaFamily = parsed.families.firstOrNull { family ->
            family.codes.any { it.protocol == IrProtocolName.RCA }
        }
        assertNotNull("the RCA remote must be filed as its own make", rcaFamily)
        assertTrue(
            "and it must keep its power button",
            rcaFamily!!.code("power") != null,
        )
    }

    /** Every family in the shipped library must have a working power button. */
    @Test
    fun everyBundledFamilyHasATransmittablePowerButton() {
        val parsed = IrFlipperParser.parse(assetText(), "flipper_tv.ir")
        for (family in parsed.families) {
            val power = family.code("power")
            assertNotNull("${family.name} must have a power code", power)
            val plan = IrTransmitPlan.plan(power!!)
            assertNull(
                "${family.name}'s power code must be transmittable, but: ${plan.reason}",
                plan.reason,
            )
            assertNotNull("${family.name}'s power code must produce a signal", plan.signal)
            assertTrue(
                "${family.name}'s power signal must carry pulses",
                plan.signal!!.pulses.isNotEmpty(),
            )
        }
    }

    /**
     * A parsed NEC code from the shipped file becomes a real NEC frame.
     *
     * This is the end-to-end proof for a synthesised code: a make in the bundled file
     * turns into pulses whose header is the protocol's documented 9000/4500.
     */
    @Test
    fun aBundledNecCodeEncodesAsARealNecFrame() {
        val parsed = IrFlipperParser.parse(assetText(), "flipper_tv.ir")
        val family = parsed.families.first { it.name.contains("VIZIO", ignoreCase = true) }
        val mute = family.code("mute")
        assertNotNull("VIZIO has a mute button", mute)
        assertEquals(IrProtocolName.NEC, mute!!.protocol)

        val signal = IrTransmitPlan.plan(mute).signal
        assertNotNull(signal)
        assertEquals("38 kHz", 38000, signal!!.carrierHz)
        assertEquals("a NEC header mark of 9000 us", 9000, signal.pulses[0])
        assertEquals("followed by 4500 us of space", 4500, signal.pulses[1])
    }

    /**
     * A raw capture from the shipped file is transmitted exactly as recorded.
     *
     * VIZIO is the case that matters: its power button is a capture, not a named
     * protocol, so it proves the raw path end to end rather than by construction.
     */
    @Test
    fun aBundledRawCaptureEncodesAsRecorded() {
        val parsed = IrFlipperParser.parse(assetText(), "flipper_tv.ir")
        val family = parsed.families.first { it.name.contains("VIZIO", ignoreCase = true) }
        val power = family.code("power")
        assertNotNull("VIZIO has a power button", power)
        assertEquals("VIZIO's power button is a capture, not a named protocol",
            IrProtocolName.RAW, power!!.protocol)
        assertNotNull("and it carries its timings", power.rawPulses)

        val signal = IrTransmitPlan.plan(power).signal
        assertNotNull(signal)
        assertEquals(38000, signal!!.carrierHz)
        assertTrue("the capture must produce real pulses", signal.pulses.size > 20)
        assertEquals(
            "the first pulse is the capture's own first timing, unchanged",
            power.rawPulses!!.first(),
            signal.pulses.first(),
        )
    }

    /** No family in the shipped library may claim capabilities its codes do not support. */
    @Test
    fun noBundledFamilyClaimsACapabilityItHasNoCodeFor() {
        val parsed = IrFlipperParser.parse(assetText(), "flipper_tv.ir")
        for (family in parsed.families) {
            val device = family.toDevice()
            for (capability in device.capabilities) {
                val button = IrButtonMapping.buttonFor(capability, device.deviceClass)
                assertNotNull("$capability must map to a button", button)
                assertNotNull(
                    "${family.name} claims $capability but has no code for '$button', which would " +
                        "make every command refuse",
                    family.code(button!!),
                )
            }
        }
    }
}
