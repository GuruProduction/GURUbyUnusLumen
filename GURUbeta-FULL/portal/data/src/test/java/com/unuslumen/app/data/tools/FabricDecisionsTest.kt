// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.fabric.Capability
import com.unuslumen.app.data.fabric.Device
import com.unuslumen.app.data.fabric.DeviceClass
import com.unuslumen.app.data.fabric.DeviceValue
import com.unuslumen.app.data.fabric.FabricDecisions
import com.unuslumen.app.data.fabric.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The fabric's two rules, tested against the real code the fabric runs.
 *
 * THIS IS THE TEST THAT CLOSES THE GAP the first commit on this branch left open.
 * DeviceFabric takes a Room DAO, so its control path could only be proved with a
 * device or a fake, and a fake proves the fake. FabricDecisions holds the same
 * rules as pure functions and DeviceFabric now calls them, so these tests exercise
 * the actual decision a device command goes through.
 */
class FabricDecisionsTest {

    private fun lamp(
        capabilities: Set<Capability> = setOf(Capability.POWER, Capability.BRIGHTNESS),
        available: Boolean = true,
    ) = Device(
        id = "light-1",
        name = "Kitchen Light",
        room = "Kitchen",
        transport = Transport.MANUAL,
        deviceClass = DeviceClass.LIGHT,
        capabilities = capabilities,
        state = mapOf(Capability.POWER to DeviceValue.Flag(false)),
        available = available,
        lastSeen = 0,
    )

    private fun washer() = Device(
        id = "washer-1",
        name = "Washing Machine",
        room = "Utility",
        transport = Transport.MANUAL,
        deviceClass = DeviceClass.LAUNDRY_WASHER,
        capabilities = setOf(Capability.CYCLE_STATE, Capability.REMAINING_TIME),
        state = mapOf(Capability.CYCLE_STATE to DeviceValue.Text("idle")),
        available = true,
        lastSeen = 0,
    )

    // ---- rule 1: capability enforcement ----

    @Test
    fun aCapabilityTheDeviceDoesNotReportIsRefusedNamingBoth() {
        val verdict = FabricDecisions.canControl(washer(), Capability.START, transportInstalled = true)
        assertTrue("START must be refused", verdict is FabricDecisions.Verdict.Refused)

        val reason = (verdict as FabricDecisions.Verdict.Refused).reason
        assertTrue("must name the device", reason.contains("Washing Machine"))
        assertTrue("must name the capability asked for", reason.contains("START"))
        assertTrue("must name what the device DOES report", reason.contains("CYCLE_STATE"))
    }

    @Test
    fun aReportedWritableCapabilityIsAllowed() {
        assertTrue(
            FabricDecisions.canControl(lamp(), Capability.POWER, transportInstalled = true)
                is FabricDecisions.Verdict.Allowed,
        )
    }

    @Test
    fun aReadingIsRefusedEvenThoughTheDeviceReportsIt() {
        // The device reports REMAINING_TIME, so the capability check passes; the
        // reading check must then refuse it as a command.
        val verdict = FabricDecisions.canControl(washer(), Capability.REMAINING_TIME, transportInstalled = true)
        assertTrue(verdict is FabricDecisions.Verdict.Refused)
        assertTrue((verdict as FabricDecisions.Verdict.Refused).reason.contains("reading"))
    }

    @Test
    fun anUnreachableDeviceIsRefusedBeforeAnythingIsSent() {
        val verdict = FabricDecisions.canControl(lamp(available = false), Capability.POWER, transportInstalled = true)
        assertTrue(verdict is FabricDecisions.Verdict.Refused)
        assertTrue((verdict as FabricDecisions.Verdict.Refused).reason.contains("not reachable"))
    }

    @Test
    fun aMissingAdapterIsRefusedAndSaysSo() {
        val verdict = FabricDecisions.canControl(lamp(), Capability.POWER, transportInstalled = false)
        assertTrue(verdict is FabricDecisions.Verdict.Refused)
        assertTrue((verdict as FabricDecisions.Verdict.Refused).reason.contains("No adapter"))
    }

    @Test
    fun anUnknownDeviceIsRefusedRatherThanNpe() {
        val verdict = FabricDecisions.canControl(null, Capability.POWER, transportInstalled = true)
        assertTrue(verdict is FabricDecisions.Verdict.Refused)
    }

    // ---- rule 2: read-back confirmation ----

    @Test
    fun aMatchingReadBackConfirms() {
        val c = FabricDecisions.confirm(
            "Kitchen Light", Capability.POWER,
            wrote = DeviceValue.Flag(true),
            observed = DeviceValue.Flag(true),
        )
        assertTrue("an observed equal value must confirm", c is FabricDecisions.Confirmation.Confirmed)
    }

    @Test
    fun aDifferentObservedValueDoesNotConfirm() {
        val c = FabricDecisions.confirm(
            "Kitchen Light", Capability.POWER,
            wrote = DeviceValue.Flag(true),
            observed = DeviceValue.Flag(false),
        )
        assertTrue("the device still reports off, so this is not a success", c is FabricDecisions.Confirmation.NotConfirmed)
        assertTrue((c as FabricDecisions.Confirmation.NotConfirmed).reason.contains("off"))
    }

    @Test
    fun aMissingReadBackDoesNotConfirm() {
        val c = FabricDecisions.confirm("Kitchen Light", Capability.POWER, DeviceValue.Flag(true), null)
        assertTrue(c is FabricDecisions.Confirmation.NotConfirmed)
        assertTrue(
            "the reason must say it could not be read back, not pretend it failed to change",
            (c as FabricDecisions.Confirmation.NotConfirmed).reason.contains("could not be read back"),
        )
    }

    @Test
    fun aBrightnessNumberMatchesItselfAndNotItsStringForm() {
        // The comparison the whole read-back rests on.
        assertTrue(
            FabricDecisions.confirm(
                "Kitchen Light", Capability.BRIGHTNESS,
                wrote = DeviceValue.Count(200),
                observed = DeviceValue.Count(200),
            ) is FabricDecisions.Confirmation.Confirmed,
        )
        assertTrue(
            "200 must not equal \"200\", or every write would look unconfirmed",
            FabricDecisions.confirm(
                "Kitchen Light", Capability.BRIGHTNESS,
                wrote = DeviceValue.Count(200),
                observed = DeviceValue.Text("200"),
            ) is FabricDecisions.Confirmation.NotConfirmed,
        )
    }

    // ---- device resolution ----

    @Test
    fun theKitchenLightResolvesFromPlainWords() {
        val r = FabricDecisions.resolve("kitchen light", listOf(lamp(), washer()))
        assertTrue("'kitchen light' must find the lamp", r.unambiguous)
        assertEquals("light-1", r.matches.first().id)
    }

    @Test
    fun anExactNameBeatsEverythingElse() {
        val r = FabricDecisions.resolve("Kitchen Light", listOf(lamp(), washer()))
        assertTrue(r.unambiguous)
        assertEquals("Kitchen Light", r.matches.first().name)
    }

    @Test
    fun anAmbiguousQueryIsNotGuessedAt() {
        val a = lamp().copy(id = "a", name = "Light One")
        val b = lamp().copy(id = "b", name = "Light Two")
        val r = FabricDecisions.resolve("light", listOf(a, b))
        assertFalse("two matches must not be reported as unambiguous", r.unambiguous)
        assertEquals(2, r.matches.size)
    }

    @Test
    fun aRoomFilterNarrowsTheSearch() {
        val r = FabricDecisions.resolve("light", listOf(lamp(), lamp().copy(id = "utility-light", name = "Utility Light", room = "Utility")), room = "Kitchen")
        assertTrue(r.unambiguous)
        assertEquals("light-1", r.matches.first().id)
    }

    @Test
    fun anEmptyQueryMatchesNothing() {
        assertFalse(FabricDecisions.resolve("   ", listOf(lamp())).unambiguous)
    }

    // ---- description ----

    @Test
    fun valuesReadAsSentences() {
        assertEquals("on", FabricDecisions.describe(DeviceValue.Flag(true)))
        assertEquals("off", FabricDecisions.describe(DeviceValue.Flag(false)))
        assertEquals("200", FabricDecisions.describe(DeviceValue.Count(200)))
        assertEquals("\"cool\"", FabricDecisions.describe(DeviceValue.Text("cool")))
        assertEquals("nothing", FabricDecisions.describe(null))
    }
}
