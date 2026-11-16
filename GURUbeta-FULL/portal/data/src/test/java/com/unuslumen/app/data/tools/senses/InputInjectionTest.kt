// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.senses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The input command builder, which is where every injected event can go wrong.
 *
 * WHY THIS IS PURE AND TESTED ON THE JVM. On a device, a badly quoted command and a correct one
 * look identical: both return exit code zero and the text arrives truncated, or the tap lands
 * off screen and nothing happens. The command string is the only artefact that can be inspected,
 * so it is built here where each trap is a test rather than something a person discovers by
 * watching their phone do the wrong thing.
 *
 * WHAT THIS CANNOT PROVE: that Android dispatches the event, that a real app receives it, or
 * that the shell UID is available. Those need the device.
 */
class InputInjectionTest {

    private val width = 1080
    private val height = 2400

    private fun command(result: InputInjection.Result): String =
        (result as? InputInjection.Result.Command)?.command
            ?: throw AssertionError("expected a command, got $result")

    private fun reason(result: InputInjection.Result): String =
        (result as? InputInjection.Result.Invalid)?.reason
            ?: throw AssertionError("expected a refusal, got $result")

    // ------------------------------------------------------------------
    // Taps
    // ------------------------------------------------------------------

    @Test
    fun aTapInsideTheScreenBecomesATapCommand() {
        assertEquals("input tap 540 1200", command(InputInjection.tap(540, 1200, width, height)))
    }

    /**
     * An off-screen tap is refused rather than sent.
     *
     * `input` accepts a tap beyond the display bounds and silently does nothing, so a tool that
     * sent it and reported success would be reporting an action it cannot observe.
     */
    @Test
    fun aTapOutsideTheScreenIsRefused() {
        assertTrue(reason(InputInjection.tap(2000, 100, width, height)).contains("outside the screen"))
        assertTrue(reason(InputInjection.tap(-1, 100, width, height)).contains("outside the screen"))
        assertTrue(reason(InputInjection.tap(100, 2500, width, height)).contains("outside the screen"))
    }

    @Test
    fun aTapWithNoCoordinatesIsRefused() {
        assertTrue(reason(InputInjection.tap(null, 100, width, height)).contains("both"))
        assertTrue(reason(InputInjection.tap(100, null, width, height)).contains("both"))
    }

    @Test
    fun aTapOnAnUnknownScreenSizeIsRefusedRatherThanGuessed() {
        val refused = reason(InputInjection.tap(10, 10, 0, 0))
        assertTrue("it must say the size could not be read: $refused", refused.contains("screen size"))
    }

    // ------------------------------------------------------------------
    // Swipes
    // ------------------------------------------------------------------

    @Test
    fun aSwipeInsideTheScreenBecomesASwipeCommand() {
        assertEquals(
            "input swipe 540 2000 540 400 300",
            command(InputInjection.swipe(540, 2000, 540, 400, 300, width, height)),
        )
    }

    @Test
    fun aSwipeDefaultsToThreeHundredMilliseconds() {
        assertEquals(
            "input swipe 10 10 20 20 300",
            command(InputInjection.swipe(10, 10, 20, 20, null, width, height)),
        )
    }

    /**
     * A negative duration is refused, not clamped.
     *
     * `input` reads the duration as an unsigned value, so -1 becomes roughly four billion
     * milliseconds and the command hangs until the shell timeout. Passing the number through
     * looks defensive and is the opposite.
     */
    @Test
    fun aNegativeSwipeDurationIsRefusedBecauseTheCommandWouldHang() {
        val refused = reason(InputInjection.swipe(10, 10, 20, 20, -1, width, height))
        assertTrue("it must explain the hang: $refused", refused.contains("hang"))
    }

    @Test
    fun aSwipeWithAnEndpointOffScreenIsRefusedAndSaysWhich() {
        val refused = reason(InputInjection.swipe(10, 10, 5000, 20, 300, width, height))
        assertTrue("it must name the offending point: $refused", refused.contains("(5000, 20)"))
    }

    @Test
    fun aSwipeWithMissingCoordinatesIsRefused() {
        assertTrue(
            reason(InputInjection.swipe(10, null, 20, 20, 300, width, height)).contains("needs"),
        )
    }

    // ------------------------------------------------------------------
    // Text, where quoting decides whether it works at all
    // ------------------------------------------------------------------

    /**
     * A space becomes %s, not a literal space.
     *
     * Without the substitution the shell splits the argument and `input` types only the first
     * word, with no error. That reads as a truncated entry rather than a failure, which is the
     * worst kind of bug to hand a model.
     */
    @Test
    fun aSpaceBecomesThePercentSEscapeRatherThanSplittingTheArgument() {
        assertEquals(
            "input text 'hello%sworld'",
            command(InputInjection.text("hello world")),
        )
    }

    /**
     * A single quote is closed, escaped and reopened.
     *
     * This is the only form that survives any content in a single-quoted shell argument. Getting
     * it wrong ends the quote and the rest of the text becomes a command, which is both a broken
     * entry and an injection.
     */
    @Test
    fun aSingleQuoteIsEscapedSoItCannotEndTheArgument() {
        val built = command(InputInjection.text("it's fine"))
        assertEquals("input text 'it'\\''s%sfine'", built)
        // The escaped form must still be one argument: the quotes balance once the escape is
        // read, so a shell parses it as a single word.
        assertTrue("the text must not be split by the quote: $built", built.contains("'\\''"))
    }

    @Test
    fun aPercentSignIsEscapedSoItIsNotReadAsAnEscape() {
        // A literal % in the text would otherwise be read as the start of an escape sequence.
        assertTrue(command(InputInjection.text("50% off")).contains("\\%"))
    }

    @Test
    fun aNewlineIsRefusedBecauseTheCommandCannotTypeOne() {
        val refused = reason(InputInjection.text("first\nsecond"))
        assertTrue("it must say a newline cannot be typed: $refused", refused.contains("newline"))
        assertTrue(
            "it must name the route that works instead: $refused",
            refused.contains("enter"),
        )
    }

    @Test
    fun emptyTextIsRefused() {
        assertTrue(reason(InputInjection.text("")).contains("non-empty"))
        assertTrue(reason(InputInjection.text(null)).contains("non-empty"))
    }

    // ------------------------------------------------------------------
    // Keys
    // ------------------------------------------------------------------

    @Test
    fun aKnownKeyBecomesAKeyevent() {
        assertEquals("input keyevent KEYCODE_HOME", command(InputInjection.key("home")))
        assertEquals("input keyevent KEYCODE_BACK", command(InputInjection.key("BACK")))
        assertEquals("input keyevent KEYCODE_ENTER", command(InputInjection.key("enter")))
    }

    @Test
    fun aKeyNameWithASpaceOrCapitalsStillResolves() {
        // "Volume Up" is normalised to volume_up, which is the whole point of the normalisation:
        // a model that says the words a person would say still reaches the right keycode.
        assertEquals("input keyevent KEYCODE_VOLUME_UP", command(InputInjection.key("Volume Up")))
        assertEquals("input keyevent KEYCODE_APP_SWITCH", command(InputInjection.key("RECENTS")))
    }

    /**
     * An unknown key is refused with the list, never passed through.
     *
     * A raw KEYCODE string accepted blindly would let a typo become a command that fails on the
     * device with a message nobody reads.
     */
    @Test
    fun anUnknownKeyIsRefusedAndTheRefusalListsTheValidNames() {
        val refused = reason(InputInjection.key("teleport"))
        assertTrue("it must name the valid keys: $refused", refused.contains("home"))
        assertTrue("it must say the name is not accepted: $refused", refused.contains("not a key"))
    }

    @Test
    fun anEmptyKeyIsRefused() {
        assertTrue(reason(InputInjection.key("")).contains("needs"))
        assertTrue(reason(InputInjection.key(null)).contains("needs"))
    }

    @Test
    fun everyDocumentedKeyNameProducesAKeycodeCommand() {
        val names = InputInjection.keyNames()
        assertTrue("the key list must not be empty", names.isNotEmpty())

        val failures = names.filter { name ->
            val built = InputInjection.key(name)
            built !is InputInjection.Result.Command || !built.command.startsWith("input keyevent KEYCODE_")
        }
        assertTrue("these documented keys produce no command: $failures", failures.isEmpty())
    }
}
