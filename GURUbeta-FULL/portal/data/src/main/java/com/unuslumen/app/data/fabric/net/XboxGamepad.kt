// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.fabric.net

/**
 * The whole Xbox controller as a 16-bit bitmask plus two trigger floats.
 *
 * WHY THIS IS THE REAL "ULTIMATE CONTROL". Power on and power off are two bits of a console's
 * life. A controller is every button, every direction, both sticks, both triggers and both
 * bumpers, and that is what makes a console genuinely drivable: menus, games, media apps,
 * everything a person can do with a pad in their hands.
 *
 * THE BIT LAYOUT, from the published protocol:
 *
 *   Nexus (guide)      0x0002      Menu (start)      0x0004
 *   View (back)        0x0008      A                 0x0010
 *   B                  0x0020      X                 0x0040
 *   Y                  0x0080      D-pad up          0x0100
 *   D-pad down         0x0200      D-pad left        0x0400
 *   D-pad right        0x0800      Left bumper       0x1000
 *   Right bumper       0x2000      Left stick click  0x4000
 *   Right stick click  0x8000
 *
 * THE TRIGGERS ARE NOT BITS. The left and right trigger travel as their own float32 fields,
 * so a half-pulled trigger is representable and a bitmask could never express it. Writing them
 * as bits would produce a console where the trigger either does nothing or slams to full.
 *
 * A PRESS AND A RELEASE ARE TWO MESSAGES. The bitmask describes the CURRENT state of the pad,
 * not an edge, so a button goes down in one message and comes up in the next. Sending only the
 * press leaves the button held, which a menu reads as a repeat.
 */
object XboxGamepad {

    /** Every button, as the protocol's own bit. */
    enum class Button(val mask: Int, val label: String) {
        NEXUS(0x0002, "nexus"),
        MENU(0x0004, "menu"),
        VIEW(0x0008, "view"),
        A(0x0010, "a"),
        B(0x0020, "b"),
        X(0x0040, "x"),
        Y(0x0080, "y"),
        DPAD_UP(0x0100, "dpad up"),
        DPAD_DOWN(0x0200, "dpad down"),
        DPAD_LEFT(0x0400, "dpad left"),
        DPAD_RIGHT(0x0800, "dpad right"),
        LEFT_BUMPER(0x1000, "left bumper"),
        RIGHT_BUMPER(0x2000, "right bumper"),
        LEFT_STICK(0x4000, "left stick click"),
        RIGHT_STICK(0x8000, "right stick click"),
        ;

        companion object {
            /** A button from a spoken or written name. */
            fun fromRaw(raw: String?): Button? {
                val key = raw?.trim()?.lowercase()
                    ?.replace("_", " ")?.replace("-", " ") ?: return null
                val normalised = key.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
                return when (normalised) {
                    "nexus", "guide", "xbox", "xbox button" -> NEXUS
                    "menu", "start", "hamburger" -> MENU
                    "view", "back", "select" -> VIEW
                    "a" -> A
                    "b" -> B
                    "x" -> X
                    "y" -> Y
                    "up", "dpad up", "d pad up" -> DPAD_UP
                    "down", "dpad down", "d pad down" -> DPAD_DOWN
                    "left", "dpad left", "d pad left" -> DPAD_LEFT
                    "right", "dpad right", "d pad right" -> DPAD_RIGHT
                    "left bumper", "lb", "l1" -> LEFT_BUMPER
                    "right bumper", "rb", "r1" -> RIGHT_BUMPER
                    "left stick", "left stick click", "ls", "l3" -> LEFT_STICK
                    "right stick", "right stick click", "rs", "r3" -> RIGHT_STICK
                    else -> null
                }
            }
        }
    }

    /**
     * The state of the pad: which buttons are down, how far each trigger is pulled, and where the
     * sticks sit.
     *
     * THE STICKS ARE PART OF THE STATE, not an afterthought. A console reads the whole pad from
     * each message, so a message that omits a stick position recentres it. That is why every
     * state carries all four coordinates rather than only the one being moved.
     */
    data class State(
        val masks: Int = 0,
        /** 0.0 to 1.0, because the protocol sends the trigger as a float. */
        val leftTrigger: Float = 0f,
        val rightTrigger: Float = 0f,
        /** -1.0 to 1.0, the range the console expects for a stick. */
        val leftX: Float = 0f,
        val leftY: Float = 0f,
        val rightX: Float = 0f,
        val rightY: Float = 0f,
    ) {
        fun with(button: Button): State = copy(masks = masks or button.mask)
        fun without(button: Button): State = copy(masks = masks and button.mask.inv())
        fun isDown(button: Button): Boolean = (masks and button.mask) != 0
    }

    /**
     * The two states a tap needs: down then up.
     *
     * A caller that sends only the down state leaves the button held, which a menu reads as a
     * repeat and a game reads as a stuck input. Returning both makes the pair the only way to
     * do it.
     */
    fun tap(button: Button, from: State = State()): List<State> =
        listOf(from.with(button), from.without(button))

    /** A trigger pull: the trigger to its travel, then back to rest. */
    fun trigger(travel: Float, right: Boolean = true, from: State = State()): List<State> {
        val pulled = if (right) {
            from.copy(rightTrigger = travel.coerceIn(0f, 1f))
        } else {
            from.copy(leftTrigger = travel.coerceIn(0f, 1f))
        }
        val released = if (right) pulled.copy(rightTrigger = 0f) else pulled.copy(leftTrigger = 0f)
        return listOf(pulled, released)
    }

    /** A direction, named as a person would say it, so a menu can be driven. */
    fun direction(word: String): Button? = when (word.trim().lowercase()) {
        "up" -> Button.DPAD_UP
        "down" -> Button.DPAD_DOWN
        "left" -> Button.DPAD_LEFT
        "right" -> Button.DPAD_RIGHT
        else -> null
    }
}
