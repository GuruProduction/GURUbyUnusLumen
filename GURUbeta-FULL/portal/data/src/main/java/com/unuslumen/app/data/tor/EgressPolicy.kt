// GURU by Unus Lumen - an AI framework that gives a language model real hands on an Android phone.
// Copyright (C) 2026 Steven Newman, Founder and Developer, Unus Lumen Ltd, Bristol UK.
// Licensed under the GNU AGPL v3.0-or-later. Full license text in the LICENSE file.
package com.unuslumen.app.data.tor

import com.unuslumen.app.preferences.PrefsConstants
import com.unuslumen.app.preferences.domain.model.EgressMode
import com.unuslumen.app.preferences.domain.model.PrefsKey
import com.unuslumen.app.preferences.domain.model.stringPreferencesKey
import com.unuslumen.app.preferences.domain.repository.PreferenceRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import org.koin.core.annotation.Single

/**
 * The live egress posture, and the one place a caller asks which posture is active.
 *
 * WHY THIS IS SEPARATE FROM [EgressClassifier]. The classifier answers a question about ONE
 * host and is pure, so it is provable on the JVM in seconds. This answers a question about
 * the INSTALL: which posture is in force right now. Keeping them apart means the posture can
 * change while the classification rules stay provably correct.
 *
 * WHY IT CACHES AS WELL AS STORES. Preferences are a DataStore flow, and a connection path
 * is synchronous: a proxy selector and a URL.openConnection cannot suspend to await a read.
 * So the value is cached in a StateFlow and observed for changes, which keeps a synchronous
 * path instant without letting the cache drift from the setting.
 *
 * THE CACHE IS SEEDED, NOT ASSUMED. Until the first read completes the posture is
 * [EgressMode.DEFAULT], which fails closed, so a connection made during boot cannot win a
 * wider route than the owner chose. That is the only direction an initial value may err in.
 */
@Single
class EgressPolicy(
    private val preferences: PreferenceRepository,
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val key: PrefsKey<String> = stringPreferencesKey(PrefsConstants.EGRESS_MODE_KEY)

    private val _mode = MutableStateFlow(EgressMode.DEFAULT)

    /** The active posture. Safe to read from any thread at any time without suspending. */
    val mode: StateFlow<EgressMode> = _mode.asStateFlow()

    init {
        scope.launch {
            preferences.getPreference(key, EgressMode.DEFAULT.id).collect { stored ->
                _mode.value = EgressMode.parse(stored)
            }
        }
    }

    /** The posture as it stands right now, for a synchronous caller. */
    fun current(): EgressMode = _mode.value

    /**
     * Read the stored posture directly, for a caller that must report the persisted value
     * rather than the cache.
     */
    suspend fun readStored(): EgressMode =
        EgressMode.parse(preferences.getPreference(key, EgressMode.DEFAULT.id).first())

    /**
     * Persist a posture. The cache is updated here as well as by the observer above, so a
     * caller that writes and then immediately reads in the same frame sees its own write
     * rather than waiting for the flow to emit.
     */
    suspend fun set(next: EgressMode) {
        preferences.savePreference(key, next.id)
        _mode.value = next
    }
}
