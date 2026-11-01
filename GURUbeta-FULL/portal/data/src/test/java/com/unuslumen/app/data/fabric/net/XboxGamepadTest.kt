// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * The Xbox controller bitmask, pinned against the protocol's own table.
 *
 * WHY EVERY BIT MATTERS. A controller is the difference between a console that can be switched
 * on and one that can actually be used. A wrong bit is a button that presses something else,
 * which in a menu is worse than a button that does nothing.
 */
class XboxGamepadTest {

    /** Every bit must be the protocol's bit, not a guess at one. */
    @Test
    fun everyButtonCarriesItsDocumentedBit() {
        val expected = mapOf(
            XboxGamepad.Button.NEXUS to 0x0002,
            XboxGamepad.Button.MENU to 0x0004,
            XboxGamepad.Button.VIEW to 0x0008,
            XboxGamepad.Button.A to 0x0010,
            XboxGamepad.Button.B to 0x0020,
            XboxGamepad.Button.X to 0x0040,
            XboxGamepad.Button.Y to 0x0080,
            XboxGamepad.Button.DPAD_UP to 0x0100,
            XboxGamepad.Button.DPAD_DOWN to 0x0200,
            XboxGamepad.Button.DPAD_LEFT to 0x0400,
            XboxGamepad.Button.DPAD_RIGHT to 0x0800,
            XboxGamepad.Button.LEFT_BUMPER to 0x1000,
            XboxGamepad.Button.RIGHT_BUMPER to 0x2000,
            XboxGamepad.Button.LEFT_STICK to 0x4000,
            XboxGamepad.Button.RIGHT_STICK to 0x8000,
        )
        for ((button, mask) in expected) {
            assertEquals("${button.label} must be $mask", mask, button.mask)
        }
    }

    /** The bits must not collide, or two buttons press each other. */
    @Test
    fun noTwoButtonsShareABit() {
        val masks = XboxGamepad.Button.entries.map { it.mask }
        assertEquals("every button needs its own bit", masks.size, masks.toSet().size)
    }

    /** A spoken name must find its button, because a person says "A" and not "0x0010". */
    @Test
    fun spokenButtonNamesResolve() {
        assertEquals(XboxGamepad.Button.A, XboxGamepad.Button.fromRaw("a"))
        assertEquals(XboxGamepad.Button.NEXUS, XboxGamepad.Button.fromRaw("guide"))
        assertEquals(XboxGamepad.Button.NEXUS, XboxGamepad.Button.fromRaw("xbox button"))
        assertEquals(XboxGamepad.Button.MENU, XboxGamepad.Button.fromRaw("start"))
        assertEquals(XboxGamepad.Button.VIEW, XboxGamepad.Button.fromRaw("back"))
        assertEquals(XboxGamepad.Button.LEFT_BUMPER, XboxGamepad.Button.fromRaw("LB"))
        assertEquals(XboxGamepad.Button.RIGHT_STICK, XboxGamepad.Button.fromRaw("r3"))
        assertNull("an unknown button must not be guessed", XboxGamepad.Button.fromRaw("trigger"))
    }

    /** A direction resolves from the word a person says. */
    @Test
    fun directionsResolveFromWords() {
        assertEquals(XboxGamepad.Button.DPAD_UP, XboxGamepad.direction("up"))
        assertEquals(XboxGamepad.Button.DPAD_DOWN, XboxGamepad.direction("down"))
        assertEquals(XboxGamepad.Button.DPAD_LEFT, XboxGamepad.direction("left"))
        assertEquals(XboxGamepad.Button.DPAD_RIGHT, XboxGamepad.direction("right"))
        assertNull(XboxGamepad.direction("diagonal"))
    }

    /** The state tracks which buttons are down, and pressing one does not clear another. */
    @Test
    fun theStateAccumulatesAndReleasesButtons() {
        val a = XboxGamepad.State().with(XboxGamepad.Button.A)
        assertTrue(a.isDown(XboxGamepad.Button.A))
        assertFalse(a.isDown(XboxGamepad.Button.B))

        val both = a.with(XboxGamepad.Button.B)
        assertTrue("pressing B must not release A", both.isDown(XboxGamepad.Button.A))
        assertTrue(both.isDown(XboxGamepad.Button.B))

        val releasedA = both.without(XboxGamepad.Button.A)
        assertFalse(releasedA.isDown(XboxGamepad.Button.A))
        assertTrue("releasing A must not release B", releasedA.isDown(XboxGamepad.Button.B))
    }

    /**
     * A tap is two states: down then up.
     *
     * A caller that sends only the down state leaves the button held, which a menu reads as a
     * repeat and a game reads as a stuck input.
     */
    @Test
    fun aTapIsADownAndAnUp() {
        val states = XboxGamepad.tap(XboxGamepad.Button.A)
        assertEquals("a tap is exactly two messages", 2, states.size)
        assertTrue("the first state presses", states[0].isDown(XboxGamepad.Button.A))
        assertFalse("the second state releases", states[1].isDown(XboxGamepad.Button.A))
    }

    /** A trigger pull returns to rest, so a trigger is never left held. */
    @Test
    fun aTriggerPullReturnsToRest() {
        val states = XboxGamepad.trigger(0.7f, right = true)
        assertEquals(2, states.size)
        assertEquals("the pull carries the travel", 0.7f, states[0].rightTrigger, 0.001f)
        assertEquals("and the release returns it to rest", 0f, states[1].rightTrigger, 0.001f)
        assertEquals("the left trigger is untouched", 0f, states[0].leftTrigger, 0.001f)
    }

    /** Travel outside 0 to 1 is clamped, because the protocol carries a float not a percentage. */
    @Test
    fun triggerTravelIsClamped() {
        assertEquals(1f, XboxGamepad.trigger(5f).first().rightTrigger, 0.001f)
        assertEquals(0f, XboxGamepad.trigger(-2f).first().rightTrigger, 0.001f)
    }

    /**
     * A combined state must export both bits, so two buttons can be held at once.
     *
     * The mask is the SECOND field of the payload, after the timestamp, which is why the buffer
     * skips a long before reading it.
     */
    @Test
    fun aCombinedStateExportsBothBits() {
        val state = XboxGamepad.State()
            .with(XboxGamepad.Button.LEFT_BUMPER)
            .with(XboxGamepad.Button.DPAD_UP)
        val buffer = ByteBuffer
            .wrap(SmartGlassMessage.gamepadPayload(state.masks))
            .order(ByteOrder.BIG_ENDIAN)
        buffer.long
        assertEquals(
            "both bits must be present in the mask",
            0x1000 or 0x0100,
            buffer.short.toInt() and 0xFFFF,
        )
    }

    /** The console's discovery packet must be a real SmartGlass packet, not an ASCII string. */
    @Test
    fun theDiscoveryPacketIsAFramedSmartGlassMessage() {
        val packet = XboxConsole().discoveryPacket()

        assertEquals(
            "a discovery packet is a six-byte header plus the ten-byte payload",
            SmartGlassMessage.SIMPLE_HEADER_SIZE + 10,
            packet.size,
        )
        val buffer = ByteBuffer.wrap(packet).order(ByteOrder.BIG_ENDIAN)
        assertEquals(
            "the type is the discovery request, as a 16-bit field",
            SmartGlassMessage.TYPE_DISCOVERY_REQUEST,
            buffer.short.toInt() and 0xFFFF,
        )
    }

    /** A reply that is not a discovery response must be refused rather than half-read. */
    @Test
    fun aNonDiscoveryReplyIsRefused() {
        val console = XboxConsole()
        assertEquals(null, console.readReply(ByteArray(0)))
        assertEquals(null, console.readReply(ByteArray(10)))

        // A packet that is a request rather than a reply must not be read as a console.
        val wrongType = ByteBuffer.allocate(SmartGlassMessage.SIMPLE_HEADER_SIZE + 8)
            .order(ByteOrder.BIG_ENDIAN)
            .putShort(SmartGlassMessage.TYPE_DISCOVERY_REQUEST.toShort())
            .array()
        assertEquals("a request is not a reply", null, console.readReply(wrongType))
    }
}
