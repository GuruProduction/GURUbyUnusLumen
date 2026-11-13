// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.fabric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 182's acceptance list, held as tests.
 *
 * The two that matter most are the ones the ticket calls non-negotiable: a control refuses a
 * capability the device does not report, and no tool reports a success it did not observe.
 */
class DeviceFabricTest {

    private fun lamp(
        id: String = "lamp-1",
        name: String = "Ceiling",
        room: String? = "kitchen",
        caps: Set<Capability> = setOf(Capability.POWER, Capability.BRIGHTNESS),
        state: Map<Capability, String> = mapOf(
            Capability.POWER to "on",
            Capability.BRIGHTNESS to "127",
        ),
        available: Boolean = true,
    ) = FabricDevice(
        id = id,
        name = name,
        room = room,
        transport = Transport.VENDOR,
        deviceClass = DeviceClass.LIGHT,
        capabilities = caps,
        state = state,
        available = available,
    )

    private fun washer() = FabricDevice(
        id = "washer-1",
        name = "Machine",
        room = "utility",
        transport = Transport.MQTT,
        deviceClass = DeviceClass.LAUNDRY_WASHER,
        capabilities = setOf(Capability.CYCLE_STATE, Capability.REMAINING_TIME),
        state = mapOf(Capability.CYCLE_STATE to "running"),
    )

    @Test
    fun aCapabilityTheDeviceDoesNotReportIsRefused() {
        // The ticket's own example, verbatim in spirit: a washing machine reporting cycle state must
        // not accept a start command.
        val refusal = DeviceFabric.mayCommand(washer(), Capability.START)
        assertTrue("a start command on a reporting-only washer must be refused", refusal != null)
        assertTrue(
            "and the sentence must say what it DOES accept: ${refusal?.because}",
            refusal!!.because.contains("does accept") || refusal.because.contains("cycle state"),
        )
    }

    @Test
    fun aCapabilityTheDeviceDoesReportIsAllowed() {
        assertEquals(null, DeviceFabric.mayCommand(lamp(), Capability.BRIGHTNESS))
    }

    @Test
    fun aReadOnlyCapabilityCannotBeSet() {
        // A thermometer reports a temperature; asking to SET it is a request the device cannot
        // satisfy, and accepting it would store a value nothing observes.
        val thermometer = FabricDevice(
            id = "t1", name = "Hall", transport = Transport.ZIGBEE,
            deviceClass = DeviceClass.THERMOMETER,
            capabilities = setOf(Capability.READ_TEMPERATURE),
            state = mapOf(Capability.READ_TEMPERATURE to "19"),
        )
        val refusal = DeviceFabric.mayCommand(thermometer, Capability.READ_TEMPERATURE)
        assertTrue(refusal != null)
        assertTrue(
            "the sentence must say it is a reading, not a control: ${refusal!!.because}",
            refusal.because.contains("only reports"),
        )
    }

    @Test
    fun anUnreachableDeviceIsRefusedWithItsOwnReason() {
        val refusal = DeviceFabric.mayCommand(lamp(available = false), Capability.POWER)
        assertTrue(refusal != null)
        assertTrue(
            "an unreachable device is a different fact from a missing capability: ${refusal!!.because}",
            refusal.because.contains("not reachable"),
        )
    }

    @Test
    fun aControlIsOnlySuccessfulWhenTheReadBackMatches() {
        val device = lamp()
        val outcome = DeviceFabric.outcomeOf(
            device = device,
            capability = Capability.BRIGHTNESS,
            askedFor = "127",
            readBack = device.copy(state = mapOf(Capability.BRIGHTNESS to "127")),
        )
        assertTrue("a matching read-back is a success", outcome.success)
        assertTrue(outcome is ControlOutcome.Verified)
    }

    @Test
    fun aCommandThatChangedNothingIsNotASuccess() {
        // This is the defect the whole rule exists for: a write returning OK while the device did not
        // move. Reporting that as success is the same fault as a zero-byte screen recording.
        val device = lamp()
        val outcome = DeviceFabric.outcomeOf(
            device = device,
            capability = Capability.BRIGHTNESS,
            askedFor = "50",
            readBack = device.copy(state = mapOf(Capability.BRIGHTNESS to "127")),
        )
        assertFalse("unchanged is not success", outcome.success)
        assertTrue(outcome is ControlOutcome.Unchanged)
        assertTrue(
            "and the sentence must say so plainly: ${outcome.sentence("the kitchen light")}",
            outcome.sentence("the kitchen light").contains("This is not a success"),
        )
    }

    @Test
    fun aMissingReadBackIsAFailureRatherThanASilentPass() {
        val device = lamp()
        val outcome = DeviceFabric.outcomeOf(
            device = device,
            capability = Capability.BRIGHTNESS,
            askedFor = "50",
            readBack = device.copy(state = emptyMap()),
        )
        assertFalse(outcome.success)
        assertTrue(outcome is ControlOutcome.Failed)
    }

    @Test
    fun numbersCompareNumericallyAndBooleansAsBooleans() {
        // Protocols differ on how they spell the same answer, and a string comparison would report a
        // working command as unchanged.
        assertTrue(DeviceFabric.sameValue("50", "50.0"))
        assertTrue(DeviceFabric.sameValue("on", "true"))
        assertTrue(DeviceFabric.sameValue("OFF", "false"))
        assertTrue(DeviceFabric.sameValue(" 127 ", "127"))
        assertFalse(DeviceFabric.sameValue("50", "51"))
        assertFalse(DeviceFabric.sameValue("on", "off"))
    }

    @Test
    fun aDeviceIsResolvedByIdNameOrDisplayName() {
        val devices = listOf(lamp(), washer())
        assertEquals(
            "lamp-1",
            (DeviceFabric.resolve(devices, "lamp-1") as DeviceFabric.Resolution.Found).device.id,
        )
        assertEquals(
            "lamp-1",
            (DeviceFabric.resolve(devices, "Ceiling") as DeviceFabric.Resolution.Found).device.id,
        )
        assertEquals(
            "lamp-1",
            (DeviceFabric.resolve(devices, "Ceiling in the kitchen") as DeviceFabric.Resolution.Found).device.id,
        )
    }

    @Test
    fun anAmbiguousNameIsAQuestionNotAChoice() {
        // Silently controlling one of two kitchen lights is worse than asking which.
        val devices = listOf(
            lamp(id = "a", name = "Ceiling", room = "kitchen"),
            lamp(id = "b", name = "Ceiling", room = "lounge"),
        )
        val resolution = DeviceFabric.resolve(devices, "Ceiling")
        assertTrue(resolution is DeviceFabric.Resolution.Ambiguous)
        assertEquals(2, (resolution as DeviceFabric.Resolution.Ambiguous).candidates.size)
    }

    @Test
    fun aMissingDeviceNamesWhatTheFabricKnows() {
        val resolution = DeviceFabric.resolve(listOf(lamp(), washer()), "the greenhouse")
        assertTrue(resolution is DeviceFabric.Resolution.Missing)
        val missing = resolution as DeviceFabric.Resolution.Missing
        assertTrue(
            "it must list what exists, so a person can correct themselves: ${missing.known}",
            missing.known.contains("Ceiling in the kitchen"),
        )
    }

    @Test
    fun aPartialNameThatMatchesOneDeviceResolves() {
        val devices = listOf(lamp(id = "a", name = "Kitchen Ceiling"), washer())
        val resolution = DeviceFabric.resolve(devices, "Kitchen Ceil")
        assertTrue(resolution is DeviceFabric.Resolution.Found)
    }

    @Test
    fun anUnfilteredQueryReturnsEverythingRatherThanNothing() {
        // A fabric that returns nothing for an unfiltered query is the absence-as-measurement defect
        // this app keeps removing.
        val devices = listOf(lamp(), washer())
        assertEquals(2, DeviceFabric.filter(devices).size)
    }

    @Test
    fun theFiltersNarrowAndCombine() {
        val devices = listOf(
            lamp(id = "a", room = "kitchen"),
            lamp(id = "b", room = "lounge"),
            washer(),
        )
        assertEquals(
            "only the kitchen lamp",
            1,
            DeviceFabric.filter(devices, room = "kitchen").size,
        )
        assertEquals(
            "and the room match is case-insensitive",
            1,
            DeviceFabric.filter(devices, room = "Lounge").size,
        )
        assertEquals(
            "a capability filter finds only devices that report it",
            2,
            DeviceFabric.filter(devices, capability = Capability.BRIGHTNESS).size,
        )
        assertEquals(1, DeviceFabric.filter(devices, deviceClass = DeviceClass.LAUNDRY_WASHER).size)
    }

    @Test
    fun aDisplayNamePutsTheRoomInFront() {
        assertEquals("Ceiling in the kitchen", lamp().displayName())
        assertEquals("Ceiling", lamp(room = null).displayName())
    }

    @Test
    fun twoProtocolsAppearInOneList() {
        // The acceptance list's first criterion: devices from at least two transports in one list.
        val devices = listOf(lamp(), washer())
        assertEquals(2, devices.map { it.transport }.distinct().size)
        assertEquals(2, DeviceFabric.filter(devices).size)
    }
}
