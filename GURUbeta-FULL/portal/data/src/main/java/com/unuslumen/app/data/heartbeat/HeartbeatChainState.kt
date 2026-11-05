// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.heartbeat

import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.booleanPreferencesKey
import com.unuslumen.app.preferences.domain.use_case.GetPreferenceUseCase
import com.unuslumen.app.preferences.domain.use_case.SavePreferenceUseCase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.koin.java.KoinJavaComponent

/**
 * Whether the heartbeat chain is switched on, and the one place that decides it.
 *
 * WHY THIS IS ONE OBJECT AND NOT A READ IN EACH PLACE. The switch is read by the
 * scheduler before every booking, by this file's own guard on the run path, and by
 * the room to draw the toggle. Three separate reads of the same key is three chances
 * for one of them to drift, and a switch the scheduler honours but the room draws
 * inverted is worse than no switch at all.
 *
 * WHY THE READ IS SYNCHRONOUS. [HeartbeatScheduler.schedule] and the alarm receiver
 * are not suspend functions: the receiver runs inside `goAsync` and Android gives it
 * a bounded window before it is killed. Blocking briefly on a preference read there
 * is correct and cheap, and the alternative — making the booking path suspend — would
 * change the shape of a chain that currently works on his phone.
 *
 * The write path IS suspend, because it is called from the room's view model where a
 * coroutine already exists.
 *
 * RESOLVED FROM THE CONTAINER RATHER THAN INJECTED. This object has no constructor
 * for Koin to fill, and the scheduler that calls it is a Kotlin object, not a class a
 * binding could own. Resolution is lazy and per call, which also keeps this working in
 * a Robolectric test that starts its own container.
 */
object HeartbeatChainState {

    private const val TAG = "guru_heartbeat"

    /** The switch, as a flow, so the room redraws the moment it changes. */
    fun flow(): Flow<Boolean> {
        val resolver = getPreferenceUseCase()
        return resolver(booleanPreferencesKey(PrefsConstants.HEARTBEAT_ENABLED_KEY), false)
    }

    /**
     * The switch right now.
     *
     * DEFAULTS TO FALSE, and that default is load-bearing. A fresh install has no
     * prompt and no chain: it must not run a beat into an empty prompt, and it must
     * not wake the phone every thirty minutes before its owner has said a word.
     * Turning it on is the owner's action, and it happens in the room, beside the
     * prompt it needs.
     *
     * An unreadable preference answers false as well. "I could not tell whether you
     * switched this on" must never resolve to "so I booked a wake-up anyway".
     */
    fun isEnabled(): Boolean = runCatching {
        runBlocking {
            getPreferenceUseCase()(
                booleanPreferencesKey(PrefsConstants.HEARTBEAT_ENABLED_KEY),
                false,
            ).first()
        }
    }.getOrElse { error ->
        android.util.Log.w(TAG, "Heartbeat switch unreadable, treating as off: ${error.message}")
        false
    }

    /** Set the switch. Scheduling and cancelling afterwards is the caller's job. */
    suspend fun setEnabled(enabled: Boolean) {
        runCatching {
            getSavePreferenceUseCase()(
                booleanPreferencesKey(PrefsConstants.HEARTBEAT_ENABLED_KEY),
                enabled,
            )
        }.onFailure {
            android.util.Log.w(TAG, "Heartbeat switch write failed: ${it.message}")
        }
    }

    private fun getPreferenceUseCase(): GetPreferenceUseCase =
        KoinJavaComponent.getKoin().get<GetPreferenceUseCase>()

    private fun getSavePreferenceUseCase(): SavePreferenceUseCase =
        KoinJavaComponent.getKoin().get<SavePreferenceUseCase>()
}
