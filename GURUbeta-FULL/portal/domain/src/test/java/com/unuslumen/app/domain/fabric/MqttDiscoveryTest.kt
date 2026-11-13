// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.domain.fabric

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Issue 185's discovery path, against the payloads real brokers carry.
 *
 * The `~` abbreviation is the trap here: a reader that ignores it subscribes to a topic no device
 * publishes, and the device appears and never updates.
 */
class MqttDiscoveryTest {

    @Test
    fun aDiscoveryTopicIsRecognisedByItsShape() {
        val topic = MqttDiscovery.parseTopic("homeassistant/light/kitchen/ceiling/config")
        assertEquals("light", topic?.component)
        assertEquals("kitchen", topic?.nodeId)
        assertEquals("ceiling", topic?.objectId)
        assertEquals("kitchen.ceiling", topic?.suggestedId)
    }

    @Test
    fun aTopicThatIsNotTheDiscoveryShapeIsNotGuessedAt() {
        assertNull(
            "a state topic is not a discovery message",
            MqttDiscovery.parseTopic("zigbee2mqtt/kitchen_light"),
        )
        assertNull(
            "and neither is a discovery topic without the config suffix",
            MqttDiscovery.parseTopic("homeassistant/light/kitchen/ceiling"),
        )
        assertNull(
            MqttDiscovery.parseTopic("other/light/kitchen/ceiling/config"),
        )
    }

    @Test
    fun aZigbee2mqttLightIsReadWithItsTopics() {
        val payload = """
            {"name":"Kitchen light","state_topic":"zigbee2mqtt/kitchen_light",
             "command_topic":"zigbee2mqtt/kitchen_light/set",
             "brightness_state_topic":"zigbee2mqtt/kitchen_light",
             "brightness_command_topic":"zigbee2mqtt/kitchen_light/set",
             "unique_id":"0x00124b_kitchen_light"}
        """.trimIndent()
        val discovered = MqttDiscovery.parse("homeassistant/light/kitchen/light/config", payload)
        assertTrue(discovered != null)
        assertEquals("Kitchen light", discovered!!.name)
        assertEquals(DeviceClass.LIGHT, discovered.deviceClass)
        assertEquals("zigbee2mqtt/kitchen_light", discovered.stateTopic)
        assertEquals("zigbee2mqtt/kitchen_light/set", discovered.commandTopic)
        assertEquals("0x00124b_kitchen_light", discovered.suggestedId)
        assertTrue(Capability.POWER in discovered.capabilities)
        assertTrue(Capability.BRIGHTNESS in discovered.capabilities)
    }

    @Test
    fun theTildeAbbreviationIsExpanded() {
        // THE TRAP. Without expansion this subscribes to "~/state", which no device publishes, and
        // the device appears and never updates.
        val payload = """
            {"~":"zigbee2mqtt/kitchen_light","name":"Kitchen light",
             "state_topic":"~/state","command_topic":"~/set"}
        """.trimIndent()
        val discovered = MqttDiscovery.parse("homeassistant/light/kitchen/light/config", payload)
        assertEquals(
            "the base must be substituted into the state topic",
            "zigbee2mqtt/kitchen_light/state",
            discovered?.stateTopic,
        )
        assertEquals("zigbee2mqtt/kitchen_light/set", discovered?.commandTopic)
    }

    @Test
    fun aLightWithoutBrightnessTopicsHasNoBrightness() {
        // A field-per-capability protocol: no brightness topic means nothing listens to a brightness
        // write, so offering one produces a control that writes into the void.
        val payload = """{"name":"Plain lamp","state_topic":"lamp/state","command_topic":"lamp/set"}"""
        val discovered = MqttDiscovery.parse("homeassistant/light/x/lamp/config", payload)
        assertTrue(Capability.POWER in discovered!!.capabilities)
        assertFalse(Capability.BRIGHTNESS in discovered.capabilities)
        assertFalse(Capability.COLOR_TEMPERATURE in discovered.capabilities)
    }

    @Test
    fun aColourTopicGrantsColourAndNotTemperature() {
        val payload = """
            {"name":"Colour lamp","state_topic":"l/state","command_topic":"l/set",
             "rgb_state_topic":"l/state","rgb_command_topic":"l/set"}
        """.trimIndent()
        val caps = MqttDiscovery.parse("homeassistant/light/x/l/config", payload)!!.capabilities
        assertTrue(Capability.COLOR_HS in caps)
        assertFalse(Capability.COLOR_TEMPERATURE in caps)
    }

    @Test
    fun aSensorWithADeviceClassBecomesAThermometer() {
        val payload = """
            {"name":"Hall temperature","state_topic":"sensor/hall",
             "device_class":"temperature","unit_of_measurement":"C"}
        """.trimIndent()
        val discovered = MqttDiscovery.parse("homeassistant/sensor/hall/temp/config", payload)
        assertEquals(DeviceClass.THERMOMETER, discovered?.deviceClass)
        assertNull("a sensor has no command topic", discovered?.commandTopic)
        assertFalse("and so it is not controllable", discovered!!.isControllable)
    }

    @Test
    fun aSensorWithoutADeviceClassIsNotClaimedToBeAnythingInParticular() {
        val payload = """{"name":"Mystery","state_topic":"sensor/x"}"""
        val discovered = MqttDiscovery.parse("homeassistant/sensor/x/mystery/config", payload)
        assertEquals(DeviceClass.UNKNOWN, discovered?.deviceClass)
    }

    @Test
    fun aCoverWithAPositionTopicCanBePositioned() {
        val payload = """
            {"name":"Lounge blind","state_topic":"cover/state","command_topic":"cover/set",
             "position_topic":"cover/position","set_position_topic":"cover/position/set"}
        """.trimIndent()
        val caps = MqttDiscovery.parse("homeassistant/cover/lounge/blind/config", payload)!!.capabilities
        assertTrue(Capability.POSITION in caps)
    }

    @Test
    fun aCoverWithoutPositionTopicsCannotBePositioned() {
        val payload = """
            {"name":"Simple blind","state_topic":"cover/state","command_topic":"cover/set"}
        """.trimIndent()
        val caps = MqttDiscovery.parse("homeassistant/cover/lounge/blind/config", payload)!!.capabilities
        assertFalse(Capability.POSITION in caps)
    }

    @Test
    fun theDeviceIdentifiersGroupSeveralEntitiesIntoOneDevice() {
        // A smart plug publishing a switch and a power sensor is one device with two entities, and a
        // fabric that made two devices would show a person a house twice the size of theirs.
        val payload = """
            {"name":"Washer plug","state_topic":"plug/state","command_topic":"plug/set",
             "device":{"identifiers":["shelly_plug_1"],"name":"Washer plug"}}
        """.trimIndent()
        val discovered = MqttDiscovery.parse("homeassistant/switch/utility/plug/config", payload)
        assertEquals("shelly_plug_1", discovered?.deviceIdentifier)
    }

    @Test
    fun anAvailabilityTopicIsCarriedSoAnUnpluggedDeviceCanBeMarkedAbsent() {
        // A discovery message stays retained on the broker after the device is unplugged, so without
        // its availability topic the fabric would report a device that is not there.
        val payload = """
            {"name":"Plug","state_topic":"p/state","command_topic":"p/set",
             "availability_topic":"p/available"}
        """.trimIndent()
        val discovered = MqttDiscovery.parse("homeassistant/switch/x/p/config", payload)
        assertEquals("p/available", discovered?.availabilityTopic)
    }

    @Test
    fun anAvailabilityListIsReadFromItsFirstEntry() {
        val payload = """
            {"name":"Plug","state_topic":"p/state","availability":[{"topic":"p/status","payload_available":"online"}]}
        """.trimIndent()
        val discovered = MqttDiscovery.parse("homeassistant/switch/x/p/config", payload)
        assertEquals("p/status", discovered?.availabilityTopic)
    }

    @Test
    fun aPayloadWithNoNameFallsBackToTheObjectIdInWords() {
        val payload = """{"state_topic":"x/state"}"""
        val discovered = MqttDiscovery.parse("homeassistant/sensor/x/temperature_sensor/config", payload)
        assertEquals("temperature sensor", discovered?.name)
    }

    @Test
    fun anEmptyOrBrokenPayloadIsNotADevice() {
        assertNull(MqttDiscovery.parse("homeassistant/light/x/y/config", ""))
        assertNull(MqttDiscovery.parse("homeassistant/light/x/y/config", "{}"))
        assertNull(
            "a topic that is not discovery never becomes a device",
            MqttDiscovery.parse("zigbee2mqtt/kitchen", """{"state_topic":"a/b"}"""),
        )
    }

    @Test
    fun theJsonReaderHandlesEscapedQuotesAndNestedObjects() {
        // A name can contain a quote and a device block is nested, and a naive split on commas
        // corrupts both.
        val payload = """
            {"name":"Steven's \"reading\" lamp","state_topic":"l/state",
             "device":{"identifiers":["abc"],"name":"Lamp, the second"}}
        """.trimIndent()
        val discovered = MqttDiscovery.parse("homeassistant/light/x/l/config", payload)
        assertEquals("Steven's \"reading\" lamp", discovered?.name)
        assertEquals("abc", discovered?.deviceIdentifier)
    }
}
