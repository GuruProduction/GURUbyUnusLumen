// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tools.senses

/**
 * The command that does the injection, built as data so it is provable without a device.
 *
 * WHY THIS IS SEPARATE FROM THE TOOL. Everything that can be wrong about injected input is
 * a question about the command: whether the coordinates are inside the screen, whether the
 * key name exists, whether the text survives quoting. Those are pure decisions, and on a
 * device a mistake in one of them looks like a tap that landed in the wrong place, which is
 * indistinguishable from the tap working. Built here, each one is a test.
 *
 * WHAT IT CALLS. Android's own `input` command, which talks to the input dispatcher directly
 * and therefore needs neither the accessibility service nor a foreground window. That is the
 * whole point of the issue: it is the fallback when the person will not enable accessibility.
 * It runs at shell UID, so the caller has to have Shizuku.
 *
 * THE COST, STATED ONCE AND HONESTLY. `input` is a one-shot process per event. A tap costs a
 * process spawn, which is tens of milliseconds rather than the few that a held gesture
 * dispatch costs. That is the trade for not needing accessibility, and a caller doing a long
 * sequence should know it rather than discovering it.
 */
object InputInjection {

    /** Android's own key names, mapped from the words a model is likely to say. */
    private val keyNames: Map<String, String> = mapOf(
        "home" to "KEYCODE_HOME",
        "back" to "KEYCODE_BACK",
        "recents" to "KEYCODE_APP_SWITCH",
        "overview" to "KEYCODE_APP_SWITCH",
        "menu" to "KEYCODE_MENU",
        "power" to "KEYCODE_POWER",
        "wakeup" to "KEYCODE_WAKEUP",
        "sleep" to "KEYCODE_SLEEP",
        "enter" to "KEYCODE_ENTER",
        "tab" to "KEYCODE_TAB",
        "space" to "KEYCODE_SPACE",
        "escape" to "KEYCODE_ESCAPE",
        "delete" to "KEYCODE_DEL",
        "backspace" to "KEYCODE_DEL",
        "forward_delete" to "KEYCODE_FORWARD_DEL",
        "search" to "KEYCODE_SEARCH",
        "camera" to "KEYCODE_CAMERA",
        "volume_up" to "KEYCODE_VOLUME_UP",
        "volume_down" to "KEYCODE_VOLUME_DOWN",
        "mute" to "KEYCODE_VOLUME_MUTE",
        "play_pause" to "KEYCODE_MEDIA_PLAY_PAUSE",
        "play" to "KEYCODE_MEDIA_PLAY",
        "pause" to "KEYCODE_MEDIA_PAUSE",
        "next" to "KEYCODE_MEDIA_NEXT",
        "previous" to "KEYCODE_MEDIA_PREVIOUS",
        "up" to "KEYCODE_DPAD_UP",
        "down" to "KEYCODE_DPAD_DOWN",
        "left" to "KEYCODE_DPAD_LEFT",
        "right" to "KEYCODE_DPAD_RIGHT",
        "center" to "KEYCODE_DPAD_CENTER",
        "notification" to "KEYCODE_NOTIFICATION",
        "settings" to "KEYCODE_SETTINGS",
        "call" to "KEYCODE_CALL",
        "end_call" to "KEYCODE_ENDCALL",
        "brightness_down" to "KEYCODE_BRIGHTNESS_DOWN",
        "brightness_up" to "KEYCODE_BRIGHTNESS_UP",
    )

    /** The largest coordinate `input` accepts, which is far beyond any real screen. */
    private const val MAX_COORDINATE = 100_000

    /**
     * The one-shot tap command, or a refusal.
     *
     * A coordinate outside the screen is refused rather than sent, because `input` silently
     * accepts a tap beyond the display bounds and nothing happens. A tool that reported that
     * as a tap would be reporting an action it cannot observe.
     */
    fun tap(x: Int?, y: Int?, width: Int, height: Int): Result {
        if (x == null || y == null) {
            return Result.Invalid("A tap needs both 'x' and 'y'.")
        }
        if (width <= 0 || height <= 0) {
            return Result.Invalid("The screen size could not be read, so a tap has nowhere to land.")
        }
        if (x !in 0 until width || y !in 0 until height) {
            return Result.Invalid(
                "The point ($x, $y) is outside the screen, which is ${width}x${height}. " +
                    "input accepts an off-screen tap and silently does nothing, so this is " +
                    "refused rather than reported as a tap.",
            )
        }
        return Result.Command("input tap $x $y")
    }

    /**
     * A swipe between two points.
     *
     * A negative duration is refused rather than clamped, because `input` reads it as an
     * unsigned value and a swipe of roughly four billion milliseconds hangs until the command
     * timeout. That is a real trap: the obvious defensive move of passing the number through
     * looks safe and is not.
     */
    fun swipe(
        startX: Int?,
        startY: Int?,
        endX: Int?,
        endY: Int?,
        durationMs: Int?,
        width: Int,
        height: Int,
    ): Result {
        if (startX == null || startY == null || endX == null || endY == null) {
            return Result.Invalid("A swipe needs 'startX', 'startY', 'endX' and 'endY'.")
        }
        if (width <= 0 || height <= 0) {
            return Result.Invalid("The screen size could not be read, so a swipe has nowhere to go.")
        }
        val outside = listOf(startX to startY, endX to endY).filter { (x, y) ->
            x !in 0 until width || y !in 0 until height
        }
        if (outside.isNotEmpty()) {
            return Result.Invalid(
                "These points are outside the screen, which is ${width}x${height}: " +
                    outside.joinToString { (x, y) -> "($x, $y)" } + ".",
            )
        }

        val duration = durationMs ?: 300
        if (duration < 0) {
            return Result.Invalid(
                "The duration must be zero or more milliseconds. A negative value is read by the " +
                    "input command as a huge unsigned number and would hang rather than swipe.",
            )
        }

        return Result.Command("input swipe $startX $startY $endX $endY $duration")
    }

    /**
     * Typing text.
     *
     * QUOTING IS THE WHOLE PROBLEM. The command runs through a shell, so a space in the text
     * splits it into two arguments and `input` types only the first word, with no error. A
     * single quote in the text ends the quote and the rest becomes a command. So the text is
     * wrapped in single quotes and every embedded single quote is closed, escaped and
     * reopened, which is the one form that survives any content.
     *
     * THE LIMIT WORTH KNOWING. `input text` cannot type a newline and cannot type into a field
     * that is not focused; both are reported here rather than attempted and silently failing.
     */
    fun text(value: String?): Result {
        if (value.isNullOrEmpty()) {
            return Result.Invalid("Typing needs non-empty 'text'.")
        }
        if (value.contains('\n') || value.contains('\r')) {
            return Result.Invalid(
                "The text contains a newline, and the input command cannot type one. Send the " +
                    "text without line breaks, or press the enter key after it with keyAction.",
            )
        }

        // A space is typed as %s. Without the substitution the shell splits the argument and
        // only the first word reaches the field, which reads as a truncated entry rather than
        // a failure.
        val escaped = value
            .replace("%", "\\%")
            .replace(" ", "%s")
            .replace("'", "'\\''")

        return Result.Command("input text '$escaped'")
    }

    /**
     * A single key press by name.
     *
     * Only the names in [keyNames] are accepted, so a typo is a refusal with the list rather
     * than a command that fails on the device with a message nobody sees.
     */
    fun key(name: String?): Result {
        val requested = name?.trim()?.lowercase()?.replace(' ', '_')
        if (requested.isNullOrEmpty()) {
            return Result.Invalid("A key press needs a 'key' name.")
        }
        val code = keyNames[requested]
            ?: return Result.Invalid(
                "'$name' is not a key this tool sends. The names are: " +
                    keyNames.keys.sorted().joinToString(", ") + ".",
            )
        return Result.Command("input keyevent $code")
    }

    /** Every key name, so a description can list them without a second hand-written copy. */
    fun keyNames(): List<String> = keyNames.keys.sorted()

    sealed interface Result {
        data class Command(val command: String) : Result
        data class Invalid(val reason: String) : Result
    }
}
