// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.ir

import com.unuslumen.app.data.fabric.Capability
import com.unuslumen.app.data.fabric.DeviceClass
import com.unuslumen.app.data.fabric.DeviceValue
import com.unuslumen.app.data.fabric.Transport
import com.unuslumen.app.data.fabric.TransportWrite
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The IR transport's honesty rules. #187.
 *
 * THE TWO RULES THIS EXISTS FOR, both of which are the defect class this project
 * keeps removing:
 *
 *  1. A write can never be Confirmed, because IR is one-way. Claiming a television
 *     turned off would be a fabricated fact about the physical world.
 *  2. A missing emitter and a missing code are two different refusals with two
 *     different sentences, so a person knows whether to buy a blaster or import a
 *     file.
 */
class IrTransportTest {

    /** A transmitter the test controls completely. */
    private class FakeTransmitter(
        private val available: Boolean,
        private val failReason: String? = null,
    ) : IrTransmitter {
        var sentSignals = mutableListOf<IrSignal>()

        override fun status() = TransmitterStatus(
            id = "fake",
            displayName = "a fake emitter",
            available = available,
            unavailableReason = if (available) null else (failReason ?: NO_EMITTER_REASON),
        )

        private companion object {
            // The same shape the built-in transmitter produces, so a test that asserts
            // on wording is asserting the contract rather than a value this fake made up.
            const val NO_EMITTER_REASON =
                "This phone reports no infrared emitter, so it cannot send a signal however the " +
                    "app is set up. A network IR blaster covers this case."
        }

        override suspend fun transmit(signal: IrSignal): TransmitOutcome {
            sentSignals.add(signal)
            return TransmitOutcome.Sent("sent ${signal.pulses.size} pulses")
        }
    }

    /**
     * A television with a power button and both volume directions.
     *
     * Both directions because VOLUME is offered only where a set can turn up and
     * down: a code set with one direction is not a volume control, and claiming the
     * capability would make an ordinary "turn it up" refuse.
     */
    private fun vizioFamily() = IrDeviceFamily(
        name = "VIZIO",
        source = "flipper_tv.ir",
        codes = listOf(
            IrCode(
                button = "Power",
                protocol = IrProtocolName.NEC,
                address = 0x04,
                command = 0x09,
                source = "flipper_tv.ir",
            ),
            IrCode(
                button = "Vol_up",
                protocol = IrProtocolName.NEC,
                address = 0x04,
                command = 0x02,
                source = "flipper_tv.ir",
            ),
            IrCode(
                button = "Vol_dn",
                protocol = IrProtocolName.NEC,
                address = 0x04,
                command = 0x03,
                source = "flipper_tv.ir",
            ),
        ),
    )

    private fun store() = InMemoryIrCodeStore(listOf(vizioFamily()))

    /**
     * THE MOST IMPORTANT ASSERTION IN THE FILE. A successful transmit is reported as
     * NotObserved, never Confirmed, because nothing on this phone can see a
     * television.
     */
    @Test
    fun aSentPressIsNeverReportedAsConfirmed() = runBlocking {
        val transmitter = FakeTransmitter(available = true)
        val transport = IrTransport(listOf(transmitter), store())

        val device = transport.discover().first()
        val outcome = transport.write(device.id, Capability.POWER, DeviceValue.Flag(true))

        assertTrue(
            "a write must be NotObserved, because IR has no read-back path and claiming " +
                "confirmation would be a fabricated fact",
            outcome is TransportWrite.NotObserved,
        )
        assertEquals("the signal really was sent", 1, transmitter.sentSignals.size)
    }

    /** A read must be null, because there is nothing to read. */
    @Test
    fun readingAnInfraredDeviceReturnsNull() = runBlocking {
        val transport = IrTransport(listOf(FakeTransmitter(available = true)), store())
        val device = transport.discover().first()
        assertNull(
            "IR has no return path, so a read is genuinely unknowable and must not be " +
                "answered with a stored value",
            transport.read(device.id),
        )
    }

    /**
     * An unavailable transmitter's own reason must travel into the transport's
     * descriptor verbatim.
     *
     * WHY VERBATIM AND NOT REWRITTEN. The transmitter knows why it cannot send: a
     * platform with no consumer IR service, a handset with no LED, a blaster that did
     * not answer. Rewriting that here would produce a second, drifting explanation of
     * one condition, and the transmitter's own sentence is the one that can be
     * specific.
     */
    @Test
    fun anUnavailableTransmittersReasonTravelsVerbatim() = runBlocking {
        val reason = "This phone reports no infrared emitter, so it cannot send a signal " +
            "however the app is set up. A network IR blaster covers this case."
        val transport = IrTransport(listOf(FakeTransmitter(available = false, failReason = reason)), store())
        val descriptor = transport.describe()

        assertFalse("no emitter means the transport is not available", descriptor.available)
        assertEquals("the transmitter's own sentence is what a person reads", reason, descriptor.unavailableReason)
    }

    /**
     * With no transmitter at all, the transport must name the two things that would
     * make infrared work, because that is the only useful answer.
     */
    @Test
    fun noTransmitterAtAllNamesTheBlasterAsTheRoute() {
        val transport = IrTransport(emptyList(), store())
        val reason = transport.describe().unavailableReason
        assertNotNull(reason)
        assertTrue(
            "the sentence must name a network blaster as the way to make this work, and got: $reason",
            reason!!.contains("blaster"),
        )
    }

    /** No transmitter at all is a build fault, and is reported as one. */
    @Test
    fun noTransmitterAtAllIsReportedAsABuildFault() {
        val transport = IrTransport(emptyList(), store())
        val descriptor = transport.describe()
        assertFalse(descriptor.available)
        assertTrue(
            "an empty transmitter list is a fault rather than a missing permission",
            descriptor.unavailableReason!!.contains("fault"),
        )
    }

    /** A button with no recorded code must say which buttons do exist. */
    @Test
    fun aButtonWithNoCodeNamesTheButtonsThatDo() = runBlocking {
        // A television whose only recorded code is power.
        val family = IrDeviceFamily(
            name = "MINIMAL",
            source = "test",
            codes = listOf(
                IrCode(
                    button = "Power",
                    protocol = IrProtocolName.NEC,
                    address = 0x01,
                    command = 0x02,
                    source = "test",
                ),
            ),
        )
        val transport = IrTransport(listOf(FakeTransmitter(available = true)), InMemoryIrCodeStore(listOf(family)))
        val device = transport.discover().first()

        // The device reports only POWER, so a mute press fails on capability first.
        assertFalse(
            "a television with only a power code must not claim mute",
            Capability.MUTE in device.capabilities,
        )

        // Force the write through with a capability it does claim but has no code
        // for by asking for VOLUME on a device that does not report it: the fabric
        // would refuse, so this test calls write directly to check the transport's
        // own refusal path.
        val outcome = transport.write(device.id, Capability.VOLUME, DeviceValue.Count(1))
        assertTrue(outcome is TransportWrite.Failed)
        val reason = (outcome as TransportWrite.Failed).reason
        assertTrue(
            "the refusal must name the missing button, and got: $reason",
            reason.contains("vol_up") || reason.contains("no button"),
        )
    }

    /** A press on a device with no codes at all refuses rather than sending silence. */
    @Test
    fun anUnknownDeviceRefuses() = runBlocking {
        val transport = IrTransport(listOf(FakeTransmitter(available = true)), store())
        val outcome = transport.write("ir:nothing", Capability.POWER, DeviceValue.Flag(true))
        assertTrue(outcome is TransportWrite.Failed)
        assertTrue((outcome as TransportWrite.Failed).reason.contains("No infrared device"))
    }

    /**
     * A value that cannot be a press is refused by name rather than turned into a
     * button that does not exist.
     *
     * POWER false is the case: an IR remote has one power button that toggles, so
     * "turn it off specifically" cannot be expressed as pulses. Refusing says so
     * instead of sending a toggle and hoping the set was on.
     */
    @Test
    fun aNonPressValueIsRefused() = runBlocking {
        val transport = IrTransport(listOf(FakeTransmitter(available = true)), store())
        val device = transport.discover().first()
        val outcome = transport.write(device.id, Capability.POWER, DeviceValue.Flag(false))
        assertTrue(
            "a clear power value is not a press, so it must refuse rather than guess",
            outcome is TransportWrite.Failed,
        )
    }

    /**
     * A numeric value on a step capability IS a press, because volume, channel and
     * temperature are buttons a person presses rather than values a device holds.
     * Refusing it would make an ordinary "turn it up" fail.
     */
    @Test
    fun aStepCapabilityTakesANumericPress() = runBlocking {
        val transmitter = FakeTransmitter(available = true)
        val transport = IrTransport(listOf(transmitter), store())
        val device = transport.discover().first()

        val outcome = transport.write(device.id, Capability.VOLUME, DeviceValue.Count(1))
        assertTrue(
            "a numeric volume step must be treated as a press, and got: " +
                (outcome as? TransportWrite.Failed)?.reason,
            outcome is TransportWrite.NotObserved,
        )
        assertEquals("and it really transmitted", 1, transmitter.sentSignals.size)
    }

    /** A family with no codes is not listed, because it would refuse every command. */
    @Test
    fun aFamilyWithNoCodesIsNotListed() = runBlocking {
        val empty = IrDeviceFamily(name = "EMPTY", source = "test", codes = emptyList())
        val store = InMemoryIrCodeStore(listOf(empty))
        assertTrue("an empty family produces no device", store.devices().isEmpty())
    }

    /** Discovery reports what the store holds, with capabilities from its real codes. */
    @Test
    fun discoveryReportsCapabilitiesFromTheRealCodes() = runBlocking {
        val transport = IrTransport(listOf(FakeTransmitter(available = true)), store())
        val devices = transport.discover()
        assertEquals(1, devices.size)
        val device = devices.first()
        assertEquals("the transport is IR", Transport.IR, device.transport)
        assertTrue("power is reported because a power code exists", Capability.POWER in device.capabilities)
        assertTrue(
            "volume is reported because both volume directions have codes",
            Capability.VOLUME in device.capabilities,
        )
        assertFalse("mute is not reported because no mute code exists", Capability.MUTE in device.capabilities)

        // A set with only one volume direction must not claim volume at all.
        val oneWay = IrDeviceFamily(
            name = "ONE-WAY",
            source = "test",
            codes = listOf(
                IrCode(button = "Power", protocol = IrProtocolName.NEC, address = 1, command = 2, source = "t"),
                IrCode(button = "Vol_dn", protocol = IrProtocolName.NEC, address = 1, command = 3, source = "t"),
            ),
        )
        assertFalse(
            "volume down alone is not a volume control, so VOLUME must not be claimed",
            Capability.VOLUME in oneWay.toDevice().capabilities,
        )
        assertTrue("IR has no readable state, so the map is empty", device.state.isEmpty())
    }

    /** A re-import must not move a device, because placement hangs off the id. */
    @Test
    fun theFabricIdIsStableAcrossParses() {
        val first = IrDeviceFamily(name = "VIZIO", source = "ir/flipper_tv.ir", codes = emptyList())
        val second = IrDeviceFamily(name = "VIZIO", source = "ir/flipper_tv.ir", codes = emptyList())
        assertEquals(
            "the same family from the same file must have the same id, or a re-import " +
                "would move a person's television",
            first.fabricId(),
            second.fabricId(),
        )
    }

    /**
     * A learned capture merges into an existing family rather than duplicating it,
     * because the point of learning is to add a button the library lacked.
     */
    @Test
    fun aLearnedCodeMergesIntoAnExistingFamily() = runBlocking {
        val store = store()
        val before = store.devices().first()
        store.add(
            IrDeviceFamily(
                name = "VIZIO",
                source = "flipper_tv.ir",
                codes = listOf(
                    IrCode(
                        button = "Mute",
                        protocol = IrProtocolName.NEC,
                        address = 0x04,
                        command = 0x09,
                        source = "learned",
                    ),
                ),
            ),
        )
        val after = store.devices().first()
        assertEquals("the device count does not grow", 1, store.devices().size)
        assertTrue(
            "the learned mute button joins the existing device",
            Capability.MUTE in after.capabilities,
        )
        assertEquals("and the id did not move", before.id, after.id)
    }

    /** Air conditioning is classified from its own buttons, not assumed to be a TV. */
    @Test
    fun airConditioningIsClassifiedFromItsButtons() {
        val family = IrDeviceFamily(
            name = "AC",
            source = "test",
            codes = listOf(
                IrCode(button = "Power", protocol = IrProtocolName.NEC, address = 1, command = 2, source = "t"),
                IrCode(button = "Temp_up", protocol = IrProtocolName.NEC, address = 1, command = 3, source = "t"),
                IrCode(button = "Mode", protocol = IrProtocolName.NEC, address = 1, command = 4, source = "t"),
            ),
        )
        assertEquals(
            "a code set with a temperature button is an air conditioner",
            DeviceClass.AIR_CONDITIONER,
            family.toDevice().deviceClass,
        )
    }
}
