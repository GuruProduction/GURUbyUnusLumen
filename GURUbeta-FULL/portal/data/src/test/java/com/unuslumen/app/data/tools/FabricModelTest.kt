// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools

import com.unuslumen.app.data.fabric.Capability
import com.unuslumen.app.data.fabric.DeviceClass
import com.unuslumen.app.data.fabric.DeviceValue
import com.unuslumen.app.data.fabric.Transport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The device fabric's model, proved on the JVM. Issue #182.
 *
 * Runs in seconds with no emulator, because everything here is a pure decision.
 * That is the right harness for it: the classes, capabilities and value
 * comparisons are the contract every transport maps onto, and a logic fault here
 * would be a logic fault on every device at once.
 */
class FabricModelTest {

    // ---- device classes ----

    @Test
    fun everyMatterDeviceClassIsRepresented() {
        // The list the issue specifies. If one is dropped, a transport meets a
        // device it cannot name and invents a parallel vocabulary.
        val required = listOf(
            "LIGHT", "PLUG", "SWITCH", "LOCK", "DOOR", "WINDOW_COVERING",
            "THERMOSTAT", "THERMOMETER", "HUMIDITY_SENSOR", "OCCUPANCY_SENSOR",
            "CONTACT_SENSOR", "ILLUMINANCE_SENSOR", "PRESSURE_SENSOR", "FLOW_SENSOR",
            "AIR_QUALITY_SENSOR", "FAN", "AIR_PURIFIER", "AIR_CONDITIONER", "HEATER",
            "WATER_HEATER", "ROBOT_VACUUM", "DISHWASHER", "LAUNDRY_WASHER",
            "LAUNDRY_DRYER", "OVEN", "COOKTOP", "REFRIGERATOR", "MICROWAVE",
            "COFFEE_MAKER", "SPEAKER", "TV", "CAMERA", "DOORBELL", "ALARM",
            "SMOKE_DETECTOR", "CO_DETECTOR", "WATER_LEAK_DETECTOR", "ENERGY_METER", "BRIDGE",
        )
        val names = DeviceClass.entries.map { it.name }
        val missing = required.filter { it !in names }
        assertTrue("device classes missing: $missing", missing.isEmpty())
    }

    @Test
    fun anUnknownClassIsStoredRatherThanDropped() {
        // A transport meeting a device type it cannot map must not lose the
        // device: "stored raw rather than dropped" is the rule.
        assertEquals(DeviceClass.UNKNOWN, DeviceClass.fromRaw("SOMETHING_NEW"))
        assertEquals(DeviceClass.UNKNOWN, DeviceClass.fromRaw(null))
    }

    @Test
    fun classLookupIsCaseInsensitive() {
        assertEquals(DeviceClass.LIGHT, DeviceClass.fromRaw("light"))
        assertEquals(DeviceClass.LIGHT, DeviceClass.fromRaw("  LiGhT  "))
    }

    // ---- capabilities ----

    @Test
    fun readingsAreNotWritable() {
        for (readOnly in listOf(
            Capability.READ_TEMPERATURE, Capability.READ_HUMIDITY, Capability.READ_OCCUPANCY,
            Capability.READ_CONTACT, Capability.READ_ILLUMINANCE, Capability.READ_PRESSURE,
            Capability.READ_FLOW, Capability.READ_AIR_QUALITY, Capability.READ_ENERGY,
            Capability.READ_POWER, Capability.REMAINING_TIME,
        )) {
            assertFalse("$readOnly is a reading and must not be writable", readOnly.isWritable)
        }
    }

    @Test
    fun commandsAreWritable() {
        for (writable in listOf(
            Capability.POWER, Capability.BRIGHTNESS, Capability.TARGET_TEMPERATURE,
            Capability.LOCK_STATE, Capability.POSITION, Capability.VOLUME,
        )) {
            assertTrue("$writable must accept a command", writable.isWritable)
        }
    }

    @Test
    fun aCapabilityNameThatDoesNotExistResolvesToNull() {
        // Null, not a default. A caller that mistypes a capability must be told,
        // not silently handed POWER.
        assertNull(Capability.fromRaw("NOT_A_CAPABILITY"))
        assertNotNull(Capability.fromRaw("power"))
    }

    // ---- transports ----

    @Test
    fun everyPlannedTransportExists() {
        for (name in listOf("MATTER", "HA", "MQTT", "ZIGBEE", "ZWAVE", "IR", "HUE", "SONOS")) {
            assertEquals(name, Transport.fromRaw(name).name)
        }
    }

    @Test
    fun anUnrecognisedTransportFallsBackToManual() {
        assertEquals(Transport.MANUAL, Transport.fromRaw("SOMETHING_ELSE"))
    }

    // ---- values ----

    @Test
    fun aBooleanBecomesAFlagAndComparesEqual() {
        val v = DeviceValue.of(true)
        assertTrue("a Boolean must become a Flag", v is DeviceValue.Flag)
        assertEquals(true, v!!.asComparable())
    }

    @Test
    fun aBrightnessStaysAnIntegerAndNeverBecomesText() {
        // This is the comparison the read-back depends on. If 200 came back as
        // "200" the write would look unconfirmed forever.
        val v = DeviceValue.of(200)
        assertTrue(v is DeviceValue.Count)
        assertEquals(200, v!!.asComparable())
        assertTrue("200 must not equal \"200\"", v.asComparable() != "200")
    }

    @Test
    fun aFloatBecomesAMeasure() {
        val v = DeviceValue.of(21.5f)
        assertTrue(v is DeviceValue.Measure)
        assertEquals(21.5, v!!.asComparable() as Double, 0.0001)
    }

    @Test
    fun aStringStaysText() {
        val v = DeviceValue.of("cool")
        assertTrue(v is DeviceValue.Text)
        assertEquals("cool", v!!.asComparable())
    }

    @Test
    fun anUnsupportedTypeProducesNothingRatherThanAGuess() {
        assertNull(DeviceValue.of(listOf(1, 2, 3)))
        assertNull(DeviceValue.of(null))
    }

    @Test
    fun aLongIsAcceptedBecauseProtocolsSendLongs() {
        val v = DeviceValue.of(45L)
        assertTrue(v is DeviceValue.Count)
        assertEquals(45, v!!.asComparable())
    }
}
