// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 184's mapping rules.
 *
 * The bitmask is the part that goes wrong silently: one table shared across domains would give a
 * blind a colour picker and a speaker a position slider, and nothing would report an error.
 */
class HomeAssistantMappingTest {

    @Test
    fun theDomainDecidesMostClasses() {
        assertEquals(DeviceClass.LIGHT, HomeAssistantMapping.deviceClassOf("light"))
        assertEquals(DeviceClass.LOCK, HomeAssistantMapping.deviceClassOf("lock"))
        assertEquals(DeviceClass.WINDOW_COVERING, HomeAssistantMapping.deviceClassOf("cover"))
        assertEquals(DeviceClass.THERMOSTAT, HomeAssistantMapping.deviceClassOf("climate"))
        assertEquals(DeviceClass.FAN, HomeAssistantMapping.deviceClassOf("fan"))
        assertEquals(DeviceClass.ROBOT_VACUUM, HomeAssistantMapping.deviceClassOf("vacuum"))
        assertEquals(DeviceClass.CAMERA, HomeAssistantMapping.deviceClassOf("camera"))
        assertEquals(DeviceClass.ALARM, HomeAssistantMapping.deviceClassOf("alarm_control_panel"))
    }

    @Test
    fun aMediaPlayerIsASpeakerUnlessItSaysItIsATelevision() {
        assertEquals(DeviceClass.SPEAKER, HomeAssistantMapping.deviceClassOf("media_player"))
        assertEquals(
            DeviceClass.TV,
            HomeAssistantMapping.deviceClassOf("media_player", "tv"),
        )
    }

    @Test
    fun aBinarySensorIsRefinedByItsOwnDeviceClass() {
        assertEquals(
            "a motion sensor is not the same thing as a door",
            DeviceClass.OCCUPANCY_SENSOR,
            HomeAssistantMapping.deviceClassOf("binary_sensor", "motion"),
        )
        assertEquals(
            DeviceClass.CONTACT_SENSOR,
            HomeAssistantMapping.deviceClassOf("binary_sensor", "door"),
        )
        assertEquals(
            DeviceClass.WATER_LEAK_DETECTOR,
            HomeAssistantMapping.deviceClassOf("binary_sensor", "moisture"),
        )
        assertEquals(
            "an unrecognised binary sensor still reports contact rather than being dropped",
            DeviceClass.CONTACT_SENSOR,
            HomeAssistantMapping.deviceClassOf("binary_sensor", "something_new"),
        )
    }

    @Test
    fun aSensorIsWhateverItMeasures() {
        assertEquals(DeviceClass.THERMOMETER, HomeAssistantMapping.deviceClassOf("sensor", "temperature"))
        assertEquals(DeviceClass.HUMIDITY_SENSOR, HomeAssistantMapping.deviceClassOf("sensor", "humidity"))
        assertEquals(DeviceClass.ENERGY_METER, HomeAssistantMapping.deviceClassOf("sensor", "power"))
        assertEquals(
            DeviceClass.AIR_QUALITY_SENSOR,
            HomeAssistantMapping.deviceClassOf("sensor", "pm25"),
        )
        assertEquals(
            "an unrecognised sensor still has a class rather than vanishing",
            DeviceClass.UNKNOWN,
            HomeAssistantMapping.deviceClassOf("sensor", "mystery"),
        )
    }

    @Test
    fun aLightAlwaysHasPowerWhateverTheMaskSays() {
        // A light can always be turned on, so the mask does not get to deny it.
        assertTrue(
            Capability.POWER in HomeAssistantMapping.capabilitiesOf("light", null),
        )
        assertTrue(
            Capability.POWER in HomeAssistantMapping.capabilitiesOf("light", 0L),
        )
    }

    @Test
    fun aLightWithoutTheBrightnessBitHasNoBrightness() {
        // The defect this prevents: every light offered a brightness slider whether or not anything
        // listens to it, and the write silently does nothing.
        val plain = HomeAssistantMapping.capabilitiesOf("light", supportedFeatures = 0L)
        assertFalse(Capability.BRIGHTNESS in plain)

        val dimmable = HomeAssistantMapping.capabilitiesOf("light", supportedFeatures = 1L)
        assertTrue(Capability.BRIGHTNESS in dimmable)
    }

    @Test
    fun colourTemperatureAndColourComeFromTheirOwnBits() {
        val ct = HomeAssistantMapping.capabilitiesOf("light", supportedFeatures = 2L)
        assertTrue(Capability.COLOR_TEMPERATURE in ct)
        assertFalse("bit 2 is temperature, not colour", Capability.COLOR_HS in ct)

        val colour = HomeAssistantMapping.capabilitiesOf("light", supportedFeatures = 4L)
        assertTrue(Capability.COLOR_HS in colour)
        assertFalse(Capability.COLOR_TEMPERATURE in colour)

        val both = HomeAssistantMapping.capabilitiesOf("light", supportedFeatures = 6L)
        assertTrue(Capability.COLOR_TEMPERATURE in both)
        assertTrue(Capability.COLOR_HS in both)
    }

    @Test
    fun aCoverThatOnlyOpensAndClosesHasNoPosition() {
        // THE TRAP: bit 4 means SET_POSITION on a cover and COLOR on a light, so one shared table
        // would give a blind a colour picker. A cover with OPEN and CLOSE only cannot be positioned.
        val openCloseOnly = HomeAssistantMapping.capabilitiesOf("cover", supportedFeatures = 3L)
        assertFalse(
            "a cover without bit 4 cannot be positioned: $openCloseOnly",
            Capability.POSITION in openCloseOnly,
        )
        assertTrue("but it can be commanded", Capability.POWER in openCloseOnly)

        val positionable = HomeAssistantMapping.capabilitiesOf("cover", supportedFeatures = 4L)
        assertTrue(Capability.POSITION in positionable)
    }

    @Test
    fun aSpeakerWithoutVolumeBitsHasNoVolume() {
        val playbackOnly = HomeAssistantMapping.capabilitiesOf("media_player", supportedFeatures = 0L)
        assertTrue(Capability.PLAYBACK in playbackOnly)
        assertFalse(
            "a speaker without bit 4 must not be offered a volume control: $playbackOnly",
            Capability.VOLUME in playbackOnly,
        )
        assertFalse(Capability.MUTE in playbackOnly)
    }

    @Test
    fun aSpeakerWithVolumeBitsHasVolumeAndMute() {
        val full = HomeAssistantMapping.capabilitiesOf("media_player", supportedFeatures = 12L)
        assertTrue(Capability.VOLUME in full)
        assertTrue(Capability.MUTE in full)
    }

    @Test
    fun aClimateEntityGetsTemperatureAndMode() {
        val caps = HomeAssistantMapping.capabilitiesOf("climate", null)
        assertTrue(Capability.TARGET_TEMPERATURE in caps)
        assertTrue(Capability.MODE in caps)
        assertTrue(Capability.CURRENT_TEMPERATURE in caps)
    }

    @Test
    fun aFanWithTheSpeedBitHasFanSpeed() {
        assertFalse(Capability.FAN_SPEED in HomeAssistantMapping.capabilitiesOf("fan", 0L))
        assertTrue(Capability.FAN_SPEED in HomeAssistantMapping.capabilitiesOf("fan", 1L))
    }

    @Test
    fun theEntityBecomesAFabricDeviceWithItsOwnId() {
        // The entity id is the stable id because HA keeps it across restarts and it is what a
        // service call addresses. An invented id could not be called.
        val device = HomeAssistantMapping.toDevice(
            entityId = "light.kitchen_ceiling",
            domain = "light",
            friendlyName = "Kitchen Ceiling",
            supportedFeatures = 1L,
            room = "Kitchen",
            stateValue = "on",
        )
        assertEquals("light.kitchen_ceiling", device.id)
        assertEquals("Kitchen Ceiling", device.name)
        assertEquals("Kitchen", device.room)
        assertEquals(Transport.HA, device.transport)
        assertEquals(DeviceClass.LIGHT, device.deviceClass)
        assertTrue(Capability.BRIGHTNESS in device.capabilities)
        assertTrue(device.available)
    }

    @Test
    fun anUnavailableEntityIsMarkedUnavailableRatherThanDropped() {
        // A device switched off at the wall reports `unavailable`; it has not ceased to exist, and a
        // fabric that omitted it would tell a person they own fewer things than they do.
        val device = HomeAssistantMapping.toDevice(
            entityId = "light.hall",
            domain = "light",
            friendlyName = "Hall",
            stateValue = "unavailable",
        )
        assertFalse(device.available)
    }

    @Test
    fun anEmptyFriendlyNameFallsBackToTheEntityName() {
        val device = HomeAssistantMapping.toDevice(
            entityId = "light.kitchen_ceiling",
            domain = "light",
            friendlyName = "",
        )
        assertEquals("kitchen_ceiling", device.name)
    }

    @Test
    fun theStateIsRecordedAgainstACapabilitySoReadingBackHasSomething() {
        val device = HomeAssistantMapping.toDevice(
            entityId = "light.hall",
            domain = "light",
            friendlyName = "Hall",
            stateValue = "on",
        )
        assertTrue(
            "the entity's own state must land in the map, so a read-back has a value to compare: " +
                "${device.state}",
            device.state.isNotEmpty(),
        )
        // The state is a typed value, so a light switched on is a power flag rather than the string
        // "on". That typing is what stops a brightness silently becoming text and a comparison
        // against zero stopping working.
        assertTrue(
            "a light's on state must be recorded as a power flag: ${device.state}",
            device.state[Capability.POWER] == DeviceValue.Flag(true),
        )
    }

    @Test
    fun anEntityWhoseClassIsUnrecognisedStillBecomesADevice() {
        // A fabric that dropped entities it did not recognise would report a smaller house than the
        // person owns, which is the absence-as-measurement defect again.
        val device = HomeAssistantMapping.toDevice(
            entityId = "sensor.mystery",
            domain = "sensor",
            friendlyName = "Mystery",
            haDeviceClass = "something_new",
        )
        assertEquals(DeviceClass.UNKNOWN, device.deviceClass)
        assertEquals("sensor.mystery", device.id)
    }
}
