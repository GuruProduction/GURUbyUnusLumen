// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.util.tiles

/**
 * What a tile can honestly say about itself.
 *
 * WHY A TILE NEEDS THREE STATES AND NOT A BOOLEAN. Quick settings draws one icon and one
 * label per tile, and the label is the only thing a person reads before tapping. If a tile
 * that opens the app shows ACTIVE and a tile that is genuinely running shows ACTIVE too,
 * the word means nothing. So a tile states which of these it is:
 *
 *   READY   the tile's job is to open something, and it can always do that. Tapping it
 *           opens the app. It never claims a running state because it has none.
 *   ON      the tile is a switch and the thing it switches is on now.
 *   OFF     the tile is a switch and the thing is off.
 *   BLOCKED the tile's action needs something the person has not set up yet. The label
 *           names what, so a tap that cannot work is visible before it is pressed rather
 *           than only afterwards.
 *
 * [GuruTileSpecs] decides these from plain values, so the whole rule is testable on the
 * JVM without a TileService and without a device.
 */
enum class TileState {
    READY,
    ON,
    OFF,
    BLOCKED,
}

/**
 * One tile's identity and its honest state.
 *
 * [id] is stable and is what the tile's own service class is looked up by, so a rename in
 * the manifest cannot silently produce two tiles claiming one job.
 */
data class GuruTile(
    val id: String,
    val label: String,
    val state: TileState,
    /**
     * What the tile will do when tapped, in a sentence.
     *
     * Present on every tile because a custom tile in Android 13 and later can show a
     * subtitle under its label, and because the result of a failed tap needs to say what
     * was supposed to happen rather than only that it did not.
     */
    val subtitle: String,
)

/**
 * The four tiles and the rule that decides what each one says.
 *
 * WHAT EACH TILE IS FOR, AND WHY THERE ARE THESE FOUR.
 *
 *   talk      opens GURU on a conversation. The reason to have it in the shade is that
 *             reaching GURU otherwise means finding the app. It has no running state, so
 *             it always reads READY and its label never lies about one.
 *   listen    starts the floating orb, which is the voice and chat overlay that stays on
 *             top of other apps. This one IS a switch, and its state is read from the
 *             service rather than remembered, because a service the person stopped from
 *             its own notification must read as off.
 *   stop      the panic tile. It cancels the heartbeat chain, which is the only part of
 *             GURU that acts on its own schedule with nobody watching. Its label says what
 *             it stopped as well as that it did something, because "stopped" on a phone
 *             where nothing was running is a lie about what just happened.
 *   task      opens GURU with the add-task form up. This is the tile that already existed,
 *             and it is kept because it is the one tile already in use, but it is declared
 *             here so all four read from one place rather than one being a special case.
 */
object GuruTileSpecs {

    const val TALK = "talk"
    const val LISTEN = "listen"
    const val STOP = "stop"
    const val ADD_TASK = "add_task"

    /** Every tile, in the order they should be offered in the edit screen. */
    val all: List<String> = listOf(TALK, LISTEN, STOP, ADD_TASK)

    /**
     * The honest state for one tile from the facts that decide it.
     *
     * [orbRunning] is read from the orb service rather than from a stored preference,
     * because a preference would say yes after the person had dismissed the orb from its
     * own notification. [heartbeatWasRunning] is the state BEFORE the stop tile was
     * pressed, which is what lets the tile report what it actually stopped.
     */
    fun stateFor(
        id: String,
        orbRunning: Boolean,
        overlayPermitted: Boolean,
        heartbeatEnabled: Boolean,
        heartbeatWasRunning: Boolean,
        lastStopAtMillis: Long?,
    ): GuruTile = when (id) {
        TALK -> GuruTile(
            id = TALK,
            label = "Talk to GURU",
            state = TileState.READY,
            subtitle = "Open GURU on a conversation",
        )

        LISTEN -> when {
            // The orb needs the overlay grant to draw itself over another app. Without it
            // the service starts and shows nothing, so the tile says which grant is
            // missing rather than toggling to ON and appearing to do nothing.
            !overlayPermitted -> GuruTile(
                id = LISTEN,
                label = "GURU needs Display over other apps",
                state = TileState.BLOCKED,
                subtitle = "Allow Display over other apps, then tap again",
            )
            orbRunning -> GuruTile(
                id = LISTEN,
                label = "GURU is floating",
                state = TileState.ON,
                subtitle = "Tap to close the floating GURU",
            )
            else -> GuruTile(
                id = LISTEN,
                label = "Float GURU",
                state = TileState.OFF,
                subtitle = "Keep GURU on top of whatever you are using",
            )
        }

        STOP -> when {
            heartbeatEnabled -> GuruTile(
                id = STOP,
                label = "Stop GURU's heartbeat",
                state = TileState.ON,
                subtitle = "The 30-minute chain is running; tapping cancels it",
            )
            // Nothing to stop, but the tile has just done something, and saying which is
            // the difference between an action and a no-op that looks identical.
            heartbeatWasRunning && lastStopAtMillis != null -> GuruTile(
                id = STOP,
                label = "Heartbeat stopped",
                state = TileState.OFF,
                subtitle = "Nothing is running on a schedule now",
            )
            else -> GuruTile(
                id = STOP,
                label = "Heartbeat is off",
                state = TileState.OFF,
                subtitle = "Nothing was scheduled to stop",
            )
        }

        ADD_TASK -> GuruTile(
            id = ADD_TASK,
            label = "Add a task",
            state = TileState.READY,
            subtitle = "Open GURU with a new task started",
        )

        else -> GuruTile(
            id = id,
            label = "Unknown GURU tile",
            state = TileState.BLOCKED,
            subtitle = "This tile has no action, which is a build fault",
        )
    }

    /** The tiles that change something on the phone, as opposed to opening the app. */
    fun isToggle(id: String): Boolean = id == LISTEN || id == STOP
}
