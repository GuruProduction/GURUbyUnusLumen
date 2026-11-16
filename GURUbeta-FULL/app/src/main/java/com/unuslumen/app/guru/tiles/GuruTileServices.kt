// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.guru.tiles

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.Intent
import android.graphics.drawable.Icon
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.unuslumen.app.data.heartbeat.HeartbeatChainState
import com.unuslumen.app.data.heartbeat.HeartbeatScheduler
import com.unuslumen.app.util.Constants
import com.unuslumen.app.util.orb.GuruOrbService
import com.unuslumen.app.util.tiles.GuruTile
import com.unuslumen.app.util.tiles.GuruTileSpecs
import com.unuslumen.app.util.tiles.TileState
import androidx.core.net.toUri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

/**
 * The shared body of every GURU tile.
 *
 * WHY A BASE CLASS RATHER THAN FOUR COPIES. Four tiles that each read the overlay grant,
 * each decide their own label and each fire their own intent is four places for one
 * question to be answered differently, and the last time this app shipped a tool whose
 * description disagreed with its behaviour it was because the two lived in separate files.
 * The rule that decides a tile's state is `GuruTileSpecs`, which is pure Kotlin and proven
 * on the JVM; this class only reads the phone and draws the answer.
 *
 * WHAT IT NEVER DOES. It never draws ON for something it has not read. Every state comes
 * from a live read: the orb's own service state, the heartbeat's stored switch, and the
 * overlay app-op. A remembered preference would say yes after the person had switched the
 * orb off from its own notification, which is the one lie a status icon cannot afford.
 */
abstract class GuruTileServiceBase : TileService() {

    /** Which of [GuruTileSpecs] this tile is. */
    abstract val tileId: String

    /** The icon this tile draws in both states. */
    abstract val iconRes: Int

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    /**
     * When this tile last stopped the heartbeat, so its label can say what it did.
     *
     * A field rather than a preference because it describes one tap's outcome in the shade
     * that is open now. It is deliberately not persisted: after a restart the tile should
     * say the heartbeat is off rather than claim a stop that happened yesterday.
     */
    private var lastStopAtMillis: Long? = null

    /**
     * Refresh the drawn tile.
     *
     * Called when the shade opens, when the tile is added, and after every tap. Android
     * gives no callback for "the thing this tile watches changed", so a tap and a shade
     * open are the two moments the state is guaranteed fresh and the label is what the
     * person then reads.
     */
    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onTileAdded() {
        super.onTileAdded()
        render()
    }

    override fun onClick() {
        super.onClick()
        when (tileId) {
            GuruTileSpecs.TALK -> openApp(onConversation = true)
            GuruTileSpecs.ADD_TASK -> openApp(addTask = true)
            GuruTileSpecs.LISTEN -> toggleOrb()
            GuruTileSpecs.STOP -> {
                lastStopAtMillis = System.currentTimeMillis()
                stopHeartbeat()
            }
        }
        // Redraw after the action so the label reflects what just happened rather than
        // what was true before the tap.
        render()
    }

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    // ------------------------------------------------------------------
    // Actions
    // ------------------------------------------------------------------

    /**
     * Open GURU, optionally with the add-task form up.
     *
     * The conversation case uses the launcher intent rather than a deep link. The portal is
     * not a deep-linkable route: the graph starts at the lobby or the portal depending on
     * the person's own startup preference, and inventing a host the manifest does not
     * declare would produce a tile that silently does nothing.
     */
    protected fun openApp(onConversation: Boolean = false, addTask: Boolean = false) {
        val intent: Intent = when {
            addTask -> Intent(
                Intent.ACTION_VIEW,
                "${Constants.TASKS_SCREEN_URI}?${Constants.ADD_TASK_ARG}=true".toUri(),
            ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

            onConversation -> packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return

            else -> packageManager.getLaunchIntentForPackage(packageName)
                ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ?: return
        }

        collapseAndStart(intent)
    }

    /**
     * Start the orb, or stop it if it is already running.
     *
     * THE OVERLAY GRANT IS CHECKED ON THE TAP AS WELL AS WHEN DRAWING. The label can say
     * BLOCKED all it likes, but the tap still has to refuse rather than start a service
     * that would draw nothing and leave the person believing it worked. When the grant is
     * missing the tile opens the screen that grants it, which is the only useful thing a
     * tap can do in that state.
     */
    protected fun toggleOrb() {
        if (!canDrawOverlays()) {
            openOverlaySettings()
            return
        }

        if (GuruOrbService.isRunning.value) {
            GuruOrbService.stopOrb(this)
        } else {
            GuruOrbService.startOrb(this)
        }
    }

    /**
     * Stop the heartbeat chain, which is GURU's own scheduled work.
     *
     * WHAT THIS CAN AND CANNOT STOP, stated because a panic button that overclaims is
     * worse than none. The heartbeat is the part of GURU that acts on a timer with nobody
     * watching, so cancelling it stops everything GURU would have done on its own. A
     * conversation already in flight is not a scheduled thing and is not touched: stopping
     * it mid-thought would leave whatever it was writing half-written.
     *
     * The switch is written false as well as the alarm cancelled, because cancelling only
     * the alarm would let the next schedule() call quietly rebook it, which is a stop that
     * undoes itself.
     */
    protected fun stopHeartbeat() {
        HeartbeatScheduler.cancel(this)
        scope.launch {
            HeartbeatChainState.setEnabled(false)
            render()
        }
    }

    // ------------------------------------------------------------------
    // Drawing
    // ------------------------------------------------------------------

    private fun render() {
        val tile = qsTile ?: return
        val spec = currentSpec()

        tile.label = spec.label
        tile.icon = Icon.createWithResource(this, iconRes)
        tile.state = when (spec.state) {
            TileState.READY, TileState.OFF -> Tile.STATE_INACTIVE
            TileState.ON -> Tile.STATE_ACTIVE
            TileState.BLOCKED -> Tile.STATE_UNAVAILABLE
        }

        // The subtitle exists from Android 13 and it is the only line that can say what a
        // tap will do. Set behind the version check because the setter is absent earlier.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            tile.subtitle = spec.subtitle
        }

        tile.updateTile()
    }

    private fun currentSpec(): GuruTile = GuruTileSpecs.stateFor(
        id = tileId,
        orbRunning = GuruOrbService.isRunning.value,
        overlayPermitted = canDrawOverlays(),
        heartbeatEnabled = HeartbeatChainState.isEnabled(),
        heartbeatWasRunning = lastStopAtMillis != null,
        lastStopAtMillis = lastStopAtMillis,
    )

    // ------------------------------------------------------------------
    // Phone reads
    // ------------------------------------------------------------------

    protected fun canDrawOverlays(): Boolean = runCatching {
        Settings.canDrawOverlays(this)
    }.getOrDefault(false)

    protected fun openOverlaySettings() {
        val intent = Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:$packageName"),
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        collapseAndStart(intent)
    }

    /**
     * Collapse the shade and start an activity, on both shapes of the API.
     *
     * `startActivityAndCollapse(Intent)` was deprecated because Android 14 requires a
     * PendingIntent for the collapse, so the version check picks the form the platform
     * accepts rather than one that throws on a modern build.
     */
    @SuppressLint("StartActivityAndCollapseDeprecated")
    protected fun collapseAndStart(intent: Intent) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            val pending = PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pending)
        } else {
            startActivityAndCollapse(intent)
        }
    }
}

/**
 * The four tiles, each a class of its own because Android identifies a tile by its
 * component name and nothing else. One class cannot hold four tiles, which is why the
 * behaviour lives in the base and each subclass is two lines.
 */
class TalkTileService : GuruTileServiceBase() {
    override val tileId: String = GuruTileSpecs.TALK
    override val iconRes: Int = com.unuslumen.app.ui.R.drawable.ic_send_message
}

class ListenTileService : GuruTileServiceBase() {
    override val tileId: String = GuruTileSpecs.LISTEN
    override val iconRes: Int = com.unuslumen.app.util.R.drawable.ic_guru_orb
}

class StopTileService : GuruTileServiceBase() {
    override val tileId: String = GuruTileSpecs.STOP
    override val iconRes: Int = com.unuslumen.app.ui.R.drawable.ic_stop
}

/**
 * The tile that already existed, kept on the same behaviour and moved onto the base so its
 * state is read the same way as the rest rather than being a special case.
 */
class AddTaskTileService : GuruTileServiceBase() {
    override val tileId: String = GuruTileSpecs.ADD_TASK
    override val iconRes: Int = com.unuslumen.app.ui.R.drawable.ic_check
}
